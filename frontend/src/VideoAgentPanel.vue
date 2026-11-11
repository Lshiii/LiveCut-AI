<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, ApiError, post } from './api'
import FailureDiagnostic from './FailureDiagnostic.vue'
import TextRequestProgress from './TextRequestProgress.vue'
import AgentAnswer from './AgentAnswer.vue'
import AgentEvidenceAnswer from './AgentEvidenceAnswer.vue'
import { parseAgentEvidenceCapability } from './agentEvidenceAnswer'
import { parseExecutorSnapshot, type ExecutorSnapshot } from './executors'
import AgentVisualEvidence from './AgentVisualEvidence.vue'
import AgentNotePanel from './AgentNotePanel.vue'
import { AgentForegroundRefresh, type AgentRefreshLease } from './agentRefresh'
import { AgentSessionHistoryPager, agentSessionHistoryHasActiveWork, mergeLegacyAgentSessions, readAgentSessionHistory, type AgentHistoryNavigation } from './agentSessionHistory'
import { parseAgentVisualCapability, type AgentVisualCapability } from './agentVisualEvidence'
import { AgentFocusError, canCaptureAgentFocus, captureAgentFocus, focusHintMatches, parseAgentFocusCapability, type AgentFocusHint } from './agentFocus'
import { agentFrameFollowupTarget, confirmAgentFrameFollowup, prepareAgentFrameFollowup, sameAgentFocusHint,
  type AgentFrameFollowupProposal, type AgentFrameFollowupTarget } from './agentFrameFollowup'
import { SequencePreview } from './sequencePreview'
import { selectAnswerClip, type AnswerClipSelection } from './answerClip'
import { topicTime } from './topics'
import { AgentReadScope, AgentResponseError, agentTurnActive, canPrefillClipInstruction,
  newestAgentSession, newestAgentTurn, parseAgentSession, parseAgentTurn,
  type AgentCitation, type AgentSession, type AgentTurn } from './videoAgent'
import { AgentLegacySubmissionContext, AgentSubmissionError, agentDraftAfterSubmission, agentDraftMatchesSubmission, agentSubmissionStorageKey, assertAgentSubmissionStored, createAgentSubmission, filterAcknowledgedAgentSubmissions, inspectAgentSubmissionLimit, mergeAgentSubmissionResult, parseAgentSubmissionResponse,
  removeAgentSubmission, resolveAgentSubmission, restoreAgentSubmission, retainAgentSubmissions, scanAgentSubmissions,
  type AgentSubmissionEntry, type AgentSubmissionIssue, type AgentSubmissionResult } from './agentSubmissionRecovery'

const props = defineProps<{ jobId: string; videoId: string; videoHash: string; videoUrl: string; clipTargetBusy?: boolean; active?: boolean }>()
const emit = defineEmits<{ clipInstruction: [instruction: string]; clipAnchor: [selection: AnswerClipSelection] }>()
const sessions = ref<AgentSession[]>([]), selectedId = ref(''), question = ref(''), maxCalls = ref(8)
const visualMode = ref<'none' | 'frames'>('none'), visualCapability = ref<AgentVisualCapability | null>(null), capabilityLoading = ref(false), capabilityError = ref('')
const evidenceMode = ref(false), evidenceSupported = ref(false), evidenceUnsupported = ref(false), evidenceLoading = ref(false), evidenceError = ref('')
const evidenceExecutor = ref<ExecutorSnapshot['stages'][number] | null>(null)
const focusHint = ref<AgentFocusHint | null>(null), focusSupported = ref(false), focusLoading = ref(false), focusError = ref(''), focusPlayerVersion = ref(0)
const followupProposal = ref<AgentFrameFollowupProposal | null>(null), questionInput = ref<HTMLTextAreaElement | null>(null)
const action = ref(''), error = ref(''), refreshError = ref(''), notice = ref(''), loading = ref(true)
const pageVisible = ref(!document.hidden), refreshing = ref(false)
const history = ref(new AgentSessionHistoryPager()), historyLoading = ref(false), historyError = ref('')
const selectionTargetId = ref(''), detailLoading = ref(false), selectionError = ref('')
const legacyLoading = ref(false), legacyError = ref('')
const selected = computed(() => sessions.value.find(session => session.id === selectedId.value && session.job_id === props.jobId
  && session.video_id === props.videoId && session.source.video_sha256 === props.videoHash) ?? null)
const selectionUnavailable = computed(() => !!selectionTargetId.value || !!selectedId.value && !selected.value)
const activeTurn = computed(() => selected.value?.turns.find(agentTurnActive))
const recentAnswer = computed(() => [...(selected.value?.turns ?? [])].reverse().find(turn => !!turn.result))
const referenceTurnId = ref('')
const noteTurnId = ref('')
const referenceAnswer = computed(() => selected.value?.turns.find(turn => turn.id === referenceTurnId.value && !!turn.result) ?? recentAnswer.value)
const recentCitations = computed(() => referenceAnswer.value?.result?.citations ?? [])
const allEvidenceVisible = ref(false)
const visibleCitations = computed(() => allEvidenceVisible.value ? recentCitations.value : recentCitations.value.slice(0, 3))
const anchorPreparing = ref(false), anchorChoices = ref<Record<string, string[]>>({})
const submissionEntries = ref<AgentSubmissionEntry[]>([]), submissionIssues = ref<AgentSubmissionIssue[]>([])
const ownDurabilityLost = ref(false)
const fullSessionConflicts = ref<Record<string, boolean>>({})
const submissionBlocked = computed(() => !!submissionEntries.value.length || !!submissionIssues.value.length)
const pending = computed(() => !!action.value || anchorPreparing.value), dirty = computed(() => !!question.value.trim() || !!focusHint.value || evidenceMode.value || ownDurabilityLost.value)
const citation = ref<AgentCitation | null>(null), sourcePlayer = ref<HTMLVideoElement | null>(null), playbackError = ref('')
const frameLocation = ref<{ time: number; title: string } | null>(null)
const messageList = ref<HTMLOListElement | null>(null)
const panel = ref<HTMLElement | null>(null), referenceDock = ref<HTMLElement | null>(null), referenceEvidence = ref<HTMLElement | null>(null)
const compactReference = ref(true), referenceExpanded = ref(false)
const conversationFocused = ref(false)
const referenceVisible = computed(() => compactReference.value ? referenceExpanded.value : !conversationFocused.value)
const sessionListOpen = ref(false)
const historyPanel = ref<HTMLElement | null>(null), historyToggle = ref<HTMLButtonElement | null>(null)
function closeHistory(restoreFocus = true) {
  if (!sessionListOpen.value) return
  sessionListOpen.value = false
  if (restoreFocus && props.active !== false) void nextTick(() => historyToggle.value?.focus())
}
function toggleHistory() {
  if (sessionListOpen.value) { closeHistory(); return }
  sessionListOpen.value = true
  void nextTick(() => historyPanel.value?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus())
}
function historyKey(event: KeyboardEvent) {
  if (event.key !== 'Tab') return
  const buttons = [...(historyPanel.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])]
  if (event.shiftKey && document.activeElement === buttons[0]) { event.preventDefault(); buttons.at(-1)?.focus() }
  else if (!event.shiftKey && document.activeElement === buttons.at(-1)) { event.preventDefault(); buttons[0]?.focus() }
}
const scope = new AgentReadScope()
const historyScope = new AgentReadScope(), legacyScope = new AgentReadScope()
let legacyContext = new AgentLegacySubmissionContext({ job_id: props.jobId, video_id: props.videoId, video_sha256: props.videoHash })
let historyVersion = 0, detailVersion = 0, legacyVersion = 0, historyProbeRequested = true
let legacyRun: { promise: Promise<void> } | null = null, legacyRequested = false
// Only actions this page started own an unacknowledged identity. Passive remote
// entries remain visible/blocking without being re-created after their owner ACKs.
const ownUnacknowledged = new Map<string, AgentSubmissionEntry>()
const acknowledgedSubmissions = new Map<string, AgentSubmissionEntry>()
let submissionVersion = 0
let stopped = false, mounted = false, refreshVersion = 0, selectionVersion = 0, previewVersion = 0
let referenceObserver: ResizeObserver | undefined, capabilityVersion = 0, focusCapabilityVersion = 0, evidenceCapabilityVersion = 0
let followupVersion = 0
let preparedEvidence: { version: number; sessionId: string; turnId: string; videoUrl: string; sourceHash: string; start: number; end?: number } | null = null
let evidenceTimer: number | undefined
const evidenceSequence = new SequencePreview()
let followLatest = true, initialScrollPending = false
const examples = ['概括这段视频', '展开解释刚才提到的第二点', '哪些片段值得单独剪']
const names: Record<string, string> = { QUEUED: '等待回答', RUNNING: '正在查找与回答', SUCCEEDED: '回答完成', FAILED: '本轮未完成', CANCELLED: '已取消' }
const scopes = { VIDEO: '来自视频', GENERAL: '通用知识补充', INSUFFICIENT: '视频证据不足' }
const toolNames = { search_transcript: '检索视频原句', read_context: '补读相邻内容', list_topics: '查看视频话题', inspect_frames: '查看指定画面', inspect_clip: '短片画面抽样' }
const canAttachFocus = computed(() => { void focusPlayerVersion.value; return canCaptureAgentFocus(focusCaptureInput()) })
const focusSubmissionBlocked = computed(() => !!focusHint.value && (visualMode.value !== 'frames' || !focusSupported.value || !focusHintMatches(focusHint.value, focusOwner())))
const evidenceSubmissionBlocked = computed(() => evidenceMode.value && (visualMode.value !== 'frames' || !evidenceSupported.value || !evidenceExecutor.value))
const frameFollowupUnavailable = computed(() => {
  if (props.active === false || !pageVisible.value || sessionListOpen.value) return '请回到当前视频对话后继续提问。'
  if (selectionUnavailable.value) return '所选会话的完整内容尚未核对，请先完成读取。'
  if (pending.value) return '当前操作正在处理，完成后可在此时间继续提问。'
  if (capabilityLoading.value || focusLoading.value) return '正在核对画面与位置能力，请稍后重试。'
  if (visualCapability.value?.visual_mode !== 'frames') return '请配置画面模型，并点击下方“检查画面能力”，再按截图时间继续提问。'
  if (!focusSupported.value) return '位置支持尚未确认，请点击下方“检查画面能力”后重试。'
  return null
})
const hasActiveRefreshWork = computed(() => agentSessionHistoryHasActiveWork(history.value, selected.value))
const foregroundRefresh = new AgentForegroundRefresh({ refresh: refreshNow, hasActiveWork: () => hasActiveRefreshWork.value,
  capabilities: async lease => { await Promise.all([loadVisualCapability(lease.current, lease.signal), loadFocusCapability(lease.current, lease.signal), loadEvidenceCapability(lease.current, lease.signal)]) } })
function foregroundReadable() { return !stopped && props.active !== false && !document.hidden }
function syncRefreshLifecycle() {
  if (!mounted) return
  if (!foregroundReadable() || pending.value) { refreshVersion++; scope.invalidate(); refreshing.value = false; cancelHistoryRead(); detailVersion++; detailLoading.value = false; cancelLegacyRead() }
  foregroundRefresh.update({ active: props.active !== false, visible: pageVisible.value && !document.hidden, blocked: pending.value })
}
function windowFocused() { scanSubmissions(); pageVisible.value = !document.hidden; historyProbeRequested = true; syncRefreshLifecycle(); foregroundRefresh.focus(); void checkLegacySlots() }
function refresh() { return foregroundRefresh.refresh() }
defineExpose({ dirty, pending })

function message(value: unknown) {
  const rawCode = value instanceof ApiError || value instanceof AgentSubmissionError || value instanceof AgentFocusError ? value.code : value instanceof AgentResponseError ? 'INVALID_AGENT_RESPONSE' : 'CLIENT_ERROR'
  const code = typeof rawCode === 'string' && /^[A-Z][A-Z0-9_]{0,99}$/.test(rawCode) ? rawCode : 'CLIENT_ERROR'
  const known: Record<string, string> = {
    NETWORK_UNAVAILABLE: '暂时无法连接服务，输入和已有回答仍保留。',
    REQUEST_TIMEOUT: '提交结果尚未确认，输入仍保留。请使用下方“核对原提交”，不会因修改问题或预算建立新请求。',
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
    AGENT_SUBMISSION_PENDING: '存在尚未核对的原提交，请逐项核对后再新建对话或发送新问题。当前草稿保留。',
    AGENT_SUBMISSION_STORAGE_UNAVAILABLE: '无法写入并复读本机回执，本次未发送新的请求。已有未决记录和当前草稿保留，请恢复浏览器存储后核对。',
    AGENT_SUBMISSION_ENTRY_INVALID: '本机存在无法识别的提交回执，未把它当作空记录。请保留下载后核对，暂不能建立新请求。',
    AGENT_SUBMISSION_LEGACY_INVALID: '旧提交回执格式不符合要求，原记录保留，未替换请求标识或直接重发。',
    AGENT_SUBMISSION_LEGACY_CHANGED: '迁移期间另一个页面更新了旧回执，两份记录均保留。请刷新回执并逐项核对。',
    AGENT_SUBMISSION_LEGACY_OWNER_UNVERIFIED: '有旧提交尚未核对所属会话，原记录保留。请先核对旧提交归属，再新建对话或发送问题。',
    AGENT_SUBMISSION_LEGACY_OWNER_INVALID: '旧提交的完整会话来源无法核对，原记录保留，暂不能建立新请求。',
    AGENT_SUBMISSION_LEGACY_LIMIT: '本机旧提交数量超过单次核对上限，原记录保留，暂不能建立新请求。',
    AGENT_SUBMISSION_ENTRY_CONFLICT: '本机原回执已被其他页面移除或更改，尚未把它当作提交成功；请按原标识只读核对。',
    AGENT_SUBMISSION_SOURCE_MISMATCH: '本机回执与当前视频来源不同，记录仍保留，不能替换原来源或建立新请求。',
    INVALID_AGENT_SUBMISSION_RESPONSE: '原提交响应的来源、问题或预算不一致，原回执与本页草稿仍保留。',
    AGENT_SUBMISSION_INVALID: '服务端原提交指纹未通过校验，原回执与本页草稿仍保留。',
    HTTP_404: '当前服务未提供提交核对路由，请更新服务后重试；原回执保留，未直接重新提问。',
    RESOURCE_NOT_FOUND: '当前服务未提供提交核对路由，请更新服务后重试；原回执保留，未直接重新提问。',
    AGENT_VISUAL_UNAVAILABLE: '当前服务未确认可结合画面，尚未提交视觉请求。画面选择和问题保留，未降级为文本请求。',
    AGENT_EVIDENCE_UNAVAILABLE: '分项协议或专用执行队列尚未通过核对，分项开关与问题保留；未改成旧视觉或文本请求。',
    AGENT_EVIDENCE_MODE_REQUIRED: '草稿已选分项证据回答，请切回“结合画面”或明确关闭分项开关后再提交。',
    AGENT_FOCUS_UNAVAILABLE: '当前服务尚未确认支持附加位置。定位提示与问题保留；未删除位置或改为无位置请求。',
    AGENT_FOCUS_NOT_READY: '请先展开并加载当前原视频，等定位完成后再附加位置。当前草稿与原提交保持不变。',
    AGENT_FOCUS_MODE_REQUIRED: '草稿包含定位提示。请移除提示或切回“结合画面”后再提交。',
    AGENT_FOCUS_SOURCE_CHANGED: '定位提示的原视频来源已变化，请明确移除或重新附加当前位置。',
    AGENT_FRAME_FOLLOWUP_INVALID: '这张截图的时间或来源无法核对，未改变问题和定位提示。请使用当前对话的有效画面引用。',
    AGENT_FRAME_FOLLOWUP_UNAVAILABLE: '请先确认画面模型与位置能力可用，再按截图时间继续提问。当前草稿保留，可点击“检查画面能力”。',
    AGENT_FRAME_FOLLOWUP_NOT_READY: '请回到可见的视频对话页，等待当前操作完成后再使用截图时间。当前草稿保留。',
    AGENT_FRAME_FOLLOWUP_CHANGED: '原定位提示或截图来源已变化，请重新选择截图时间。当前草稿保留。',
  }
  return known[code] ?? `对话操作未完成：${code}`
}
function parseSession(raw: unknown, id?: string) { return parseAgentSession(raw, props.jobId, props.videoId, props.videoHash, id) }
async function loadVisualCapability(current: () => boolean = foregroundReadable, signal?: AbortSignal) {
  if (!foregroundReadable() || !current() || signal?.aborted) return null
  const version = ++capabilityVersion
  const owner = `${props.jobId}:${props.videoId}:${props.videoHash}`
  const validRead = () => foregroundReadable() && current() && !signal?.aborted && owner === `${props.jobId}:${props.videoId}:${props.videoHash}` && version === capabilityVersion
  capabilityLoading.value = true; capabilityError.value = ''
  try {
    const value = parseAgentVisualCapability(await api<unknown>('/api/agent-capabilities', { signal }))
    if (!validRead()) return null
    visualCapability.value = value; return value
  } catch {
    if (validRead()) { visualCapability.value = null; capabilityError.value = '暂时无法确认画面能力；普通文本提问仍可使用。' }
    return null
  } finally { if (!stopped && version === capabilityVersion) capabilityLoading.value = false }
}
async function loadFocusCapability(current: () => boolean = foregroundReadable, signal?: AbortSignal) {
  if (!foregroundReadable() || !current() || signal?.aborted) return false
  const version = ++focusCapabilityVersion
  const owner = `${props.jobId}:${props.videoId}:${props.videoHash}`
  const validRead = () => foregroundReadable() && current() && !signal?.aborted && owner === `${props.jobId}:${props.videoId}:${props.videoHash}` && version === focusCapabilityVersion
  focusLoading.value = true; focusError.value = ''
  try {
    parseAgentFocusCapability(await api<unknown>('/api/agent-capabilities/focus', { signal }))
    if (!validRead()) return false
    focusSupported.value = true; return true
  } catch {
    if (validRead()) { focusSupported.value = false; focusError.value = '暂不能附加位置；普通视觉提问仍可使用，现有定位提示保留。' }
    return false
  } finally { if (!stopped && version === focusCapabilityVersion) focusLoading.value = false }
}
async function loadEvidenceCapability(current: () => boolean = foregroundReadable, signal?: AbortSignal) {
  if (!foregroundReadable() || !current() || signal?.aborted) return false
  const version = ++evidenceCapabilityVersion, owner = `${props.jobId}:${props.videoId}:${props.videoHash}`
  const validRead = () => foregroundReadable() && current() && !signal?.aborted && owner === `${props.jobId}:${props.videoId}:${props.videoHash}` && version === evidenceCapabilityVersion
  evidenceLoading.value = true; evidenceError.value = ''
  let protocolChecked = false
  try {
    parseAgentEvidenceCapability(await api<unknown>('/api/agent-capabilities/evidence', { signal }))
    protocolChecked = true
    if (!validRead()) return false
    evidenceSupported.value = true; evidenceUnsupported.value = false
    const executors = parseExecutorSnapshot(await api<unknown>(`/api/executors?job_id=${props.jobId}`, { signal }))
    if (!validRead()) return false
    evidenceExecutor.value = executors.stages.find(stage => stage.stage_type === 'AGENT_EVIDENCE') ?? null
    if (!evidenceExecutor.value) throw new ApiError('AGENT_EVIDENCE_UNAVAILABLE')
    return true
  } catch (value) {
    if (validRead()) { evidenceSupported.value = false; evidenceExecutor.value = null; evidenceUnsupported.value = !protocolChecked && value instanceof ApiError && value.status === 404
      evidenceError.value = evidenceUnsupported.value ? '当前服务版本未提供分项证据协议；原草稿与原提交保留。' : '暂不能核对分项协议或专用执行器能力；开关与原草稿保留，请重新检查。' }
    return false
  } finally { if (!stopped && version === evidenceCapabilityVersion) evidenceLoading.value = false }
}
function focusOwner() {
  const duration = selected.value?.source.duration_ms ?? Math.floor((sourcePlayer.value?.duration ?? 0) * 1000)
  return { job_id: props.jobId, video_id: props.videoId, source_hash: props.videoHash, duration_ms: duration }
}
function focusCaptureInput() {
  let url = ''
  try { url = new URL(props.videoUrl, document.baseURI).href } catch { /* An invalid current source cannot be captured. */ }
  return { player: sourcePlayer.value, owner: focusOwner(), expectedSourceUrl: url, active: props.active !== false && !stopped,
    visible: !document.hidden && referenceVisible.value && !sessionListOpen.value, frames: visualMode.value === 'frames', supported: focusSupported.value,
    pending: pending.value || selectionUnavailable.value, pendingPreview: preparedEvidence !== null }
}
function touchFocusPlayer() { focusPlayerVersion.value++ }
function attachFocus() {
  try { focusHint.value = captureAgentFocus(focusCaptureInput(), pauseReference); notice.value = '已暂停并附加定位提示；后续播放或拖动不会改变它，尚未获取或发送截图。'; error.value = '' }
  catch (value) { error.value = message(value) }
}
function removeFocus() { if (!pending.value) { focusHint.value = null; notice.value = '已移除本页定位提示；未改变任何原提交。' } }
function frameFollowupContext() {
  return { owner: focusOwner(), session_id: selectedId.value, transcript_hash: selected.value?.source.transcript_hash ?? '',
    active: props.active !== false && !stopped, visible: !document.hidden && !sessionListOpen.value, pending: pending.value || selectionUnavailable.value,
    visual_available: visualCapability.value?.visual_mode === 'frames' && !capabilityLoading.value,
    focus_supported: focusSupported.value && !focusLoading.value }
}
function clearFrameFollowup() { followupVersion++; followupProposal.value = null }
function applyFrameFollowup(target: AgentFrameFollowupTarget) {
  const fresh = agentFrameFollowupTarget(selected.value, target.turn_id, target.frame_id, frameFollowupContext())
  if (JSON.stringify(fresh) !== JSON.stringify(target)) throw new AgentFocusError('AGENT_FRAME_FOLLOWUP_CHANGED')
  pauseReference()
  visualMode.value = 'frames'; focusHint.value = fresh.hint; clearFrameFollowup()
  error.value = ''; notice.value = `已采用第 ${fresh.sequence} 轮 ${fresh.frame_id} 的实际时间 ${topicTime(fresh.hint.timestamp_ms)}，并切到“结合画面”。问题与请求上限保留，发送后将重新取帧。`
  const version = ++followupVersion
  void nextTick(() => {
    if (version === followupVersion && !stopped && props.active !== false && !document.hidden && !sessionListOpen.value && !pending.value
      && selectedId.value === fresh.session_id && sameAgentFocusHint(focusHint.value, fresh.hint) && visualMode.value === 'frames') questionInput.value?.focus()
  })
}
function continueFromFrame(turnId: string, frameId: string) {
  try {
    const value = prepareAgentFrameFollowup(selected.value, turnId, frameId, frameFollowupContext(), focusHint.value)
    if (value.kind === 'replace') { followupProposal.value = value.proposal; error.value = ''; notice.value = '' }
    else applyFrameFollowup(value.target)
  } catch (value) { error.value = message(value) }
}
function acceptFrameFollowup() {
  if (!followupProposal.value) return
  try { applyFrameFollowup(confirmAgentFrameFollowup(followupProposal.value, selected.value, frameFollowupContext(), focusHint.value)) }
  catch (value) { clearFrameFollowup(); error.value = message(value) }
}
function keepFrameFollowupHint() { clearFrameFollowup(); notice.value = '已保留原定位提示，问题与请求上限未变。' }
function upsertSession(value: AgentSession) {
  const index = sessions.value.findIndex(session => session.id === value.id)
  const latest = newestAgentSession(index < 0 ? undefined : sessions.value[index], value)
  if (index < 0) sessions.value.unshift(latest)
  else sessions.value[index] = latest
  sessions.value.sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at))
  history.value.observeDetail(latest)
  return latest
}
function scrollToLatest(force = false) {
  const sessionId = selectedId.value
  void nextTick(() => {
    if (!sessionId || stopped || props.active === false || selectedId.value !== sessionId || !messageList.value) return
    if (!force && !initialScrollPending && !followLatest) return
    const list = messageList.value, last = list.lastElementChild
    // Open a long answer at its question, rather than dropping the reader into its ending.
    const top = (force || initialScrollPending) && last
      ? list.scrollTop + last.getBoundingClientRect().top - list.getBoundingClientRect().top - 18
      : list.scrollHeight
    list.scrollTo({ top })
    followLatest = list.scrollHeight - list.scrollTop - list.clientHeight <= 64; initialScrollPending = false
  })
}
function messageScroll(event: Event) {
  if (props.active === false || stopped) return
  const list = event.currentTarget as HTMLOListElement
  followLatest = list.scrollHeight - list.scrollTop - list.clientHeight <= 64
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
  if (id === selectedId.value && !selectionTargetId.value) { closeHistory(); return }
  if (!canSwitch()) return
  closeHistory()
  scope.invalidate(); selectionTargetId.value = id; selectionError.value = ''
  await foregroundRefresh.restart()
}
function cancelHistoryRead() { historyVersion++; historyScope.invalidate(); historyLoading.value = false }
async function readHistory(navigation: AgentHistoryNavigation = 'refresh', parent?: AgentRefreshLease) {
  if (!foregroundReadable() || pending.value || navigation === 'refresh' && historyLoading.value) return
  cancelHistoryRead()
  const read = historyScope.begin(parent?.signal), version = historyVersion, owner = submissionOwner(), cursor = history.value.cursorFor(navigation)
  const current = () => read.current() && (!parent || parent.current()) && foregroundReadable() && !pending.value
    && version === historyVersion && owner.job_id === props.jobId && owner.video_id === props.videoId && owner.video_sha256 === props.videoHash
  historyLoading.value = true
  const probe = historyProbeRequested; historyProbeRequested = false
  try {
    const result = await readAgentSessionHistory({ pager: history.value, owner, cursor, probe,
      fetch: path => api<unknown>(path, { signal: read.signal }), current })
    if (!result || !current()) return
    if (result.kind === 'modern') history.value.acceptModern(result.page, navigation, cursor)
    else {
      sessions.value = mergeLegacyAgentSessions(sessions.value, result.details, owner)
      history.value.acceptLegacy(result.items, navigation)
    }
    historyError.value = ''
  } catch (value) { if (current()) historyError.value = '暂时无法核对对话历史，已有页和当前回答保留。'; throw value }
  finally { read.release(); if (version === historyVersion) historyLoading.value = false }
}
async function navigateHistory(navigation: Exclude<AgentHistoryNavigation, 'refresh'>) {
  if (!foregroundReadable() || pending.value || historyLoading.value) return
  if (history.value.navigateLocally(navigation)) return
  try { await readHistory(navigation) } catch { /* The history panel owns its fixed error. */ }
}
async function probeHistoryUpgrade() {
  if (!foregroundReadable() || pending.value || historyLoading.value) return
  historyProbeRequested = true
  try { await readHistory() } catch { /* Keep the existing compatibility page. */ }
}
function keepCurrentSession() {
  scope.invalidate(); detailVersion++; detailLoading.value = false; selectionTargetId.value = ''; selectionError.value = ''
  void foregroundRefresh.restart()
}
async function readSelectedSession(id: string, lease: AgentRefreshLease, parentCurrent: () => boolean) {
  const version = ++detailVersion
  detailLoading.value = true
  const current = () => parentCurrent() && version === detailVersion && (selectionTargetId.value || selectedId.value) === id
  try {
    const raw = await api<unknown>(`/api/agent-sessions/${id}`, { signal: lease.signal })
    if (!current()) return
    const value = parseSession(raw, id)
    upsertSession(value)
    if (selectionTargetId.value === id) {
      selectionVersion++; selectedId.value = id; selectionTargetId.value = ''; closePreview()
    }
    selectionError.value = ''
  } catch (value) { if (current()) selectionError.value = '所选会话的完整内容暂时无法核对，原回答与当前输入保留。'; throw value }
  finally { if (version === detailVersion) detailLoading.value = false }
}
async function refreshNow(lease: AgentRefreshLease) {
  const read = scope.begin(lease.signal), jobId = props.jobId, videoHash = props.videoHash, version = ++refreshVersion
  const current = () => foregroundReadable() && !pending.value && lease.current() && read.current() && version === refreshVersion && jobId === props.jobId && videoHash === props.videoHash
  if (!current()) { read.release(); return }
  refreshing.value = true
  try {
    await readHistory('refresh', lease)
    if (!current()) return
    scanSubmissions()
    if (!selectedId.value && !selectionTargetId.value && history.value.items[0] && !pending.value) selectionTargetId.value = history.value.items[0].id
    const id = selectionTargetId.value || selectedId.value
    if (id) await readSelectedSession(id, { signal: read.signal, current }, current)
    refreshError.value = ''
  } catch (value) {
    if (current()) refreshError.value = value instanceof ApiError && ['NETWORK_UNAVAILABLE', 'REQUEST_TIMEOUT'].includes(value.code)
      ? '暂时无法刷新对话，请稍后重试。' : '对话返回的来源或内容无法核对，请稍后刷新。'
    throw value
  }
  finally { read.release(); if (version === refreshVersion) { refreshing.value = false; loading.value = false } }
}
function submissionOwner() { return { job_id: props.jobId, video_id: props.videoId, video_sha256: props.videoHash } }
function scanSubmissions() {
  try {
    const own = [...ownUnacknowledged.values()]
    const scanned = filterAcknowledgedAgentSubmissions(scanAgentSubmissions(localStorage, submissionOwner(), sessions.value, legacyContext), [...acknowledgedSubmissions.values()])
    const result = retainAgentSubmissions(own, scanned, localStorage)
    submissionEntries.value = result.entries; submissionIssues.value = result.issues
    ownDurabilityLost.value = result.issues.some(issue => own.some(entry => issue.storage_key === agentSubmissionStorageKey(entry)))
  } catch { submissionIssues.value = [{ storage_key: '', code: 'AGENT_SUBMISSION_STORAGE_UNAVAILABLE' }]; ownDurabilityLost.value = ownUnacknowledged.size > 0 }
}
function cancelLegacyRead() { legacyVersion++; legacyScope.invalidate(); legacyLoading.value = false; legacyRun = null; legacyRequested = false }
function checkLegacySlots(): Promise<void> {
  if (!foregroundReadable() || pending.value) return Promise.resolve()
  if (legacyRun) { legacyRequested = true; return legacyRun.promise }
  const read = legacyScope.begin(), context = legacyContext, owner = submissionOwner(), version = ++legacyVersion
  const current = () => read.current() && context === legacyContext && version === legacyVersion && foregroundReadable() && !pending.value
    && owner.job_id === props.jobId && owner.video_id === props.videoId && owner.video_sha256 === props.videoHash
  const run = { promise: Promise.resolve() }; legacyRun = run
  run.promise = (async () => {
    try {
      const queue = context.unresolved(localStorage, owner)
      if (!queue.length) { scanSubmissions(); return }
      legacyLoading.value = true; legacyError.value = ''
      const worker = async () => {
        while (queue.length && current()) {
          const slot = queue.shift()!
          if (localStorage.getItem(slot.storage_key) !== slot.raw) continue
          try {
            const raw = await api<unknown>(`/api/agent-sessions/${slot.session_id}`, { signal: read.signal })
            if (!current()) return
            const session = context.classify(localStorage, slot, raw, current)
            if (session && current()) upsertSession(session)
            if (current()) scanSubmissions()
          } catch { if (current()) { context.forget(slot); legacyError.value = '部分旧提交的完整会话无法核对，原槽保留。请恢复连接后重试核对旧提交归属。' } }
        }
      }
      await Promise.all([worker(), worker()])
      if (current()) scanSubmissions()
    } catch (value) { if (current()) { legacyError.value = message(value); scanSubmissions() } }
    finally {
      read.release()
      if (legacyRun === run) {
        legacyRun = null; legacyLoading.value = false
        const again = legacyRequested; legacyRequested = false
        if (again && current()) void checkLegacySlots()
      }
    }
  })()
  return run.promise
}
function requireNoSubmission() {
  scanSubmissions()
  if (submissionIssues.value.length) throw new AgentSubmissionError(submissionIssues.value[0]!.code)
  if (submissionEntries.value.length) throw new AgentSubmissionError('AGENT_SUBMISSION_PENDING')
}
function rememberSubmission(entry: AgentSubmissionEntry) {
  ownUnacknowledged.set(agentSubmissionStorageKey(entry), entry)
  submissionEntries.value = retainAgentSubmissions(submissionEntries.value, { entries: [entry], issues: [] }).entries
}
function acknowledgeSubmission(entry: AgentSubmissionEntry) {
  removeAgentSubmission(localStorage, entry)
  acknowledgedSubmissions.set(agentSubmissionStorageKey(entry), JSON.parse(JSON.stringify(entry)) as AgentSubmissionEntry)
  ownUnacknowledged.delete(agentSubmissionStorageKey(entry))
  submissionEntries.value = submissionEntries.value.filter(value => agentSubmissionStorageKey(value) !== agentSubmissionStorageKey(entry))
  scanSubmissions()
}
function submissionCurrent(version: number, operation: number, owner: ReturnType<typeof submissionOwner>, sessionId?: string) {
  return valid(version, sessionId) && operation === submissionVersion && props.active !== false && !document.hidden
    && owner.job_id === props.jobId && owner.video_id === props.videoId && owner.video_sha256 === props.videoHash
}
function postSubmission(entry: AgentSubmissionEntry, posted: () => void = () => {}) {
  assertAgentSubmissionStored(localStorage, entry)
  posted()
  return api<unknown>(entry.kind === 'SESSION' ? `/api/jobs/${entry.job_id}/agent-sessions` : `/api/agent-sessions/${entry.session_id}/turns`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': entry.key }, body: entry.input,
  })
}
async function requestSession(version: number, operation: number): Promise<AgentSession | null> {
  const owner = submissionOwner()
  requireNoSubmission()
  let entry: AgentSubmissionEntry
  try { entry = createAgentSubmission(localStorage, owner, 'SESSION', '{}', () => crypto.randomUUID(), sessions.value, undefined, [...acknowledgedSubmissions.values()], legacyContext) }
  catch (value) { scanSubmissions(); throw value }
  rememberSubmission(entry)
  const raw = await postSubmission(entry)
  if (!submissionCurrent(version, operation, owner)) return null
  const result = parseAgentSubmissionResponse(raw, entry)
  if (result.kind !== 'SESSION') throw new AgentSubmissionError()
  adoptSubmissionResult(result)
  acknowledgeSubmission(entry)
  const value = sessions.value.find(session => session.id === result.session.id)!
  selectedId.value = value.id; closePreview()
  return value
}
async function newSession() {
  if (props.active === false || document.hidden || !canSwitch()) return
  if (selectionUnavailable.value) { error.value = '所选会话完整内容尚未核对，请先重试读取或保留当前会话。'; return }
  try { requireNoSubmission() } catch (value) { error.value = message(value); return }
  closeHistory(false)
  scope.invalidate(); const version = ++selectionVersion, operation = ++submissionVersion
  action.value = 'session'; error.value = ''; notice.value = ''
  try { if (await requestSession(version, operation)) notice.value = '新对话已创建，可以围绕这段视频提问。' }
  catch (value) { if (valid(version) && operation === submissionVersion) { scanSubmissions(); error.value = message(value) } }
  finally { if (valid(version) && operation === submissionVersion) action.value = '' }
}
async function send() {
  const input = question.value.trim(), budget = maxCalls.value, mode = visualMode.value, structured = evidenceMode.value
  const focus = focusHint.value ? { ...focusHint.value } : null
  if (pending.value || !input || props.active === false || document.hidden) return
  if (selectionUnavailable.value || !selected.value && !history.value.loaded) { error.value = '请先核对所选会话的完整内容，再发送问题。未新建其他对话，当前输入保留。'; return }
  if (followupProposal.value) { error.value = '请先选择“使用此时间”或“保留原提示”，再发送问题。'; return }
  try { requireNoSubmission() } catch (value) { error.value = message(value); return }
  if (structured && mode !== 'frames') { error.value = message(new ApiError('AGENT_EVIDENCE_MODE_REQUIRED')); return }
  if (focus && mode !== 'frames') { error.value = message(new AgentFocusError('AGENT_FOCUS_MODE_REQUIRED')); return }
  if (focus && !focusHintMatches(focus, focusOwner())) { error.value = message(new AgentFocusError('AGENT_FOCUS_SOURCE_CHANGED')); return }
  if (input.length > 2000 || !Number.isInteger(budget) || budget < 1 || budget > 24) { error.value = '问题最多 2000 字，模型请求上限须为 1～24 的整数。'; return }
  if (activeTurn.value) { error.value = '本对话还有一轮正在执行，请等待回答或取消本轮后再提问。'; return }
  scope.invalidate(); const version = ++selectionVersion, operation = ++submissionVersion, owner = submissionOwner()
  const draft = JSON.stringify({ question: question.value, budget: maxCalls.value, visual_mode: visualMode.value, focus: focusHint.value, evidence: evidenceMode.value })
  action.value = 'send'; error.value = ''; notice.value = ''
  let submitted: AgentSubmissionEntry | null = null, posted = false
  try {
    if (mode === 'frames') {
      const capability = await loadVisualCapability(() => submissionCurrent(version, operation, owner))
      if (!submissionCurrent(version, operation, owner)) return
      if (capability?.visual_mode !== 'frames') throw new ApiError('AGENT_VISUAL_UNAVAILABLE')
    }
    if (focus) {
      const supported = await loadFocusCapability(() => submissionCurrent(version, operation, owner))
      if (!submissionCurrent(version, operation, owner)) return
      if (!supported) throw new AgentFocusError('AGENT_FOCUS_UNAVAILABLE')
    }
    if (structured) {
      const supported = await loadEvidenceCapability(() => submissionCurrent(version, operation, owner))
      if (!submissionCurrent(version, operation, owner)) return
      if (!supported) throw new ApiError('AGENT_EVIDENCE_UNAVAILABLE')
    }
    const session = selected.value ?? await requestSession(version, operation)
    if (!session || !submissionCurrent(version, operation, owner, session.id)) return
    requireNoSubmission()
    let entry: AgentSubmissionEntry
    try { entry = createAgentSubmission(localStorage, owner, 'TURN', JSON.stringify({ question: input, max_text_calls: budget, ...(mode === 'frames' ? { visual_mode: 'frames' } : {}), ...(focus ? { focus_timestamp_ms: focus.timestamp_ms } : {}), ...(structured ? { evidence_mode: 'structured' } : {}) }), () => crypto.randomUUID(), sessions.value, session, [...acknowledgedSubmissions.values()], legacyContext) }
    catch (value) { scanSubmissions(); throw value }
    rememberSubmission(entry)
    submitted = entry
    const raw = await postSubmission(entry, () => { posted = true })
    if (!submissionCurrent(version, operation, owner, session.id)) return
    const result = parseAgentSubmissionResponse(raw, entry, session)
    if (result.kind !== 'TURN') throw new AgentSubmissionError()
    adoptSubmissionResult(result); acknowledgeSubmission(entry)
    if (draft === JSON.stringify({ question: question.value, budget: maxCalls.value, visual_mode: visualMode.value, focus: focusHint.value, evidence: evidenceMode.value })) { question.value = ''; focusHint.value = null; evidenceMode.value = false }
    followLatest = true; scrollToLatest(true)
    notice.value = '问题已保存，回答与引用会自动更新。'
  } catch (value) {
    const current = () => submissionCurrent(version, operation, owner)
    if (current()) {
      scanSubmissions(); error.value = message(value)
      if (submitted && posted) await inspectLimitAfterPost(submitted, value, current)
    }
  }
  finally { if (valid(version) && operation === submissionVersion) action.value = '' }
}
async function reconcileSubmission(entry: AgentSubmissionEntry) {
  if (pending.value || stopped || props.active === false || document.hidden) return
  scope.invalidate(); const version = ++selectionVersion, operation = ++submissionVersion, owner = submissionOwner()
  rememberSubmission(entry)
  const current = () => submissionCurrent(version, operation, owner)
  action.value = `recover:${entry.key}`; error.value = ''; notice.value = ''
  let posted = false
  try {
    const result = await resolveAgentSubmission(entry, {
      get: () => api<unknown>(entry.kind === 'SESSION' ? `/api/jobs/${entry.job_id}/agent-session-submissions/${entry.key}` : `/api/agent-sessions/${entry.session_id}/turn-submissions/${entry.key}`),
      readSession: () => api<unknown>(`/api/agent-sessions/${entry.session_id}`), current,
      beforePost: () => { if (!current()) throw new AgentSubmissionError('AGENT_SUBMISSION_PENDING'); restoreAgentSubmission(localStorage, entry) },
      post: () => postSubmission(entry, () => { posted = true }),
    })
    if (!result || !current()) return
    adoptAcknowledgedSubmission(entry, result)
    notice.value = entry.kind === 'SESSION' ? '原对话提交已核对，本页问题与预算保留，尚未自动提问。'
      : '原问题提交已核对；成功、失败或取消均保留原轮次。本页问题与预算仍保留，不会自动再提问。'
  } catch (value) { if (current()) { scanSubmissions(); error.value = message(value); if (posted) await inspectLimitAfterPost(entry, value, current) } }
  finally { if (valid(version) && operation === submissionVersion) action.value = '' }
}
function adoptSubmissionResult(result: AgentSubmissionResult) {
  const known = sessions.value.find(value => value.id === result.session.id)
  upsertSession(mergeAgentSubmissionResult(known, result))
  if (!selectedId.value) selectedId.value = result.session.id
}
function adoptAcknowledgedSubmission(entry: AgentSubmissionEntry, result: AgentSubmissionResult) {
  const draft = { session_id: selectedId.value, question: question.value, budget: maxCalls.value, visual_mode: visualMode.value, evidence_mode: evidenceMode.value ? 'structured' as const : 'none' as const, ...(focusHint.value ? { focus_timestamp_ms: focusHint.value.timestamp_ms } : {}) }
  const matched = agentDraftMatchesSubmission(entry, draft), nextQuestion = agentDraftAfterSubmission(entry, draft)
  adoptSubmissionResult(result); acknowledgeSubmission(entry); question.value = nextQuestion
  if (matched) { focusHint.value = null; evidenceMode.value = false }
}
function inspectLimit(entry: AgentSubmissionEntry, current: () => boolean) {
  return inspectAgentSubmissionLimit(entry, {
    get: () => api<unknown>(`/api/agent-sessions/${entry.session_id}/turn-submissions/${entry.key}`),
    readSession: () => api<unknown>(`/api/agent-sessions/${entry.session_id}`), current,
  })
}
async function inspectLimitAfterPost(entry: AgentSubmissionEntry, value: unknown, current: () => boolean) {
  if (entry.kind !== 'TURN' || !(value instanceof ApiError) || value.status !== 409 || value.code !== 'AGENT_SESSION_LIMIT') return
  try {
    const result = await inspectLimit(entry, current)
    if (!result || !current()) return
    if (result.kind === 'completed') { adoptAcknowledgedSubmission(entry, result.result); notice.value = '原轮次已经找到并核对，未建立新请求。' }
    else { fullSessionConflicts.value = { ...fullSessionConflicts.value, [agentSubmissionStorageKey(entry)]: true }; error.value = ''; notice.value = '原问题未落库，原会话已达到 100 轮。回执仍保留，可下载后明确解除本条阻塞。' }
  } catch (failure) { if (current()) { scanSubmissions(); error.value = message(failure) } }
}
async function releaseFullSession(entry: AgentSubmissionEntry) {
  if (pending.value || !fullSessionConflicts.value[agentSubmissionStorageKey(entry)] || props.active === false || document.hidden) return
  const version = ++selectionVersion, operation = ++submissionVersion, owner = submissionOwner()
  const current = () => submissionCurrent(version, operation, owner)
  action.value = `release:${entry.key}`; error.value = ''; notice.value = ''
  try {
    const result = await inspectLimit(entry, current)
    if (!result || !current()) return
    if (result.kind === 'completed') { adoptAcknowledgedSubmission(entry, result.result); notice.value = '原轮次已经出现并核对，未按未落库删除。' }
    else { upsertSession(result.session); acknowledgeSubmission(entry); notice.value = '已明确解除本条未落库提交，原草稿保留；尚未创建新对话或发送问题。' }
    delete fullSessionConflicts.value[agentSubmissionStorageKey(entry)]
  } catch (value) { if (current()) error.value = message(value) }
  finally { if (valid(version) && operation === submissionVersion) action.value = '' }
}
function downloadSubmission(entry: AgentSubmissionEntry) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(entry, null, 2)], { type: 'application/json' }))
  const link = document.createElement('a'); link.href = url; link.download = `agent-${entry.kind.toLowerCase()}-${entry.key}.json`; link.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
function originalQuestion(entry: AgentSubmissionEntry): string { return entry.kind === 'TURN' ? JSON.parse(entry.input).question : '新建视频对话' }
function originalBudget(entry: AgentSubmissionEntry): number | null { return entry.kind === 'TURN' ? JSON.parse(entry.input).max_text_calls : null }
function originalVisualMode(entry: AgentSubmissionEntry): string { return entry.kind === 'TURN' && JSON.parse(entry.input).visual_mode === 'frames' ? JSON.parse(entry.input).evidence_mode === 'structured' ? '结合画面 · 分项证据回答' : '结合画面' : '文本模式' }
function originalFocus(entry: AgentSubmissionEntry): number | undefined { return entry.kind === 'TURN' ? JSON.parse(entry.input).focus_timestamp_ms : undefined }
function submissionStorageChanged(event: StorageEvent) {
  if (event.key === null || event.key.startsWith(`livecut-agent-submission-v1:${props.jobId}:`) || event.key === `livecut-agent-session-${props.jobId}`
    || event.key.startsWith('livecut-agent-turn-')) { scanSubmissions(); void checkLegacySlots() }
}
function invalidateSubmission() {
  submissionVersion++
  if (action.value === 'send' || action.value === 'session' || action.value.startsWith('recover:') || action.value.startsWith('release:')) action.value = ''
}
function submissionVisibilityChanged() { pageVisible.value = !document.hidden; if (document.hidden) { invalidateSubmission(); pauseReference() } else { scanSubmissions(); historyProbeRequested = true; void checkLegacySlots() }; touchFocusPlayer(); syncRefreshLifecycle() }
async function cancel(turn: AgentTurn) {
  const session = selected.value
  if (pending.value || selectionUnavailable.value || !session || !agentTurnActive(turn)) return
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
function discard() { if (!pending.value) { question.value = ''; focusHint.value = null; evidenceMode.value = false; error.value = ''; notice.value = '' } }
function suggest(value: string) {
  if (selectionUnavailable.value) { error.value = '请先核对所选会话的完整内容。'; return }
  if (!canPrefillClipInstruction(value, !!props.clipTargetBusy, pending.value)) { error.value = '剪辑区域有未提交内容或正在提交，请先处理后再送入要求。'; return }
  emit('clipInstruction', value)
}
function chooseAnchor(turn: AgentTurn, id: string, event: Event) {
  if (pending.value || selectionUnavailable.value || props.active === false || turn.status !== 'SUCCEEDED' || turn.result?.answer_scope !== 'VIDEO' || !turn.result.citations.some(citation => citation.utterance_id === id)) return
  const previous = anchorChoices.value[turn.id] ?? []
  anchorChoices.value = { ...anchorChoices.value, [turn.id]: (event.target as HTMLInputElement).checked ? [...new Set([...previous, id])] : previous.filter(value => value !== id) }
}
async function suggestAnchors(turn: AgentTurn) {
  const owner = selected.value, ids = anchorChoices.value[turn.id] ?? [], version = selectionVersion, videoHash = props.videoHash
  if (!owner || !ids.length || pending.value || selectionUnavailable.value || props.active === false || props.clipTargetBusy) return
  anchorPreparing.value = true; error.value = ''
  try {
    const selection = await selectAnswerClip(owner, turn, ids)
    if (stopped || version !== selectionVersion || selectedId.value !== owner.id || !anchorEditorActive() || props.clipTargetBusy || props.videoHash !== videoHash) return
    emit('clipAnchor', selection)
  } catch { error.value = '所选原句或回答来源无法核对。请使用成功视频回答里的引用，最多24句、合计12000字。' }
  finally { anchorPreparing.value = false }
}
function anchorEditorActive() { return props.active !== false }
async function prepareEvidencePreview(turn: AgentTurn, start: number, end?: number) {
  if (stopped || selectionUnavailable.value || props.active === false || document.hidden || !selected.value?.turns.some(value => value.id === turn.id && value.status === 'SUCCEEDED')
    || !Number.isSafeInteger(start) || start < 0 || start >= selected.value.source.duration_ms
    || end !== undefined && (!Number.isSafeInteger(end) || end <= start || end > selected.value.source.duration_ms)) return
  pauseReference()
  conversationFocused.value = false
  referenceExpanded.value = true
  referenceTurnId.value = turn.id; playbackError.value = ''; const version = previewVersion
  const target = { version, sessionId: selectedId.value, turnId: turn.id, videoUrl: props.videoUrl, sourceHash: props.videoHash, start, ...(end !== undefined ? { end } : {}) }
  preparedEvidence = target
  evidenceTimer = window.setTimeout(() => { if (preparedEvidence === target && evidenceCurrent(target)) { pauseReference(); playbackError.value = '原视频尚未准备好，请稍后重试定位。' } }, 15000)
  await nextTick()
  if (!evidenceCurrent(target)) return
  referenceEvidence.value?.scrollTo({ top: 0 })
  if (compactReference.value) referenceDock.value?.scrollIntoView({ block: 'nearest' })
  if (sourcePlayer.value && sourcePlayer.value.readyState >= 1) seekCitation()
}
function preview(value: AgentCitation, turn = referenceAnswer.value) {
  if (!turn) return
  citation.value = value; frameLocation.value = null
  return prepareEvidencePreview(turn, value.start_ms, value.end_ms)
}
function locateFrame(turn: AgentTurn, timestamp: number) {
  if (!turn.result?.visual_evidence?.some(frame => frame.timestamp_ms === timestamp)) return
  citation.value = null; frameLocation.value = { time: timestamp, title: `第 ${turn.sequence} 轮画面` }
  return prepareEvidencePreview(turn, timestamp)
}
function previewVisualRange(turn: AgentTurn, range: { start_ms: number; end_ms: number }) {
  if (!turn.result?.tool_calls.some(call => call.tool_name === 'inspect_clip' && call.arguments.start_ms === range.start_ms && call.arguments.end_ms === range.end_ms)) return
  citation.value = null; frameLocation.value = { time: range.start_ms, title: `第 ${turn.sequence} 轮请求区间回看` }
  return prepareEvidencePreview(turn, range.start_ms, range.end_ms)
}
function mediaActive() { return !stopped && props.active !== false && !document.hidden && !sessionListOpen.value && referenceVisible.value }
function evidenceCurrent(value: NonNullable<typeof preparedEvidence>) { return mediaActive() && value.version === previewVersion && value.sessionId === selectedId.value
  && value.turnId === referenceTurnId.value && value.videoUrl === props.videoUrl && value.sourceHash === props.videoHash }
function seekCitation() {
  const player = sourcePlayer.value, value = preparedEvidence
  if (!player || !value || !evidenceCurrent(value)) return
  preparedEvidence = null; window.clearTimeout(evidenceTimer); evidenceTimer = undefined
  touchFocusPlayer()
  try {
    if (value.end === undefined) { player.pause(); player.currentTime = value.start / 1000 }
    else void evidenceSequence.start(player, { id: `${value.turnId}:${value.version}`, parts: [{ start_ms: value.start, end_ms: value.end }] })
      .then(result => { if (result === 'failed' && evidenceCurrent(value)) playbackError.value = '区间暂时无法播放，请先确认原视频可播放后重试。' })
  }
  catch { if (evidenceCurrent(value)) playbackError.value = '暂时无法定位，请检查原视频是否可播放。' }
}
function pauseReference() { previewVersion++; preparedEvidence = null; window.clearTimeout(evidenceTimer); evidenceTimer = undefined; evidenceSequence.cancel(); sourcePlayer.value?.pause(); touchFocusPlayer() }
function toggleReference() { pauseReference(); referenceExpanded.value = !referenceExpanded.value }
function toggleConversationFocus() { pauseReference(); conversationFocused.value = !conversationFocused.value }
function guardPlayback(event: Event) { if (!mediaActive()) (event.currentTarget as HTMLVideoElement).pause() }
function closePreview() { pauseReference(); citation.value = null; frameLocation.value = null; referenceTurnId.value = ''; playbackError.value = '' }
function date(value: string) { return new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }
function unload(event: BeforeUnloadEvent) { scanSubmissions(); if (dirty.value || pending.value || submissionBlocked.value) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => {
  mounted = true
  scanSubmissions(); window.addEventListener('storage', submissionStorageChanged); window.addEventListener('focus', windowFocused)
  document.addEventListener('visibilitychange', submissionVisibilityChanged)
  window.addEventListener('beforeunload', unload); syncRefreshLifecycle(); void checkLegacySlots()
  referenceObserver = new ResizeObserver(entries => {
    const width = entries[0]?.contentRect.width
    if (stopped || !width) return
    const compact = width <= 780
    if (compact && !compactReference.value) referenceExpanded.value = false
    compactReference.value = compact
  })
  if (panel.value) referenceObserver.observe(panel.value)
})
watch(selectedId, id => { initialScrollPending = !!id; followLatest = true; if (id) scrollToLatest(true) }, { flush: 'post' })
watch(selectedId, () => { noteTurnId.value = '' }, { flush: 'sync' })
watch(() => referenceAnswer.value?.id, () => { allEvidenceVisible.value = false })
watch(() => {
  const last = selected.value?.turns.at(-1)
  return `${selectedId.value}:${last?.id ?? ''}:${last?.state_version ?? 0}`
}, () => { if (initialScrollPending || followLatest) scrollToLatest() }, { flush: 'post' })
watch(() => props.active, value => {
  if (value === false) { invalidateSubmission(); closeHistory(false); pauseReference() }
  else { scanSubmissions(); historyProbeRequested = true; void checkLegacySlots(); if (initialScrollPending) scrollToLatest(true) }
}, { flush: 'sync' })
watch([() => props.active, pageVisible, pending], syncRefreshLifecycle, { flush: 'sync' })
watch(pending, (value, previous) => { if (!value && previous) void checkLegacySlots() }, { flush: 'sync' })
watch(hasActiveRefreshWork, () => foregroundRefresh.reschedule(), { flush: 'sync' })
watch(() => JSON.stringify([selectedId.value, props.jobId, props.videoId, props.videoHash, props.videoUrl,
  selected.value?.source.transcript_hash, selected.value?.source.duration_ms, props.active, pageVisible.value, sessionListOpen.value,
  visualMode.value, focusHint.value, pending.value, selectionUnavailable.value, visualCapability.value?.visual_mode, focusSupported.value, capabilityLoading.value, focusLoading.value]),
  clearFrameFollowup, { flush: 'sync' })
watch(() => `${props.jobId}:${props.videoId}:${props.videoHash}`, () => {
  refreshVersion++; scope.invalidate(); refreshing.value = false; cancelHistoryRead(); cancelLegacyRead(); detailVersion++; detailLoading.value = false
  legacyContext.dispose(); legacyContext = new AgentLegacySubmissionContext(submissionOwner()); history.value = new AgentSessionHistoryPager(); historyProbeRequested = true
  selectionTargetId.value = ''; selectedId.value = ''; selectionError.value = ''; historyError.value = ''; legacyError.value = ''
  sessions.value = []
  evidenceCapabilityVersion++; evidenceSupported.value = false; evidenceUnsupported.value = false; evidenceExecutor.value = null; evidenceLoading.value = false; evidenceError.value = ''
  if (mounted) void foregroundRefresh.restart(true)
  void checkLegacySlots()
}, { flush: 'sync' })
watch(referenceVisible, value => { if (!value) pauseReference() }, { flush: 'sync' })
watch(() => props.videoUrl, pauseReference, { flush: 'sync' })
watch(() => props.videoHash, closePreview, { flush: 'sync' })
watch(sessionListOpen, value => { if (value) pauseReference() }, { flush: 'sync' })
onBeforeUnmount(() => { scanSubmissions(); stopped = true; mounted = false; clearFrameFollowup(); refreshVersion++; capabilityVersion++; focusCapabilityVersion++; evidenceCapabilityVersion++; submissionVersion++; selectionVersion++; pauseReference(); referenceObserver?.disconnect(); foregroundRefresh.dispose(); scope.dispose(); cancelHistoryRead(); historyScope.dispose(); cancelLegacyRead(); legacyScope.dispose(); legacyContext.dispose(); window.removeEventListener('beforeunload', unload); window.removeEventListener('storage', submissionStorageChanged); window.removeEventListener('focus', windowFocused); document.removeEventListener('visibilitychange', submissionVisibilityChanged) })
</script>

<template>
  <section ref="panel" class="panel agent-panel" aria-label="视频对话助手" @keydown.esc="closeHistory()">
    <div v-if="sessionListOpen" class="agent-history-backdrop" aria-hidden="true" @click="closeHistory()"></div>
    <p v-if="error" class="message error" role="alert">{{ error }}</p><p v-if="notice" class="message notice" role="status">{{ notice }}</p>
    <p v-if="refreshError" class="agent-refresh-error" role="status">{{ refreshError }} 已有记录与输入仍保留。</p>
    <section v-if="selectionTargetId || selectionError" class="agent-selection-status" aria-label="所选会话读取状态">
      <p role="status">{{ detailLoading ? '正在读取并核对所选会话的完整内容…' : selectionError || '所选会话完整内容尚未读取，当前显示的原回答与输入仍保留。' }}</p>
      <button class="text-button" :disabled="pending || refreshing" @click="foregroundRefresh.restart()">重试读取所选会话</button><button v-if="selected" class="text-button" :disabled="pending" @click="keepCurrentSession">保留当前会话</button>
    </section>
    <section v-if="submissionBlocked" class="agent-submission-recovery" aria-label="待核对的原提交">
      <div class="agent-section-heading"><h3>原提交结果待核对 · {{ submissionEntries.length }} 条</h3><button class="text-button" :disabled="pending" @click="scanSubmissions">刷新本机回执</button></div>
      <p v-if="legacyError" class="agent-submission-error" role="status">{{ legacyError }}</p><button class="text-button" :disabled="pending || legacyLoading || active === false" @click="checkLegacySlots">{{ legacyLoading ? '正在核对旧提交归属…' : '核对旧提交归属' }}</button>
      <p>可继续编辑本页草稿；新对话和新提问暂时停止。逐项核对会使用原标识与原内容，不会自动再发其他问题。</p>
      <p v-for="issue in submissionIssues" :key="issue.storage_key + issue.code" class="agent-submission-error" role="alert">{{ message(new AgentSubmissionError(issue.code)) }}</p>
      <article v-for="entry in submissionEntries" :key="agentSubmissionStorageKey(entry)" class="agent-submission-entry">
        <strong>{{ entry.kind === 'SESSION' ? '新建对话' : '提交问题' }} · #{{ entry.key.slice(0, 8) }}</strong><p class="agent-original-question">{{ originalQuestion(entry) }}</p><p v-if="entry.kind === 'TURN'">原预算上限 {{ originalBudget(entry) }} 次 · {{ originalVisualMode(entry) }} · 原对话 #{{ entry.session_id?.slice(0, 8) }}<template v-if="originalFocus(entry) !== undefined"> · 原定位提示 {{ topicTime(originalFocus(entry)!) }}</template></p>
        <div class="agent-submission-actions"><button class="secondary" :disabled="pending || active === false" @click="reconcileSubmission(entry)">{{ action === `recover:${entry.key}` ? '正在核对…' : '核对原提交' }}</button><button class="text-button" @click="downloadSubmission(entry)">下载原回执</button></div>
        <div v-if="fullSessionConflicts[agentSubmissionStorageKey(entry)]" class="agent-submission-limit"><p>已核对原标识仍不存在，原会话恰有 100 轮。解除前将再次只读核对，不会自动新建对话或提问。</p><button class="secondary" :disabled="pending || active === false" @click="releaseFullSession(entry)">解除本条未落库提交</button></div>
      </article>
    </section>
    <div class="agent-layout" :class="{ 'conversation-focused': conversationFocused && !compactReference }">
      <aside v-show="sessionListOpen" ref="historyPanel" role="dialog" aria-modal="true" @keydown="historyKey" :id="`agent-history-${jobId}`" class="agent-sessions" aria-label="视频对话列表">
        <div class="agent-section-heading"><h3>对话记录 · 本页 {{ history.items.length }} 条</h3><div class="agent-history-actions"><button class="text-button" @click="closeHistory()">关闭对话记录</button><button class="text-button" :disabled="pending || historyLoading" @click="refresh">刷新</button><button class="text-button" :disabled="pending || historyLoading" @click="probeHistoryUpgrade">检查升级</button></div></div>
        <p v-if="historyError" class="agent-submission-error" role="status">{{ historyError }}</p><p v-if="historyLoading" class="help" role="status">正在读取对话历史…</p><p v-else-if="history.loaded && !history.items.length" class="help">本页没有对话记录。</p>
        <p v-if="history.view === 'legacy'" class="help">兼容旧服务，仅展示最近 100 个会话；此处翻页整理已有完整列表。</p><p v-if="history.upgradeAvailable" class="help">已检测到轻量历史接口；点击“回到最新”采用新分页，当前页保持不变。</p><p v-if="history.unsupportedOldPage" class="help">当前服务不能继续读取此分页位置，请明确回到最新。</p>
        <p v-if="selected && !history.items.some(item => item.id === selectedId)" class="help">当前会话 #{{ selected.id.slice(0,8) }} 不在本页；回答与输入保持不变。</p>
        <button v-for="session in history.items" :key="session.id" class="agent-session" :class="{ selected: selectedId === session.id }" :aria-pressed="selectedId === session.id" :disabled="pending || dirty" @click="selectSession(session.id)"><strong>{{ session.first_question ?? '新对话' }}</strong><small>{{ date(session.created_at) }} · {{ session.turn_count }} 轮<span v-if="session.active_turn_count"> · 进行中 {{ session.active_turn_count }}</span></small></button>
        <nav class="agent-history-pagination" aria-label="视频对话历史分页"><span>第 {{ history.pageNumber }} 页</span><button class="text-button" :disabled="pending || historyLoading || !history.canNewer" @click="navigateHistory('newer')">上一页</button><button class="text-button" :disabled="pending || historyLoading || !history.canOlder" @click="navigateHistory('older')">下一页</button><button class="text-button" :disabled="pending || historyLoading" @click="navigateHistory('latest')">回到最新</button></nav>
      </aside>
      <aside v-show="compactReference || !conversationFocused" ref="referenceDock" class="agent-reference" :inert="sessionListOpen ? true : undefined" aria-label="视频参考">
        <div class="agent-reference-heading"><h3><span class="agent-video-icon" aria-hidden="true">▶</span> 原视频</h3><span class="agent-reference-caption">点击原句，定位回看</span><button v-if="compactReference && referenceVisible" type="button" class="text-button agent-focus-compact" aria-label="附加当前画面位置" title="暂停并固定当前播放位置，仅作为查找提示，尚未取得截图。需结合画面且定位已完成。" :disabled="!canAttachFocus" @click="attachFocus">附加位置</button><button class="text-button agent-reference-toggle" :aria-expanded="referenceVisible" :aria-controls="`agent-reference-${jobId}`" @click="toggleReference">{{ referenceVisible ? '收起视频' : '展开视频' }}</button></div>
        <div v-show="referenceVisible" :id="`agent-reference-${jobId}`" class="agent-reference-body">
          <div class="agent-reference-video"><video :key="`${videoId}:${videoHash}`" ref="sourcePlayer" :src="videoUrl" controls preload="metadata" playsinline @loadedmetadata="touchFocusPlayer(); seekCitation()" @loadeddata="touchFocusPlayer" @canplay="touchFocusPlayer" @durationchange="touchFocusPlayer" @timeupdate="touchFocusPlayer(); evidenceSequence.timeUpdate($event.currentTarget as HTMLVideoElement)" @playing="evidenceSequence.timeUpdate($event.currentTarget as HTMLVideoElement)" @seeking="touchFocusPlayer(); evidenceSequence.seeking($event.currentTarget as HTMLVideoElement)" @seeked="touchFocusPlayer(); evidenceSequence.seeked($event.currentTarget as HTMLVideoElement)" @pause="touchFocusPlayer(); evidenceSequence.pause($event.currentTarget as HTMLVideoElement)" @ended="evidenceSequence.ended($event.currentTarget as HTMLVideoElement)" @ratechange="evidenceSequence.rateChange($event.currentTarget as HTMLVideoElement)" @play="touchFocusPlayer(); guardPlayback($event)" @error="pauseReference()"></video></div>
          <p v-if="playbackError" class="agent-reference-help" role="status">{{ playbackError }}</p>
          <div v-if="!compactReference" class="agent-focus-controls"><button type="button" class="secondary" :disabled="!canAttachFocus" @click="attachFocus">附加当前画面位置</button><small>暂停并固定当前播放位置，仅作为查找提示，不代表已经取得截图。需结合画面、视频可见且定位已完成。</small><small v-if="focusError" role="status">{{ focusError }}</small></div>
          <p v-if="frameLocation" class="agent-reference-help" role="status">{{ frameLocation.title }} · 原视频 {{ topicTime(frameLocation.time) }}。单帧定位保持暂停，区间回看由你明确触发。</p>
          <div class="agent-evidence-heading"><h4>原句引用 <span v-if="referenceAnswer">第 {{ referenceAnswer.sequence }} 轮 · </span><span>{{ recentCitations.length }}</span></h4><button v-if="recentCitations.length > 3" class="text-button" :aria-expanded="allEvidenceVisible" :aria-controls="`agent-evidence-${jobId}`" @click="allEvidenceVisible = !allEvidenceVisible">{{ allEvidenceVisible ? '收起列表' : `查看全部 ${recentCitations.length} 条` }}</button></div>
          <div :id="`agent-evidence-${jobId}`" ref="referenceEvidence" class="agent-reference-evidence"><div v-if="citation" class="agent-current-citation"><strong>正在回看 {{ topicTime(citation.start_ms) }} → {{ topicTime(citation.end_ms) }}</strong><button class="text-button" @click="closePreview">关闭定位</button><p>{{ citation.text }}</p></div><p v-if="!recentCitations.length" class="agent-evidence-empty">{{ referenceAnswer ? '本轮未引用原句；可在回答下查看画面依据或回看原视频。' : '提问后，这里会列出回答引用的原句。' }}</p><button v-for="(source, index) in visibleCitations" :key="source.utterance_id" class="agent-evidence-item" :class="{ selected: citation?.utterance_id === source.utterance_id }" :title="source.text" @click="preview(source)"><span class="agent-evidence-number" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span><span class="agent-evidence-copy"><span class="agent-evidence-time">{{ topicTime(source.start_ms) }} → {{ topicTime(source.end_ms) }}</span><span class="agent-evidence-text">{{ source.text }}</span></span><span class="agent-evidence-play" aria-hidden="true">▶</span></button></div>
        </div>
      </aside>
      <div class="agent-conversation" :inert="sessionListOpen ? true : undefined">
        <div class="agent-toolbar"><h2><span class="agent-ai-icon" aria-hidden="true">✦</span> 视频助手</h2><div class="agent-toolbar-actions"><button class="secondary agent-focus-toggle" :aria-pressed="conversationFocused" @click="toggleConversationFocus">{{ conversationFocused ? '并排回看' : '专注对话' }}</button><button ref="historyToggle" class="secondary" :aria-expanded="sessionListOpen" :aria-controls="`agent-history-${jobId}`" @click="toggleHistory">对话记录 <span>本页 {{ history.items.length }}</span></button><button class="secondary" :disabled="pending || selectionUnavailable || dirty || submissionBlocked" @click="newSession">＋ 新对话</button></div></div>
        <div v-if="!selected?.turns.length" class="agent-welcome"><template v-if="selectionUnavailable || !history.loaded"><h3>正在核对会话内容</h3><p>完整会话读取并通过来源核对后，才能围绕本轮提问与使用证据。</p></template><template v-else><h3>从视频里找答案</h3><p>可以先概括内容，也可以沿着已有回答继续追问。证据不足时会明确说明。</p></template></div>
        <ol v-else ref="messageList" class="agent-turns" aria-label="对话记录" @scroll.passive="messageScroll"><li v-for="turn in selected.turns" :key="turn.id" class="agent-turn"><div class="agent-question"><span>你的问题 · 第 {{ turn.sequence }} 轮</span><p>{{ turn.question }}</p><small v-if="turn.focus_timestamp_ms !== undefined">附加定位提示 {{ topicTime(turn.focus_timestamp_ms) }}，不是已验证画面证据。</small></div><div class="agent-answer"><div class="agent-answer-heading"><strong>视频助手</strong><span class="agent-status">{{ names[turn.status] }}</span><button v-if="agentTurnActive(turn)" class="text-button" :disabled="pending" @click="cancel(turn)">{{ action === 'cancel' ? '正在取消…' : '取消本轮' }}</button></div><TextRequestProgress :progress="turn.stage.text_progress" :terminal="!agentTurnActive(turn)" /><FailureDiagnostic :diagnostic="turn.stage.diagnostic" :error-code="turn.stage.error_code" :attempt="turn.stage.attempt" />
          <template v-if="turn.result"><span class="agent-scope" :class="turn.result.answer_scope.toLowerCase()">{{ scopes[turn.result.answer_scope] }}</span><AgentEvidenceAnswer v-if="turn.evidence_mode === 'structured'" :turn="turn" :active="active !== false && !sessionListOpen && !selectionUnavailable" @citation="preview($event, turn)" @locate="locateFrame(turn, $event)" /><AgentAnswer v-else class="agent-answer-text" :text="turn.result.answer" /><p v-if="turn.result.answer_scope === 'GENERAL'" class="help">这部分是通用知识补充，不能当作视频中已经说过的话。</p><p v-else-if="turn.result.answer_scope === 'INSUFFICIENT'" class="help">当前视频证据不足，可换个说法，或补充想找的具体内容。</p>
            <AgentVisualEvidence :key="turn.id" :turn="turn" :active="active !== false && !sessionListOpen && !selectionUnavailable" :followup-unavailable="frameFollowupUnavailable" @locate="locateFrame(turn, $event)" @preview="previewVisualRange(turn, $event)" @followup="continueFromFrame(turn.id, $event)" />
            <AgentNotePanel v-if="selected" :key="`note-${turn.id}`" :session="selected" :turn="turn" :expanded="noteTurnId === turn.id" :active="active !== false && !sessionListOpen && !selectionUnavailable" @toggle="noteTurnId = noteTurnId === turn.id ? '' : turn.id" @close="noteTurnId === turn.id && (noteTurnId = '')" />
            <details v-if="turn.result.citations.length" class="agent-citations"><summary>视频原句依据 · {{ turn.result.citations.length }} 条</summary><p v-if="turn.result.answer_scope === 'VIDEO'" class="agent-anchor-help">从同一回答中选择要保留的原句，剪成一个片段；可以补充前后解释，所选句会完整保留。</p><div v-for="(source, sourceIndex) in turn.result.citations" :key="source.utterance_id" class="agent-citation-row"><label v-if="turn.result.answer_scope === 'VIDEO' && turn.status === 'SUCCEEDED'" class="agent-anchor-choice"><input type="checkbox" :checked="(anchorChoices[turn.id] ?? []).includes(source.utterance_id)" :disabled="pending || clipTargetBusy || active === false" :aria-label="`选择剪辑原句 第${sourceIndex + 1}条 ${topicTime(source.start_ms)}至${topicTime(source.end_ms)}`" @change="chooseAnchor(turn, source.utterance_id, $event)"><span>选择此原句</span></label><button class="agent-citation" :class="{ selected: citation?.utterance_id === source.utterance_id }" @click="preview(source, turn)"><span>回看 {{ topicTime(source.start_ms) }} → {{ topicTime(source.end_ms) }}</span><p>{{ source.text }}</p></button></div><div v-if="turn.result.answer_scope === 'VIDEO'" class="agent-anchor-actions"><span>已选 {{ (anchorChoices[turn.id] ?? []).length }} 条 · 来自第 {{ turn.sequence }} 轮视频回答</span><button class="secondary" :disabled="pending || clipTargetBusy || active === false || !(anchorChoices[turn.id] ?? []).length" @click="suggestAnchors(turn)">按所选原句剪辑</button><small>仅填入剪辑区，不创建请求；初始候选只核对所选句及相邻上下文，仍需人工回看。</small></div></details>
            <details v-if="turn.result.tool_calls.length" class="agent-tools"><summary>查找过程 · {{ turn.result.tool_calls.length }} 步</summary><ol><li v-for="call in turn.result.tool_calls" :key="call.step"><strong>{{ call.step }}. {{ toolNames[call.tool_name] }}<template v-if="call.origin"> · {{ call.origin === 'USER_FOCUS' ? '用户位置触发' : '模型选择' }}</template></strong><p v-if="call.tool_name === 'search_transcript'">查询：{{ call.arguments.query }}</p><p v-else-if="call.tool_name === 'read_context'">补读前 {{ call.arguments.before }} 句、后 {{ call.arguments.after }} 句</p><p v-else-if="call.tool_name === 'list_topics'">查看第 {{ Number(call.arguments.offset) + 1 }} 项起的话题，最多 {{ call.arguments.limit }} 项</p><p v-else-if="call.tool_name === 'inspect_frames'">请求时间：{{ (call.arguments.timestamps_ms as number[]).map(topicTime).join('、') }}</p><p v-else>短片抽样：{{ topicTime(Number(call.arguments.start_ms)) }} → {{ topicTime(Number(call.arguments.end_ms)) }} · 2fps、无原始音轨</p><small>返回 {{ call.result_utterance_ids.length }} 条原句依据<template v-if="call.result_frame_ids"> · {{ call.result_frame_ids.length }} 帧画面</template></small></li></ol></details>
            <div v-if="turn.result.suggested_clip_instruction" class="agent-clip-suggestion"><p>{{ turn.result.suggested_clip_instruction }}</p><button class="secondary" :disabled="pending || clipTargetBusy" @click="suggest(turn.result.suggested_clip_instruction)">送到剪辑要求</button><small v-if="clipTargetBusy">请先保存或放弃剪辑区域的未提交内容。</small><small v-else>仅填入要求，你仍需在剪辑区域点击查找、核对并确认。</small></div>
          </template><p v-else class="help">{{ turn.status === 'CANCELLED' ? '本轮已取消，已有对话和视频结果仍保留。' : turn.status === 'FAILED' ? '本轮未生成可用回答。请查看原因，原转写与此前回答仍保留。' : turn.status === 'QUEUED' ? '问题已保存，正在等待执行服务。' : '正在结合视频内容查找证据并回答，进度会自动更新。' }}</p>
        </div></li></ol>
        <div class="agent-reading-actions"><span>当前视频 · {{ selected?.turns.length ?? 0 }} 轮对话</span><button class="text-button" :disabled="pending || refreshing" @click="refresh">刷新对话</button><button v-if="selected?.turns.length" class="text-button" @click="scrollToLatest(true)">定位最新一轮 ↓</button></div>
        <form class="agent-compose" @submit.prevent="send">
          <div class="agent-visual-option"><label><input v-model="visualMode" type="checkbox" true-value="frames" false-value="none" :disabled="pending || visualMode === 'none' && visualCapability?.visual_mode !== 'frames'">结合画面</label><span v-if="visualMode === 'frames'">本轮可按需抽帧；所有模型请求、重试及修复共用下方次数上限。</span><span v-else>默认只用视频转写。</span><button type="button" class="text-button" :disabled="capabilityLoading || focusLoading || evidenceLoading || pending" @click="foregroundRefresh.checkCapabilities()">{{ capabilityLoading ? '正在检查…' : '检查画面能力' }}</button><small v-if="capabilityError">{{ capabilityError }}</small><small v-else-if="visualCapability?.visual_mode === null">当前部署未配置视觉模型。</small><small v-else-if="visualCapability?.visual_mode === 'frames'">配置模型 {{ visualCapability.visual_model_id }}；配置可用不代表额度或识别效果已验证。</small></div>
          <div v-if="visualMode === 'frames' || evidenceMode" class="agent-visual-option agent-evidence-option"><label v-if="!evidenceUnsupported || evidenceMode"><input v-model="evidenceMode" type="checkbox" :disabled="pending || !evidenceMode && (!evidenceSupported || !evidenceExecutor)">分项证据回答</label><span>分别展示原句、画面、前后采样、推断与缺口；仍共用本轮请求上限，不证明语义正确。</span><small v-if="evidenceError" role="status">{{ evidenceError }}</small><small v-else-if="evidenceLoading">正在核对分项协议与专用执行队列…</small><small v-else-if="evidenceExecutor">分项执行器 {{ evidenceExecutor.online_workers }} 个在线 · 空闲 {{ evidenceExecutor.available_slots }} 槽。<template v-if="!evidenceExecutor.online_workers">提交后等待支持分项协议的执行器；旧视觉执行器不会领取。</template></small><small v-if="evidenceMode && visualMode !== 'frames'" role="status">分项开关保留，请切回“结合画面”或明确关闭后再提交。</small></div>
          <div v-if="focusHint" class="agent-focus-chip" role="status"><strong>定位提示 {{ topicTime(focusHint.timestamp_ms) }}</strong><span>播放或拖动不会改变此位置，尚不是画面证据。</span><div><button type="button" class="text-button" :disabled="pending" @click="removeFocus">移除定位提示</button><button type="button" class="text-button" :disabled="!canAttachFocus" @click="attachFocus">重新附加当前位置</button></div><p v-if="visualMode !== 'frames'">请移除提示或切回“结合画面”后再提交。</p><p v-else-if="!focusHintMatches(focusHint, focusOwner())">原视频来源已变化，请明确移除或重新附加。</p><p v-else-if="!focusSupported">位置支持尚未确认，提示仍保留；可点击“检查画面能力”重新核对。</p></div>
          <section v-if="followupProposal" class="agent-frame-followup-proposal" aria-label="替换定位提示">
            <strong>是否替换下一轮定位提示？</strong><p>原提示 {{ topicTime(followupProposal.previous_hint.timestamp_ms) }}<span v-if="!focusHintMatches(followupProposal.previous_hint, focusOwner())">（来自此前视频来源）</span> → 截图时间 {{ topicTime(followupProposal.target.hint.timestamp_ms) }} · 第 {{ followupProposal.target.sequence }} 轮 {{ followupProposal.target.frame_id }}</p>
            <p>使用后将切到“结合画面”；本次只附加时间，不发送问题，下一轮重新取帧。</p><div><button type="button" class="secondary" :disabled="pending || !!frameFollowupUnavailable" @click="acceptFrameFollowup">使用此时间</button><button type="button" class="text-button" @click="keepFrameFollowupHint">保留原提示</button></div>
          </section>
          <div v-if="!selected?.turns.length" class="agent-examples"><button v-for="example in examples" :key="example" type="button" class="secondary" :disabled="pending || dirty" @click="chooseExample(example)">{{ example }}</button></div>
          <div class="agent-input-box"><label for="agent-question" class="visually-hidden">向视频助手提问</label><textarea id="agent-question" ref="questionInput" v-model="question" :disabled="pending" maxlength="2000" rows="2" placeholder="问问这段视频，例如：概括主要内容，列出关键观点。"></textarea>
          <div class="agent-compose-actions"><details class="agent-options"><summary>回答设置 <span>· {{ maxCalls }} 次上限</span></summary><div class="agent-options-body"><label>本轮模型请求上限<input v-model.number="maxCalls" type="number" min="1" max="24" :disabled="pending" aria-label="对话模型请求上限"></label><p>自动重试计入此上限。{{ visualMode === 'frames' ? '结合原句与实际抽查画面，画面采样不等于连续观看。' : '只使用当前视频转写。' }}剪辑建议需另行审核确认。</p></div></details><span class="agent-character-count">{{ question.length }} / 2000</span><button v-if="dirty" class="text-button" type="button" :disabled="pending" @click="discard">放弃输入</button><button class="primary" type="submit" :disabled="pending || selectionUnavailable || (!selected && !history.loaded) || !!followupProposal || submissionBlocked || focusSubmissionBlocked || evidenceSubmissionBlocked || !question.trim() || !!activeTurn">{{ action === 'send' ? '正在提交…' : selected ? '发送问题 ↑' : '开始提问 ↑' }}</button></div></div>
          <p v-if="activeTurn" class="help" role="status">正在处理上一轮。你可以先编辑问题，等待回答或取消本轮后发送。</p>
        </form>
      </div>
    </div>
  </section>
</template>

<style scoped>
.agent-selection-status{flex:none;max-height:100px;overflow:auto;padding:8px 12px;margin-bottom:8px;border:1px solid #dfc8ae;border-radius:7px;background:#fff8ef;font-size:12px;line-height:1.6;overflow-wrap:anywhere}.agent-selection-status button{margin:5px 10px 0 0;font-size:12px}.agent-history-pagination{display:flex;flex-wrap:wrap;align-items:center;gap:6px 10px;margin-top:10px;padding-top:9px;border-top:1px solid #e2e8f0;font-size:12px}.agent-history-pagination button{font-size:12px}
.agent-frame-followup-proposal{margin:7px 0;padding:10px;border:1px solid #dcb48d;border-radius:7px;background:#fff8ee;color:#79583c;font-size:12px;line-height:1.7;overflow-wrap:anywhere}.agent-frame-followup-proposal>div{display:flex;flex-wrap:wrap;gap:8px;margin-top:7px}.agent-frame-followup-proposal button{max-width:100%;font-size:12px}
.agent-panel { container: agent / inline-size; position: relative; display: flex; flex-direction: column; height: 100%; min-height: 0; min-width: 0; margin: 0; padding: 0; overflow: hidden; border: 0; border-radius: 0; background: transparent; color: #1e293b; font-size: 14px; }
.agent-submission-recovery { flex: none; max-height: min(32vh, 260px); overflow-y: auto; padding: 12px; margin-bottom: 10px; border: 1px solid #e7c895; border-radius: 8px; background: #fff9ef; font-size: 12px; line-height: 1.6; }
.agent-submission-recovery h3 { font-size: 14px; }.agent-submission-entry { border-top: 1px solid #ead7b7; padding: 10px 0; }.agent-submission-entry:last-child { padding-bottom: 0; }.agent-original-question { white-space: pre-wrap; overflow-wrap: anywhere; }.agent-submission-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 6px; }.agent-submission-error, .agent-submission-limit { color: #985c32; overflow-wrap: anywhere; }
.agent-panel h2, .agent-panel h3, .agent-panel h4, .agent-panel p { margin: 0; }
.agent-panel > .message, .agent-refresh-error { flex: none; max-height: 60px; overflow-y: auto; margin: 0 0 10px; padding: 9px 12px; font-size: 13px; line-height: 1.6; }
.agent-refresh-error { color: #9a5a37; }
.agent-layout { display: grid; grid-template-columns: var(--workspace-preview-width, clamp(300px, 32%, 400px)) minmax(0, 1fr); gap: 20px; flex: 1; min-width: 0; min-height: 0; overflow: hidden; }
.agent-layout.conversation-focused { grid-template-columns: minmax(0, 1fr); }
.agent-reference, .agent-conversation { min-width: 0; min-height: 0; border: 1px solid #e2e8f0; border-radius: 10px; background: #fff; overflow: hidden; }
.agent-reference { display: flex; flex-direction: column; }
.agent-reference-heading, .agent-toolbar { display: flex; flex: none; min-height: 48px; align-items: center; justify-content: space-between; gap: 8px 10px; padding: 7px 14px; border-bottom: 1px solid #edf1f5; }
.agent-toolbar { flex-wrap: wrap; }
.agent-reference-heading h3, .agent-toolbar h2 { display: flex; align-items: center; gap: 7px; color: #1f2937; font-size: 16px; line-height: 1.6; font-weight: 650; white-space: nowrap; }
.agent-video-icon { color: #7b8799; font-size: 11px; }
.agent-ai-icon { display: inline-flex; align-items: center; justify-content: center; width: 26px; height: 26px; border-radius: 8px; background: #fff0e8; color: #d26a44; font-size: 20px; }
.agent-reference-caption { margin-left: auto; color: #8491a2; font-size: 11px; }
.agent-reference-toggle { display: none; align-items: center; min-height: 30px; color: #475569; font-size: 13px; }
.agent-focus-compact { flex: none; margin-left: auto; min-height: 30px; padding: 3px 0; color: #475569; font-size: 12px; white-space: nowrap; }
.agent-reference-body { display: flex; flex-direction: column; flex: 1; min-height: 0; min-width: 0; gap: 0; padding: 0; overflow: hidden; }
.agent-reference-video { position: relative; flex: none; width: 100%; aspect-ratio: 16/9; background: #101520; overflow: hidden; }
.agent-reference-video video { position: absolute; inset: 0; display: block; width: 100%; height: 100%; object-fit: contain; }
.agent-current-citation { display: flex; flex: none; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 4px 10px; border: 1px solid #f0d8cc; border-radius: 5px; padding: 6px 9px; background: #fff7f2; }
.agent-current-citation strong { color: #a85635; font-size: 13px; line-height: 1.6; font-weight: 500; }
.agent-current-citation .text-button { min-height: 28px; color: #64748b; font-size: 12px; white-space: normal; }
.agent-current-citation > p { flex-basis: 100%; color: #334155; font-size: 14px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.agent-reference-help { flex: none; color: #9a5a37; font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }
.agent-evidence-heading { display: flex; flex: none; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 4px 8px; padding: 11px 12px 7px; }
.agent-evidence-heading h4 { color: #334155; font-size: 13px; line-height: 1.6; font-weight: 600; }
.agent-evidence-heading h4 > span { display: inline-block; min-width: 20px; margin-left: 4px; padding: 1px 5px; border-radius: 4px; color: #7b8799; background: #f1f4f8; font-size: 11px; text-align: center; }
.agent-evidence-heading .text-button { font-size: 12px; }
.agent-reference-evidence { flex: 1; min-height: 0; min-width: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: stable; scrollbar-width: thin; padding: 0 12px 12px; }
.agent-evidence-empty { padding: 12px 7px; color: #64748b; font-size: 13px; line-height: 1.8; }
.agent-evidence-item { display: flex; align-items: flex-start; gap: 8px; width: 100%; min-width: 0; border: 1px solid #edf1f5; border-radius: 7px; margin-top: 7px; padding: 9px; background: #fff; text-align: left; color: #334155; }
.agent-evidence-item:hover { background: #f8fafc; }
.agent-evidence-item.selected, .agent-evidence-item.selected:hover { border-color: #efc6b5; background: #fff7f2; }
.agent-evidence-number { flex: none; color: #a0aaba; font-size: 11px; line-height: 20px; font-variant-numeric: tabular-nums; }
.agent-evidence-copy { min-width: 0; flex: 1; }
.agent-evidence-time { display: block; color: #a35d42; font-size: 11px; line-height: 20px; font-variant-numeric: tabular-nums; }
.agent-evidence-text { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; margin-top: 4px; color: #475569; font-size: 14px; line-height: 1.7; overflow-wrap: anywhere; }
.agent-evidence-play { flex: none; color: #bdc6d2; font-size: 10px; line-height: 20px; }
.agent-conversation { display: flex; flex-direction: column; }
.agent-toolbar-actions { display: flex; flex: 0 1 auto; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 6px; min-width: 0; max-width: 100%; margin-left: auto; }
.agent-toolbar-actions button { min-height: 36px; padding: 6px 10px; color: #475569; background: #fff; font-size: 13px; line-height: 1.6; white-space: nowrap; }
.agent-toolbar-actions .agent-focus-toggle { border-color: transparent; background: #f5f7fa; }
.agent-focus-toggle[aria-pressed=true] { color: #b95431; background: #fff0e8; }
.agent-toolbar-actions button > span { margin-left: 4px; color: #64748b; }
.agent-history-backdrop { position: absolute; inset: 0; z-index: 9; background: #0f172a20; }
.agent-sessions { position: absolute; top: 65px; right: 24px; z-index: 10; width: min(340px, calc(100% - 32px)); max-height: min(420px, calc(100% - 90px)); overflow-y: auto; overscroll-behavior: contain; border: 1px solid #dce4ed; border-radius: 8px; background: #fff; padding: 14px; box-shadow: 0 12px 36px #0f172a20; }
.agent-section-heading, .agent-answer-heading, .agent-compose-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
.agent-section-heading { justify-content: space-between; }
.agent-section-heading h3 { font-size: 14px; font-weight: 600; }
.agent-history-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
.agent-session { display: block; width: 100%; border: 1px solid transparent; border-radius: 6px; background: #fff; text-align: left; padding: 10px; margin-top: 8px; color: #334155; }
.agent-session strong { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; overflow-wrap: anywhere; font-size: 13px; font-weight: 500; line-height: 1.6; }
.agent-session small { display: block; margin-top: 4px; color: #64748b; font-size: 12px; }
.agent-session.selected, .agent-session.selected:hover { border-color: #f0c9ba; background: #fff4ee; }
.agent-turns, .agent-welcome { flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: stable; padding: 20px max(24px, calc((100% - 760px) / 2)); margin: 0; list-style: none; scrollbar-width: thin; }
.agent-welcome { display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; color: #64748b; }
.agent-welcome h3 { margin-bottom: 10px; color: #1e293b; font-size: 20px; font-weight: 600; }
.agent-welcome p { max-width: 380px; font-size: 14px; line-height: 1.8; }
.agent-turn { margin: 0 0 26px; }
.agent-turn:last-child { margin-bottom: 0; }
.agent-question { width: fit-content; max-width: 94%; margin-left: auto; padding: 12px 16px; border-radius: 12px 12px 3px 12px; background: #f3f5f8; }
.agent-question span { display: block; margin-bottom: 5px; color: #64748b; font-size: 12px; }
.agent-question p, .agent-clip-suggestion > p { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.85; font-size: 15px; }
.agent-answer { padding-top: 18px; }
.agent-answer-text { font-size: 15px; line-height: 1.85; }
.agent-answer-heading { margin-bottom: 10px; }
.agent-answer-heading > strong { font-size: 14px; font-weight: 600; }
.agent-status { color: #64748b; font-size: 12px; }
.agent-scope { display: inline-block; margin-bottom: 8px; padding: 3px 7px; border-radius: 4px; color: #55745c; background: #edf5ef; font-size: 12px; }
.agent-scope.general { background: #eff6ff; color: #526985; }
.agent-scope.insufficient { background: #fff7ed; color: #9a6238; }
.agent-citations, .agent-tools { margin: 8px 0; border: 1px solid #edf1f5; border-radius: 6px; color: #64748b; font-size: 12px; background: #fafbfc; }
.agent-citations summary, .agent-tools summary { padding: 8px 10px; cursor: pointer; color: #64748b; }
.agent-citation { display: block; width: calc(100% - 20px); padding: 9px 10px; margin: 0 10px 10px; border: 1px solid #e2e8f0; border-radius: 5px; background: #fff; text-align: left; color: #334155; }
.agent-citation span { color: #64748b; font-size: 12px; }
.agent-citation p { margin-top: 5px; font-size: 14px; line-height: 1.8; overflow-wrap: anywhere; }
.agent-citation.selected, .agent-citation.selected:hover { border-color: #e47650; background: #fff8f4; }
.agent-citation-row { margin: 0 10px 10px; padding: 10px; border: 1px solid #e2e8f0; border-radius: 7px; background: #fff; }
.agent-citation-row .agent-citation { width: 100%; margin: 0; padding: 8px 0; border: 0; background: transparent; }
.agent-anchor-choice { display: inline-flex; min-height: 36px; align-items: center; gap: 8px; color: #475569; font-size: 13px; cursor: pointer; }
.agent-anchor-choice input { width: 17px; height: 17px; margin: 0; accent-color: #e47650; }
.agent-anchor-help { padding: 0 12px 10px; color: #64748b; font-size: 13px; line-height: 1.8; }
.agent-anchor-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; padding: 2px 12px 12px; color: #64748b; font-size: 13px; }
.agent-anchor-actions button { min-height: 36px; margin-left: auto; }
.agent-anchor-actions small { flex-basis: 100%; font-size: 12px; line-height: 1.8; }

.agent-tools ol { padding: 0 11px 10px; margin: 0; list-style: none; }
.agent-tools li { padding: 6px 0 9px 11px; border-left: 2px solid #e2e8f0; overflow-wrap: anywhere; }
.agent-tools strong { color: #334155; font-weight: 500; }
.agent-tools p { margin: 4px 0; line-height: 1.7; }
.agent-tools small { font-size: 12px; }
.agent-clip-suggestion { margin-top: 12px; border: 1px solid #e2e8f0; border-radius: 6px; padding: 11px; background: #f8fafc; }
.agent-clip-suggestion > p { margin-bottom: 9px; }
.agent-clip-suggestion small { display: block; margin-top: 8px; color: #64748b; font-size: 12px; line-height: 1.7; }
.agent-reading-actions { display: flex; flex: none; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 3px 8px; padding: 4px 20px; border-top: 1px solid #edf1f5; color: #64748b; font-size: 12px; }
.agent-reading-actions .text-button { padding: 3px 0; color: #64748b; font-size: 12px; }
.agent-compose { position: relative; flex: none; width: min(820px, 100%); margin: 0 auto; padding: 5px 16px 10px; background: #fff; }
.agent-input-box { border: 1px solid #d8e0e9; border-radius: 9px; padding: 8px 10px; background: #fff; }
.agent-input-box:focus-within { border-color: #d49a80; box-shadow: 0 0 0 2px #fcf0e9; }
.agent-compose textarea { display: block; width: 100%; height: 38px; min-height: 36px; max-height: 100px; resize: vertical; margin: 0; border: 0; padding: 0; color: #1e293b; background: transparent; font: inherit; font-size: 14px; line-height: 1.7; }
.agent-compose textarea:focus { outline: 0; }
.agent-compose textarea::placeholder { color: #8491a2; }
.agent-compose-actions { position: relative; margin-top: 5px; gap: 6px 10px; }
.agent-compose-actions .primary { min-height: 34px; margin-left: auto; padding: 7px 12px; font-size: 13px; line-height: 1.5; }
.agent-character-count { color: #64748b; font-size: 12px; }
.agent-options { position: relative; margin-right: auto; color: #64748b; font-size: 12px; }
.agent-options summary { padding: 5px 0; cursor: pointer; }
.agent-options summary > span { color: #8491a2; }
.agent-options-body { position: absolute; bottom: calc(100% + 16px); left: 0; z-index: 3; width: 270px; max-width: calc(100vw - 64px); border: 1px solid #dce4ed; border-radius: 7px; padding: 14px; background: #fff; box-shadow: 0 8px 32px #0f172a15; }
.agent-options-body label { display: flex; align-items: center; gap: 12px; font-size: 13px; }
.agent-options-body input { width: 60px; min-width: 0; border: 1px solid #cbd5e1; border-radius: 5px; padding: 7px; color: #334155; }
.agent-options-body p { margin-top: 9px; font-size: 12px; line-height: 1.7; }
.agent-visual-option{display:flex;flex-wrap:wrap;align-items:center;gap:4px 9px;margin-bottom:6px;font-size:11px;line-height:1.6;color:#64748b}.agent-visual-option label{display:flex;align-items:center;gap:5px;white-space:nowrap;color:#334155}.agent-visual-option input{margin:0}.agent-visual-option small{flex-basis:100%;overflow-wrap:anywhere}.agent-visual-option .text-button{padding:2px 0}
.agent-focus-controls{display:flex;flex-wrap:wrap;align-items:center;gap:6px;padding:8px 12px;font-size:11px;color:#64748b;line-height:1.6}.agent-focus-controls small{flex-basis:100%;overflow-wrap:anywhere}.agent-focus-chip{margin:5px 0 8px;padding:8px 10px;background:#eef6fb;border:1px solid #cbdfea;border-radius:7px;font-size:11px;line-height:1.6;overflow-wrap:anywhere}.agent-focus-chip strong{margin-right:8px;color:#334155}.agent-focus-chip>div{display:flex;gap:12px;flex-wrap:wrap}.agent-focus-chip p{color:#985c32}
.agent-examples { display: flex; gap: 8px; margin-bottom: 10px; padding-bottom: 3px; overflow-x: auto; scrollbar-width: thin; }
.agent-examples button { flex: none; padding: 7px 9px; border-radius: 6px; color: #64748b; background: #fff; font-size: 12px; white-space: nowrap; }
.agent-panel .help { color: #64748b; font-size: 13px; line-height: 1.7; }
.agent-compose > .help { margin-top: 8px; font-size: 12px; }
.agent-panel :deep(.text-recovery) { color: #475569; background: #f8fafc; border-color: #cbd5e1; }
@container agent (max-width:780px) {
  .agent-layout { display: flex; flex-direction: column; gap: 10px; overflow: hidden; }
  .agent-reference { flex: 0 1 auto; max-height: max(44px, min(220px, calc(100% - 340px))); }
  .agent-reference-heading { min-height: 44px; padding: 6px 12px; }
  .agent-reference-caption, .agent-focus-toggle { display: none; }
  .agent-reference-toggle { display: inline-flex; }
  .agent-reference-body { display: block; overflow-y: auto; padding: 0; }
  .agent-reference-video { max-height: 175px; }
  .agent-current-citation { margin-top: 10px; }
  .agent-reference-evidence { overflow: visible; scrollbar-gutter: auto; }
  .agent-conversation { flex: 1; min-height: 0; }
  .agent-toolbar { padding: 8px 12px; }
  .agent-turns, .agent-welcome { padding: 16px; }
  .agent-reading-actions { padding-inline: 12px; }
  .agent-compose { max-height: 48%; overflow-y: auto; padding: 6px 12px 10px; }
  .agent-options[open] { flex-basis: 100%; margin-right: 0; }
  .agent-options-body { position: static; width: 100%; max-width: none; margin-top: 8px; padding: 12px; box-shadow: none; }
  .agent-compose textarea { height: 38px; max-height: 80px; }
}
@container agent (max-width:520px) {
  .agent-toolbar { gap: 6px 10px; }
  .agent-toolbar h2 { font-size: 13px; }
  .agent-toolbar-actions { gap: 5px; }
  .agent-toolbar-actions button { min-height: 30px; padding: 5px 7px; }
  .agent-toolbar-actions button > span, .agent-character-count, .agent-options summary > span { display: none; }
  .agent-question p, .agent-answer-text { font-size: 14px; }
  .agent-input-box { padding: 9px 10px; }
  .agent-compose-actions { gap: 8px; }
  .agent-sessions { top: 60px; right: 12px; }
}
@media (max-height:650px) {
  .agent-compose { padding-bottom: 10px; }
  .agent-compose textarea { height: 38px; max-height: 80px; }
}
@media (max-height:600px) {
  .agent-reference-body { overflow-y: auto; }
  .agent-reference-evidence { flex: none; overflow: visible; }
}
@media (max-height:420px) {
  @container agent (max-width:780px) {
    .agent-layout { overflow-y: auto; }
    .agent-reference { flex: none; max-height: 180px; }
    .agent-conversation { flex: none; min-height: 0; overflow: visible; }
    .agent-turns, .agent-welcome { flex: none; min-height: 120px; max-height: 200px; }
    .agent-compose { max-height: none; overflow: visible; }
  }
}
</style>
