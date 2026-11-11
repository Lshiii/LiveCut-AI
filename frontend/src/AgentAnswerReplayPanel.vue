<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from './api'
import { AgentReadScope, newestAgentSession, parseAgentSession, type AgentSession, type AgentTurn } from './videoAgent'
import { answerReplayCandidate, answerReplayMessage, answerReplaySubmissionMissing, parseAnswerReplayCapability, parseAnswerReplayChild, parseAnswerReplayPreview, parseAnswerReplaySubmission, type AnswerReplayPreview } from './agentAnswerReplay'
import { ANSWER_REPLAY_PREFIX, AnswerReplayError, answerReplayStorageKey, assertAnswerReplayStored, prepareAnswerReplay, removeAnswerReplay, scanAnswerReplays, type AnswerReplayEntry } from './agentAnswerReplayStorage'
import type { AgentSubmissionOwner } from './agentSubmissionRecovery'

const props = defineProps<{ owner: AgentSubmissionOwner; session: AgentSession | null; knownSessions?: AgentSession[]; active: boolean; blocked: boolean; newBlocked: boolean }>()
const emit = defineEmits<{ session: [session: AgentSession] }>()
const action = ref(''), error = ref(''), notice = ref(''), entries = ref<AnswerReplayEntry[]>([]), issues = ref<string[]>([])
const previews = ref<Record<string, AnswerReplayPreview>>({}), retries = ref<Record<string, boolean>>({}), releases = ref<Record<string, AgentTurn>>({})
const writes = ref(new Set<string>())
// This mount alone observed the first POST's definite rollback. Recovered entries
// and any later uncertain write never acquire this proof from local storage.
const definiteRejections = new Set<string>(), rejectedReleases = ref<Record<string, boolean>>({})
const scope = new AgentReadScope(), retained = new Map<string, AnswerReplayEntry>()
let alive = true, version = 0
const pending = computed(() => !!action.value || writes.value.size > 0), unresolved = computed(() => !!entries.value.length || !!issues.value.length)
const currentSession = computed(() => props.session?.job_id === props.owner.job_id && props.session.video_id === props.owner.video_id && props.session.source.video_sha256 === props.owner.video_sha256 ? props.session : null)
const candidates = computed(() => currentSession.value?.turns.filter(answerReplayCandidate) ?? [])
const context = computed(() => JSON.stringify([props.owner, props.session?.id, props.session?.source]))
const sourceState = computed(() => JSON.stringify(candidates.value.map(turn => [turn.id, turn.state_version, turn.stage.attempt, turn.budget])))
const reasonText: Record<string, string> = {
  INELIGIBLE_SOURCE: '原轮次不在首版恢复范围内。', SESSION_BUSY: '原会话仍有执行中的轮次，请完成后再核对。', SESSION_LIMIT: '原会话已达到轮数上限。',
  ALREADY_EXISTS: '此失败轮次已有派生恢复，不能另建一轮。', SOURCE_UNAVAILABLE: '原视频或原句来源暂时无法核对。', CACHE_UNAVAILABLE: '原缓存缺失、发生变化或无法核对。',
  CALLS_NOT_REPLAYABLE: '原调用有未决状态，或已收响应不完整，不能零调用恢复。', FIRST_ANSWER_UNSUPPORTED: '首个已收决策不是可直接恢复的回答；首版不继续工具链或模型请求。', CONFIG_MISMATCH: '原配置与缓存身份不一致。',
}
function foreground() { return alive && props.active && !document.hidden }
function scan() {
  const result = scanAnswerReplays(localStorage, props.owner), next = new Map(result.entries.map(entry => [answerReplayStorageKey(entry), entry]))
  for (const [key, entry] of retained) if (entry.job_id === props.owner.job_id && entry.video_id === props.owner.video_id && entry.video_sha256 === props.owner.video_sha256) next.set(key, entry)
  entries.value = [...next.values()]; issues.value = result.issues
}
function invalidate() { version++; scope.invalidate(); action.value = ''; previews.value = {}; retries.value = {}; releases.value = {}; rejectedReleases.value = {}; definiteRejections.clear(); error.value = ''; notice.value = '' }
function begin(name: string) {
  scope.invalidate(); const read = scope.begin(), token = ++version, owner = context.value
  action.value = name; error.value = ''; notice.value = ''
  return { ...read, current: () => token === version && owner === context.value && read.current() && foreground() && !props.blocked }
}
function finish(read: ReturnType<typeof begin>) { if (read.current()) action.value = ''; read.release() }
function path(entry: AnswerReplayEntry) { return `/api/agent-turns/${entry.source_turn_id}/response-replay` }
function mergeKnown(session: AgentSession) {
  const known = props.knownSessions?.find(value => value.id === session.id) ?? (props.session?.id === session.id ? props.session : undefined)
  const merged = newestAgentSession(known, session)
  return parseAgentSession(merged, props.owner.job_id, props.owner.video_id, props.owner.video_sha256, session.id)
}
async function check(turn: AgentTurn) {
  const session = currentSession.value
  if (!session || !foreground() || props.blocked || pending.value || !answerReplayCandidate(turn)) return
  const read = begin(`check:${turn.id}`); delete previews.value[turn.id]
  try {
    parseAnswerReplayCapability(await api('/api/agent-capabilities/cached-answer-replay', { signal: read.signal }))
    if (!read.current()) return
    const raw = await api(`/api/agent-turns/${turn.id}/response-replay`, { signal: read.signal })
    if (read.current()) previews.value = { ...previews.value, [turn.id]: parseAnswerReplayPreview(raw, session, turn) }
  } catch (value) { if (read.current()) error.value = answerReplayMessage(value) }
  finally { finish(read) }
}
async function adopt(raw: unknown, entry: AnswerReplayEntry, read: ReturnType<typeof begin>) {
  if (!read.current()) return
  const session = await api(`/api/agent-sessions/${entry.session_id}`, { signal: read.signal })
  if (!read.current()) return
  const verified = mergeKnown(parseAnswerReplaySubmission(raw, entry, session))
  removeAnswerReplay(localStorage, entry); retained.delete(answerReplayStorageKey(entry)); scan()
  emit('session', verified); previews.value = {}; retries.value = {}
  notice.value = '原恢复提交已核对。派生轮次复用已收回答，新增模型与 ASR 请求均为 0；原失败和原预算保留。'
}
async function postEntry(entry: AnswerReplayEntry, read: ReturnType<typeof begin>, first = false) {
  if (!read.current()) return
  assertAnswerReplayStored(localStorage, entry)
  if (!read.current()) return
  writes.value = new Set([...writes.value, entry.key])
  definiteRejections.delete(entry.key); delete rejectedReleases.value[entry.key]
  try {
    let raw: unknown
    try { raw = await api(path(entry), { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': entry.key }, body: entry.input }) }
    catch (value) {
      const failure = value as { status?: number; code?: string }
      if (first && read.current() && (failure.status === 400 && failure.code === 'INVALID_AGENT_RESPONSE_REPLAY'
        || failure.status === 409 && ['AGENT_RESPONSE_REPLAY_UNAVAILABLE', 'AGENT_RESPONSE_REPLAY_SOURCE_CHANGED', 'AGENT_SESSION_BUSY', 'AGENT_SESSION_LIMIT', 'AGENT_RESPONSE_REPLAY_ALREADY_EXISTS'].includes(failure.code ?? ''))) definiteRejections.add(entry.key)
      throw value
    }
    if (read.current()) await adopt(raw, entry, read)
  } finally { const next = new Set(writes.value); next.delete(entry.key); writes.value = next }
}
async function create(turn: AgentTurn) {
  const session = currentSession.value, preview = previews.value[turn.id]
  scan()
  if (!session || !foreground() || props.blocked || props.newBlocked || pending.value || unresolved.value || !preview?.eligible || !preview.snapshot_hash) return
  const read = begin(`create:${turn.id}`)
  try {
    const entry = prepareAnswerReplay(localStorage, session, turn.id, preview.snapshot_hash, crypto.randomUUID())
    retained.set(answerReplayStorageKey(entry), entry); scan(); delete previews.value[turn.id]
    await postEntry(entry, read, true)
  } catch (value) { if (read.current()) { previews.value = {}; scan(); error.value = answerReplayMessage(value) } }
  finally { finish(read) }
}
async function reconcile(entry: AnswerReplayEntry) {
  if (!foreground() || props.blocked || pending.value) return
  const read = begin(`receipt:${entry.key}`); delete retries.value[entry.key]; delete releases.value[entry.key]
  try {
    const raw = await api(`${path(entry)}-submissions/${entry.key}`, { signal: read.signal })
    if (read.current()) await adopt(raw, entry, read)
  } catch (value) {
    if (read.current()) {
      if (answerReplaySubmissionMissing(value)) {
        retries.value = { ...retries.value, [entry.key]: true }; notice.value = '服务已明确确认此恢复标识不存在；可明确使用原标识与原快照重试。尚未发送 POST。'
        await inspectExisting(entry, read)
        if (read.current() && definiteRejections.has(entry.key)) rejectedReleases.value = { ...rejectedReleases.value, [entry.key]: true }
      }
      else error.value = answerReplayMessage(value)
    }
  } finally { finish(read) }
}
async function inspectExisting(entry: AnswerReplayEntry, read: ReturnType<typeof begin>) {
  try {
    const session = parseAgentSession(await api(`/api/agent-sessions/${entry.session_id}`, { signal: read.signal }), entry.job_id, entry.video_id, entry.video_sha256, entry.session_id)
    if (!read.current()) return null
    if (session.source.transcript_hash !== entry.source.transcript_hash || session.source.duration_ms !== entry.source.duration_ms) throw new AnswerReplayError()
    const source = session.turns.find(turn => turn.id === entry.source_turn_id)
    if (!source) throw new AnswerReplayError()
    const preview = parseAnswerReplayPreview(await api(path(entry), { signal: read.signal }), session, source)
    if (!read.current()) return null
    if (preview.block_reason === 'ALREADY_EXISTS' && preview.existing_turn) { releases.value = { ...releases.value, [entry.key]: preview.existing_turn }; return session }
  } catch (value) { if (read.current()) error.value = answerReplayMessage(value) }
  return null
}
async function releaseUncreated(entry: AnswerReplayEntry) {
  const definite = !!rejectedReleases.value[entry.key] && definiteRejections.has(entry.key)
  if (!foreground() || props.blocked || pending.value || !releases.value[entry.key] && !definite) return
  const read = begin(`release:${entry.key}`); delete releases.value[entry.key]; delete retries.value[entry.key]; delete rejectedReleases.value[entry.key]
  try {
    let absent = false
    try { const raw = await api(`${path(entry)}-submissions/${entry.key}`, { signal: read.signal }); if (read.current()) await adopt(raw, entry, read) }
    catch (value) { if (!read.current()) return; if (!answerReplaySubmissionMissing(value)) throw value; absent = true }
    if (!absent || !read.current()) return
    if (definite && definiteRejections.has(entry.key)) {
      removeAnswerReplay(localStorage, entry); retained.delete(answerReplayStorageKey(entry)); definiteRejections.delete(entry.key); scan()
      notice.value = '已再次确认原 key 不存在，并明确解除本页首次已被业务拒绝的恢复标识；没有创建派生轮次或模型请求。'; return
    }
    const session = await inspectExisting(entry, read)
    if (!read.current() || !session || !releases.value[entry.key]) return
    const merged = mergeKnown(session)
    removeAnswerReplay(localStorage, entry); retained.delete(answerReplayStorageKey(entry)); scan(); emit('session', merged)
    delete releases.value[entry.key]; notice.value = '已查看已有恢复，并明确解除仅本机未创建的标识；没有把其他 key 的派生轮次认作本条回执。'
  } catch (value) { if (read.current()) { scan(); error.value = answerReplayMessage(value) } }
  finally { finish(read) }
}
async function retry(entry: AnswerReplayEntry) {
  if (!foreground() || props.blocked || pending.value || !retries.value[entry.key]) return
  const read = begin(`retry:${entry.key}`); delete retries.value[entry.key]
  try {
    const session = parseAgentSession(await api(`/api/agent-sessions/${entry.session_id}`, { signal: read.signal }), entry.job_id, entry.video_id, entry.video_sha256, entry.session_id)
    if (!read.current()) return
    const source = session.turns.find(turn => turn.id === entry.source_turn_id)
    if (!source || !answerReplayCandidate(source) || session.source.transcript_hash !== entry.source.transcript_hash || session.source.duration_ms !== entry.source.duration_ms) throw new AnswerReplayError()
    await postEntry(entry, read)
  } catch (value) { if (read.current()) { scan(); error.value = answerReplayMessage(value) } }
  finally { finish(read) }
}
async function viewExisting(sourceId: string) {
  const session = currentSession.value, existing = previews.value[sourceId]?.existing_turn
  if (!session || !existing || !foreground() || props.blocked || pending.value) return
  const read = begin(`existing:${sourceId}`)
  try {
    const fresh = parseAgentSession(await api(`/api/agent-sessions/${session.id}`, { signal: read.signal }), session.job_id, session.video_id, session.source.video_sha256, session.id)
    if (!read.current()) return
    const child = fresh.turns.find(turn => turn.id === existing.id)
    parseAnswerReplayChild(child, fresh, sourceId, existing.replay_snapshot_hash)
    emit('session', mergeKnown(fresh)); notice.value = '已核对已有派生轮次；它不作为其他恢复标识的提交回执。'
  } catch (value) { if (read.current()) error.value = answerReplayMessage(value) }
  finally { finish(read) }
}
function download(entry: AnswerReplayEntry) {
  if (!foreground()) return
  const url = URL.createObjectURL(new Blob([JSON.stringify(entry, null, 2)], { type: 'application/json' })), link = document.createElement('a')
  link.href = url; link.download = `agent-answer-replay-${entry.key}.json`; link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
function storageChanged(event: StorageEvent) { if (event.key === null || event.key.startsWith(ANSWER_REPLAY_PREFIX)) scan() }
function visibility() { if (document.hidden) invalidate(); else scan() }
watch(context, () => { invalidate(); scan() }, { flush: 'sync' })
watch(sourceState, invalidate, { flush: 'sync' })
watch(() => props.active, active => { if (!active) invalidate(); else scan() }, { flush: 'sync' })
watch(() => props.blocked, blocked => { if (blocked) invalidate() }, { flush: 'sync' })
onMounted(() => { scan(); window.addEventListener('storage', storageChanged); window.addEventListener('focus', scan); document.addEventListener('visibilitychange', visibility) })
onBeforeUnmount(() => { alive = false; invalidate(); scope.dispose(); window.removeEventListener('storage', storageChanged); window.removeEventListener('focus', scan); document.removeEventListener('visibilitychange', visibility) })
defineExpose({ pending, unresolved })
</script>

<template>
  <section v-if="candidates.length || unresolved" class="answer-replay" aria-label="已收回答零调用恢复">
    <h3>复用已收回答恢复</h3><p>只核对原轮次已收到的首答，不继续模型工具链。创建独立派生轮次，新增模型、ASR 请求均为 0；原失败、响应和原预算保留。</p>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="unresolved">存在原恢复回执时暂不能发送新问题。可下载保留原标识；网络未知或重新打开的旧回执不能仅因缓存不可用而解除。</p>
    <p v-for="(issue, index) in issues" :key="index" role="alert">{{ answerReplayMessage({ code: issue }) }}</p>
    <article v-for="entry in entries" :key="entry.key">
      <strong>原恢复提交结果待核对</strong><small>原轮次 {{ entry.source_turn_id }} · 标识 {{ entry.key }}</small>
      <div><button type="button" class="secondary" :disabled="pending || blocked || !active" @click="reconcile(entry)">核对原恢复提交</button><button v-if="retries[entry.key] && !releases[entry.key]" type="button" class="secondary" :disabled="pending || blocked || !active" @click="retry(entry)">按原标识与快照重试</button><button v-if="releases[entry.key]" type="button" class="secondary" :disabled="pending || blocked || !active" @click="releaseUncreated(entry)">查看已有恢复并解除本机未创建标识</button><button v-if="rejectedReleases[entry.key] && !releases[entry.key]" type="button" class="text-button" :disabled="pending || blocked || !active" @click="releaseUncreated(entry)">解除明确拒绝且未创建的恢复标识</button><button type="button" class="text-button" :disabled="!active" @click="download(entry)">下载恢复回执</button></div>
    </article>
    <article v-for="turn in candidates" :key="turn.id">
      <strong>第 {{ turn.sequence }} 轮 · 原失败保留</strong><small>原模型预算 {{ turn.budget.text_calls_reserved }} / {{ turn.budget.max_text_calls }}；本次恢复新增 0。</small>
      <button type="button" class="text-button" :disabled="pending || blocked || !active" @click="check(turn)">核对第 {{ turn.sequence }} 轮可复用回答</button>
      <template v-if="previews[turn.id]">
        <p v-if="previews[turn.id]!.eligible">已核对可复用首答快照；尚未创建恢复。提交后等待支持协议 3 的恢复执行器，不使用模型额度。</p>
        <p v-else>{{ reasonText[previews[turn.id]!.block_reason ?? ''] }}</p>
        <button v-if="previews[turn.id]!.eligible" type="button" class="secondary" :disabled="pending || blocked || newBlocked || unresolved || !active" @click="create(turn)">复用第 {{ turn.sequence }} 轮已收回答恢复</button>
        <button v-if="previews[turn.id]!.existing_turn" type="button" class="text-button" :disabled="pending || blocked || !active" @click="viewExisting(turn.id)">核对并查看已有派生轮次</button>
      </template>
    </article>
  </section>
</template>

<style scoped>
.answer-replay{flex:none;max-height:min(38vh,340px);overflow:auto;margin:8px 0;padding:10px;border:1px solid #d8dfeb;border-radius:8px;background:#f6f8fc;font-size:12px;line-height:1.65;overflow-wrap:anywhere}.answer-replay h3{margin:0;font-size:14px}.answer-replay p{margin:5px 0}.answer-replay article{border-top:1px solid #dce3ee;margin-top:8px;padding-top:8px}.answer-replay small{display:block;margin:4px 0;color:#536174}.answer-replay article>div{display:flex;flex-wrap:wrap;gap:6px}.answer-replay button{max-width:100%;margin:4px 5px 0 0;white-space:normal}.answer-replay [role=alert]{color:#985c32}
</style>
