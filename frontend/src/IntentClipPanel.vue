<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, ApiError, post, type ExportJob } from './api'
import FailureDiagnostic from './FailureDiagnostic.vue'
import TextRequestProgress from './TextRequestProgress.vue'
import { canPrefillClipInstruction } from './videoAgent'
import CaptionPanel from './CaptionPanel.vue'
import TranscriptRangePicker from './TranscriptRangePicker.vue'
import DraftBackupPanel from './DraftBackupPanel.vue'
import { assessIntentDraft, captureIntentDraft, parseIntentDraft, restoreIntentDraft } from './intentDraft'
import { CaptionBatchError, prepareCaptionBatch } from './captions'
import { newestSnapshot, parseExportSnapshot, SnapshotError } from './snapshotStream'
import { topicTime } from './topics'
import { intentPreviewParts } from './intentPreviewParts'
import { SequencePreview, type SequencePreviewPart, type SequencePreviewState } from './sequencePreview'
import { validateTranscriptRange } from './transcriptRange'
import { intentSaveRecovered } from './intentSaveRecovery'
import { intentCanCancel, intentCanConfirm, intentCanExport, intentCanResume, intentOutputValidation, intentPayload, intentResumeReceipt,
  IntentReadScope, IntentResponseError, newestIntentSnapshot, parseIntentSnapshot, refreshIntentEvidence,
  parseResumedIntentSnapshot, type IntentOutput, type IntentResumeReceipt, type IntentSnapshot, type IntentUtterance } from './intentClips'

const props = defineProps<{ jobId: string; videoUrl: string; active?: boolean }>()
const instruction = ref(''), maxCalls = ref<number | string>(6), requests = ref<IntentSnapshot[]>([])
const selectedId = ref(''), snapshot = ref<IntentSnapshot | null>(null), outputs = ref<IntentOutput[]>([])
const baseline = ref(''), draftRevision = ref<number | null>(null), action = ref('')
const draftBasePlanHash = ref<string | null>(null)
const draftReadResults = ref<Record<string, IntentSnapshot>>({})
let draftRestoreVersion = 0
const saveNeedsCheck = ref(false)
const error = ref(''), refreshError = ref(''), notice = ref(''), loading = ref(true)
const batches = ref<ExportJob[]>([]), previewUrl = ref(''), sourcePlayer = ref<HTMLVideoElement | null>(null)
const instructionInput = ref<HTMLTextAreaElement | null>(null)
const exportPlayer = ref<HTMLVideoElement | null>(null)
const captionTarget = ref<{ exportId: string; clipId: string; contentUrl: string } | null>(null)
const captionEditor = ref<{ dirty: boolean; pending: boolean } | null>(null)
interface RangePickerTarget {
  token: number; requestId: string; selection: number; draftRevision: number | null; snapshotRevision: number
  planHash: string | null; videoUrl: string; videoHash: string; transcriptHash: string; durationMs: number
  outputId: string; partIndex: number; signature: string; output: IntentOutput; utterances: IntentUtterance[]
  initialField: 'start' | 'end'; initialTimeMs?: number
}
interface RangeSelection { startId: string; endId: string; preview: boolean }
const rangePickerTarget = ref<RangePickerTarget | null>(null), rangePickerFailed = ref(false)
const rangePickerEditor = ref<{ dirty: boolean; pending: boolean } | null>(null)
let rangePickerVersion = 0, rangePickerOpener: HTMLElement | null = null
const packageBusyId = ref(''), packageUrls = ref<Record<string, string>>({})
type IntentView = 'compose' | 'review' | 'deliveries'
const intentView = ref<IntentView>('compose'), historyOpen = ref(false)
const historyPanel = ref<HTMLElement | null>(null), historyToggle = ref<HTMLButtonElement | null>(null)
const previewSection = ref<HTMLElement | null>(null)
const intentPanel = ref<HTMLElement | null>(null), sourceContextOpen = ref(true)
let contextObserver: ResizeObserver | undefined, compactContext = false
let viewChosen = false, defaultViewRequest = '', navigationVersion = 0
const sequenceState = ref<SequencePreviewState | null>(null), preparingSequence = ref(''), sequenceFeedback = ref('')
const sequenceDescription = ref<{ id: string; title: string; partIndex?: number } | null>(null)
const previewPageVisible = ref(!document.hidden)
interface PreparedSequence {
  generation: number; requestId: string; selection: number; navigation: number; sourceUrl: string
  outputId: string; signature: string; id: string; parts: SequencePreviewPart[]; player: HTMLVideoElement
}
let preparedSequence: PreparedSequence | null = null, sequencePreparationVersion = 0, sequencePreparationTimer: number | undefined
const sequence = new SequencePreview((state, reason) => {
  const previous = sequenceState.value
  sequenceState.value = state
  if (reason === 'finished' && previous?.id === sequenceDescription.value?.id) sequenceFeedback.value = sequenceDescription.value?.partIndex === undefined ? '该目标的区间回看已结束。' : '本段回看已结束。'
  else if (reason === 'failed') sequenceFeedback.value = '区间预览暂时未能播放。可先点击原视频播放按钮，再重试预览。'
})
const scope = new IntentReadScope()
let stopped = false, refreshing = false, timer: number | undefined, selectionVersion = 0, focusVersion = 0
let pendingCreate: { input: string; key: string } | null = null
const pendingResumes = new Map<string, IntentResumeReceipt>()
const pending = computed(() => !!action.value)
const planDirty = computed(() => !!snapshot.value?.plan && JSON.stringify(intentPayload(outputs.value)) !== baseline.value)
const planRevisionConflict = computed(() => draftRevision.value !== null && !!snapshot.value?.plan && snapshot.value.revision > draftRevision.value
  && JSON.stringify(intentPayload(outputs.value)) !== JSON.stringify(intentPayload(snapshot.value.plan.outputs)))
const dirty = computed(() => planDirty.value || planRevisionConflict.value || saveNeedsCheck.value || !!instruction.value.trim() || !!rangePickerEditor.value?.dirty)
const backupValue = computed(() => {
  const planChanged = planDirty.value || saveNeedsCheck.value || planRevisionConflict.value || action.value === 'save'
  if (!instruction.value.length && maxCalls.value === 6 && !planChanged) return null
  return captureIntentDraft(props.jobId, instruction.value, maxCalls.value, planChanged && snapshot.value?.plan ? {
    snapshot: snapshot.value, outputs: outputs.value, baseRevision: draftRevision.value,
    basePlanHash: draftBasePlanHash.value, needsCheck: saveNeedsCheck.value || planRevisionConflict.value || action.value === 'save',
  } : null)
})
const backupBlocked = computed(() => loading.value || props.active === false || historyOpen.value || pending.value || dirty.value
  || maxCalls.value !== 6 || !!rangePickerTarget.value || !!captionTarget.value || !!captionEditor.value?.dirty || !!captionEditor.value?.pending)
const validation = computed(() => snapshot.value?.plan
  ? intentOutputValidation(outputs.value, snapshot.value.utterances, snapshot.value.source.duration_ms, snapshot.value.plan.goals) : '')
const canConfirm = computed(() => !saveNeedsCheck.value && !planRevisionConflict.value && !rangePickerTarget.value && !instruction.value.trim() && intentCanConfirm(snapshot.value, outputs.value, planDirty.value, pending.value))
const canExport = computed(() => !saveNeedsCheck.value && !planRevisionConflict.value && !rangePickerTarget.value && !instruction.value.trim() && intentCanExport(snapshot.value, planDirty.value, pending.value))
const canCancel = computed(() => intentCanCancel(snapshot.value, planDirty.value, pending.value))
const canResume = computed(() => intentCanResume(snapshot.value, dirty.value, pending.value))
const selectedCount = computed(() => outputs.value.filter(value => value.enabled).length)
const unresolved = computed(() => outputs.value.filter(value => value.status !== 'FOUND').length)
const confirmed = computed(() => !!snapshot.value && snapshot.value.confirmed_revisions.includes(snapshot.value.revision))
const completedClips = computed(() => batches.value.reduce((count, batch) => count + batch.clips.filter(clip => clip.status === 'SUCCEEDED').length, 0))
const primaryAction = computed(() => saveNeedsCheck.value || planRevisionConflict.value
  ? { label: action.value === 'check-save' ? '正在核对…' : '核对保存结果', enabled: !rangePickerTarget.value && !pending.value, run: checkSavedPlan }
  : planDirty.value
  ? { label: action.value === 'save' ? '正在保存…' : '保存方案', enabled: !rangePickerTarget.value && !pending.value && !validation.value, run: save }
  : confirmed.value
    ? { label: action.value === 'export' ? '正在创建…' : '生成独立 MP4', enabled: canExport.value, run: exportPlan }
    : { label: action.value === 'confirm' ? '正在确认…' : '确认所选目标', enabled: canConfirm.value, run: confirm })
const sequenceOptions = computed(() => new Map(outputs.value.map(output => [output.output_id, {
  all: snapshot.value ? intentPreviewParts(output, snapshot.value.utterances, snapshot.value.source.duration_ms) : null,
  parts: output.parts.map((_, index) => snapshot.value ? intentPreviewParts(output, snapshot.value.utterances, snapshot.value.source.duration_ms, index) : null),
}])))
const outputPartsSignature = computed(() => JSON.stringify(outputs.value.map(output => ({ id: output.output_id, status: output.status, parts: output.parts }))))
const rangePickerStale = computed(() => rangePickerFailed.value || !!rangePickerTarget.value && !rangeContextCurrent(rangePickerTarget.value))
const rangePickerCloseHandler = computed(() => {
  const token = rangePickerTarget.value?.token
  return () => { if (rangePickerTarget.value?.token === token) closeRangePicker() }
})
const rangePickerApplyHandler = computed(() => {
  const token = rangePickerTarget.value?.token
  return (value: RangeSelection) => { if (token !== undefined) applyRangeSelection(token, value) }
})
const names: Record<string, string> = { QUEUED: '等待执行', RUNNING: '正在查找', PLAN_READY: '候选已生成', FAILED: '执行失败', CANCELLED: '已取消',
  FOUND: '已找到证据', NOT_FOUND: '未找到', NEEDS_REVIEW: '需进一步核对', READY: '等待领取', BLOCKED: '等待前序',
  RETRY_WAIT: '等待重试', SUCCEEDED: '已完成', PARTIAL_FAILED: '部分失败', SKIPPED: '已跳过' }
defineExpose({ dirty: computed(() => dirty.value || !!captionEditor.value?.dirty), pending: computed(() => pending.value || !!captionEditor.value?.pending), prefillInstruction })
function prefillInstruction(value: string): boolean {
  if (!canPrefillClipInstruction(value, dirty.value || !!captionEditor.value?.dirty, pending.value || !!captionEditor.value?.pending)) return false
  closeRangePicker(false)
  instruction.value = value.trim(); error.value = ''; notice.value = '已填入视频助手的建议。请核对要求，再点击查找片段。'
  closeHistory(false); setView('compose')
  const version = ++focusVersion
  void nextTick(() => { if (!stopped && props.active !== false && intentView.value === 'compose' && version === focusVersion) instructionInput.value?.focus() })
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
  const protectedDraft = planDirty.value || saveNeedsCheck.value || planRevisionConflict.value
  const known = snapshot.value?.id === value.id ? snapshot.value : null
  const latest = newestIntentSnapshot(known, upsert(value))
  if (selectedId.value !== value.id) return
  if (force && latest.revision > value.revision) throw new ApiError('CLIP_PLAN_REVISION_CONFLICT')
  const recovered = !force && protectedDraft && (!pending.value || action.value === 'check-save') && intentSaveRecovered(known, latest, outputs.value, draftRevision.value)
  snapshot.value = latest
  if (force || recovered || (!protectedDraft && !pending.value)) {
    outputs.value = latest.plan ? intentPayload(latest.plan.outputs).outputs : []
    baseline.value = JSON.stringify(intentPayload(outputs.value)); draftRevision.value = latest.revision
    draftBasePlanHash.value = latest.plan_hash
  }
  if (force || recovered) saveNeedsCheck.value = false
  if (recovered) { error.value = ''; notice.value = `已核对服务端 r${latest.revision} 与本页方案一致，无需重复保存。` }
}
function canChangeRequest() {
  if (captionEditor.value?.dirty || captionEditor.value?.pending) { error.value = '请先保存或放弃字幕修改，等待提交完成后再切换剪辑请求。'; return false }
  if (rangePickerTarget.value) { error.value = '请先应用或取消当前原句选择，再切换剪辑请求。'; return false }
  if (pending.value) { error.value = '当前操作正在提交，请稍后切换请求。'; return false }
  if (saveNeedsCheck.value) { error.value = '请先核对上次保存结果，再切换剪辑请求。'; return false }
  if (dirty.value) { error.value = '本页有尚未提交的输入或方案修改，请先提交、保存或放弃本页修改。'; return false }
  return true
}
async function selectRequest(id: string) {
  closeHistory()
  if (id === selectedId.value || !canChangeRequest()) return
  pauseMedia(); defaultViewRequest = id; viewChosen = false; intentView.value = 'review'
  scope.invalidate(); selectionVersion++; selectedId.value = id; snapshot.value = null; outputs.value = []
  baseline.value = ''; draftRevision.value = null; draftBasePlanHash.value = null; batches.value = []; previewUrl.value = ''; error.value = ''; notice.value = ''
  const known = requests.value.find(row => row.id === id)
  if (known) accept(known, true)
  await refresh()
}
async function refresh() {
  // Mutations invalidate older reads; do not start a new list read during their POST.
  // Otherwise an exports response captured before creation can erase the new batch.
  if (refreshing || stopped || pending.value) return
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
      defaultViewRequest = selectedId.value
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
      if (defaultViewRequest === requestId) {
        defaultViewRequest = ''
        if (!viewChosen && !dirty.value) intentView.value = batches.value.length ? 'deliveries' : 'review'
      }
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
  if (pending.value || rangePickerTarget.value || !query) return
  if (saveNeedsCheck.value || planRevisionConflict.value) { error.value = '请先核对当前方案的保存结果，再创建其他请求。'; return }
  if ([...query].length > 2000) { error.value = '剪辑要求最多 2000 字。'; return }
  if (typeof maxCalls.value !== 'number' || !Number.isInteger(maxCalls.value) || maxCalls.value < 1 || maxCalls.value > 24) { error.value = '模型请求上限须为 1～24 的整数。'; return }
  if (planDirty.value) { error.value = '请先保存或放弃当前方案修改，再创建其他请求。'; return }
  const jobId = props.jobId, payload = { instruction: query, max_text_calls: maxCalls.value }
  const storageKey = `livecut-intent-create-${jobId}`, input = JSON.stringify(payload)
  let saved: { input: string; key: string } | null = pendingCreate
  if (!saved) { try { saved = JSON.parse(localStorage.getItem(storageKey) ?? 'null') } catch { /* A corrupt old receipt is never reused. */ } }
  const key = saved?.input === input && typeof saved.key === 'string' ? saved.key : crypto.randomUUID()
  pendingCreate = { input, key }
  try { localStorage.setItem(storageKey, JSON.stringify(pendingCreate)) } catch { /* Keep the mounted-view receipt when storage is unavailable. */ }
  action.value = 'create'; error.value = ''; notice.value = ''; scope.invalidate()
  const version = ++selectionVersion, navigation = navigationVersion
  try {
    const raw = await post<unknown>(`/api/jobs/${jobId}/clip-requests`, payload, { 'Idempotency-Key': key })
    if (stopped || version !== selectionVersion || jobId !== props.jobId) return
    const value = parseIntentSnapshot(raw, jobId)
    selectedId.value = value.id; snapshot.value = null; outputs.value = []; batches.value = []; previewUrl.value = ''
    instruction.value = ''; accept(value, true); defaultViewRequest = ''
    if (navigation === navigationVersion && intentView.value === 'compose') setView('review', false)
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
  if (typeof maxCalls.value !== 'number' || !Number.isInteger(maxCalls.value) || maxCalls.value < 1 || maxCalls.value > 24) { error.value = '模型请求上限须为 1～24 的整数。'; return }
  const jobId = props.jobId, id = source.id, budget = maxCalls.value
  const storageKey = `livecut-intent-resume-${id}-${budget}`
  let saved: unknown = pendingResumes.get(storageKey)
  if (!saved) { try { saved = JSON.parse(localStorage.getItem(storageKey) ?? 'null') } catch { /* Only valid receipts are reused. */ } }
  const receipt = intentResumeReceipt(saved, id, budget, () => crypto.randomUUID())
  pendingResumes.set(storageKey, receipt)
  try { localStorage.setItem(storageKey, JSON.stringify(receipt)) } catch { /* Keep the mounted-view receipt if storage is unavailable. */ }
  action.value = 'resume'; error.value = ''; notice.value = ''; scope.invalidate()
  const version = ++selectionVersion, navigation = navigationVersion
  try {
    const raw = await post<unknown>(`/api/clip-requests/${id}/resume`, { max_text_calls: budget }, { 'Idempotency-Key': receipt.key })
    if (!validOperation(id, version) || props.jobId !== jobId) return
    const value = parseResumedIntentSnapshot(raw, source, budget)
    selectedId.value = value.id; snapshot.value = null; outputs.value = []; batches.value = []; previewUrl.value = ''
    accept(value, true)
    defaultViewRequest = ''
    if (navigation === navigationVersion) setView('review', false)
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
  if (!snapshot.value?.plan || pending.value || rangePickerTarget.value || saveNeedsCheck.value || planRevisionConflict.value || !planDirty.value || validation.value || draftRevision.value === null) return
  const id = selectedId.value, version = selectionVersion, revision = draftRevision.value
  const body = { base_revision: revision, ...intentPayload(outputs.value) }
  action.value = 'save'; error.value = ''; notice.value = ''; scope.invalidate()
  try {
    const raw = await api<unknown>(`/api/clip-requests/${id}/plan`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    if (!validOperation(id, version)) return
    const value = parseIntentSnapshot(raw, props.jobId, id); accept(value, true)
    notice.value = `方案 r${value.revision} 已保存，请核对所选目标后确认。`
  } catch (value) {
    if (validOperation(id, version)) {
      fail(value)
      // Error bodies can hide the HTTP status; require a fresh read after any
      // unsuccessful acknowledgement instead of guessing whether it committed.
      saveNeedsCheck.value = true
    }
  }
  finally { if (validOperation(id, version)) action.value = '' }
}
async function checkSavedPlan() {
  if (!snapshot.value?.plan || pending.value || rangePickerTarget.value) return
  const id = selectedId.value, version = selectionVersion
  action.value = 'check-save'; error.value = ''; notice.value = ''; scope.invalidate()
  try {
    const raw = await api<unknown>(`/api/clip-requests/${id}`)
    if (!validOperation(id, version)) return
    accept(parseIntentSnapshot(raw, props.jobId, id))
    saveNeedsCheck.value = false
    if (planRevisionConflict.value) notice.value = '服务端内容与本页草稿不同。本地修改仍保留，未使用新版本覆盖或重新保存。'
    else if (planDirty.value) notice.value = `服务端当前为 r${snapshot.value!.revision}，本页修改仍待保存。可稍后再核对，或重新保存。`
    else if (!notice.value) notice.value = `已核对服务端 r${snapshot.value!.revision}。`
  } catch (value) { if (validOperation(id, version)) { saveNeedsCheck.value = true; fail(value) } }
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
  const id = selectedId.value, version = selectionVersion, navigation = navigationVersion, revision = snapshot.value.revision, planHash = snapshot.value.plan_hash
  action.value = 'export'; error.value = ''; notice.value = ''; scope.invalidate()
  try {
    const raw = await post<unknown>(`/api/clip-requests/${id}/exports`, { revision, plan_hash: planHash, profile: 'h264-aac-v1' })
    if (!validOperation(id, version)) return
    if (!raw || typeof raw !== 'object' || !('id' in raw) || typeof raw.id !== 'string') throw new IntentResponseError()
    const value = parseExportSnapshot(raw, raw.id, props.jobId)
    const known = batches.value.findIndex(batch => batch.id === value.id)
    if (known < 0) batches.value.unshift(value)
    else batches.value[known] = newestSnapshot(batches.value[known], value)
    if (navigation === navigationVersion && intentView.value === 'review') setView('deliveries', false)
    notice.value = '导出任务已创建。每个已选目标生成一个文件，多段内容按原视频顺序拼接。'
  } catch (value) { if (validOperation(id, version)) fail(value) }
  finally { if (validOperation(id, version)) action.value = '' }
}
function discard() {
  if (pending.value) return
  try {
    const latest = snapshot.value ? newestIntentSnapshot(snapshot.value, requests.value.find(request => request.id === snapshot.value!.id) ?? snapshot.value) : null
    if (latest) accept(latest, true)
    closeRangePicker(false)
    error.value = ''; notice.value = '方案修改已放弃；剪辑要求页中的新要求保持不变。'
  } catch (value) { fail(value) }
}
function assessBackup(raw: unknown) {
  const draft = parseIntentDraft(raw, props.jobId), id = draft?.plan?.request_id
  let server: IntentSnapshot | null = null
  if (id) {
    try {
      for (const candidate of [draftReadResults.value[id], requests.value.find(value => value.id === id), snapshot.value?.id === id ? snapshot.value : null]) {
        if (candidate) server = newestIntentSnapshot(server, candidate)
      }
    } catch { return { title: '需要人工核对的剪辑备份', description: '已读取的来源信息不一致，当前内容未改变。', compatible: false, reason: '请下载保留后人工核对，不能覆盖当前方案。' } }
  }
  return assessIntentDraft(raw, props.jobId, server)
}
function editorDraftSignature() {
  return JSON.stringify({ selected: selectedId.value, instruction: instruction.value, max_calls: maxCalls.value,
    revision: draftRevision.value, base_hash: draftBasePlanHash.value, outputs: intentPayload(outputs.value),
    needs_check: saveNeedsCheck.value, conflict: planRevisionConflict.value })
}
async function restoreBackup(raw: unknown) {
  if (backupBlocked.value) { error.value = '请先保存或放弃当前修改，关闭原句选择和字幕窗口，再恢复备份。'; return }
  const requested = parseIntentDraft(raw, props.jobId)
  if (!requested) { error.value = '这份备份无法恢复，原记录仍可下载保留。'; return }
  const jobId = props.jobId, selection = selectionVersion, navigation = navigationVersion
  const before = editorDraftSignature(), operation = ++draftRestoreVersion
  action.value = 'restore-draft'; error.value = ''; notice.value = ''; scope.invalidate()
  const current = () => !stopped && operation === draftRestoreVersion && props.jobId === jobId
    && selectionVersion === selection && navigationVersion === navigation && props.active !== false && !historyOpen.value
    && editorDraftSignature() === before && !dirty.value && maxCalls.value === 6 && !rangePickerTarget.value && !captionTarget.value
    && !captionEditor.value?.dirty && !captionEditor.value?.pending && action.value === 'restore-draft'
  try {
    let server: IntentSnapshot | null = null
    if (requested.plan) {
      const id = requested.plan.request_id
      const response = await api<unknown>(`/api/clip-requests/${id}`)
      if (!current()) return
      const fresh = parseIntentSnapshot(response, jobId, id)
      draftReadResults.value = { ...draftReadResults.value, [id]: fresh }
      server = newestIntentSnapshot(requests.value.find(value => value.id === id), fresh)
      draftReadResults.value = { ...draftReadResults.value, [id]: server }
    }
    const result = restoreIntentDraft(requested, jobId, server)
    if (!current()) return
    if (!result.ok) { error.value = result.reason; return }
    pauseMedia(); closeHistory(false)
    if (result.draft.plan && server && result.outputs) {
      scope.invalidate(); selectionVersion++; selectedId.value = server.id
      batches.value = []; previewUrl.value = ''; snapshot.value = null; outputs.value = []
      baseline.value = ''; draftRevision.value = null; draftBasePlanHash.value = null
      accept(server, true)
      outputs.value = result.outputs
      draftRevision.value = result.draft.plan.base_revision; draftBasePlanHash.value = result.draft.plan.base_plan_hash
      saveNeedsCheck.value = result.draft.plan.needs_check
      defaultViewRequest = ''; setView('review')
    } else setView('compose')
    instruction.value = result.draft.instruction; maxCalls.value = result.draft.max_calls
    notice.value = result.draft.plan?.needs_check
      ? '已恢复本机草稿，上次保存结果仍需核对。请先核对保存结果，再决定保存、确认或生成成片。'
      : '已恢复本机草稿，尚未提交。请核对要求与方案，再手动查找、保存和确认；不会自动生成成片。'
  } catch (value) { if (current()) fail(value) }
  finally { if (!stopped && operation === draftRestoreVersion) action.value = '' }
}
function addPart(output: IntentOutput) {
  if (pending.value || output.parts.length >= 12) return
  output.parts.push({ start_utterance_id: '', end_utterance_id: '', start_ms: -1, end_ms: -1 })
}
function removePart(output: IntentOutput, index: number) { output.parts.splice(index, 1); refreshIntentEvidence(output, snapshot.value?.utterances ?? []) }
function unexclude(output: IntentOutput, index: number) { output.excluded.splice(index, 1) }
function sourceText(id: string) { return snapshot.value?.utterances.find(value => value.utterance_id === id)?.text ?? '原句不可用' }
function sourceTime(id: string) { return snapshot.value?.utterances.find(value => value.utterance_id === id)?.start_ms ?? -1 }
function rangeSignature(output: IntentOutput) { return JSON.stringify({ id: output.output_id, status: output.status, parts: output.parts, excluded: output.excluded }) }
function rangeContextCurrent(target: RangePickerTarget) {
  const value = snapshot.value, output = outputs.value.find(output => output.output_id === target.outputId)
  return validOperation(target.requestId, target.selection) && !!value?.plan && value.id === target.requestId
    && draftRevision.value === target.draftRevision && value.revision === target.snapshotRevision && value.plan_hash === target.planHash
    && props.videoUrl === target.videoUrl && value.source.video_sha256 === target.videoHash
    && value.source.transcript_hash === target.transcriptHash && value.source.duration_ms === target.durationMs
    && !!output?.parts[target.partIndex] && rangeSignature(output) === target.signature
}
function openRangePicker(output: IntentOutput, partIndex: number, initialField: 'start' | 'end', event: Event) {
  const value = snapshot.value
  if (!value?.plan || pending.value || rangePickerTarget.value || !mediaActive('review') || !output.parts[partIndex] || output.status === 'NOT_FOUND') return
  const initialTimeMs = sourcePlayer.value && sourcePlayer.value.currentTime > 0 ? sourcePlayer.value.currentTime * 1000 : undefined
  pauseMedia(); error.value = ''; rangePickerFailed.value = false
  rangePickerOpener = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  rangePickerTarget.value = {
    token: ++rangePickerVersion, requestId: selectedId.value, selection: selectionVersion,
    draftRevision: draftRevision.value, snapshotRevision: value.revision, planHash: value.plan_hash,
    videoUrl: props.videoUrl, videoHash: value.source.video_sha256, transcriptHash: value.source.transcript_hash, durationMs: value.source.duration_ms,
    outputId: output.output_id, partIndex, signature: rangeSignature(output), output: intentPayload([output]).outputs[0]!,
    utterances: value.utterances.map(utterance => ({ ...utterance })), initialField, initialTimeMs,
  }
}
function closeRangePicker(restoreFocus = true) {
  const opener = rangePickerOpener, version = ++rangePickerVersion
  rangePickerTarget.value = null; rangePickerOpener = null; rangePickerFailed.value = false
  if (restoreFocus) void nextTick(() => {
    if (version === rangePickerVersion && !rangePickerTarget.value && mediaActive('review') && opener?.isConnected) opener.focus()
  })
}
async function applyRangeSelection(token: number, selection: RangeSelection) {
  const target = rangePickerTarget.value
  if (!target || target.token !== token || pending.value || !mediaActive('review', true)) return
  if (!rangeContextCurrent(target)) { rangePickerFailed.value = true; return }
  const output = outputs.value.find(output => output.output_id === target.outputId)!
  const result = validateTranscriptRange(output, target.partIndex, selection.startId, selection.endId, snapshot.value!.utterances, target.durationMs)
  if (!result.part || result.error) { error.value = result.error; return }
  const previous = output.parts[target.partIndex]!, part = result.part
  const changed = previous.start_utterance_id !== part.start_utterance_id || previous.end_utterance_id !== part.end_utterance_id
    || previous.start_ms !== part.start_ms || previous.end_ms !== part.end_ms
  pauseMedia()
  if (changed) { output.parts.splice(target.partIndex, 1, part); refreshIntentEvidence(output, snapshot.value!.utterances) }
  const signature = rangeSignature(output), navigation = navigationVersion
  closeRangePicker(!selection.preview)
  notice.value = changed ? '区间已应用到本页草稿。核对后请保存方案，再确认生成。' : '所选区间未变化。'
  if (!selection.preview) return
  await nextTick()
  const current = outputs.value.find(output => output.output_id === target.outputId)
  if (validOperation(target.requestId, target.selection) && navigationVersion === navigation && !rangePickerTarget.value
    && props.videoUrl === target.videoUrl && snapshot.value?.revision === target.snapshotRevision && draftRevision.value === target.draftRevision
    && snapshot.value?.source.video_sha256 === target.videoHash && snapshot.value?.source.transcript_hash === target.transcriptHash
    && snapshot.value?.source.duration_ms === target.durationMs && mediaActive('review') && current && rangeSignature(current) === signature) await previewSequence(current, target.partIndex)
}
function sequenceId(output: IntentOutput, partIndex?: number) { return partIndex === undefined ? `goal:${output.output_id}` : `part:${output.output_id}:${partIndex}` }
function sequenceSelected(output: IntentOutput, partIndex?: number) {
  const id = sequenceId(output, partIndex)
  return sequenceState.value?.id === id || preparingSequence.value === id
}
function partsForPreview(output: IntentOutput, partIndex?: number) {
  const options = sequenceOptions.value.get(output.output_id)
  return partIndex === undefined ? options?.all ?? null : options?.parts[partIndex] ?? null
}
function canPreviewSequence(output: IntentOutput, partIndex?: number) { return previewPageVisible.value && !document.hidden && !pending.value && !!partsForPreview(output, partIndex) && mediaActive('review') }
function stopSequencePreview(feedback = '') {
  preparedSequence = null; preparingSequence.value = ''; sequencePreparationVersion++
  window.clearTimeout(sequencePreparationTimer); sequencePreparationTimer = undefined
  sequence.cancel(); sequenceFeedback.value = feedback
}
function previewSignature(output: IntentOutput) { return JSON.stringify({ id: output.output_id, status: output.status, parts: output.parts }) }
function preparationCurrent(value: PreparedSequence) {
  const output = outputs.value.find(output => output.output_id === value.outputId)
  return preparedSequence === value && value.generation === sequencePreparationVersion
    && validOperation(value.requestId, value.selection) && navigationVersion === value.navigation
    && props.videoUrl === value.sourceUrl && sourcePlayer.value === value.player && sourceContextOpen.value && previewPageVisible.value && !document.hidden
    && mediaActive('review') && !pending.value && !!output && previewSignature(output) === value.signature
}
async function startPreparedSequence(value: PreparedSequence) {
  await nextTick()
  if (preparedSequence !== value) return
  if (!preparationCurrent(value)) { stopSequencePreview(); return }
  if (value.player.readyState < 1) return
  preparedSequence = null; preparingSequence.value = ''
  window.clearTimeout(sequencePreparationTimer); sequencePreparationTimer = undefined
  if (compactContext) value.player.scrollIntoView({ block: 'nearest' })
  const result = await sequence.start(value.player, { id: value.id, parts: value.parts })
  if (result === 'failed' && sequencePreparationVersion === value.generation) sequenceFeedback.value = '区间预览暂时未能播放。可先点击原视频播放按钮，再重试预览。'
}
async function previewSequence(output: IntentOutput, partIndex?: number) {
  if (sequenceSelected(output, partIndex)) { stopSequencePreview('区间预览已停止。'); return }
  const parts = partsForPreview(output, partIndex), player = sourcePlayer.value
  if (!canPreviewSequence(output, partIndex) || !parts || !player) return
  stopSequencePreview(); sourceContextOpen.value = true
  const id = sequenceId(output, partIndex)
  sequenceDescription.value = { id, title: output.title, partIndex }
  preparingSequence.value = id
  const prepared: PreparedSequence = {
    generation: sequencePreparationVersion, requestId: selectedId.value, selection: selectionVersion,
    navigation: navigationVersion, sourceUrl: props.videoUrl, outputId: output.output_id,
    signature: previewSignature(output), id, parts: parts.map(part => ({ ...part })), player,
  }
  preparedSequence = prepared
  sequencePreparationTimer = window.setTimeout(() => {
    if (preparedSequence === prepared) stopSequencePreview('原视频还未准备好。请确认原视频可播放后，再试一次区间预览。')
  }, 15000)
  await startPreparedSequence(prepared)
}
function sourceMetadata(event: Event) {
  if (preparedSequence?.player === event.currentTarget) void startPreparedSequence(preparedSequence)
}
function sourceSequenceTime(event: Event) { sequence.timeUpdate(event.currentTarget as HTMLVideoElement) }
function sourceSequenceSeeking(event: Event) {
  if (preparedSequence?.player === event.currentTarget) stopSequencePreview()
  sequence.seeking(event.currentTarget as HTMLVideoElement)
}
function sourceSequenceError(event: Event) {
  if (event.currentTarget === sourcePlayer.value && sourcePlayer.value?.error) stopSequencePreview('原视频暂时无法播放。方案修改仍保留，可以稍后重试。')
}
function updatePreviewVisibility() {
  previewPageVisible.value = !document.hidden
  if (!previewPageVisible.value && (preparedSequence || sequenceState.value)) stopSequencePreview()
}
async function locate(id: string) {
  stopSequencePreview()
  const start = sourceTime(id)
  if (start < 0 || !mediaActive('review')) return
  const requestId = selectedId.value, version = selectionVersion, navigation = navigationVersion
  sourceContextOpen.value = true
  await nextTick()
  if (!sourcePlayer.value || !validOperation(requestId, version) || navigation !== navigationVersion || !mediaActive('review')) return
  sourcePlayer.value.pause()
  try {
    sourcePlayer.value.currentTime = start / 1000
    if (compactContext) sourcePlayer.value.scrollIntoView({ block: 'nearest' })
  }
  catch { error.value = '暂时无法定位原句，请等待视频加载后重试。' }
}
function goal(output: IntentOutput) { return snapshot.value?.plan?.goals.find(value => value.goal_id === output.goal_id)?.description ?? output.title }
function duration(output: IntentOutput) { return output.parts.reduce((sum, part) => sum + Math.max(0, part.end_ms - part.start_ms), 0) }
function date(value: string) { return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }
function unload(event: BeforeUnloadEvent) { if (dirty.value || pending.value) { event.preventDefault(); event.returnValue = '' } }
function pauseMedia() { stopSequencePreview(); sourcePlayer.value?.pause(); exportPlayer.value?.pause() }
function mediaActive(view: IntentView, allowRangePicker = false) { return props.active !== false && !stopped && (allowRangePicker || !rangePickerTarget.value) && !(captionTarget.value && intentView.value === 'deliveries') && !historyOpen.value && intentView.value === view }
function guardPlayback(event: Event) {
  const player = event.currentTarget as HTMLVideoElement
  if (!(player === sourcePlayer.value && sourceContextOpen.value && mediaActive('review')) && !(player === exportPlayer.value && mediaActive('deliveries'))) player.pause()
}
function setView(view: IntentView, userInitiated = true) {
  if (view !== 'compose' && !snapshot.value) return
  if (preparedSequence) stopSequencePreview()
  if (userInitiated) { viewChosen = true; navigationVersion++ }
  intentView.value = view
  if (!userInitiated) {
    const id = selectedId.value, version = selectionVersion, navigation = navigationVersion
    void nextTick(() => {
      if (validOperation(id, version) && props.active !== false && !historyOpen.value && navigation === navigationVersion && intentView.value === view) document.getElementById(`intent-view-${props.jobId}-${view}`)?.focus()
    })
  }
}
function viewKey(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  const views: IntentView[] = snapshot.value ? ['compose', 'review', 'deliveries'] : ['compose']
  event.preventDefault()
  const index = views.indexOf(intentView.value)
  const next = event.key === 'Home' ? views[0]! : event.key === 'End' ? views.at(-1)! : views[(index + (event.key === 'ArrowRight' ? 1 : -1) + views.length) % views.length]!
  setView(next)
  void nextTick(() => document.getElementById(`intent-view-${props.jobId}-${next}`)?.focus())
}
function closeHistory(restoreFocus = true) {
  if (!historyOpen.value) return
  historyOpen.value = false
  if (restoreFocus && props.active !== false) void nextTick(() => historyToggle.value?.focus())
}
function toggleHistory() {
  if (historyOpen.value) { closeHistory(); return }
  pauseMedia(); historyOpen.value = true
  void nextTick(() => historyPanel.value?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus())
}
function historyKey(event: KeyboardEvent) {
  if (event.key !== 'Tab') return
  const buttons = [...(historyPanel.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])]
  if (event.shiftKey && document.activeElement === buttons[0]) { event.preventDefault(); buttons.at(-1)?.focus() }
  else if (!event.shiftKey && document.activeElement === buttons.at(-1)) { event.preventDefault(); buttons[0]?.focus() }
}
function showExportPreview(url: string) {
  if (!mediaActive('deliveries') || !url) return
  exportPlayer.value?.pause(); previewUrl.value = url
  const requestId = selectedId.value, version = selectionVersion
  void nextTick(() => { if (validOperation(requestId, version) && mediaActive('deliveries') && previewUrl.value === url) previewSection.value?.scrollIntoView({ block: 'nearest' }) })
}
function openCaptions(batch: ExportJob, clip: ExportJob['clips'][number]) {
  if (pending.value || captionEditor.value?.pending || clip.status !== 'SUCCEEDED' || !clip.content_url) return
  if (captionEditor.value?.dirty) { error.value = '字幕有未保存修改，请先保存或放弃字幕修改。'; return }
  pauseMedia(); captionTarget.value = { exportId: batch.id, clipId: clip.id, contentUrl: clip.content_url }
}
async function downloadBatchPackage(batch: ExportJob) {
  if (packageBusyId.value) return
  const id = selectedId.value, version = selectionVersion
  packageBusyId.value = batch.id; error.value = ''
  try {
    const prepared = await prepareCaptionBatch(batch, url => api<unknown>(url))
    if (!validOperation(id, version)) return
    packageUrls.value[batch.id] = prepared.url; notice.value = `已核对 ${prepared.items.length} 个成片的确认字幕版本，素材包已准备好。请点击“下载本批素材包”开始下载。`
  } catch (value) {
    if (validOperation(id, version)) {
      if (value instanceof CaptionBatchError) error.value = value.code === 'BATCH_INCOMPLETE' ? `本批有 ${value.clips.length} 个片段尚未完成，不能整批打包。请先处理失败或未完成项。` : `有 ${value.clips.length} 个成片${value.code === 'CAPTIONS_MISSING' ? '尚未校对字幕' : '字幕尚未确认'}：${value.clips.slice(0, 3).join('、')}。请逐片校对并确认后再打包。`
      else fail(value)
    }
  } finally { if (packageBusyId.value === batch.id) packageBusyId.value = '' }
}
watch(intentView, () => { focusVersion++; pauseMedia() }, { flush: 'sync' })
watch(sourceContextOpen, open => { if (!open) { stopSequencePreview(); sourcePlayer.value?.pause() } }, { flush: 'sync' })
watch(outputPartsSignature, () => { stopSequencePreview() }, { flush: 'sync' })
watch(() => props.videoUrl, () => { pauseMedia() }, { flush: 'sync' })
watch(pending, value => { if (value) stopSequencePreview() }, { flush: 'sync' })
watch(() => props.active, value => { if (value === false) { focusVersion++; closeHistory(false); pauseMedia() } }, { flush: 'sync' })
onMounted(() => {
  window.addEventListener('beforeunload', unload); document.addEventListener('visibilitychange', updatePreviewVisibility); updatePreviewVisibility(); void poll()
  contextObserver = new ResizeObserver(entries => {
    const width = entries[0]?.contentRect.width
    if (stopped || !width) return
    const compact = width <= 760
    if (compact !== compactContext) { compactContext = compact; sourceContextOpen.value = !compact }
  })
  if (intentPanel.value) contextObserver.observe(intentPanel.value)
})
onBeforeUnmount(() => { stopped = true; selectionVersion++; focusVersion++; closeRangePicker(false); pauseMedia(); contextObserver?.disconnect(); scope.dispose(); window.clearTimeout(timer); window.removeEventListener('beforeunload', unload); document.removeEventListener('visibilitychange', updatePreviewVisibility) })
</script>

<template>
  <section ref="intentPanel" class="panel intent-panel" aria-label="按需求找片段" @keydown.esc="closeHistory()">
    <div class="intent-surface" :inert="historyOpen ? true : undefined">
      <header class="intent-toolbar"><h2>按需剪辑 <span v-if="snapshot" class="status">{{ names[snapshot.status] }}</span></h2>
      <nav class="intent-view-tabs" role="tablist" aria-label="按需剪辑流程" @keydown="viewKey">
        <button :id="`intent-view-${jobId}-compose`" role="tab" :aria-controls="`intent-page-${jobId}-compose`" :aria-selected="intentView === 'compose'" :tabindex="intentView === 'compose' ? 0 : -1" @click="setView('compose')">剪辑要求<span v-if="instruction.trim()" class="draft-dot" aria-label="有未提交要求"></span></button>
        <button :id="`intent-view-${jobId}-review`" role="tab" :aria-controls="`intent-page-${jobId}-review`" :aria-selected="intentView === 'review'" :tabindex="intentView === 'review' ? 0 : -1" :disabled="!snapshot" @click="setView('review')">核对方案<span v-if="planDirty" class="draft-dot" aria-label="有未保存修改"></span></button>
        <button :id="`intent-view-${jobId}-deliveries`" role="tab" :aria-controls="`intent-page-${jobId}-deliveries`" :aria-selected="intentView === 'deliveries'" :tabindex="intentView === 'deliveries' ? 0 : -1" :disabled="!snapshot" @click="setView('deliveries')">成片与字幕<span v-if="batches.length" class="intent-view-count">{{ completedClips }}</span></button>
      </nav>
      <div class="intent-toolbar-actions"><button ref="historyToggle" class="secondary" :aria-expanded="historyOpen" :aria-controls="`intent-history-${jobId}`" @click="toggleHistory">历史请求 {{ requests.length }}</button><button class="text-button" :disabled="pending" @click="refresh">刷新</button></div></header>
      <div class="intent-feedback"><div v-if="error" class="message error" role="alert"><span>{{ error }}</span><button aria-label="关闭剪辑错误" @click="error = ''">×</button></div><p v-if="planRevisionConflict" class="intent-warning" role="status">服务端已更新为 r{{ snapshot?.revision }}，与当前基于 r{{ draftRevision }} 的草稿不同。请先核对保存结果；本地修改仍保留。</p><p v-else-if="saveNeedsCheck" class="intent-warning" role="status">上次保存结果尚未确认。请先核对服务端，当前修改仍保留。</p><p v-if="refreshError" class="intent-warning" role="status">{{ refreshError }} 后台状态会继续刷新，当前修改不受影响。</p><p v-if="notice" class="intent-notice" role="status">{{ notice }}</p></div>
      <DraftBackupPanel kind="intent" :document-id="jobId" :value="backupValue" :blocked="backupBlocked" :assess="assessBackup" @restore="restoreBackup" />
      <div class="intent-views">
        <div v-show="intentView === 'compose'" :id="`intent-page-${jobId}-compose`" class="intent-compose-view" role="tabpanel" :aria-labelledby="`intent-view-${jobId}-compose`">
          <div class="intent-compose-title"><h3>想从视频里剪出什么？</h3><p class="help">每个目标生成一个视频。先找候选，再由你核对、确认。</p></div>
          <div class="intent-compose"><label for="intent-instruction">剪辑要求<textarea id="intent-instruction" ref="instructionInput" v-model="instruction" :disabled="pending" maxlength="2000" rows="4" placeholder="例如：把缓存失效原因和解决方法分别剪出来，保留完整解释，跳过关注引导。"></textarea></label><div class="intent-create-row"><label>模型请求上限<input v-model.number="maxCalls" type="number" min="1" max="24" :disabled="pending" aria-label="剪辑模型请求上限"></label><span>复用现有转写，不重新提交 ASR。</span><button class="primary" :disabled="pending || !instruction.trim() || planDirty || saveNeedsCheck || planRevisionConflict" @click="create">{{ action === 'create' ? '正在创建…' : '查找片段' }}</button></div></div>
          <p v-if="saveNeedsCheck || planRevisionConflict" class="intent-draft-hint">当前方案的保存结果需要核对，新要求仍保留。<button class="text-button" @click="setView('review')">回到核对方案 →</button></p>
          <p v-else-if="planDirty" class="intent-draft-hint">当前方案有未保存修改。新要求会保留，先回到核对方案保存或放弃修改，再查找。<button class="text-button" @click="setView('review')">回到核对方案 →</button></p>
          <div v-if="instruction.length || maxCalls !== 6" class="intent-compose-actions"><button class="text-button" :disabled="pending" @click="instruction = ''; maxCalls = 6">放弃新要求与请求上限修改</button></div>
          <details class="intent-guide"><summary>查找与自动恢复说明</summary><p>系统复用本视频的转写，返回原句证据与候选范围。短暂错误会在次数和预算上限内自动恢复，已完成的语音转写会保留；每个确认目标生成一个视频。新请求不会覆盖原来的话题草稿或既有成片。</p></details>
        </div>
        <div v-show="intentView === 'review'" :id="`intent-page-${jobId}-review`" class="intent-review-view" role="tabpanel" :aria-labelledby="`intent-view-${jobId}-review`">
          <div v-if="snapshot" class="intent-review-layout">
            <aside class="intent-context" aria-label="剪辑原视频与执行信息">
              <h3 class="intent-reference-title">原视频回看</h3>
              <details class="intent-source-details" :open="sourceContextOpen" @toggle="sourceContextOpen = ($event.target as HTMLDetailsElement).open"><summary>回看原视频 <span>{{ sourceContextOpen ? '收起' : '展开' }}</span></summary>
              <video ref="sourcePlayer" :src="videoUrl" controls preload="metadata" playsinline class="intent-source" @play="guardPlayback" @loadedmetadata="sourceMetadata" @timeupdate="sourceSequenceTime" @playing="sourceSequenceTime" @seeking="sourceSequenceSeeking" @seeked="sequence.seeked($event.currentTarget as HTMLVideoElement)" @pause="sequence.pause($event.currentTarget as HTMLVideoElement)" @ended="sequence.ended($event.currentTarget as HTMLVideoElement)" @ratechange="sequence.rateChange($event.currentTarget as HTMLVideoElement)" @error="sourceSequenceError"></video>
              <div v-if="preparingSequence || sequenceState" class="intent-sequence-preview"><div class="intent-sequence-heading"><strong>{{ sequenceDescription?.title }}</strong><button class="text-button" @click="stopSequencePreview('区间预览已停止。')">停止预览</button></div><p role="status">{{ preparingSequence ? '正在准备原视频…' : sequenceDescription?.partIndex !== undefined ? `正在回看保留第 ${sequenceDescription.partIndex + 1} 段` : `正在回看第 ${(sequenceState?.part_index ?? 0) + 1} / ${sequenceState?.part_count ?? 1} 段` }}</p><template v-if="sequenceState"><progress :value="sequenceState.played_ms" :max="sequenceState.total_ms" aria-label="目标区间预览进度" :aria-valuetext="`${topicTime(sequenceState.played_ms)} / ${topicTime(sequenceState.total_ms)}`"></progress><span class="intent-sequence-time">{{ topicTime(sequenceState.played_ms) }} / {{ topicTime(sequenceState.total_ms) }} · 当前 {{ topicTime(sequenceState.start_ms) }} → {{ topicTime(sequenceState.end_ms) }}</span></template><small>浏览器按原视频区间回看，正式拼接以导出文件为准。</small></div>
              <p v-if="sequenceFeedback" class="intent-sequence-feedback" role="status">{{ sequenceFeedback }}</p>
              <p class="help">点击候选原句可定位回看；多段内容按所列顺序拼接。</p>
              <details class="intent-original-details"><summary>本次剪辑要求</summary><p class="intent-original">{{ snapshot.instruction }}</p></details>
              </details>
        <p v-if="snapshot.resumed_from_request_id" class="help">本次继续自请求 #{{ snapshot.resumed_from_request_id.slice(0, 8) }}，原转写复用，文本分析与预算独立。</p>
        <p class="help">本次已预留文本请求 {{ snapshot.budget.text_calls_reserved }} / {{ snapshot.budget.max_text_calls }}，包含自动恢复尝试，为次数上限。</p>
        <div v-if="['QUEUED', 'RUNNING'].includes(snapshot.status)" class="intent-recovery-actions">
          <p class="help">不再需要继续查找时可取消，已经完成的语音转写会保留。</p>
          <button class="text-button" :disabled="!canCancel" @click="cancelRequest">{{ action === 'cancel' ? '正在取消…' : '取消本次查找' }}</button>
        </div>
        <div v-if="['FAILED', 'CANCELLED'].includes(snapshot.status)" class="intent-recovery-actions">
          <p class="help">继续查找会复用已完成转写，新建一次文本分析，使用下方设置的调用次数上限；旧请求与调用记录保留。</p>
          <p v-if="instruction.trim()" class="help">剪辑要求页有尚未提交的新要求，请先提交或放弃输入，再继续当前请求。</p>
          <label class="intent-resume-budget">新请求模型上限<input v-model.number="maxCalls" type="number" min="1" max="24" :disabled="pending" aria-label="继续查找模型请求上限"></label><button class="primary" :disabled="!canResume" @click="resumeRequest">{{ action === 'resume' ? '正在继续…' : '继续查找' }}</button>
        </div>
        <TextRequestProgress :progress="snapshot.stage.text_progress" :terminal="['PLAN_READY', 'FAILED', 'CANCELLED'].includes(snapshot.status)" />
        <FailureDiagnostic v-if="snapshot.status !== 'CANCELLED'" :error-code="snapshot.stage.error_code" :attempt="snapshot.stage.attempt" />

            </aside>
            <div class="intent-output-list"><div class="intent-subheading"><h3>候选目标 <span v-if="snapshot.plan">{{ outputs.length }} 个 · 已选 {{ selectedCount }} 个 · r{{ draftRevision }}</span></h3><button v-if="planDirty || saveNeedsCheck || planRevisionConflict" class="text-button" :disabled="pending" @click="discard">放弃方案修改</button></div>
              <p v-if="!snapshot.plan" class="intent-empty">{{ snapshot.status === 'FAILED' ? '本次查找未完成。请查看执行原因，原视频和原话题结果仍保留。' : snapshot.status === 'CANCELLED' ? '本次请求已取消。' : snapshot.status === 'RUNNING' ? '正在查找候选片段，执行进度会自动更新。' : '正在等待执行服务生成候选方案。' }}</p>
              <template v-else>
          <p v-if="unresolved" class="intent-warning">有 {{ unresolved }} 个目标未找到充分证据，不能勾选导出。你可以只确认已经核对的目标，或另建更明确的请求。</p>
          <article v-for="(output, index) in outputs" :key="output.output_id" class="intent-output">
            <div class="intent-output-heading"><label><input v-model="output.enabled" type="checkbox" :disabled="pending || output.status !== 'FOUND'" :aria-label="`选择剪辑目标 ${goal(output)}`">目标 {{ index + 1 }}：{{ goal(output) }}</label><div class="intent-output-actions"><span>{{ names[output.status] }}</span><button class="secondary" :disabled="!canPreviewSequence(output)" :aria-label="`${sequenceSelected(output) ? '停止预览' : '预览'}目标 ${index + 1} 全部区间`" @click="previewSequence(output)">{{ sequenceSelected(output) ? '停止预览' : '预览全部区间' }}</button></div></div>
            <details class="intent-reason-details" :open="output.status !== 'FOUND'"><summary>AI 选段说明<span v-if="output.status !== 'FOUND'"> · 需要复核</span></summary><p class="intent-reason">{{ output.reason }}</p></details>
            <label class="intent-title-label">成片标题<input v-model="output.title" maxlength="120" :disabled="pending" :aria-label="`剪辑标题 ${index + 1}`"></label>
            <div v-for="(part, partIndex) in output.parts" :key="partIndex" class="intent-part">
              <div class="intent-part-label"><b>保留第 {{ partIndex + 1 }} 段</b><div class="intent-part-actions"><button class="text-button" :disabled="!canPreviewSequence(output, partIndex)" :aria-label="`${sequenceSelected(output, partIndex) ? '停止预览' : '预览'}目标 ${index + 1} 第 ${partIndex + 1} 段`" @click="previewSequence(output, partIndex)">{{ sequenceSelected(output, partIndex) ? '停止本段' : '预览本段' }}</button><button class="text-button" :disabled="pending" @click="locate(part.start_utterance_id)">定位原视频 ↗</button></div></div>
              <div class="intent-boundaries"><button class="intent-boundary-button" :disabled="pending || output.status === 'NOT_FOUND'" :aria-label="`调整目标 ${index + 1} 第 ${partIndex + 1} 段起始原句`" @click="openRangePicker(output, partIndex, 'start', $event)"><span>开始 <strong>{{ part.start_ms >= 0 ? topicTime(part.start_ms) : '未选择' }}</strong><small>调整 ↗</small></span><p>{{ part.start_utterance_id ? sourceText(part.start_utterance_id) : '选择要保留的第一句' }}</p></button><button class="intent-boundary-button" :disabled="pending || output.status === 'NOT_FOUND'" :aria-label="`调整目标 ${index + 1} 第 ${partIndex + 1} 段结束原句`" @click="openRangePicker(output, partIndex, 'end', $event)"><span>结束 <strong>{{ part.end_ms >= 0 ? topicTime(part.end_ms) : '未选择' }}</strong><small>调整 ↗</small></span><p>{{ part.end_utterance_id ? sourceText(part.end_utterance_id) : '选择要保留的最后一句' }}</p></button></div>
              <button class="text-button intent-remove" :disabled="pending" @click="removePart(output, partIndex)">移除此段</button>
            </div>
            <button v-if="output.status !== 'NOT_FOUND' && output.parts.length < 12" class="text-button" :disabled="pending" @click="addPart(output)">＋ 增加保留区间</button>
            <details v-if="output.evidence_utterance_ids.length" class="intent-evidence"><summary>依据原句 {{ output.evidence_utterance_ids.length }} 条</summary><button v-for="id in output.evidence_utterance_ids" :key="id" @click="locate(id)"><span>{{ topicTime(sourceTime(id)) }}</span>{{ sourceText(id) }}</button></details>
            <details v-if="output.excluded.length" class="intent-excluded"><summary>排除内容 {{ output.excluded.length }} 条</summary><div v-for="(excluded, excludeIndex) in output.excluded" :key="excluded.utterance_id"><button class="text-button" @click="locate(excluded.utterance_id)">{{ topicTime(sourceTime(excluded.utterance_id)) }} {{ sourceText(excluded.utterance_id) }}</button><small>{{ excluded.reason }}</small><button class="text-button" :disabled="pending" @click="unexclude(output, excludeIndex)">取消排除</button></div></details>
            <p class="help">{{ output.parts.length }} 个连续区间 · 预计保留 {{ topicTime(duration(output)) }} · 导出时合成一个文件</p>
          </article>
              </template>
            </div>
          </div>
          <p v-else class="intent-empty">{{ loading ? '正在读取剪辑请求…' : '先描述剪辑要求，或打开一条历史请求。' }}</p>
        </div>
        <div v-show="intentView === 'deliveries'" :id="`intent-page-${jobId}-deliveries`" class="intent-delivery-view" role="tabpanel" :aria-labelledby="`intent-view-${jobId}-deliveries`">
          <div class="intent-delivery-heading"><h3>成片与字幕 <span>{{ completedClips }} 个成片完成 · {{ batches.length }} 个批次</span></h3><p class="help">这里展示本次请求已经冻结的成片。修改新方案不会改变已有文件。</p><p v-if="dirty" class="intent-draft-hint">其他页面的未提交要求与方案修改仍保留；下载只包含已确认版本。</p></div>
          <section v-if="previewUrl" ref="previewSection" class="intent-preview" aria-label="剪辑成片预览"><div class="intent-subheading"><h3>成片预览</h3><button class="text-button" @click="exportPlayer?.pause(); previewUrl = ''">收起预览</button></div><video ref="exportPlayer" :key="previewUrl" :src="previewUrl" controls preload="metadata" playsinline class="intent-source" @play="guardPlayback"></video></section>
          <div v-if="batches.length" class="intent-exports"><article v-for="batch in batches" :key="batch.id" class="intent-batch"><p>确认版本 r{{ batch.approved_revision }} · {{ names[batch.status] ?? batch.status }} · {{ date(batch.created_at) }}</p><div class="caption-batch-actions"><button class="secondary" :disabled="!!packageBusyId || pending" @click="downloadBatchPackage(batch)">{{ packageBusyId === batch.id ? '正在核对字幕版本…' : '核对并准备素材包' }}</button><a v-if="packageUrls[batch.id]" class="text-button" :href="packageUrls[batch.id]" download target="_blank" rel="noopener">下载本批素材包</a><small>全部成片完成并确认字幕后，可打包原 MP4 与字幕。</small></div><div v-for="clip in batch.clips" :key="clip.id" class="intent-export-clip"><strong>{{ clip.title ?? '剪辑视频' }}</strong><span>{{ names[clip.status] ?? clip.status }}</span><FailureDiagnostic :diagnostic="clip.diagnostic" :error-code="clip.error_code" :attempt="clip.attempt" /><div v-if="clip.status === 'SUCCEEDED'" class="actions"><button class="text-button" @click="showExportPreview(clip.content_url ?? '')">预览成片</button><button class="text-button" :disabled="pending" @click="openCaptions(batch, clip)">校对字幕</button><a class="text-button" :href="clip.download_url ?? undefined" download target="_blank" rel="noopener">下载 MP4</a></div></div></article></div>
          <div v-else class="intent-empty"><p>还没有生成成片。先核对并确认所选目标，再生成 MP4。</p><button class="secondary" @click="setView('review')">回到核对方案</button></div>
        </div>
      </div>
      <footer v-if="snapshot?.plan" v-show="intentView === 'review'" class="intent-save-row"><p v-if="validation" class="intent-validation" role="status">{{ validation }}</p><div class="intent-save-controls"><span>{{ planRevisionConflict ? '服务端与本地版本不同，当前草稿仍保留' : saveNeedsCheck ? '保存结果待核对，暂不重复提交' : instruction.trim() ? '有尚未提交的新要求，请先提交或放弃输入。' : planDirty ? '方案修改尚未保存' : confirmed ? `r${snapshot.revision} 已确认，可以生成成片。` : '核对并勾选目标，再确认生成' }}</span><div class="actions"><button class="primary" :disabled="!primaryAction.enabled" @click="primaryAction.run">{{ primaryAction.label }}</button></div></div></footer>
    </div>
    <div v-if="historyOpen" class="intent-history-backdrop" aria-hidden="true" @click="closeHistory()"></div>
    <aside v-show="historyOpen" ref="historyPanel" :id="`intent-history-${jobId}`" class="intent-history" role="dialog" aria-modal="true" aria-label="剪辑请求列表" @keydown="historyKey"><div class="intent-subheading"><h3>历史请求 {{ requests.length }}</h3><button class="text-button" @click="closeHistory()">关闭历史请求</button></div><p v-if="loading" class="help">正在读取请求…</p><p v-else-if="!requests.length" class="help">还没有剪辑请求。填写要求即可开始。</p><div class="intent-request-list"><button v-for="request in requests" :key="request.id" class="intent-request" :class="{ selected: selectedId === request.id }" :aria-pressed="selectedId === request.id" :disabled="pending" @click="selectRequest(request.id)"><strong>{{ request.instruction }}</strong><span>{{ names[request.status] }} · {{ date(request.created_at) }}</span></button></div></aside>
    <CaptionPanel v-if="captionTarget" :key="`${captionTarget.exportId}:${captionTarget.clipId}`" ref="captionEditor" :export-id="captionTarget.exportId" :clip-id="captionTarget.clipId" :content-url="captionTarget.contentUrl" :active="props.active !== false && intentView === 'deliveries' && !historyOpen" @close="captionTarget = null" />
    <TranscriptRangePicker v-if="rangePickerTarget" :key="rangePickerTarget.token" ref="rangePickerEditor" :output="rangePickerTarget.output" :part-index="rangePickerTarget.partIndex" :utterances="rangePickerTarget.utterances" :duration-ms="rangePickerTarget.durationMs" :initial-field="rangePickerTarget.initialField" :initial-time-ms="rangePickerTarget.initialTimeMs" :active="props.active !== false && intentView === 'review' && !historyOpen" :stale="rangePickerStale" @close="rangePickerCloseHandler" @apply="rangePickerApplyHandler" />
  </section>
</template>

<style scoped src="./intent-workspace.css"></style>
