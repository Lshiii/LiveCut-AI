<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, ApiError, post } from './api'
import FailureDiagnostic from './FailureDiagnostic.vue'
import TextRequestProgress from './TextRequestProgress.vue'
import { topicTime } from './topics'
import { AgentReadScope, AgentResponseError, agentTurnActive, agentTurnReceipt, canPrefillClipInstruction,
  newestAgentSession, newestAgentTurn, parseAgentSession, parseAgentTurn,
  type AgentCitation, type AgentReceipt, type AgentSession, type AgentTurn } from './videoAgent'

const props = defineProps<{ jobId: string; videoId: string; videoHash: string; videoUrl: string; clipTargetBusy?: boolean }>()
const emit = defineEmits<{ clipInstruction: [instruction: string] }>()
const sessions = ref<AgentSession[]>([]), selectedId = ref(''), question = ref(''), maxCalls = ref(8)
const action = ref(''), error = ref(''), refreshError = ref(''), notice = ref(''), loading = ref(true)
const selected = computed(() => sessions.value.find(session => session.id === selectedId.value) ?? null)
const activeTurn = computed(() => selected.value?.turns.find(agentTurnActive))
const pending = computed(() => !!action.value), dirty = computed(() => !!question.value.trim())
const citation = ref<AgentCitation | null>(null), sourcePlayer = ref<HTMLVideoElement | null>(null), playbackError = ref('')
const scope = new AgentReadScope(), receiptCache = new Map<string, AgentReceipt>()
let stopped = false, refreshing = false, timer: number | undefined, selectionVersion = 0, previewVersion = 0
const examples = ['概括这段视频', '刚才提到的缓存失效原因有什么区别', '哪些片段值得单独剪']
const names: Record<string, string> = { QUEUED: '等待回答', RUNNING: '正在查找与回答', SUCCEEDED: '回答完成', FAILED: '本轮未完成', CANCELLED: '已取消' }
const scopes = { VIDEO: '来自视频', GENERAL: '通用知识补充', INSUFFICIENT: '视频证据不足' }
const toolNames = { search_transcript: '检索视频原句', read_context: '补读相邻内容', list_topics: '查看视频话题' }
defineExpose({ dirty, pending })

function message(value: unknown) {
  const rawCode = value instanceof ApiError ? value.code : value instanceof AgentResponseError ? 'INVALID_AGENT_RESPONSE' : 'CLIENT_ERROR'
  const code = typeof rawCode === 'string' && /^[A-Z][A-Z0-9_]{0,99}$/.test(rawCode) ? rawCode : 'CLIENT_ERROR'
  const known: Record<string, string> = {
    NETWORK_UNAVAILABLE: '暂时无法连接服务，输入和已有回答仍保留。',
    REQUEST_TIMEOUT: '请求超时，问题输入仍保留。再次发送会沿用同一个请求标识，请先等状态刷新核对。',
    INVALID_AGENT_RESPONSE: '返回的对话内容不完整或来源不匹配，已保留本页输入和已有回答。',
    AGENT_SESSION_BUSY: '本对话还有一轮正在执行，请等待回答或取消本轮后再提问。',
    AGENT_SESSION_LIMIT: '当前对话已达到轮数上限，请新建对话后继续。',
    AGENT_SOURCE_NOT_READY: '请先完成视频话题解析，再围绕视频提问。',
    AGENT_SOURCE_UNAVAILABLE: '当前视频的分析来源暂时不可读取，已有对话和输入仍保留。',
    AGENT_SOURCE_INVALID: '视频分析来源未通过校验，已有对话和输入仍保留。',
    AGENT_SOURCE_CHANGED: '视频来源发生变化，请先核对素材，已有对话和输入仍保留。',
    AGENT_HISTORY_INVALID: '对话历史未通过校验，请刷新核对记录，当前输入仍保留。',
    AGENT_TURN_NOT_CANCELLABLE: '本轮已结束，请等待状态刷新后再操作。',
    AGENT_STAGE_UNAVAILABLE: '本轮执行状态暂时不可用，请刷新后核对，当前输入仍保留。',
    IDEMPOTENCY_CONFLICT: '请求标识与此前输入不一致，请核对本页问题和已有记录。',
  }
  return known[code] ?? `对话操作未完成：${code}`
}
function parseSession(raw: unknown, id?: string) { return parseAgentSession(raw, props.jobId, props.videoId, props.videoHash, id) }
function upsertSession(value: AgentSession) {
  const index = sessions.value.findIndex(session => session.id === value.id)
  const latest = newestAgentSession(index < 0 ? undefined : sessions.value[index], value)
  if (index < 0) sessions.value.unshift(latest)
  else sessions.value[index] = latest
  sessions.value.sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at))
  return latest
}
function upsertTurn(value: AgentTurn, sessionId: string) {
  const index = sessions.value.findIndex(session => session.id === sessionId)
  const session = sessions.value[index]
  if (!session) throw new AgentResponseError()
  const known = session.turns.find(turn => turn.id === value.id)
  const latest = newestAgentTurn(known, value)
  const turns = [...session.turns.filter(turn => turn.id !== value.id), latest].sort((a, b) => a.sequence - b.sequence)
  if (new Set(turns.map(turn => turn.sequence)).size !== turns.length) throw new AgentResponseError()
  sessions.value[index] = { ...session, turns }
}
function valid(version: number, sessionId?: string) { return !stopped && version === selectionVersion && (!sessionId || selectedId.value === sessionId) }
function canSwitch() {
  if (pending.value) { error.value = '当前操作正在提交，请稍后切换对话。'; return false }
  if (dirty.value) { error.value = '问题尚未发送，请先发送或放弃输入，再切换对话。'; return false }
  return true
}
async function selectSession(id: string) {
  if (id === selectedId.value || !canSwitch()) return
  scope.invalidate(); selectionVersion++; selectedId.value = id; citation.value = null; previewVersion++
  sourcePlayer.value?.pause(); error.value = ''; notice.value = ''; playbackError.value = ''
  await refresh()
}
async function refresh() {
  if (stopped || refreshing) return
  refreshing = true
  const read = scope.begin(), jobId = props.jobId
  try {
    const raw = await api<{ items: unknown[] }>(`/api/jobs/${jobId}/agent-sessions`, { signal: read.signal })
    if (!read.current() || jobId !== props.jobId) return
    if (!Array.isArray(raw.items)) throw new AgentResponseError()
    const incoming = raw.items.map(value => parseSession(value))
    if (new Set(incoming.map(value => value.id)).size !== incoming.length) throw new AgentResponseError()
    for (const value of incoming) upsertSession(value)
    if (!selectedId.value && sessions.value[0] && !pending.value) selectedId.value = sessions.value[0].id
    const id = selectedId.value
    if (id) {
      const detail = await api<unknown>(`/api/agent-sessions/${id}`, { signal: read.signal })
      if (!read.current() || selectedId.value !== id) return
      upsertSession(parseSession(detail, id))
    }
    refreshError.value = ''
  } catch (value) { if (read.current()) refreshError.value = message(value) }
  finally { read.release(); refreshing = false; loading.value = false }
}
async function poll() { await refresh(); if (!stopped) timer = window.setTimeout(() => { void poll() }, 4000) }
function readReceipt(key: string): AgentReceipt | null {
  if (receiptCache.has(key)) return receiptCache.get(key)!
  try {
    const value: unknown = JSON.parse(localStorage.getItem(key) ?? 'null')
    if (value && typeof value === 'object' && 'input' in value && typeof value.input === 'string' && value.input.length <= 5000
      && 'key' in value && typeof value.key === 'string' && /^[A-Za-z0-9_-]{1,100}$/.test(value.key)) return value as AgentReceipt
  } catch { /* A corrupt receipt must not be used to identify a submission. */ }
  return null
}
function storeReceipt(key: string, value: AgentReceipt) {
  receiptCache.set(key, value)
  try { localStorage.setItem(key, JSON.stringify(value)) } catch { /* Keep the mounted-view receipt. */ }
}
function clearReceipt(key: string, value: AgentReceipt) {
  receiptCache.delete(key)
  try { if (readReceipt(key)?.key === value.key) localStorage.removeItem(key) } catch { /* The accepted response remains visible. */ }
}
async function requestSession(version: number): Promise<AgentSession | null> {
  const storageKey = `livecut-agent-session-${props.jobId}`, previous = readReceipt(storageKey)
  const receipt = previous?.input === '{}' ? previous : { input: '{}', key: crypto.randomUUID() }
  storeReceipt(storageKey, receipt)
  const raw = await post<unknown>(`/api/jobs/${props.jobId}/agent-sessions`, {}, { 'Idempotency-Key': receipt.key })
  if (!valid(version)) return null
  const value = upsertSession(parseSession(raw))
  selectedId.value = value.id; citation.value = null; previewVersion++; sourcePlayer.value?.pause()
  clearReceipt(storageKey, receipt)
  return value
}
async function newSession() {
  if (!canSwitch()) return
  scope.invalidate(); const version = ++selectionVersion
  action.value = 'session'; error.value = ''; notice.value = ''
  try { if (await requestSession(version)) notice.value = '新对话已创建，可以围绕这段视频提问。' }
  catch (value) { if (valid(version)) error.value = message(value) }
  finally { if (valid(version)) action.value = '' }
}
async function send() {
  const input = question.value.trim(), budget = maxCalls.value
  if (pending.value || !input) return
  if (input.length > 2000 || !Number.isInteger(budget) || budget < 1 || budget > 24) { error.value = '问题最多 2000 字，模型请求上限须为 1～24 的整数。'; return }
  if (activeTurn.value) { error.value = '本对话还有一轮正在执行，请等待回答或取消本轮后再提问。'; return }
  scope.invalidate(); const version = ++selectionVersion
  action.value = 'send'; error.value = ''; notice.value = ''
  try {
    const session = selected.value ?? await requestSession(version)
    if (!session || !valid(version, session.id)) return
    const storageKey = `livecut-agent-turn-${session.id}`
    const receipt = agentTurnReceipt(readReceipt(storageKey), input, budget, () => crypto.randomUUID())
    storeReceipt(storageKey, receipt)
    const raw = await post<unknown>(`/api/agent-sessions/${session.id}/turns`, { question: input, max_text_calls: budget }, { 'Idempotency-Key': receipt.key })
    if (!valid(version, session.id)) return
    const turn = parseAgentTurn(raw, session)
    if (turn.question !== input || turn.budget.max_text_calls !== budget) throw new AgentResponseError()
    upsertTurn(turn, session.id); clearReceipt(storageKey, receipt); question.value = ''
    notice.value = '问题已保存，回答与引用会自动更新。'
  } catch (value) { if (valid(version)) error.value = message(value) }
  finally { if (valid(version)) action.value = '' }
}
async function cancel(turn: AgentTurn) {
  const session = selected.value
  if (pending.value || !session || !agentTurnActive(turn)) return
  scope.invalidate(); const version = ++selectionVersion
  action.value = 'cancel'; error.value = ''; notice.value = ''
  try {
    const raw = await post<unknown>(`/api/agent-turns/${turn.id}/cancel`)
    if (!valid(version, session.id)) return
    upsertTurn(parseAgentTurn(raw, session, turn.id), session.id)
    notice.value = '本轮取消状态已更新，已有回答和本页输入仍保留。'
  } catch (value) { if (valid(version, session.id)) error.value = message(value) }
  finally { if (valid(version, session.id)) action.value = '' }
}
function chooseExample(value: string) {
  if (pending.value || dirty.value) { error.value = '请先发送或放弃当前问题，再选择示例。'; return }
  question.value = value; error.value = ''
}
function discard() { if (!pending.value) { question.value = ''; error.value = ''; notice.value = '' } }
function suggest(value: string) {
  if (!canPrefillClipInstruction(value, !!props.clipTargetBusy, pending.value)) { error.value = '剪辑区域有未提交内容或正在提交，请先处理后再送入要求。'; return }
  emit('clipInstruction', value)
}
async function preview(value: AgentCitation) {
  citation.value = value; playbackError.value = ''; const version = ++previewVersion
  await nextTick()
  if (!stopped && version === previewVersion) sourcePlayer.value?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  if (!stopped && version === previewVersion && sourcePlayer.value && sourcePlayer.value.readyState >= 1) seekCitation()
}
function seekCitation() {
  const player = sourcePlayer.value, value = citation.value
  if (!player || !value) return
  try { player.currentTime = value.start_ms / 1000; void player.play().catch(() => { playbackError.value = '点击视频播放按钮，可从所选原句继续回看。' }) }
  catch { playbackError.value = '暂时无法定位这条原句，请检查原视频是否可播放。' }
}
function previewTime() { if (sourcePlayer.value && citation.value && sourcePlayer.value.currentTime >= citation.value.end_ms / 1000) sourcePlayer.value.pause() }
function date(value: string) { return new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }
function unload(event: BeforeUnloadEvent) { if (dirty.value || pending.value) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => { window.addEventListener('beforeunload', unload); void poll() })
onBeforeUnmount(() => { stopped = true; selectionVersion++; previewVersion++; scope.dispose(); window.clearTimeout(timer); sourcePlayer.value?.pause(); window.removeEventListener('beforeunload', unload) })
</script>

<template>
  <section class="panel agent-panel" aria-label="视频对话助手">
    <div class="panel-heading"><div><div class="eyebrow">ASK ABOUT THIS VIDEO</div><h2>视频对话助手</h2></div><button class="text-button" :disabled="pending || dirty" @click="newSession">新建对话</button></div>
    <p class="agent-intro">围绕这段视频提问、追问，查看回答依据并回看原句。通用知识补充会单独标注；剪辑建议可以送到下方要求区，由你再提交。</p>
    <p v-if="error" class="message error" role="alert">{{ error }}</p><p v-if="notice" class="message notice" role="status">{{ notice }}</p>
    <p v-if="refreshError" class="agent-refresh-error" role="status">{{ refreshError }} 已有记录与输入仍保留。</p>
    <div class="agent-layout">
      <aside class="agent-sessions" aria-label="视频对话列表"><div class="agent-section-heading"><h3>对话 {{ sessions.length }}</h3><button class="text-button" :disabled="pending" @click="refresh">刷新</button></div><p v-if="loading && !sessions.length" class="help">正在读取对话…</p><p v-else-if="!sessions.length" class="help">发送第一个问题，即可开始对话。</p><button v-for="session in sessions" :key="session.id" class="agent-session" :class="{ selected: selectedId === session.id }" :aria-pressed="selectedId === session.id" :disabled="pending" @click="selectSession(session.id)"><strong>{{ session.turns[0]?.question ?? '新对话' }}</strong><small>{{ date(session.created_at) }} · {{ session.turns.length }} 轮</small></button></aside>
      <div class="agent-conversation">
        <div v-if="!selected?.turns.length" class="agent-welcome"><h3>从视频里找答案</h3><p>可以先概括内容，也可以沿着已有回答继续追问。证据不足时会明确说明。</p></div>
        <ol v-else class="agent-turns" aria-label="对话记录"><li v-for="turn in selected.turns" :key="turn.id" class="agent-turn"><div class="agent-question"><span>你的问题 · 第 {{ turn.sequence }} 轮</span><p>{{ turn.question }}</p></div><div class="agent-answer"><div class="agent-answer-heading"><strong>视频助手</strong><span class="agent-status">{{ names[turn.status] }}</span><button v-if="agentTurnActive(turn)" class="text-button" :disabled="pending" @click="cancel(turn)">{{ action === 'cancel' ? '正在取消…' : '取消本轮' }}</button></div><TextRequestProgress :progress="turn.stage.text_progress" :terminal="!agentTurnActive(turn)" /><FailureDiagnostic :diagnostic="turn.stage.diagnostic" :error-code="turn.stage.error_code" :attempt="turn.stage.attempt" />
          <template v-if="turn.result"><span class="agent-scope" :class="turn.result.answer_scope.toLowerCase()">{{ scopes[turn.result.answer_scope] }}</span><p class="agent-answer-text">{{ turn.result.answer }}</p><p v-if="turn.result.answer_scope === 'GENERAL'" class="help">这部分是通用知识补充，不能当作视频中已经说过的话。</p><p v-else-if="turn.result.answer_scope === 'INSUFFICIENT'" class="help">当前视频证据不足，可换个说法，或补充想找的具体内容。</p>
            <div v-if="turn.result.citations.length" class="agent-citations"><h4>视频原句依据</h4><button v-for="source in turn.result.citations" :key="source.utterance_id" class="agent-citation" :class="{ selected: citation?.utterance_id === source.utterance_id }" @click="preview(source)"><span>回看 {{ topicTime(source.start_ms) }} → {{ topicTime(source.end_ms) }}</span><p>{{ source.text }}</p></button></div>
            <details v-if="turn.result.tool_calls.length" class="agent-tools"><summary>查找过程 · {{ turn.result.tool_calls.length }} 步</summary><ol><li v-for="call in turn.result.tool_calls" :key="call.step"><strong>{{ call.step }}. {{ toolNames[call.tool_name] }}</strong><p v-if="call.tool_name === 'search_transcript'">查询：{{ call.arguments.query }}</p><p v-else-if="call.tool_name === 'read_context'">补读前 {{ call.arguments.before }} 句、后 {{ call.arguments.after }} 句</p><p v-else>查看第 {{ Number(call.arguments.offset) + 1 }} 项起的话题，最多 {{ call.arguments.limit }} 项</p><small>返回 {{ call.result_utterance_ids.length }} 条原句依据</small></li></ol></details>
            <div v-if="turn.result.suggested_clip_instruction" class="agent-clip-suggestion"><p>{{ turn.result.suggested_clip_instruction }}</p><button class="secondary" :disabled="pending || clipTargetBusy" @click="suggest(turn.result.suggested_clip_instruction)">送到剪辑要求</button><small v-if="clipTargetBusy">请先保存或放弃剪辑区域的未提交内容。</small><small v-else>仅填入要求，你仍需在剪辑区域点击查找、核对并确认。</small></div>
          </template><p v-else class="help">{{ turn.status === 'CANCELLED' ? '本轮已取消，已有对话和视频结果仍保留。' : turn.status === 'FAILED' ? '本轮未生成可用回答。请查看原因，原转写与此前回答仍保留。' : turn.status === 'QUEUED' ? '问题已保存，正在等待执行服务。' : '正在结合视频内容查找证据并回答，进度会自动更新。' }}</p>
        </div></li></ol>
        <div v-if="citation" class="agent-preview"><div class="agent-section-heading"><h3>回看原句</h3><span>{{ topicTime(citation.start_ms) }} → {{ topicTime(citation.end_ms) }}</span></div><video ref="sourcePlayer" :src="videoUrl" controls preload="metadata" playsinline @loadedmetadata="seekCitation" @timeupdate="previewTime"></video><p v-if="playbackError" class="help" role="status">{{ playbackError }}</p></div>
        <form class="agent-compose" @submit.prevent="send"><div class="agent-examples"><button v-for="example in examples" :key="example" type="button" class="text-button" :disabled="pending || dirty" @click="chooseExample(example)">{{ example }}</button></div><label for="agent-question">向视频助手提问</label><textarea id="agent-question" v-model="question" :disabled="pending" maxlength="2000" rows="3" placeholder="例如：视频里缓存失效的原因是什么？与刚才的方法有什么区别？"></textarea><div class="agent-compose-actions"><label>本轮模型请求上限<input v-model.number="maxCalls" type="number" min="1" max="24" :disabled="pending" aria-label="对话模型请求上限"></label><span>{{ question.length }} / 2000</span><button v-if="dirty" class="text-button" type="button" :disabled="pending" @click="discard">放弃输入</button><button class="primary" type="submit" :disabled="pending || !question.trim() || !!activeTurn">{{ action === 'send' ? '正在提交…' : selected ? '发送问题' : '开始提问' }}</button></div><p class="help">复用当前视频已完成的转写。自动恢复也计入本轮请求上限；对话中的建议不会自动创建剪辑或导出。</p><p v-if="activeTurn" class="help" role="status">本对话正在处理上一轮，可以先编辑问题，等待回答或取消本轮后发送。</p></form>
      </div>
    </div>
  </section>
</template>

<style scoped>
.agent-panel { margin-top: 24px; padding: 24px; min-width: 0; }
.agent-panel h2, .agent-panel h3, .agent-panel h4, .agent-panel p { margin-top: 0; }
.agent-intro, .agent-refresh-error { font-size: 12px; line-height: 1.8; color: #73816d; overflow-wrap: anywhere; }
.agent-refresh-error { color: #a07250; }
.agent-layout { display: grid; grid-template-columns: minmax(140px, 210px) minmax(0, 1fr); gap: 22px; margin-top: 20px; }
.agent-sessions { min-width: 0; border-right: 1px solid #e6ebe0; padding-right: 16px; }
.agent-section-heading, .agent-answer-heading, .agent-compose-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 12px; }
.agent-section-heading h3, .agent-preview h3 { margin: 0; font-size: 13px; color: #5f7051; }
.agent-session { display: block; width: 100%; border: 1px solid transparent; border-radius: 6px; background: transparent; text-align: left; padding: 12px 10px; margin-top: 8px; color: #64745a; }
.agent-session strong, .agent-session small { display: block; font-size: 11px; line-height: 1.7; overflow-wrap: anywhere; }
.agent-session strong { font-weight: 500; }
.agent-session small { color: #95a08a; margin-top: 4px; }
.agent-session.selected { border-color: #dce6d2; background: #f3f7ee; }
.agent-conversation { min-width: 0; }
.agent-welcome { padding: 20px 0; color: #78866e; }
.agent-welcome h3 { color: #5d7050; font-size: 17px; font-weight: 500; }
.agent-welcome p { font-size: 12px; line-height: 1.8; }
.agent-turns, .agent-tools ol { padding: 0; margin: 0; list-style: none; }
.agent-turn { margin-bottom: 22px; }
.agent-question { padding: 14px 16px; border-radius: 7px; background: #f2f6ed; color: #536447; }
.agent-question span { display: block; color: #89977d; font-size: 10px; margin-bottom: 5px; }
.agent-question p, .agent-answer-text, .agent-clip-suggestion > p { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.9; font-size: 12px; margin-bottom: 0; }
.agent-answer { padding: 16px 0 4px; }
.agent-answer-heading { justify-content: flex-start; margin-bottom: 12px; }
.agent-answer-heading > strong { font-weight: 500; font-size: 13px; color: #667c52; }
.agent-status { font-size: 10px; color: #95a087; }
.agent-scope { display: inline-block; border-radius: 4px; padding: 3px 7px; background: #edf4e6; color: #738a5b; font-size: 10px; margin-bottom: 8px; }
.agent-scope.general { background: #edf1f8; color: #7185a0; }
.agent-scope.insufficient { background: #fbf1e7; color: #a27e55; }
.agent-answer-text { margin-bottom: 14px; color: #5f6e55; }
.agent-citations h4 { font-size: 11px; font-weight: 500; color: #819170; margin-bottom: 8px; }
.agent-citation { width: 100%; padding: 10px 12px; background: #fbfcf9; border: 1px solid #e7ecdf; border-radius: 5px; text-align: left; margin-bottom: 7px; color: #708261; }
.agent-citation span { font-size: 10px; color: #8b9b7b; }
.agent-citation p { margin: 5px 0 0; font-size: 11px; line-height: 1.8; overflow-wrap: anywhere; }
.agent-citation.selected { border-color: #b3c69e; background: #f4f8ef; }
.agent-tools { color: #8c997f; font-size: 11px; margin: 12px 0; }
.agent-tools summary { cursor: pointer; }
.agent-tools ol { padding: 10px 0; }
.agent-tools li { padding: 6px 0 9px 12px; border-left: 1px solid #dfe7d6; overflow-wrap: anywhere; }
.agent-tools strong { font-weight: 500; color: #728760; }
.agent-tools p { margin: 4px 0; line-height: 1.7; }
.agent-tools small { color: #a1ac97; }
.agent-clip-suggestion { border: 1px solid #e2e9d9; border-radius: 5px; padding: 12px; background: #f9fbf6; margin-top: 14px; }
.agent-clip-suggestion > p { margin-bottom: 10px; color: #6e7e60; }
.agent-clip-suggestion small { display: block; color: #8b987e; font-size: 10px; line-height: 1.7; margin-top: 8px; }
.agent-preview { margin: 18px 0; padding: 12px; border: 1px solid #e3eadb; border-radius: 6px; }
.agent-preview .agent-section-heading > span { font-size: 10px; color: #89977c; }
.agent-preview video { display: block; width: 100%; max-height: 340px; margin-top: 10px; background: #1c2516; border-radius: 4px; }
.agent-compose { border-top: 1px solid #e6ecdf; padding-top: 14px; }
.agent-examples { display: flex; flex-wrap: wrap; gap: 6px 15px; margin-bottom: 12px; }
.agent-examples button { text-align: left; font-size: 10px; }
.agent-compose > label, .agent-compose-actions label { display: flex; flex-direction: column; gap: 7px; color: #7b8a6e; font-size: 11px; }
.agent-compose textarea { width: 100%; resize: vertical; min-height: 90px; border: 1px solid #dfe7d6; background: #fff; border-radius: 5px; font: inherit; line-height: 1.8; padding: 10px 12px; color: #5e7050; margin-top: 7px; box-sizing: border-box; }
.agent-compose-actions { margin-top: 12px; font-size: 10px; color: #95a18a; }
.agent-compose-actions input { width: 74px; border: 1px solid #dfe7d6; border-radius: 4px; color: #687b59; padding: 6px 8px; background: white; }
.agent-panel button:disabled { cursor: not-allowed; opacity: .55; }
@media (max-width: 720px) { .agent-panel { padding: 16px; } .agent-layout { grid-template-columns: minmax(0, 1fr); gap: 16px; } .agent-sessions { border-right: 0; border-bottom: 1px solid #e6ebe0; padding: 0 0 12px; max-height: 200px; overflow-y: auto; } .agent-session { padding: 8px; } .agent-compose-actions { gap: 12px; } }
</style>
