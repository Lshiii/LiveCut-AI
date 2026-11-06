<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, ApiError, post, type ExportJob } from './api'
import FailureDiagnostic from './FailureDiagnostic.vue'
import TextRequestProgress from './TextRequestProgress.vue'
import { canPrefillClipInstruction } from './videoAgent'
import { newestSnapshot, parseExportSnapshot, SnapshotError } from './snapshotStream'
import { topicTime } from './topics'
import { editIntentBoundary, intentCanCancel, intentCanConfirm, intentCanExport, intentCanResume, intentOutputValidation, intentPayload, intentResumeReceipt,
  IntentReadScope, IntentResponseError, newestIntentSnapshot, parseIntentSnapshot, refreshIntentEvidence,
  parseResumedIntentSnapshot, type IntentOutput, type IntentPart, type IntentResumeReceipt, type IntentSnapshot } from './intentClips'

const props = defineProps<{ jobId: string; videoUrl: string; active?: boolean }>()
const instruction = ref(''), maxCalls = ref(6), requests = ref<IntentSnapshot[]>([])
const selectedId = ref(''), snapshot = ref<IntentSnapshot | null>(null), outputs = ref<IntentOutput[]>([])
const baseline = ref(''), draftRevision = ref<number | null>(null), action = ref('')
const error = ref(''), refreshError = ref(''), notice = ref(''), loading = ref(true)
const batches = ref<ExportJob[]>([]), previewUrl = ref(''), sourcePlayer = ref<HTMLVideoElement | null>(null)
const instructionInput = ref<HTMLTextAreaElement | null>(null)
const exportPlayer = ref<HTMLVideoElement | null>(null)
const scope = new IntentReadScope()
let stopped = false, refreshing = false, timer: number | undefined, selectionVersion = 0, focusVersion = 0
let pendingCreate: { input: string; key: string } | null = null
const pendingResumes = new Map<string, IntentResumeReceipt>()
const pending = computed(() => !!action.value)
const planDirty = computed(() => !!snapshot.value?.plan && JSON.stringify(intentPayload(outputs.value)) !== baseline.value)
const dirty = computed(() => planDirty.value || !!instruction.value.trim())
const validation = computed(() => snapshot.value?.plan
  ? intentOutputValidation(outputs.value, snapshot.value.utterances, snapshot.value.source.duration_ms, snapshot.value.plan.goals) : '')
const canConfirm = computed(() => !instruction.value.trim() && intentCanConfirm(snapshot.value, outputs.value, planDirty.value, pending.value))
const canExport = computed(() => !instruction.value.trim() && intentCanExport(snapshot.value, planDirty.value, pending.value))
const canCancel = computed(() => intentCanCancel(snapshot.value, planDirty.value, pending.value))
const canResume = computed(() => intentCanResume(snapshot.value, dirty.value, pending.value))
const selectedCount = computed(() => outputs.value.filter(value => value.enabled).length)
const unresolved = computed(() => outputs.value.filter(value => value.status !== 'FOUND').length)
const confirmed = computed(() => !!snapshot.value && snapshot.value.confirmed_revisions.includes(snapshot.value.revision))
const names: Record<string, string> = { QUEUED: '等待执行', RUNNING: '正在查找', PLAN_READY: '候选已生成', FAILED: '执行失败', CANCELLED: '已取消',
  FOUND: '已找到证据', NOT_FOUND: '未找到', NEEDS_REVIEW: '需进一步核对', READY: '等待领取', BLOCKED: '等待前序',
  RETRY_WAIT: '等待重试', SUCCEEDED: '已完成', PARTIAL_FAILED: '部分失败', SKIPPED: '已跳过' }
defineExpose({ dirty, pending, prefillInstruction })
function prefillInstruction(value: string): boolean {
  if (!canPrefillClipInstruction(value, dirty.value, pending.value)) return false
  instruction.value = value.trim(); error.value = ''; notice.value = '已填入视频助手的建议。请核对要求，再点击查找片段。'
  const version = ++focusVersion
  void nextTick(() => { if (!stopped && props.active !== false && version === focusVersion) instructionInput.value?.focus() })
  return true
}

function message(value: unknown) {
  const code = value instanceof ApiError ? value.code : value instanceof IntentResponseError || value instanceof SnapshotError ? 'INVALID_CLIP_RESPONSE' : 'CLIENT_ERROR'
  const messages: Record<string, string> = {
    NETWORK_UNAVAILABLE: '无法连接服务，本页输入和方案修改仍然保留。',
    REQUEST_TIMEOUT: '请求超时。创建或继续查找可再次点击，系统会复用同一个请求标识；其余操作请先重新读取核对服务端状态。',
    CLIP_PLAN_REVISION_CONFLICT: '此方案已有新版本。本页修改仍保留，请核对后再决定是否放弃并重新读取。',
    CLIP_REQUEST_NOT_READY: '候选尚未生成，请等待执行完成。',
    CLIP_PLAN_NOT_CONFIRMED: '请先保存并确认当前方案版本，再导出。',
    CLIP_EMPTY_SELECTION: '请至少勾选一个已找到证据的目标。',
    INVALID_CLIP_PLAN: '标题、原句区间或证据不符合要求，请检查后再保存。',
    CLIP_PLAN_SOURCE_INVALID: '原自动方案校验未通过，请核对来源或重新查找片段。当前修改仍然保留。',
    INVALID_CLIP_RESPONSE: '返回的剪辑方案不完整、来源不匹配或版本过期，已保留本页内容。',
    MODEL_BUDGET_EXHAUSTED: '本次请求的模型次数已用尽，请查看执行原因，调整要求后另建请求。',
    CLIP_REQUEST_NOT_CANCELLABLE: '当前请求已不在等待或执行状态，无法取消。请核对自动更新后的结果。',
    CLIP_REQUEST_NOT_RESUMABLE: '只有失败或已取消的请求可以继续查找。原请求记录保持不变。',
    CLIP_RESUME_SOURCE_CHANGED: '原视频或转写来源已变化，无法复用本次请求。请重新核对来源。',
    IDEMPOTENCY_CONFLICT: '这次操作的请求标识与内容不一致，已保留本页内容，请重新读取核对。',
    INVALID_CLIP_REQUEST: '剪辑要求或文本请求上限无效，请检查后再操作。',
  }
  return messages[code] ?? `剪辑操作未完成：${code}`
}
function fail(value: unknown) { error.value = message(value) }
function upsert(value: IntentSnapshot) {
  const index = requests.value.findIndex(row => row.id === value.id)
  const latest = newestIntentSnapshot(index < 0 ? undefined : requests.value[index], value)
  if (index < 0) requests.value.unshift(latest)
  else requests.value[index] = latest
  return latest
}
function accept(value: IntentSnapshot, force = false) {
  const protectedDraft = planDirty.value
  const latest = newestIntentSnapshot(snapshot.value?.id === value.id ? snapshot.value : null, upsert(value))
  if (selectedId.value !== value.id) return
  if (force && latest.revision > value.revision) throw new ApiError('CLIP_PLAN_REVISION_CONFLICT')
  snapshot.value = latest
  if (force || (!protectedDraft && !pending.value)) {
    outputs.value = latest.plan ? intentPayload(latest.plan.outputs).outputs : []
    baseline.value = JSON.stringify(intentPayload(outputs.value)); draftRevision.value = latest.revision
  }
}
function canChangeRequest() {
  if (pending.value) { error.value = '当前操作正在提交，请稍后切换请求。'; return false }
  if (dirty.value) { error.value = '本页有尚未提交的输入或方案修改，请先提交、保存或放弃本页修改。'; return false }
  return true
}
async function selectRequest(id: string) {
  if (id === selectedId.value || !canChangeRequest()) return
  scope.invalidate(); selectionVersion++; selectedId.value = id; snapshot.value = null; outputs.value = []
  baseline.value = ''; draftRevision.value = null; batches.value = []; previewUrl.value = ''; error.value = ''; notice.value = ''
  const known = requests.value.find(row => row.id === id)
  if (known) accept(known, true)
  await refresh()
}
async function refresh() {
  if (refreshing || stopped) return
  refreshing = true
  const read = scope.begin(), jobId = props.jobId
  try {
    const raw = await api<{ items: unknown[] }>(`/api/jobs/${jobId}/clip-requests`, { signal: read.signal })
    if (!read.current() || jobId !== props.jobId) return
    if (!Array.isArray(raw.items)) throw new IntentResponseError()
    const ids = new Set<string>()
    for (const item of raw.items) {
      const value = parseIntentSnapshot(item, jobId)
      if (ids.has(value.id)) throw new IntentResponseError()
      ids.add(value.id); upsert(value)
    }
    requests.value.sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at))
    if (!selectedId.value && requests.value[0]) {
      selectedId.value = requests.value[0].id; accept(requests.value[0], true)
    }
    const requestId = selectedId.value
    if (requestId) {
      const detail = await api<unknown>(`/api/clip-requests/${requestId}`, { signal: read.signal })
      if (!read.current() || selectedId.value !== requestId) return
      accept(parseIntentSnapshot(detail, jobId, requestId))
      const exported = await api<{ items: unknown[] }>(`/api/clip-requests/${requestId}/exports`, { signal: read.signal })
      if (!read.current() || selectedId.value !== requestId) return
      if (!Array.isArray(exported.items)) throw new IntentResponseError()
      const incoming = exported.items.map(value => {
        if (!value || typeof value !== 'object' || !('id' in value) || typeof value.id !== 'string') throw new IntentResponseError()
        return parseExportSnapshot(value, value.id, jobId)
      })
      batches.value = incoming.map(value => newestSnapshot(batches.value.find(known => known.id === value.id), value))
    }
    refreshError.value = ''
  } catch (value) { if (read.current()) refreshError.value = message(value) }
  finally { read.release(); refreshing = false; loading.value = false }
}
async function poll() {
  await refresh()
  if (!stopped) timer = window.setTimeout(() => { void poll() }, 5000)
}
function validOperation(requestId: string, version: number) { return !stopped && selectedId.value === requestId && version === selectionVersion }
async function create() {
  const query = instruction.value.trim()
  if (pending.value || !query) return
  if ([...query].length > 2000) { error.value = '剪辑要求最多 2000 字。'; return }
  if (!Number.isInteger(maxCalls.value) || maxCalls.value < 1 || maxCalls.value > 24) { error.value = '模型请求上限须为 1～24 的整数。'; return }
  if (planDirty.value) { error.value = '请先保存或放弃当前方案修改，再创建其他请求。'; return }
  const jobId = props.jobId, payload = { instruction: query, max_text_calls: maxCalls.value }
  const storageKey = `livecut-intent-create-${jobId}`, input = JSON.stringify(payload)
  let saved: { input: string; key: string } | null = pendingCreate
  if (!saved) { try { saved = JSON.parse(localStorage.getItem(storageKey) ?? 'null') } catch { /* A corrupt old receipt is never reused. */ } }
  const key = saved?.input === input && typeof saved.key === 'string' ? saved.key : crypto.randomUUID()
  pendingCreate = { input, key }
  try { localStorage.setItem(storageKey, JSON.stringify(pendingCreate)) } catch { /* Keep the mounted-view receipt when storage is unavailable. */ }
  action.value = 'create'; error.value = ''; notice.value = ''; scope.invalidate()
  const version = ++selectionVersion
  try {
    const raw = await post<unknown>(`/api/jobs/${jobId}/clip-requests`, payload, { 'Idempotency-Key': key })
    if (stopped || version !== selectionVersion || jobId !== props.jobId) return
    const value = parseIntentSnapshot(raw, jobId)
    selectedId.value = value.id; snapshot.value = null; outputs.value = []; batches.value = []; previewUrl.value = ''
    instruction.value = ''; accept(value, true)
    pendingCreate = null
    try {
      const current = JSON.parse(localStorage.getItem(storageKey) ?? 'null')
      if (current?.key === key) localStorage.removeItem(storageKey)
    } catch { /* The accepted request remains visible even if local storage is unavailable. */ }
    notice.value = '请求已保存，执行服务会查找符合要求的片段。原话题草稿保持独立。'
  } catch (value) { if (!stopped && version === selectionVersion) fail(value) }
  finally { if (!stopped && version === selectionVersion) action.value = '' }
}
async function cancelRequest() {
  if (!canCancel.value || !snapshot.value) return
  const id = selectedId.value, version = selectionVersion
  action.value = 'cancel'; error.value = ''; notice.value = ''; scope.invalidate()
  try {
    const raw = await post<unknown>(`/api/clip-requests/${id}/cancel`, {})
    if (!validOperation(id, version)) return
    const value = parseIntentSnapshot(raw, props.jobId, id)
    if (value.status !== 'CANCELLED') throw new IntentResponseError()
    accept(value)
    notice.value = '已取消后续处理，原视频、已完成转写和调用记录仍保留。已发出的模型请求可能仍由服务方处理。'
  } catch (value) { if (validOperation(id, version)) fail(value) }
  finally { if (validOperation(id, version)) action.value = '' }
}
async function resumeRequest() {
  const source = snapshot.value
  if (!source || !canChangeRequest() || !intentCanResume(source, dirty.value, pending.value)) return
  if (!Number.isInteger(maxCalls.value) || maxCalls.value < 1 || maxCalls.value > 24) { error.value = '模型请求上限须为 1～24 的整数。'; return }
  const jobId = props.jobId, id = source.id, budget = maxCalls.value
  const storageKey = `livecut-intent-resume-${id}-${budget}`
  let saved: unknown = pendingResumes.get(storageKey)
  if (!saved) { try { saved = JSON.parse(localStorage.getItem(storageKey) ?? 'null') } catch { /* Only valid receipts are reused. */ } }
  const receipt = intentResumeReceipt(saved, id, budget, () => crypto.randomUUID())
  pendingResumes.set(storageKey, receipt)
  try { localStorage.setItem(storageKey, JSON.stringify(receipt)) } catch { /* Keep the mounted-view receipt if storage is unavailable. */ }
  action.value = 'resume'; error.value = ''; notice.value = ''; scope.invalidate()
  const version = ++selectionVersion
  try {
    const raw = await post<unknown>(`/api/clip-requests/${id}/resume`, { max_text_calls: budget }, { 'Idempotency-Key': receipt.key })
    if (!validOperation(id, version) || props.jobId !== jobId) return
    const value = parseResumedIntentSnapshot(raw, source, budget)
    selectedId.value = value.id; snapshot.value = null; outputs.value = []; batches.value = []; previewUrl.value = ''
    accept(value, true)
    pendingResumes.delete(storageKey)
    try {
      const current = JSON.parse(localStorage.getItem(storageKey) ?? 'null')
      if (current?.key === receipt.key) localStorage.removeItem(storageKey)
    } catch { /* The accepted new request remains visible. */ }
    notice.value = '已复用原转写，新建一次文本分析。新请求按设置的次数上限执行，旧请求和调用记录仍保留。'
  } catch (value) { if (validOperation(id, version) && props.jobId === jobId) fail(value) }
  finally { if (!stopped && version === selectionVersion) action.value = '' }
}
async function save() {
  if (!snapshot.value?.plan || pending.value || !planDirty.value || validation.value || draftRevision.value === null) return
  const id = selectedId.value, version = selectionVersion, revision = draftRevision.value
  const body = { base_revision: revision, ...intentPayload(outputs.value) }
  action.value = 'save'; error.value = ''; notice.value = ''; scope.invalidate()
  try {
    const raw = await api<unknown>(`/api/clip-requests/${id}/plan`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    if (!validOperation(id, version)) return
    const value = parseIntentSnapshot(raw, props.jobId, id); accept(value, true)
    notice.value = `方案 r${value.revision} 已保存，请核对所选目标后确认。`
  } catch (value) { if (validOperation(id, version)) fail(value) }
  finally { if (validOperation(id, version)) action.value = '' }
}
async function confirm() {
  if (!canConfirm.value || !snapshot.value) return
  const id = selectedId.value, version = selectionVersion, revision = snapshot.value.revision, planHash = snapshot.value.plan_hash
  action.value = 'confirm'; error.value = ''; notice.value = ''; scope.invalidate()
  try {
    const raw = await post<unknown>(`/api/clip-requests/${id}/confirm`, { revision, plan_hash: planHash })
    if (!validOperation(id, version)) return
    const value = parseIntentSnapshot(raw, props.jobId, id); accept(value, true)
    notice.value = `方案 r${revision} 已确认，可将各目标分别生成独立 MP4。`
  } catch (value) { if (validOperation(id, version)) fail(value) }
  finally { if (validOperation(id, version)) action.value = '' }
}
async function exportPlan() {
  if (!canExport.value || !snapshot.value) return
  const id = selectedId.value, version = selectionVersion, revision = snapshot.value.revision, planHash = snapshot.value.plan_hash
  action.value = 'export'; error.value = ''; notice.value = ''; scope.invalidate()
  try {
    const raw = await post<unknown>(`/api/clip-requests/${id}/exports`, { revision, plan_hash: planHash, profile: 'h264-aac-v1' })
    if (!validOperation(id, version)) return
    if (!raw || typeof raw !== 'object' || !('id' in raw) || typeof raw.id !== 'string') throw new IntentResponseError()
    const value = parseExportSnapshot(raw, raw.id, props.jobId)
    const known = batches.value.findIndex(batch => batch.id === value.id)
    if (known < 0) batches.value.unshift(value)
    else batches.value[known] = newestSnapshot(batches.value[known], value)
    notice.value = '导出任务已创建。每个已选目标生成一个文件，多段内容按原视频顺序拼接。'
  } catch (value) { if (validOperation(id, version)) fail(value) }
  finally { if (validOperation(id, version)) action.value = '' }
}
function discard() {
  if (pending.value) return
  instruction.value = ''; error.value = ''; notice.value = ''
  if (snapshot.value) accept(snapshot.value, true)
}
function editBoundary(output: IntentOutput, index: number, field: 'start_utterance_id' | 'end_utterance_id', event: Event) {
  editIntentBoundary(output, index, field, (event.target as HTMLInputElement).value, snapshot.value?.utterances ?? [])
}
function addPart(output: IntentOutput) {
  if (pending.value || output.parts.length >= 12) return
  output.parts.push({ start_utterance_id: '', end_utterance_id: '', start_ms: -1, end_ms: -1 })
}
function removePart(output: IntentOutput, index: number) { output.parts.splice(index, 1); refreshIntentEvidence(output, snapshot.value?.utterances ?? []) }
function unexclude(output: IntentOutput, index: number) { output.excluded.splice(index, 1) }
function sourceText(id: string) { return snapshot.value?.utterances.find(value => value.utterance_id === id)?.text ?? '原句不可用' }
function sourceTime(id: string) { return snapshot.value?.utterances.find(value => value.utterance_id === id)?.start_ms ?? -1 }
function locate(id: string) { const start = sourceTime(id); if (sourcePlayer.value && start >= 0) { sourcePlayer.value.pause(); sourcePlayer.value.currentTime = start / 1000 } }
function goal(output: IntentOutput) { return snapshot.value?.plan?.goals.find(value => value.goal_id === output.goal_id)?.description ?? output.title }
function duration(output: IntentOutput) { return output.parts.reduce((sum, part) => sum + Math.max(0, part.end_ms - part.start_ms), 0) }
function date(value: string) { return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }
function unload(event: BeforeUnloadEvent) { if (dirty.value || pending.value) { event.preventDefault(); event.returnValue = '' } }
function pauseMedia() { sourcePlayer.value?.pause(); exportPlayer.value?.pause() }
function guardPlayback(event: Event) { if (props.active === false || stopped) (event.currentTarget as HTMLVideoElement).pause() }
watch(() => props.active, value => { if (value === false) { focusVersion++; pauseMedia() } })
onMounted(() => { window.addEventListener('beforeunload', unload); void poll() })
onBeforeUnmount(() => { stopped = true; selectionVersion++; focusVersion++; pauseMedia(); scope.dispose(); window.clearTimeout(timer); window.removeEventListener('beforeunload', unload) })
</script>

<template>
  <section class="panel intent-panel" aria-label="按需求找片段">
    <div class="panel-heading"><div><div class="eyebrow">YOUR REQUEST → CLIP PLAN</div><h2>按需求找片段</h2></div><span class="status" v-if="snapshot">{{ names[snapshot.status] }}</span></div>
    <p class="intent-intro">告诉 AI 想保留什么内容。系统复用本视频的转写，返回原句证据与候选范围；你确认后，每个目标生成独立视频。短暂错误会在次数和预算上限内自动恢复，已完成的语音转写会保留。</p>
    <div v-if="error" class="message error" role="alert"><span>{{ error }}</span><button aria-label="关闭剪辑错误" @click="error = ''">×</button></div>
    <p v-if="refreshError" class="intent-warning" role="status">{{ refreshError }} 后台状态会继续刷新，当前修改不受影响。</p>
    <p v-if="notice" class="intent-notice" role="status">{{ notice }}</p>
    <div class="intent-compose">
      <label for="intent-instruction">剪辑要求<textarea id="intent-instruction" ref="instructionInput" v-model="instruction" :disabled="pending" maxlength="2000" rows="3" placeholder="例如：把缓存失效原因和解决方法分别剪出来，保留完整解释，跳过关注引导。"></textarea></label>
      <div class="intent-create-row"><label>模型请求上限<input v-model.number="maxCalls" type="number" min="1" max="24" :disabled="pending" aria-label="剪辑模型请求上限"></label><span>复用已完成的语音转写，本次不重新提交 ASR。</span><button class="primary" :disabled="pending || !instruction.trim() || planDirty" @click="create">{{ action === 'create' ? '正在创建…' : '查找片段' }}</button></div>
    </div>
    <div class="intent-workspace">
      <aside class="intent-requests" aria-label="剪辑请求列表"><div class="intent-subheading"><h3>剪辑请求 {{ requests.length }}</h3><button class="text-button" :disabled="pending" @click="refresh">刷新</button></div><p v-if="loading" class="help">正在读取请求…</p><p v-else-if="!requests.length" class="help">还没有剪辑请求。上面的要求会保存为独立请求，不会覆盖原来的话题草稿。</p><button v-for="request in requests" :key="request.id" class="intent-request" :class="{ selected: selectedId === request.id }" :disabled="pending" @click="selectRequest(request.id)"><strong>{{ request.instruction }}</strong><span>{{ names[request.status] }} · {{ date(request.created_at) }}</span></button></aside>
      <div class="intent-result" v-if="snapshot">
        <div class="intent-subheading"><h3>当前方案 <span v-if="snapshot.plan">r{{ draftRevision }} · {{ selectedCount }} 个目标已选</span></h3><button v-if="dirty" class="text-button" :disabled="pending" @click="discard">放弃本页修改</button></div>
        <p class="intent-original">{{ snapshot.instruction }}</p>
        <p v-if="snapshot.resumed_from_request_id" class="help">本次继续自请求 #{{ snapshot.resumed_from_request_id.slice(0, 8) }}，原转写复用，文本分析与预算独立。</p>
        <p class="help">本次已预留文本请求 {{ snapshot.budget.text_calls_reserved }} / {{ snapshot.budget.max_text_calls }}，包含自动恢复尝试，为次数上限。</p>
        <div v-if="['QUEUED', 'RUNNING'].includes(snapshot.status)" class="intent-recovery-actions">
          <p class="help">不再需要继续查找时可取消，已经完成的语音转写会保留。</p>
          <button class="text-button" :disabled="!canCancel" @click="cancelRequest">{{ action === 'cancel' ? '正在取消…' : '取消本次查找' }}</button>
        </div>
        <div v-if="['FAILED', 'CANCELLED'].includes(snapshot.status)" class="intent-recovery-actions">
          <p class="help">继续查找会复用已完成转写，新建一次文本分析，使用上方设置的调用次数上限；旧请求与调用记录保留。</p>
          <p v-if="instruction.trim()" class="help">上方有尚未提交的新要求，请先提交或放弃输入，再继续当前请求。</p>
          <button class="primary" :disabled="!canResume" @click="resumeRequest">{{ action === 'resume' ? '正在继续…' : '继续查找' }}</button>
        </div>
        <TextRequestProgress :progress="snapshot.stage.text_progress" :terminal="['PLAN_READY', 'FAILED', 'CANCELLED'].includes(snapshot.status)" />
        <FailureDiagnostic v-if="snapshot.status !== 'CANCELLED'" :error-code="snapshot.stage.error_code" :attempt="snapshot.stage.attempt" />
        <p v-if="!snapshot.plan" class="intent-empty">{{ snapshot.status === 'FAILED' ? '本次查找未完成。请查看执行原因，原视频和原话题结果仍保留。' : snapshot.status === 'CANCELLED' ? '本次请求已取消。' : snapshot.status === 'RUNNING' ? '正在查找候选片段，执行进度会自动更新。你可以继续回看原视频。' : '正在等待执行服务生成候选方案。你可以继续回看原视频。' }}</p>
        <template v-else>
          <video ref="sourcePlayer" :src="videoUrl" controls preload="metadata" playsinline class="intent-source" @play="guardPlayback"></video>
          <p class="help">点击原句可定位源视频；确认后的成片会按所列区间顺序拼接。</p>
          <p v-if="unresolved" class="intent-warning">有 {{ unresolved }} 个目标未找到充分证据，不能勾选导出。你可以只确认已经核对的目标，或另建更明确的请求。</p>
          <datalist :id="`intent-utterances-${jobId}`"><option v-for="utterance in snapshot.utterances" :key="utterance.utterance_id" :value="utterance.utterance_id">{{ topicTime(utterance.start_ms) }} {{ utterance.text.slice(0, 60) }}</option></datalist>
          <article v-for="(output, index) in outputs" :key="output.output_id" class="intent-output">
            <div class="intent-output-heading"><label><input v-model="output.enabled" type="checkbox" :disabled="pending || output.status !== 'FOUND'" :aria-label="`选择剪辑目标 ${goal(output)}`">目标 {{ index + 1 }}：{{ goal(output) }}</label><span>{{ names[output.status] }}</span></div>
            <p class="intent-reason">{{ output.reason }}</p>
            <label class="intent-title-label">成片标题<input v-model="output.title" maxlength="120" :disabled="pending" :aria-label="`剪辑标题 ${index + 1}`"></label>
            <div v-for="(part, partIndex) in output.parts" :key="partIndex" class="intent-part">
              <div class="intent-part-label"><b>保留第 {{ partIndex + 1 }} 段</b><button class="text-button" :disabled="pending" @click="locate(part.start_utterance_id)">定位原视频 ↗</button></div>
              <div class="intent-boundaries"><label>起始原句<input :value="part.start_utterance_id" :list="`intent-utterances-${jobId}`" :disabled="pending" :aria-label="`目标 ${index + 1} 第 ${partIndex + 1} 段起始原句`" @input="editBoundary(output, partIndex, 'start_utterance_id', $event)"></label><label>结束原句<input :value="part.end_utterance_id" :list="`intent-utterances-${jobId}`" :disabled="pending" :aria-label="`目标 ${index + 1} 第 ${partIndex + 1} 段结束原句`" @input="editBoundary(output, partIndex, 'end_utterance_id', $event)"></label></div>
              <p class="help">{{ topicTime(part.start_ms) }} → {{ topicTime(part.end_ms) }}</p><p class="intent-quote">{{ sourceText(part.start_utterance_id) }}<template v-if="part.end_utterance_id !== part.start_utterance_id"> … {{ sourceText(part.end_utterance_id) }}</template></p>
              <button class="text-button intent-remove" :disabled="pending" @click="removePart(output, partIndex)">移除此段</button>
            </div>
            <button v-if="output.status !== 'NOT_FOUND' && output.parts.length < 12" class="text-button" :disabled="pending" @click="addPart(output)">＋ 增加保留区间</button>
            <details v-if="output.evidence_utterance_ids.length" class="intent-evidence"><summary>依据原句 {{ output.evidence_utterance_ids.length }} 条</summary><button v-for="id in output.evidence_utterance_ids" :key="id" @click="locate(id)"><span>{{ topicTime(sourceTime(id)) }} · {{ id }}</span>{{ sourceText(id) }}</button></details>
            <div v-if="output.excluded.length" class="intent-excluded"><b>排除内容</b><div v-for="(excluded, excludeIndex) in output.excluded" :key="excluded.utterance_id"><button class="text-button" @click="locate(excluded.utterance_id)">{{ topicTime(sourceTime(excluded.utterance_id)) }} {{ sourceText(excluded.utterance_id) }}</button><small>{{ excluded.reason }}</small><button class="text-button" :disabled="pending" @click="unexclude(output, excludeIndex)">取消排除</button></div></div>
            <p class="help">{{ output.parts.length }} 个连续区间 · 预计保留 {{ topicTime(duration(output)) }} · 导出时合成一个文件</p>
          </article>
          <p v-if="validation" class="intent-warning" role="status">{{ validation }}</p>
          <div class="intent-save-row"><span>{{ planDirty ? '方案修改尚未保存' : confirmed ? `r${snapshot.revision} 已确认` : '请核对、勾选并保存所需目标' }}</span><div class="actions"><button class="secondary" :disabled="pending || !planDirty || !!validation" @click="save">{{ action === 'save' ? '正在保存…' : '保存方案' }}</button><button class="primary" :disabled="!canConfirm" @click="confirm">{{ action === 'confirm' ? '正在确认…' : '确认所选目标' }}</button><button class="primary" :disabled="!canExport" @click="exportPlan">{{ action === 'export' ? '正在创建…' : '生成独立 MP4' }}</button></div></div>
        </template>
      </div>
    </div>
    <div v-if="batches.length" class="intent-exports"><h3>按需求剪辑成片</h3><p class="help">每个文件对应一个已确认目标。后续编辑不会改变已有导出批次。</p><article v-for="batch in batches" :key="batch.id" class="intent-batch"><p>确认版本 r{{ batch.approved_revision }} · {{ names[batch.status] ?? batch.status }} · {{ date(batch.created_at) }}</p><div v-for="clip in batch.clips" :key="clip.id" class="intent-export-clip"><strong>{{ clip.title ?? '剪辑视频' }}</strong><span>{{ names[clip.status] ?? clip.status }}</span><FailureDiagnostic :diagnostic="clip.diagnostic" :error-code="clip.error_code" :attempt="clip.attempt" /><div v-if="clip.status === 'SUCCEEDED'" class="actions"><button class="text-button" @click="previewUrl = clip.content_url ?? ''">预览成片</button><a class="text-button" :href="clip.download_url ?? undefined" download>下载 MP4</a></div></div></article><video v-if="previewUrl" ref="exportPlayer" :key="previewUrl" :src="previewUrl" controls preload="metadata" playsinline class="intent-source" @play="guardPlayback"></video></div>
  </section>
</template>

<style scoped>
.intent-panel { margin-top: 24px; padding: 24px; min-width: 0; }
.intent-intro, .intent-original, .intent-reason { color: #73816d; line-height: 1.8; font-size: 12px; overflow-wrap: anywhere; }
.intent-intro { margin-bottom: 18px; }
.intent-compose { background: #f7f9f5; border: 1px solid #e6ebe0; border-radius: 7px; padding: 16px; }
.intent-compose > label, .intent-title-label, .intent-boundaries label { display: flex; flex-direction: column; gap: 7px; color: #75816e; font-size: 11px; min-width: 0; }
textarea, input:not([type=checkbox]) { width: 100%; border: 1px solid #dfe5d7; border-radius: 5px; background: white; padding: 9px 10px; color: #4c5d43; font: inherit; min-width: 0; }
textarea { resize: vertical; min-height: 90px; line-height: 1.7; }
.intent-create-row, .intent-subheading, .intent-output-heading, .intent-part-label, .intent-save-row { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px; }
.intent-create-row { margin-top: 12px; font-size: 10px; color: #89927e; }
.intent-create-row label { display: flex; align-items: center; gap: 8px; }
.intent-create-row input { width: 72px; }
.intent-create-row > span { flex: 1; min-width: 140px; line-height: 1.6; }
.intent-workspace { display: grid; grid-template-columns: minmax(180px, .6fr) minmax(0, 2fr); gap: 22px; margin-top: 22px; }
.intent-requests, .intent-result { min-width: 0; }
h3 { color: #52634b; font-size: 13px; } h3 > span { font-size: 10px; font-weight: 400; color: #8a967f; }
.intent-subheading { margin-bottom: 13px; }
.intent-request { display: block; width: 100%; text-align: left; border: 1px solid #e5e9df; background: #fff; border-radius: 6px; padding: 12px; margin-bottom: 8px; }
.intent-request.selected { background: #fbf4ee; border-color: #e9c5ac; }
.intent-request strong { font-size: 11px; font-weight: 500; color: #59684f; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; overflow-wrap: anywhere; line-height: 1.7; }
.intent-request > span { display: block; color: #939c89; font-size: 9px; margin-top: 6px; }
.intent-original { padding-bottom: 10px; }
.intent-recovery-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 14px; padding: 10px 12px; margin: 12px 0; border: 1px solid #e5e9df; border-radius: 6px; background: #f8faf6; }.intent-recovery-actions p { flex: 1; min-width: 180px; margin: 0; }.intent-recovery-actions button { flex-shrink: 0; }
.intent-source { width: 100%; max-height: 360px; display: block; border-radius: 6px; background: #20251e; margin: 14px 0 8px; }
.intent-empty { padding: 25px 12px; font-size: 12px; color: #8b957e; line-height: 1.8; background: #f8faf6; margin-top: 15px; border-radius: 6px; }
.intent-output { border: 1px solid #e4e9dc; border-radius: 7px; padding: 16px; margin-top: 16px; min-width: 0; }
.intent-output-heading { font-size: 11px; color: #79866b; }.intent-output-heading label { color: #596a4f; display: flex; align-items: flex-start; gap: 6px; max-width: 100%; overflow-wrap: anywhere; }
.intent-output-heading input { flex-shrink: 0; }.intent-reason { margin: 10px 0; }
.intent-part { border-top: 1px solid #eef0e9; padding-top: 12px; margin-top: 12px; }.intent-part-label { font-size: 11px; color: #7c8a70; }
.intent-boundaries { display: grid; grid-template-columns: minmax(0,1fr) minmax(0,1fr); gap: 10px; margin: 9px 0; }
.intent-quote { font-size: 11px; color: #8a967e; line-height: 1.8; overflow-wrap: anywhere; margin-top: 7px; }
.intent-remove { display: block; margin: 6px 0 12px auto; font-size: 10px; }.intent-evidence { margin: 12px 0; font-size: 11px; color: #79896c; }
.intent-evidence summary { cursor: pointer; }.intent-evidence > button { display: block; text-align: left; width: 100%; background: #f8faf5; color: #7c8970; border-radius: 4px; margin: 6px 0; padding: 9px; font-size: 11px; line-height: 1.8; overflow-wrap: anywhere; }
.intent-evidence > button > span { display: block; color: #a2ad96; font-size: 9px; }
.intent-excluded { padding: 12px; background: #fdf8f2; margin: 12px 0; font-size: 11px; color: #9b886c; border-radius: 5px; }.intent-excluded > div { margin-top: 8px; }.intent-excluded small { display: block; margin: 5px 0; }.intent-excluded .text-button { text-align: left; overflow-wrap: anywhere; line-height: 1.7; }
.intent-save-row { padding-top: 16px; margin-top: 17px; border-top: 1px solid #e4e9df; color: #8a977d; font-size: 11px; }.actions { flex-wrap: wrap; gap: 7px; }
.intent-warning, .intent-notice { font-size: 11px; line-height: 1.8; padding: 10px 12px; margin: 12px 0; background: #fcf5eb; color: #a17d48; border-radius: 5px; overflow-wrap: anywhere; }.intent-notice { background: #f0f7eb; color: #758e64; }
.intent-exports { margin-top: 24px; border-top: 1px solid #e6ebdf; padding-top: 20px; }.intent-batch { border: 1px solid #e5e9de; border-radius: 6px; margin-top: 12px; padding: 14px; font-size: 11px; color: #8a957e; }.intent-export-clip { padding: 12px 0; border-top: 1px solid #edf0e7; margin-top: 12px; display: flex; flex-wrap: wrap; gap: 8px 18px; align-items: center; }.intent-export-clip strong { color: #5e6f52; overflow-wrap: anywhere; }.intent-export-clip .actions { margin-left: auto; }
@media (max-width: 850px) { .intent-workspace { grid-template-columns: minmax(0, 1fr); }.intent-requests { border-bottom: 1px solid #e6ebdf; padding-bottom: 12px; }.intent-panel { padding: 18px; } }
@media (max-width: 420px) { .intent-boundaries { grid-template-columns: minmax(0, 1fr); }.intent-output { padding: 12px; }.intent-create-row > button { width: 100%; }.intent-save-row .actions { width: 100%; }.intent-save-row .actions > button { flex: 1; min-width: 110px; } }
.workbench-shell .intent-panel { margin: 0; padding: 20px 24px; border: 0; border-radius: 0; color: #1f2937; font-size: 14px; }
.workbench-shell .intent-panel h2 { color: #1f2937; font-size: 20px; }
.workbench-shell .intent-panel h3 { color: #334155; font-size: 16px; }
.workbench-shell .intent-panel .eyebrow { color: #64748b; font-size: 11px; }
.workbench-shell .intent-panel .help, .workbench-shell .intent-intro, .workbench-shell .intent-original, .workbench-shell .intent-reason { color: #64748b; font-size: 13px; line-height: 1.75; }
.workbench-shell .intent-compose { background: #f8fafc; border-color: #e2e8f0; padding: 16px; }
.workbench-shell .intent-compose > label, .workbench-shell .intent-title-label, .workbench-shell .intent-boundaries label { color: #475569; font-size: 13px; }
.workbench-shell .intent-panel textarea, .workbench-shell .intent-panel input:not([type=checkbox]), .workbench-shell .intent-panel select { border-color: #cbd5e1; color: #1f2937; font-size: 14px; }
.workbench-shell .intent-create-row, .workbench-shell .intent-part-label, .workbench-shell .intent-save-row { color: #64748b; font-size: 12px; }
.workbench-shell .intent-workspace { grid-template-columns: 220px minmax(0, 1fr); gap: 24px; }
.workbench-shell .intent-request { border-color: #e2e8f0; padding: 12px; }
.workbench-shell .intent-request.selected { border-color: #efc7b7; background: #fff5ef; }
.workbench-shell .intent-request strong { color: #334155; font-size: 13px; }
.workbench-shell .intent-request > span { color: #64748b; font-size: 12px; }
.workbench-shell .intent-output { border-color: #e2e8f0; background: white; }
.workbench-shell .intent-output-heading { color: #64748b; font-size: 13px; }
.workbench-shell .intent-output-heading label { color: #1f2937; }
.workbench-shell .intent-quote, .workbench-shell .intent-evidence, .workbench-shell .intent-evidence > button { color: #475569; font-size: 13px; }
.workbench-shell .intent-evidence > button { background: #f8fafc; }
.workbench-shell .intent-evidence > button > span { color: #64748b; font-size: 12px; }
.workbench-shell .intent-batch, .workbench-shell .intent-exports { border-color: #e2e8f0; color: #64748b; font-size: 13px; }
.workbench-shell .intent-export-clip strong { color: #334155; font-size: 14px; }
.workbench-shell .intent-panel :deep(.text-recovery) { color: #475569; background: #f8fafc; border-color: #cbd5e1; }
@media(max-width: 900px) { .workbench-shell .intent-workspace { grid-template-columns: minmax(0, 1fr); } }
@media(max-width: 650px) { .workbench-shell .intent-panel { padding: 16px; } .workbench-shell .intent-panel h2 { font-size: 18px; } }
</style>
