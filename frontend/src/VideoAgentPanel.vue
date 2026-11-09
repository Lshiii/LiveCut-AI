<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, ApiError, post } from './api'
import FailureDiagnostic from './FailureDiagnostic.vue'
import TextRequestProgress from './TextRequestProgress.vue'
import AgentAnswer from './AgentAnswer.vue'
import AgentVisualEvidence from './AgentVisualEvidence.vue'
import { parseAgentVisualCapability, type AgentVisualCapability } from './agentVisualEvidence'
import { SequencePreview } from './sequencePreview'
import { selectAnswerClip, type AnswerClipSelection } from './answerClip'
import { topicTime } from './topics'
import { AgentReadScope, AgentResponseError, agentTurnActive, canPrefillClipInstruction,
  newestAgentSession, newestAgentTurn, parseAgentSession, parseAgentTurn,
  type AgentCitation, type AgentSession, type AgentTurn } from './videoAgent'
import { AgentSubmissionError, agentDraftAfterSubmission, agentSubmissionStorageKey, assertAgentSubmissionStored, createAgentSubmission, filterAcknowledgedAgentSubmissions, inspectAgentSubmissionLimit, mergeAgentSubmissionResult, parseAgentSubmissionResponse,
  removeAgentSubmission, resolveAgentSubmission, restoreAgentSubmission, retainAgentSubmissions, scanAgentSubmissions,
  type AgentSubmissionEntry, type AgentSubmissionIssue, type AgentSubmissionResult } from './agentSubmissionRecovery'

const props = defineProps<{ jobId: string; videoId: string; videoHash: string; videoUrl: string; clipTargetBusy?: boolean; active?: boolean }>()
const emit = defineEmits<{ clipInstruction: [instruction: string]; clipAnchor: [selection: AnswerClipSelection] }>()
const sessions = ref<AgentSession[]>([]), selectedId = ref(''), question = ref(''), maxCalls = ref(8)
const visualMode = ref<'none' | 'frames'>('none'), visualCapability = ref<AgentVisualCapability | null>(null), capabilityLoading = ref(false), capabilityError = ref('')
const action = ref(''), error = ref(''), refreshError = ref(''), notice = ref(''), loading = ref(true)
const selected = computed(() => sessions.value.find(session => session.id === selectedId.value) ?? null)
const activeTurn = computed(() => selected.value?.turns.find(agentTurnActive))
const recentAnswer = computed(() => [...(selected.value?.turns ?? [])].reverse().find(turn => !!turn.result))
const referenceTurnId = ref('')
const referenceAnswer = computed(() => selected.value?.turns.find(turn => turn.id === referenceTurnId.value && !!turn.result) ?? recentAnswer.value)
const recentCitations = computed(() => referenceAnswer.value?.result?.citations ?? [])
const allEvidenceVisible = ref(false)
const visibleCitations = computed(() => allEvidenceVisible.value ? recentCitations.value : recentCitations.value.slice(0, 3))
const anchorPreparing = ref(false), anchorChoices = ref<Record<string, string[]>>({})
const submissionEntries = ref<AgentSubmissionEntry[]>([]), submissionIssues = ref<AgentSubmissionIssue[]>([])
const ownDurabilityLost = ref(false)
const fullSessionConflicts = ref<Record<string, boolean>>({})
const submissionBlocked = computed(() => !!submissionEntries.value.length || !!submissionIssues.value.length)
const pending = computed(() => !!action.value || anchorPreparing.value), dirty = computed(() => !!question.value.trim() || ownDurabilityLost.value)
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
// Only actions this page started own an unacknowledged identity. Passive remote
// entries remain visible/blocking without being re-created after their owner ACKs.
const ownUnacknowledged = new Map<string, AgentSubmissionEntry>()
const acknowledgedSubmissions = new Map<string, AgentSubmissionEntry>()
let submissionVersion = 0
let stopped = false, refreshing = false, timer: number | undefined, selectionVersion = 0, previewVersion = 0
let referenceObserver: ResizeObserver | undefined, capabilityVersion = 0
let preparedEvidence: { version: number; sessionId: string; turnId: string; videoUrl: string; sourceHash: string; start: number; end?: number } | null = null
let evidenceTimer: number | undefined
const evidenceSequence = new SequencePreview()
let followLatest = true, initialScrollPending = false
const examples = ['概括这段视频', '展开解释刚才提到的第二点', '哪些片段值得单独剪']
const names: Record<string, string> = { QUEUED: '等待回答', RUNNING: '正在查找与回答', SUCCEEDED: '回答完成', FAILED: '本轮未完成', CANCELLED: '已取消' }
const scopes = { VIDEO: '来自视频', GENERAL: '通用知识补充', INSUFFICIENT: '视频证据不足' }
const toolNames = { search_transcript: '检索视频原句', read_context: '补读相邻内容', list_topics: '查看视频话题', inspect_frames: '查看指定画面', inspect_clip: '短片画面抽样' }
defineExpose({ dirty, pending })

function message(value: unknown) {
  const rawCode = value instanceof ApiError || value instanceof AgentSubmissionError ? value.code : value instanceof AgentResponseError ? 'INVALID_AGENT_RESPONSE' : 'CLIENT_ERROR'
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
    AGENT_SUBMISSION_ENTRY_CONFLICT: '本机原回执已被其他页面移除或更改，尚未把它当作提交成功；请按原标识只读核对。',
    AGENT_SUBMISSION_SOURCE_MISMATCH: '本机回执与当前视频来源不同，记录仍保留，不能替换原来源或建立新请求。',
    INVALID_AGENT_SUBMISSION_RESPONSE: '原提交响应的来源、问题或预算不一致，原回执与本页草稿仍保留。',
    AGENT_SUBMISSION_INVALID: '服务端原提交指纹未通过校验，原回执与本页草稿仍保留。',
    HTTP_404: '当前服务未提供提交核对路由，请更新服务后重试；原回执保留，未直接重新提问。',
    RESOURCE_NOT_FOUND: '当前服务未提供提交核对路由，请更新服务后重试；原回执保留，未直接重新提问。',
    AGENT_VISUAL_UNAVAILABLE: '当前服务未确认可结合画面，尚未提交视觉请求。画面选择和问题保留，未降级为文本请求。',
  }
  return known[code] ?? `对话操作未完成：${code}`
}
function parseSession(raw: unknown, id?: string) { return parseAgentSession(raw, props.jobId, props.videoId, props.videoHash, id) }
async function loadVisualCapability(current: () => boolean = () => !stopped) {
  const version = ++capabilityVersion
  capabilityLoading.value = true; capabilityError.value = ''
  try {
    const value = parseAgentVisualCapability(await api<unknown>('/api/agent-capabilities'))
    if (!current() || version !== capabilityVersion) return null
    visualCapability.value = value; return value
  } catch {
    if (current() && version === capabilityVersion) { visualCapability.value = null; capabilityError.value = '暂时无法确认画面能力；普通文本提问仍可使用。' }
    return null
  } finally { if (!stopped && version === capabilityVersion) capabilityLoading.value = false }
}
function upsertSession(value: AgentSession) {
  const index = sessions.value.findIndex(session => session.id === value.id)
  const latest = newestAgentSession(index < 0 ? undefined : sessions.value[index], value)
  if (index < 0) sessions.value.unshift(latest)
  else sessions.value[index] = latest
  sessions.value.sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at))
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
  if (id === selectedId.value || !canSwitch()) return
  closeHistory()
  scope.invalidate(); selectionVersion++; selectedId.value = id; closePreview()
  error.value = ''; notice.value = ''; playbackError.value = ''
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
    scanSubmissions()
    if (!selectedId.value && sessions.value[0] && !pending.value) selectedId.value = sessions.value[0].id
    const id = selectedId.value
    if (id) {
      const detail = await api<unknown>(`/api/agent-sessions/${id}`, { signal: read.signal })
      if (!read.current() || selectedId.value !== id) return
      upsertSession(parseSession(detail, id))
    }
    refreshError.value = ''
  } catch (value) {
    if (read.current()) refreshError.value = value instanceof ApiError && ['NETWORK_UNAVAILABLE', 'REQUEST_TIMEOUT'].includes(value.code)
      ? '暂时无法刷新对话，已有记录与输入保留。' : message(value)
  }
  finally { read.release(); refreshing = false; loading.value = false }
}
async function poll() { await refresh(); if (!stopped) timer = window.setTimeout(() => { void poll() }, 4000) }
function submissionOwner() { return { job_id: props.jobId, video_id: props.videoId, video_sha256: props.videoHash } }
function scanSubmissions() {
  try {
    const own = [...ownUnacknowledged.values()]
    const scanned = filterAcknowledgedAgentSubmissions(scanAgentSubmissions(localStorage, submissionOwner(), sessions.value), [...acknowledgedSubmissions.values()])
    const result = retainAgentSubmissions(own, scanned, localStorage)
    submissionEntries.value = result.entries; submissionIssues.value = result.issues
    ownDurabilityLost.value = result.issues.some(issue => own.some(entry => issue.storage_key === agentSubmissionStorageKey(entry)))
  } catch { submissionIssues.value = [{ storage_key: '', code: 'AGENT_SUBMISSION_STORAGE_UNAVAILABLE' }]; ownDurabilityLost.value = ownUnacknowledged.size > 0 }
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
  try { entry = createAgentSubmission(localStorage, owner, 'SESSION', '{}', () => crypto.randomUUID(), sessions.value, undefined, [...acknowledgedSubmissions.values()]) }
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
  try { requireNoSubmission() } catch (value) { error.value = message(value); return }
  closeHistory(false)
  scope.invalidate(); const version = ++selectionVersion, operation = ++submissionVersion
  action.value = 'session'; error.value = ''; notice.value = ''
  try { if (await requestSession(version, operation)) notice.value = '新对话已创建，可以围绕这段视频提问。' }
  catch (value) { if (valid(version) && operation === submissionVersion) { scanSubmissions(); error.value = message(value) } }
  finally { if (valid(version) && operation === submissionVersion) action.value = '' }
}
async function send() {
  const input = question.value.trim(), budget = maxCalls.value, mode = visualMode.value
  if (pending.value || !input || props.active === false || document.hidden) return
  try { requireNoSubmission() } catch (value) { error.value = message(value); return }
  if (input.length > 2000 || !Number.isInteger(budget) || budget < 1 || budget > 24) { error.value = '问题最多 2000 字，模型请求上限须为 1～24 的整数。'; return }
  if (activeTurn.value) { error.value = '本对话还有一轮正在执行，请等待回答或取消本轮后再提问。'; return }
  scope.invalidate(); const version = ++selectionVersion, operation = ++submissionVersion, owner = submissionOwner()
  const draft = JSON.stringify({ question: question.value, budget: maxCalls.value, visual_mode: visualMode.value })
  action.value = 'send'; error.value = ''; notice.value = ''
  let submitted: AgentSubmissionEntry | null = null, posted = false
  try {
    if (mode === 'frames') {
      const capability = await loadVisualCapability(() => submissionCurrent(version, operation, owner))
      if (!submissionCurrent(version, operation, owner)) return
      if (capability?.visual_mode !== 'frames') throw new ApiError('AGENT_VISUAL_UNAVAILABLE')
    }
    const session = selected.value ?? await requestSession(version, operation)
    if (!session || !submissionCurrent(version, operation, owner, session.id)) return
    requireNoSubmission()
    let entry: AgentSubmissionEntry
    try { entry = createAgentSubmission(localStorage, owner, 'TURN', JSON.stringify({ question: input, max_text_calls: budget, ...(mode === 'frames' ? { visual_mode: 'frames' } : {}) }), () => crypto.randomUUID(), sessions.value, session, [...acknowledgedSubmissions.values()]) }
    catch (value) { scanSubmissions(); throw value }
    rememberSubmission(entry)
    submitted = entry
    const raw = await postSubmission(entry, () => { posted = true })
    if (!submissionCurrent(version, operation, owner, session.id)) return
    const result = parseAgentSubmissionResponse(raw, entry, session)
    if (result.kind !== 'TURN') throw new AgentSubmissionError()
    adoptSubmissionResult(result); acknowledgeSubmission(entry)
    if (draft === JSON.stringify({ question: question.value, budget: maxCalls.value, visual_mode: visualMode.value })) question.value = ''
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
  const nextQuestion = agentDraftAfterSubmission(entry, { session_id: selectedId.value, question: question.value, budget: maxCalls.value, visual_mode: visualMode.value })
  adoptSubmissionResult(result); acknowledgeSubmission(entry); question.value = nextQuestion
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
function originalVisualMode(entry: AgentSubmissionEntry): string { return entry.kind === 'TURN' && JSON.parse(entry.input).visual_mode === 'frames' ? '结合画面' : '文本模式' }
function submissionStorageChanged(event: StorageEvent) {
  if (event.key === null || event.key.startsWith(`livecut-agent-submission-v1:${props.jobId}:`) || event.key === `livecut-agent-session-${props.jobId}`
    || sessions.value.some(session => event.key === `livecut-agent-turn-${session.id}`)) scanSubmissions()
}
function invalidateSubmission() {
  submissionVersion++
  if (action.value === 'send' || action.value === 'session' || action.value.startsWith('recover:') || action.value.startsWith('release:')) action.value = ''
}
function submissionVisibilityChanged() { if (document.hidden) { invalidateSubmission(); pauseReference() } else scanSubmissions() }
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
function chooseAnchor(turn: AgentTurn, id: string, event: Event) {
  if (pending.value || props.active === false || turn.status !== 'SUCCEEDED' || turn.result?.answer_scope !== 'VIDEO' || !turn.result.citations.some(citation => citation.utterance_id === id)) return
  const previous = anchorChoices.value[turn.id] ?? []
  anchorChoices.value = { ...anchorChoices.value, [turn.id]: (event.target as HTMLInputElement).checked ? [...new Set([...previous, id])] : previous.filter(value => value !== id) }
}
async function suggestAnchors(turn: AgentTurn) {
  const owner = selected.value, ids = anchorChoices.value[turn.id] ?? [], version = selectionVersion, videoHash = props.videoHash
  if (!owner || !ids.length || pending.value || props.active === false || props.clipTargetBusy) return
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
  if (stopped || props.active === false || document.hidden || !selected.value?.turns.some(value => value.id === turn.id && value.status === 'SUCCEEDED')
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
  try {
    if (value.end === undefined) { player.pause(); player.currentTime = value.start / 1000 }
    else void evidenceSequence.start(player, { id: `${value.turnId}:${value.version}`, parts: [{ start_ms: value.start, end_ms: value.end }] })
      .then(result => { if (result === 'failed' && evidenceCurrent(value)) playbackError.value = '区间暂时无法播放，请先确认原视频可播放后重试。' })
  }
  catch { if (evidenceCurrent(value)) playbackError.value = '暂时无法定位，请检查原视频是否可播放。' }
}
function pauseReference() { previewVersion++; preparedEvidence = null; window.clearTimeout(evidenceTimer); evidenceTimer = undefined; evidenceSequence.cancel(); sourcePlayer.value?.pause() }
function toggleReference() { pauseReference(); referenceExpanded.value = !referenceExpanded.value }
function toggleConversationFocus() { pauseReference(); conversationFocused.value = !conversationFocused.value }
function guardPlayback(event: Event) { if (!mediaActive()) (event.currentTarget as HTMLVideoElement).pause() }
function closePreview() { pauseReference(); citation.value = null; frameLocation.value = null; referenceTurnId.value = ''; playbackError.value = '' }
function date(value: string) { return new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }
function unload(event: BeforeUnloadEvent) { scanSubmissions(); if (dirty.value || pending.value || submissionBlocked.value) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => {
  scanSubmissions(); window.addEventListener('storage', submissionStorageChanged); window.addEventListener('focus', scanSubmissions)
  void loadVisualCapability()
  document.addEventListener('visibilitychange', submissionVisibilityChanged)
  window.addEventListener('beforeunload', unload); void poll()
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
watch(() => referenceAnswer.value?.id, () => { allEvidenceVisible.value = false })
watch(() => {
  const last = selected.value?.turns.at(-1)
  return `${selectedId.value}:${last?.id ?? ''}:${last?.state_version ?? 0}`
}, () => { if (initialScrollPending || followLatest) scrollToLatest() }, { flush: 'post' })
watch(() => props.active, value => {
  if (value === false) { invalidateSubmission(); closeHistory(false); pauseReference() }
  else { scanSubmissions(); if (initialScrollPending) scrollToLatest(true) }
}, { flush: 'sync' })
watch(referenceVisible, value => { if (!value) pauseReference() }, { flush: 'sync' })
watch(() => props.videoUrl, pauseReference, { flush: 'sync' })
watch(() => props.videoHash, closePreview, { flush: 'sync' })
watch(sessionListOpen, value => { if (value) pauseReference() }, { flush: 'sync' })
onBeforeUnmount(() => { scanSubmissions(); stopped = true; capabilityVersion++; submissionVersion++; selectionVersion++; pauseReference(); referenceObserver?.disconnect(); scope.dispose(); window.clearTimeout(timer); window.removeEventListener('beforeunload', unload); window.removeEventListener('storage', submissionStorageChanged); window.removeEventListener('focus', scanSubmissions); document.removeEventListener('visibilitychange', submissionVisibilityChanged) })
</script>

<template>
  <section ref="panel" class="panel agent-panel" aria-label="视频对话助手" @keydown.esc="closeHistory()">
    <div v-if="sessionListOpen" class="agent-history-backdrop" aria-hidden="true" @click="closeHistory()"></div>
    <p v-if="error" class="message error" role="alert">{{ error }}</p><p v-if="notice" class="message notice" role="status">{{ notice }}</p>
    <p v-if="refreshError" class="agent-refresh-error" role="status">{{ refreshError }} 已有记录与输入仍保留。</p>
    <section v-if="submissionBlocked" class="agent-submission-recovery" aria-label="待核对的原提交">
      <div class="agent-section-heading"><h3>原提交结果待核对 · {{ submissionEntries.length }} 条</h3><button class="text-button" :disabled="pending" @click="scanSubmissions">刷新本机回执</button></div>
      <p>可继续编辑本页草稿；新对话和新提问暂时停止。逐项核对会使用原标识与原内容，不会自动再发其他问题。</p>
      <p v-for="issue in submissionIssues" :key="issue.storage_key + issue.code" class="agent-submission-error" role="alert">{{ message(new AgentSubmissionError(issue.code)) }}</p>
      <article v-for="entry in submissionEntries" :key="agentSubmissionStorageKey(entry)" class="agent-submission-entry">
        <strong>{{ entry.kind === 'SESSION' ? '新建对话' : '提交问题' }} · #{{ entry.key.slice(0, 8) }}</strong><p class="agent-original-question">{{ originalQuestion(entry) }}</p><p v-if="entry.kind === 'TURN'">原预算上限 {{ originalBudget(entry) }} 次 · {{ originalVisualMode(entry) }} · 原对话 #{{ entry.session_id?.slice(0, 8) }}</p>
        <div class="agent-submission-actions"><button class="secondary" :disabled="pending || active === false" @click="reconcileSubmission(entry)">{{ action === `recover:${entry.key}` ? '正在核对…' : '核对原提交' }}</button><button class="text-button" @click="downloadSubmission(entry)">下载原回执</button></div>
        <div v-if="fullSessionConflicts[agentSubmissionStorageKey(entry)]" class="agent-submission-limit"><p>已核对原标识仍不存在，原会话恰有 100 轮。解除前将再次只读核对，不会自动新建对话或提问。</p><button class="secondary" :disabled="pending || active === false" @click="releaseFullSession(entry)">解除本条未落库提交</button></div>
      </article>
    </section>
    <div class="agent-layout" :class="{ 'conversation-focused': conversationFocused && !compactReference }">
      <aside v-show="sessionListOpen" ref="historyPanel" role="dialog" aria-modal="true" @keydown="historyKey" :id="`agent-history-${jobId}`" class="agent-sessions" aria-label="视频对话列表"><div class="agent-section-heading"><h3>对话记录 · {{ sessions.length }}</h3><div class="agent-history-actions"><button class="text-button" @click="closeHistory()">关闭对话记录</button><button class="text-button" :disabled="pending" @click="refresh">刷新</button></div></div><p v-if="loading && !sessions.length" class="help">正在读取对话…</p><p v-else-if="!sessions.length" class="help">发送第一个问题，即可开始对话。</p><button v-for="session in sessions" :key="session.id" class="agent-session" :class="{ selected: selectedId === session.id }" :aria-pressed="selectedId === session.id" :disabled="pending || dirty" @click="session.id === selectedId ? closeHistory() : selectSession(session.id)"><strong>{{ session.turns[0]?.question ?? '新对话' }}</strong><small>{{ date(session.created_at) }} · {{ session.turns.length }} 轮</small></button></aside>
      <aside v-show="compactReference || !conversationFocused" ref="referenceDock" class="agent-reference" :inert="sessionListOpen ? true : undefined" aria-label="视频参考">
        <div class="agent-reference-heading"><h3><span class="agent-video-icon" aria-hidden="true">▶</span> 原视频</h3><span class="agent-reference-caption">点击原句，定位回看</span><button class="text-button agent-reference-toggle" :aria-expanded="referenceVisible" :aria-controls="`agent-reference-${jobId}`" @click="toggleReference">{{ referenceVisible ? '收起视频' : '展开视频' }}</button></div>
        <div v-show="referenceVisible" :id="`agent-reference-${jobId}`" class="agent-reference-body">
          <div class="agent-reference-video"><video ref="sourcePlayer" :src="videoUrl" controls preload="metadata" playsinline @loadedmetadata="seekCitation" @timeupdate="evidenceSequence.timeUpdate($event.currentTarget as HTMLVideoElement)" @playing="evidenceSequence.timeUpdate($event.currentTarget as HTMLVideoElement)" @seeking="evidenceSequence.seeking($event.currentTarget as HTMLVideoElement)" @seeked="evidenceSequence.seeked($event.currentTarget as HTMLVideoElement)" @pause="evidenceSequence.pause($event.currentTarget as HTMLVideoElement)" @ended="evidenceSequence.ended($event.currentTarget as HTMLVideoElement)" @ratechange="evidenceSequence.rateChange($event.currentTarget as HTMLVideoElement)" @play="guardPlayback" @error="pauseReference()"></video></div>
          <p v-if="playbackError" class="agent-reference-help" role="status">{{ playbackError }}</p>
          <p v-if="frameLocation" class="agent-reference-help" role="status">{{ frameLocation.title }} · 原视频 {{ topicTime(frameLocation.time) }}。单帧定位保持暂停，区间回看由你明确触发。</p>
          <div class="agent-evidence-heading"><h4>原句引用 <span v-if="referenceAnswer">第 {{ referenceAnswer.sequence }} 轮 · </span><span>{{ recentCitations.length }}</span></h4><button v-if="recentCitations.length > 3" class="text-button" :aria-expanded="allEvidenceVisible" :aria-controls="`agent-evidence-${jobId}`" @click="allEvidenceVisible = !allEvidenceVisible">{{ allEvidenceVisible ? '收起列表' : `查看全部 ${recentCitations.length} 条` }}</button></div>
          <div :id="`agent-evidence-${jobId}`" ref="referenceEvidence" class="agent-reference-evidence"><div v-if="citation" class="agent-current-citation"><strong>正在回看 {{ topicTime(citation.start_ms) }} → {{ topicTime(citation.end_ms) }}</strong><button class="text-button" @click="closePreview">关闭定位</button><p>{{ citation.text }}</p></div><p v-if="!recentCitations.length" class="agent-evidence-empty">{{ referenceAnswer ? '本轮未引用原句；可在回答下查看画面依据或回看原视频。' : '提问后，这里会列出回答引用的原句。' }}</p><button v-for="(source, index) in visibleCitations" :key="source.utterance_id" class="agent-evidence-item" :class="{ selected: citation?.utterance_id === source.utterance_id }" :title="source.text" @click="preview(source)"><span class="agent-evidence-number" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span><span class="agent-evidence-copy"><span class="agent-evidence-time">{{ topicTime(source.start_ms) }} → {{ topicTime(source.end_ms) }}</span><span class="agent-evidence-text">{{ source.text }}</span></span><span class="agent-evidence-play" aria-hidden="true">▶</span></button></div>
        </div>
      </aside>
      <div class="agent-conversation" :inert="sessionListOpen ? true : undefined">
        <div class="agent-toolbar"><h2><span class="agent-ai-icon" aria-hidden="true">✦</span> 视频助手</h2><div class="agent-toolbar-actions"><button class="secondary agent-focus-toggle" :aria-pressed="conversationFocused" @click="toggleConversationFocus">{{ conversationFocused ? '并排回看' : '专注对话' }}</button><button ref="historyToggle" class="secondary" :aria-expanded="sessionListOpen" :aria-controls="`agent-history-${jobId}`" @click="toggleHistory">对话记录 <span>{{ sessions.length }}</span></button><button class="secondary" :disabled="pending || dirty || submissionBlocked" @click="newSession">＋ 新对话</button></div></div>
        <div v-if="!selected?.turns.length" class="agent-welcome"><h3>从视频里找答案</h3><p>可以先概括内容，也可以沿着已有回答继续追问。证据不足时会明确说明。</p></div>
        <ol v-else ref="messageList" class="agent-turns" aria-label="对话记录" @scroll.passive="messageScroll"><li v-for="turn in selected.turns" :key="turn.id" class="agent-turn"><div class="agent-question"><span>你的问题 · 第 {{ turn.sequence }} 轮</span><p>{{ turn.question }}</p></div><div class="agent-answer"><div class="agent-answer-heading"><strong>视频助手</strong><span class="agent-status">{{ names[turn.status] }}</span><button v-if="agentTurnActive(turn)" class="text-button" :disabled="pending" @click="cancel(turn)">{{ action === 'cancel' ? '正在取消…' : '取消本轮' }}</button></div><TextRequestProgress :progress="turn.stage.text_progress" :terminal="!agentTurnActive(turn)" /><FailureDiagnostic :diagnostic="turn.stage.diagnostic" :error-code="turn.stage.error_code" :attempt="turn.stage.attempt" />
          <template v-if="turn.result"><span class="agent-scope" :class="turn.result.answer_scope.toLowerCase()">{{ scopes[turn.result.answer_scope] }}</span><AgentAnswer class="agent-answer-text" :text="turn.result.answer" /><p v-if="turn.result.answer_scope === 'GENERAL'" class="help">这部分是通用知识补充，不能当作视频中已经说过的话。</p><p v-else-if="turn.result.answer_scope === 'INSUFFICIENT'" class="help">当前视频证据不足，可换个说法，或补充想找的具体内容。</p>
            <AgentVisualEvidence :key="turn.id" :turn="turn" :active="active !== false && !sessionListOpen" @locate="locateFrame(turn, $event)" @preview="previewVisualRange(turn, $event)" />
            <details v-if="turn.result.citations.length" class="agent-citations"><summary>视频原句依据 · {{ turn.result.citations.length }} 条</summary><p v-if="turn.result.answer_scope === 'VIDEO'" class="agent-anchor-help">从同一回答中选择要保留的原句，剪成一个片段；可以补充前后解释，所选句会完整保留。</p><div v-for="(source, sourceIndex) in turn.result.citations" :key="source.utterance_id" class="agent-citation-row"><label v-if="turn.result.answer_scope === 'VIDEO' && turn.status === 'SUCCEEDED'" class="agent-anchor-choice"><input type="checkbox" :checked="(anchorChoices[turn.id] ?? []).includes(source.utterance_id)" :disabled="pending || clipTargetBusy || active === false" :aria-label="`选择剪辑原句 第${sourceIndex + 1}条 ${topicTime(source.start_ms)}至${topicTime(source.end_ms)}`" @change="chooseAnchor(turn, source.utterance_id, $event)"><span>选择此原句</span></label><button class="agent-citation" :class="{ selected: citation?.utterance_id === source.utterance_id }" @click="preview(source, turn)"><span>回看 {{ topicTime(source.start_ms) }} → {{ topicTime(source.end_ms) }}</span><p>{{ source.text }}</p></button></div><div v-if="turn.result.answer_scope === 'VIDEO'" class="agent-anchor-actions"><span>已选 {{ (anchorChoices[turn.id] ?? []).length }} 条 · 来自第 {{ turn.sequence }} 轮视频回答</span><button class="secondary" :disabled="pending || clipTargetBusy || active === false || !(anchorChoices[turn.id] ?? []).length" @click="suggestAnchors(turn)">按所选原句剪辑</button><small>仅填入剪辑区，不创建请求；初始候选只核对所选句及相邻上下文，仍需人工回看。</small></div></details>
            <details v-if="turn.result.tool_calls.length" class="agent-tools"><summary>查找过程 · {{ turn.result.tool_calls.length }} 步</summary><ol><li v-for="call in turn.result.tool_calls" :key="call.step"><strong>{{ call.step }}. {{ toolNames[call.tool_name] }}</strong><p v-if="call.tool_name === 'search_transcript'">查询：{{ call.arguments.query }}</p><p v-else-if="call.tool_name === 'read_context'">补读前 {{ call.arguments.before }} 句、后 {{ call.arguments.after }} 句</p><p v-else-if="call.tool_name === 'list_topics'">查看第 {{ Number(call.arguments.offset) + 1 }} 项起的话题，最多 {{ call.arguments.limit }} 项</p><p v-else-if="call.tool_name === 'inspect_frames'">请求时间：{{ (call.arguments.timestamps_ms as number[]).map(topicTime).join('、') }}</p><p v-else>短片抽样：{{ topicTime(Number(call.arguments.start_ms)) }} → {{ topicTime(Number(call.arguments.end_ms)) }} · 2fps、无原始音轨</p><small>返回 {{ call.result_utterance_ids.length }} 条原句依据<template v-if="call.result_frame_ids"> · {{ call.result_frame_ids.length }} 帧画面</template></small></li></ol></details>
            <div v-if="turn.result.suggested_clip_instruction" class="agent-clip-suggestion"><p>{{ turn.result.suggested_clip_instruction }}</p><button class="secondary" :disabled="pending || clipTargetBusy" @click="suggest(turn.result.suggested_clip_instruction)">送到剪辑要求</button><small v-if="clipTargetBusy">请先保存或放弃剪辑区域的未提交内容。</small><small v-else>仅填入要求，你仍需在剪辑区域点击查找、核对并确认。</small></div>
          </template><p v-else class="help">{{ turn.status === 'CANCELLED' ? '本轮已取消，已有对话和视频结果仍保留。' : turn.status === 'FAILED' ? '本轮未生成可用回答。请查看原因，原转写与此前回答仍保留。' : turn.status === 'QUEUED' ? '问题已保存，正在等待执行服务。' : '正在结合视频内容查找证据并回答，进度会自动更新。' }}</p>
        </div></li></ol>
        <div v-if="selected?.turns.length" class="agent-reading-actions"><span>当前视频 · {{ selected.turns.length }} 轮对话</span><button class="text-button" @click="scrollToLatest(true)">定位最新一轮 ↓</button></div>
        <form class="agent-compose" @submit.prevent="send">
          <div class="agent-visual-option"><label><input v-model="visualMode" type="checkbox" true-value="frames" false-value="none" :disabled="pending || visualMode === 'none' && visualCapability?.visual_mode !== 'frames'">结合画面</label><span v-if="visualMode === 'frames'">本轮可按需抽帧；所有模型请求、重试及修复共用下方次数上限。</span><span v-else>默认只用视频转写。</span><button type="button" class="text-button" :disabled="capabilityLoading || pending" @click="loadVisualCapability()">{{ capabilityLoading ? '正在检查…' : '检查画面能力' }}</button><small v-if="capabilityError">{{ capabilityError }}</small><small v-else-if="visualCapability?.visual_mode === null">当前部署未配置视觉模型。</small><small v-else-if="visualCapability?.visual_mode === 'frames'">配置模型 {{ visualCapability.visual_model_id }}；配置可用不代表额度或识别效果已验证。</small></div>
          <div v-if="!selected?.turns.length" class="agent-examples"><button v-for="example in examples" :key="example" type="button" class="secondary" :disabled="pending || dirty" @click="chooseExample(example)">{{ example }}</button></div>
          <div class="agent-input-box"><label for="agent-question" class="visually-hidden">向视频助手提问</label><textarea id="agent-question" v-model="question" :disabled="pending" maxlength="2000" rows="2" placeholder="问问这段视频，例如：概括主要内容，列出关键观点。"></textarea>
          <div class="agent-compose-actions"><details class="agent-options"><summary>回答设置 <span>· {{ maxCalls }} 次上限</span></summary><div class="agent-options-body"><label>本轮模型请求上限<input v-model.number="maxCalls" type="number" min="1" max="24" :disabled="pending" aria-label="对话模型请求上限"></label><p>自动重试计入此上限。{{ visualMode === 'frames' ? '结合原句与实际抽查画面，画面采样不等于连续观看。' : '只使用当前视频转写。' }}剪辑建议需另行审核确认。</p></div></details><span class="agent-character-count">{{ question.length }} / 2000</span><button v-if="dirty" class="text-button" type="button" :disabled="pending" @click="discard">放弃输入</button><button class="primary" type="submit" :disabled="pending || submissionBlocked || !question.trim() || !!activeTurn">{{ action === 'send' ? '正在提交…' : selected ? '发送问题 ↑' : '开始提问 ↑' }}</button></div></div>
          <p v-if="activeTurn" class="help" role="status">正在处理上一轮。你可以先编辑问题，等待回答或取消本轮后发送。</p>
        </form>
      </div>
    </div>
  </section>
</template>

<style scoped>
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
