<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, ApiError, post, type AnalysisJob, type Page, type VideoAsset } from './api'
import ReviewPanel from './ReviewPanel.vue'
import TopicReviewPanel from './TopicReviewPanel.vue'
import IntentClipPanel from './IntentClipPanel.vue'
import VideoAgentPanel from './VideoAgentPanel.vue'
import UploadPanel from './UploadPanel.vue'
import FailureDiagnostic from './FailureDiagnostic.vue'
import TextRequestProgress from './TextRequestProgress.vue'
import ExecutorStatus from './ExecutorStatus.vue'
import { DEFAULT_OCR_MAX_FRAMES, newAnalysisConfig, samplingLabel } from './sampling'
import { SnapshotError, SnapshotStream, isAnalysisTerminal, parseAnalysisSnapshot, streamLabels, type StreamMode } from './snapshotStream'
import { analysisCanResume, analysisResumeReceipt, analysisRetryExplanation, newestAnalysisSnapshot, parseResumedAnalysisSnapshot, type AnalysisResumeReceipt } from './analysisResume'
import { DetailRecovery, isConnectionErrorCode } from './connectionRecovery'
import { workbenchTasks, type TaskListScope } from './workbenchTasks'
import './style.css'
import './workbench.css'

const videos = ref<VideoAsset[]>([]), jobs = ref<AnalysisJob[]>([])
const selectedVideoId = ref(''), selectedJob = ref<AnalysisJob | null>(null)
const pending = ref(''), errorMessage = ref(''), listConnectionError = ref(''), detailConnectionError = ref(''), notice = ref('')
const connectionError = computed(() => detailConnectionError.value || listConnectionError.value)
const online = ref(false), loading = ref(true), query = ref(''), filter = ref('ALL')
const visualProvider = ref<'ocr' | 'none'>('none'), maxTextCalls = ref(24), maxOcrFrames = ref(DEFAULT_OCR_MAX_FRAMES)
const recoveryBudget = ref(12)
const pendingResumes = new Map<string, AnalysisResumeReceipt>()
const uploadPanel = ref<{ chooseFile: () => void } | null>(null)
const reviewEditor = ref<{ dirty: boolean; pending: boolean } | null>(null)
const intentEditor = ref<{ dirty: boolean; pending: boolean; prefillInstruction(value: string): boolean } | null>(null)
const agentEditor = ref<{ dirty: boolean; pending: boolean } | null>(null)
type WorkbenchTab = 'agent' | 'topics' | 'clips' | 'task'
const activeTab = ref<WorkbenchTab>('task'), mobileTasksOpen = ref(false)
const compactLayout = ref(window.matchMedia('(max-width: 1100px)').matches)
const sidebarCollapsed = ref(new URL(location.href).searchParams.has('job'))
const taskListScope = ref<TaskListScope>('recent')
const taskListExpanded = computed(() => compactLayout.value ? mobileTasksOpen.value : !sidebarCollapsed.value)
const compactQuery = window.matchMedia('(max-width: 1100px)')
function updateCompactLayout() {
  const focusInTasks = !!document.activeElement?.closest('#workbench-tasks')
  compactLayout.value = compactQuery.matches; mobileTasksOpen.value = false
  if (focusInTasks && (compactLayout.value || sidebarCollapsed.value)) void nextTick(() => document.getElementById('workbench-task-toggle')?.focus())
}
function toggleTasks() {
  // A bookmarked historical analysis should still be visible when opening its library.
  if (!taskListExpanded.value && selectedJob.value && !query.value.trim() && taskListScope.value === 'recent'
      && !workbenchTasks(jobs.value, 'recent', '', 'ALL', videoName).some(job => job.id === selectedJob.value?.id)) taskListScope.value = 'all'
  if (compactLayout.value) {
    mobileTasksOpen.value = !mobileTasksOpen.value
    if (mobileTasksOpen.value) void nextTick(() => document.getElementById('workbench-task-close')?.focus())
  }
  else sidebarCollapsed.value = !sidebarCollapsed.value
}
function taskDrawerKey(event: KeyboardEvent) {
  if (!compactLayout.value || !mobileTasksOpen.value || event.key !== 'Tab') return
  const elements = [...document.querySelectorAll<HTMLElement>('#workbench-tasks a, #workbench-tasks button:not(:disabled), #workbench-tasks input, #workbench-tasks select')]
    .filter(element => element.getClientRects().length)
  const first = elements[0], last = elements.at(-1)
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
const taskPreview = ref<HTMLVideoElement | null>(null)
const editorsDirty = computed(() => !!reviewEditor.value?.dirty || !!intentEditor.value?.dirty || !!agentEditor.value?.dirty)
const editorsPending = computed(() => !!reviewEditor.value?.pending || !!intentEditor.value?.pending || !!agentEditor.value?.pending)
const canResumeFromAsr = computed(() => analysisCanResume(selectedJob.value, editorsDirty.value, !!pending.value || editorsPending.value))
const streamMode = ref<StreamMode>('idle')
let timer: number | undefined, refreshing = false, stopped = false, selectionVersion = 0
let uploadSelectionVersion = 0
const detailRecovery = new DetailRecovery()
const taskStream = new SnapshotStream<AnalysisJob>({
  kind: 'job', parse: (raw, id) => {
    const snapshot = parseAnalysisSnapshot(raw, id)
    // Reject changed lineage/accounting before the stream can adopt it internally.
    latestSnapshot(snapshot)
    return snapshot
  }, terminal: isAnalysisTerminal,
  fetchSnapshot: (id, signal) => api<unknown>(`/api/jobs/${id}`, { signal }),
  onMode: mode => { streamMode.value = mode },
  onSnapshot: snapshot => {
    if (stopped || selectedJob.value?.id !== snapshot.id) return
    applySelectedSnapshot(snapshot)
  },
})
const names: Record<string, string> = {
  QUEUED: '等待执行', RUNNING: '正在分析', REVIEW_READY: '待人工审核', FAILED: '执行失败',
  CANCELLED: '已取消', READY: '等待领取', BLOCKED: '等待前序', SUCCEEDED: '已完成', RETRY_WAIT: '等待重试', SKIPPED: '已跳过',
}
const stageNames: Record<string, string> = { MEDIA: '媒体探测', ASR: '语音转写', SEGMENT: '商品分段', TIMELINE: '结果校验' }
const stageOrder = ['MEDIA', 'ASR', 'SEGMENT', 'TIMELINE']
const orderedStages = computed(() => [...(selectedJob.value?.stages ?? [])].sort((a, b) => stageOrder.indexOf(a.stage_type) - stageOrder.indexOf(b.stage_type)))
const activeCount = computed(() => jobs.value.filter(j => ['QUEUED', 'RUNNING'].includes(j.status)).length)
const reviewCount = computed(() => jobs.value.filter(j => j.status === 'REVIEW_READY').length)
const visibleJobs = computed(() => workbenchTasks(jobs.value, taskListScope.value, query.value, filter.value, videoName))
const currentVideo = computed(() => videos.value.find(v => v.id === (selectedJob.value?.video_id || selectedVideoId.value)))
const topicMode = computed(() => selectedJob.value?.config?.analysis_mode === 'topics')
const completeCount = computed(() => selectedJob.value?.stages.filter(s => ['SUCCEEDED', 'SKIPPED'].includes(s.status)).length ?? 0)
const retryExplanation = computed(() => analysisRetryExplanation(selectedJob.value))
const resultsAvailable = computed(() => selectedJob.value?.status === 'REVIEW_READY' && !!currentVideo.value)
const topicResultsAvailable = computed(() => resultsAvailable.value && topicMode.value)
watch(() => selectedJob.value?.id, (id, previous) => {
  if (id === previous) return
  activeTab.value = id && selectedJob.value?.status === 'REVIEW_READY' ? topicMode.value ? 'agent' : 'topics' : 'task'
  closeTasks()
})
watch(activeTab, value => { if (value !== 'task') taskPreview.value?.pause() }, { flush: 'sync' })
function setTab(tab: WorkbenchTab) {
  if ((tab === 'agent' || tab === 'clips') && !topicResultsAvailable.value) return
  if (tab === 'topics' && !resultsAvailable.value) return
  activeTab.value = tab
}
function tabKey(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  const tabs: WorkbenchTab[] = topicResultsAvailable.value ? ['agent', 'topics', 'clips', 'task'] : resultsAvailable.value ? ['topics', 'task'] : ['task']
  event.preventDefault()
  const index = tabs.indexOf(activeTab.value)
  const next = event.key === 'Home' ? tabs[0]! : event.key === 'End' ? tabs.at(-1)! : tabs[(index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length]!
  setTab(next)
  void nextTick(() => document.getElementById(`workbench-tab-${next}`)?.focus())
}
function openNewAnalysis() {
  closeTasks()
  selectVideo()
  if (!selectedJob.value) { activeTab.value = 'task'; closeTasks() }
}
function openTask(id: string) {
  closeTasks()
  void loadDetail(id)
}
async function chooseUpload() {
  closeTasks()
  // The file input lives in main, which is inert while the task drawer is open.
  await nextTick()
  uploadPanel.value?.chooseFile()
}
function closeTasks() {
  if (!mobileTasksOpen.value) return
  mobileTasksOpen.value = false
  void nextTick(() => document.getElementById('workbench-task-toggle')?.focus())
}
function latestSnapshot(incoming: AnalysisJob) {
  const candidates = [selectedJob.value, jobs.value.find(job => job.id === incoming.id)]
  // Equal versions remain refreshable: a Java restart may change the current
  // max_attempts configuration without changing this persisted job version.
  return candidates.reduce<AnalysisJob>((latest, known) => newestAnalysisSnapshot(known, latest), incoming)
}
function applySelectedSnapshot(snapshot: AnalysisJob) {
  const latest = latestSnapshot(snapshot)
  const index = jobs.value.findIndex(job => job.id === latest.id)
  if (index >= 0) jobs.value[index] = latest
  // Updating status must never unmount an editor with unsaved or in-flight changes.
  if (selectedJob.value?.id === latest.id && selectedJob.value.status === 'REVIEW_READY'
      && latest.status !== 'REVIEW_READY' && (editorsDirty.value || editorsPending.value)) return
  selectedJob.value = latest; selectedVideoId.value = latest.video_id
}
function adoptHttpSnapshot(snapshot: AnalysisJob) {
  const latest = latestSnapshot(snapshot)
  if (taskStream.resourceId !== latest.id) {
    applySelectedSnapshot(latest); taskStream.select(latest.id, latest)
  } else {
    const accepted = taskStream.acceptHttp(latest)
    if (accepted) applySelectedSnapshot(accepted)
  }
}
function videoName(id: string) { return videos.value.find(v => v.id === id)?.original_filename ?? `视频 ${id.slice(0, 8)}` }
function stageLabel(type: string) {
  if (selectedJob.value?.resumed_from_job_id && type === 'MEDIA') return '复用音频'
  if (selectedJob.value?.resumed_from_job_id && type === 'ASR') return '复用语音转写'
  return type === 'SEGMENT' && topicMode.value ? '话题解析' : stageNames[type] ?? type
}
function date(value: string) { return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }
function formatError(error: unknown) {
  const code = error instanceof ApiError ? error.code : error instanceof SnapshotError ? 'INVALID_SERVER_RESPONSE' : 'CLIENT_ERROR'
  const translated: Record<string, string> = {
    NETWORK_UNAVAILABLE: '无法连接服务，请检查 Java 服务是否正在运行。',
    REQUEST_TIMEOUT: '请求超时。创建任务可再次点击，系统会复用幂等键。',
    ATTEMPTS_EXHAUSTED: '阶段执行次数已耗尽，请创建新的分析任务。新任务使用独立的模型调用预算。',
    TEXT_RECOVERY_EXHAUSTED: '本次文本请求的恢复次数或等待窗口已耗尽。请使用“复用转写重新分析”，设置新的文本请求预算。',
    RESUME_REQUIRES_TOPICS: '只有话题分析任务支持复用转写重新分析。',
    JOB_NOT_RESUMABLE: '仅失败、已取消或已完成话题分析的任务可以复用转写，请刷新状态。',
    PREPARATION_NOT_READY: '原任务的音频或转写尚未完成，暂时无法复用。',
    PREPARATION_SOURCE_INVALID: '原任务的音频或转写来源校验未通过，请检查源文件和已保存产物。',
    JOB_NOT_RETRYABLE: '任务当前不可重试，请刷新状态。',
    JOB_NOT_CANCELLABLE: '任务当前不可取消，请刷新状态。',
    INVALID_SERVER_RESPONSE: '任务返回的数据不完整或不匹配，已保留上次有效状态。',
  }
  const message = translated[code] ?? `操作未完成：${code}`
  return error instanceof ApiError && error.requestId ? `${message}（请求 ${error.requestId}）` : message
}
function showError(error: unknown) { errorMessage.value = formatError(error) }
function connectionFailure(error: unknown) { return error instanceof ApiError && isConnectionErrorCode(error.code) }
function dismissErrors() { errorMessage.value = ''; listConnectionError.value = ''; detailConnectionError.value = '' }
function useAgentClipInstruction(value: string) {
  if (!intentEditor.value?.prefillInstruction(value)) { errorMessage.value = '剪辑区域有未提交内容或正在提交，请先处理后再填入助手建议。'; return }
  activeTab.value = 'clips'
  notice.value = '剪辑要求已填入，请核对后点击“查找片段”。'
}
async function loadDetail(id: string, background = false, allowPending = false) {
  if (stopped || !canSelect(id, allowPending)) return
  const version = background ? selectionVersion : ++selectionVersion
  if (!background) { detailRecovery.cancel(); detailConnectionError.value = '' }
  if (selectedJob.value?.id !== id) taskStream.select(null)
  try {
    const raw = await api<unknown>(`/api/jobs/${encodeURIComponent(id)}`)
    if (stopped || version !== selectionVersion || !canSelect(id, allowPending)) return
    const result = parseAnalysisSnapshot(raw, id)
    adoptHttpSnapshot(result)
    const url = new URL(location.href); url.searchParams.set('job', id); history.replaceState({}, '', url)
    if (!videos.value.some(v => v.id === result.video_id)) {
      const video = await api<VideoAsset>(`/api/videos/${encodeURIComponent(result.video_id)}`)
      if (!stopped && !videos.value.some(v => v.id === video.id)) videos.value.push(video)
    }
    if (stopped || version !== selectionVersion) return
    detailConnectionError.value = ''; detailRecovery.cancel()
  } catch (error) {
    if (version === selectionVersion) {
      if (connectionFailure(error)) {
        detailConnectionError.value = formatError(error)
        if (!background) detailRecovery.queue(id, version)
      } else {
        detailConnectionError.value = ''; detailRecovery.cancel(); showError(error)
      }
    }
  } finally {
    if (!stopped && version === selectionVersion && selectedJob.value && taskStream.resourceId === null) taskStream.select(selectedJob.value.id, selectedJob.value)
  }
}
function canSelect(id: string, allowPending = false) {
  if (pending.value && !allowPending) { errorMessage.value = '请等待当前操作完成，再切换任务。'; return false }
  if (selectedJob.value?.id === id) return true
  if (editorsPending.value) { errorMessage.value = '审核、剪辑方案或对话正在提交，请稍后再切换任务。'; return false }
  if (editorsDirty.value) { errorMessage.value = '审核、剪辑方案或对话有未提交内容，请先保存或在相应区域放弃修改。'; return false }
  return true
}
async function refresh() {
  if (refreshing || stopped) return
  refreshing = true
  const selectedAtStart = selectedJob.value?.id, versionAtStart = selectionVersion
  try {
    const [videoPage, jobPage] = await Promise.all([api<Page<VideoAsset>>('/api/videos?limit=100&offset=0'), api<Page<AnalysisJob>>('/api/jobs?limit=100&offset=0')])
    if (stopped) return
    const selected = currentVideo.value
    videos.value = videoPage.items
    if (selected && !videos.value.some(v => v.id === selected.id)) videos.value.push(selected)
    jobs.value = jobPage.items.map(item => latestSnapshot(parseAnalysisSnapshot(item, item.id))); online.value = true; listConnectionError.value = ''
    if (!selectedVideoId.value && videos.value[0]) selectedVideoId.value = videos.value[0].id
    if (selectedAtStart && selectedJob.value?.id === selectedAtStart && selectionVersion === versionAtStart) {
      const snapshot = jobs.value.find(job => job.id === selectedAtStart)
      if (snapshot) adoptHttpSnapshot(snapshot)
    }
    const recoveryId = detailRecovery.next(selectionVersion, !!pending.value || editorsDirty.value || editorsPending.value)
    if (recoveryId) await loadDetail(recoveryId, true)
  } catch (error) {
    online.value = false
    if (connectionFailure(error)) listConnectionError.value = formatError(error)
    else { listConnectionError.value = ''; showError(error) }
  }
  finally { refreshing = false; loading.value = false }
}
function selectVideo() {
  if (pending.value) {
    selectedVideoId.value = selectedJob.value?.video_id ?? selectedVideoId.value
    errorMessage.value = '请等待当前操作完成，再新建或切换分析。'; return
  }
  if (editorsPending.value) {
    selectedVideoId.value = selectedJob.value?.video_id ?? selectedVideoId.value
    errorMessage.value = '审核、剪辑方案或对话正在提交，请稍后再切换任务。'; return
  }
  if (editorsDirty.value) {
    selectedVideoId.value = selectedJob.value?.video_id ?? selectedVideoId.value
    errorMessage.value = '审核、剪辑方案或对话有未提交内容，请先保存或在相应区域放弃修改。'; return
  }
  detailRecovery.cancel(); detailConnectionError.value = ''
  selectedJob.value = null; selectionVersion++; taskStream.select(null)
  const url = new URL(location.href); url.searchParams.delete('job'); history.replaceState({}, '', url)
}
function canStartUpload() {
  if (pending.value && pending.value !== 'upload') { errorMessage.value = '请等待当前操作完成，再上传视频。'; return false }
  if (editorsPending.value) { errorMessage.value = '请等待审核、剪辑方案或对话提交完成，再上传新视频。'; return false }
  if (editorsDirty.value) { errorMessage.value = '请先保存或放弃审核、剪辑方案修改或对话输入，再上传新视频。'; return false }
  return true
}
function uploadBusy(value: boolean) {
  if (value) pending.value = 'upload'
  else if (pending.value === 'upload') pending.value = ''
}
function uploadStarted() { uploadSelectionVersion = selectionVersion; errorMessage.value = ''; notice.value = '' }
async function uploaded(video: VideoAsset) {
  if (stopped) return
  if (!videos.value.some(item => item.id === video.id)) videos.value.unshift(video)
  // A delayed upload must not replace a task the user opened or edited while it ran.
  if (selectionVersion === uploadSelectionVersion && !editorsDirty.value && !editorsPending.value) {
    selectedVideoId.value = video.id; selectVideo()
    notice.value = '视频已保存，可以创建分析任务。'
  } else notice.value = '视频已保存。当前审核和任务选择保持不变，可在视频来源中选择新视频。'
  await refresh()
}
async function createJob() {
  if (!selectedVideoId.value || pending.value) return
  if (!Number.isInteger(maxTextCalls.value) || maxTextCalls.value < 1 || maxTextCalls.value > 100) { errorMessage.value = '文本请求上限须为 1～100 的整数。'; return }
  let config: ReturnType<typeof newAnalysisConfig>
  try { config = newAnalysisConfig(visualProvider.value, maxTextCalls.value, maxOcrFrames.value) }
  catch (error) { errorMessage.value = error instanceof RangeError ? error.message : '画面取样配置无效。'; return }
  detailRecovery.cancel(); detailConnectionError.value = ''
  const version = ++selectionVersion, videoId = selectedVideoId.value
  pending.value = 'create'; errorMessage.value = ''; notice.value = ''
  const payload = { video_id: videoId, config }
  const inputKey = JSON.stringify(payload)
  try {
    let saved: { input: string; key: string } | null = null
    try { saved = JSON.parse(localStorage.getItem('livecut-pending-create') ?? 'null') } catch { /* discard invalid browser state */ }
    const key = saved?.input === inputKey ? saved.key : crypto.randomUUID()
    localStorage.setItem('livecut-pending-create', JSON.stringify({ input: inputKey, key }))
    const raw = await post<AnalysisJob>('/api/jobs', payload, { 'Idempotency-Key': key })
    if (stopped) return
    const job = parseAnalysisSnapshot(raw, raw.id)
    if (job.video_id !== videoId) throw new ApiError('INVALID_SERVER_RESPONSE')
    localStorage.removeItem('livecut-pending-create')
    if (version === selectionVersion && selectedVideoId.value === videoId && !editorsDirty.value && !editorsPending.value) {
      await loadDetail(job.id, false, true)
      notice.value = '任务已创建。计算 Worker 在线后会自动领取。'
    } else notice.value = '任务已创建。当前选择和审核内容保持不变，可在任务列表中打开。'
    await refresh()
  } catch (error) { showError(error) }
  finally { pending.value = '' }
}
async function resumeFromAsr() {
  const source = selectedJob.value
  if (!source || pending.value) return
  if (editorsPending.value) { errorMessage.value = '审核、剪辑方案或对话正在提交，请稍后再重新分析。'; return }
  if (editorsDirty.value) { errorMessage.value = '审核、剪辑方案或对话有未提交内容，请先保存或在相应区域放弃修改。'; return }
  if (!canResumeFromAsr.value) { errorMessage.value = '当前任务无法复用转写，请刷新任务状态。'; return }
  const budget = recoveryBudget.value
  if (!Number.isInteger(budget) || budget < 1 || budget > 100) { errorMessage.value = '新文本请求上限须为 1～100 的整数。'; return }
  const storageKey = `livecut-analysis-resume-${source.id}-${budget}`
  let saved: unknown = pendingResumes.get(storageKey)
  if (!saved) { try { saved = JSON.parse(localStorage.getItem(storageKey) ?? 'null') } catch { /* Only valid receipts are reused. */ } }
  const receipt = analysisResumeReceipt(saved, source.id, budget, () => crypto.randomUUID())
  pendingResumes.set(storageKey, receipt)
  try { localStorage.setItem(storageKey, JSON.stringify(receipt)) } catch { /* Keep the mounted-view receipt when storage is unavailable. */ }
  detailRecovery.cancel(); detailConnectionError.value = ''
  const version = ++selectionVersion
  pending.value = 'resume'; errorMessage.value = ''; notice.value = ''
  try {
    const raw = await post<unknown>(`/api/jobs/${source.id}/resume-from-asr`, { max_text_calls: budget }, { 'Idempotency-Key': receipt.key })
    if (stopped || version !== selectionVersion || selectedJob.value?.id !== source.id) return
    const job = parseResumedAnalysisSnapshot(raw, source, budget)
    const existing = jobs.value.findIndex(item => item.id === job.id)
    const accepted = newestAnalysisSnapshot(existing >= 0 ? jobs.value[existing] : undefined, job)
    pendingResumes.delete(storageKey)
    try {
      const current = JSON.parse(localStorage.getItem(storageKey) ?? 'null')
      if (current?.key === receipt.key) localStorage.removeItem(storageKey)
    } catch { /* A valid accepted job remains available even without local storage. */ }
    if (existing >= 0) jobs.value[existing] = accepted
    else jobs.value.unshift(accepted)
    // Editors may have changed while the POST was in flight; never discard their drafts.
    if (!editorsDirty.value && !editorsPending.value) {
      adoptHttpSnapshot(accepted)
      const url = new URL(location.href); url.searchParams.set('job', job.id); history.replaceState({}, '', url)
      notice.value = `已创建新分析，文本请求上限 ${budget} 次。将复用原音频与转写，不新增 ASR 请求；原任务与调用记录保留。`
    } else notice.value = '新分析已创建。当前审核或剪辑草稿保持不变，可保存后从任务列表打开新分析。'
    await refresh()
  } catch (error) { if (!stopped && version === selectionVersion && selectedJob.value?.id === source.id) showError(error) }
  finally { if (!stopped && version === selectionVersion) pending.value = '' }
}
async function action(kind: 'cancel' | 'retry') {
  if (!selectedJob.value || pending.value) return
  if (kind === 'retry' && !selectedJob.value.actions?.can_retry) {
    errorMessage.value = ['ATTEMPTS_EXHAUSTED', 'TEXT_RECOVERY_EXHAUSTED'].includes(selectedJob.value.actions?.retry_block_reason ?? '')
      ? retryExplanation.value : '任务当前不可重试，请刷新状态。'
    return
  }
  if (kind === 'cancel' && !selectedJob.value.actions?.can_cancel) {
    errorMessage.value = '任务当前不可取消，请刷新状态。'; return
  }
  const jobId = selectedJob.value.id, version = selectionVersion
  pending.value = kind; errorMessage.value = ''; notice.value = ''
  try {
    const result = await post<AnalysisJob>(`/api/jobs/${jobId}/${kind}`)
    if (stopped || selectedJob.value?.id !== jobId || version !== selectionVersion || result.id !== jobId) return
    adoptHttpSnapshot(parseAnalysisSnapshot(result, jobId))
    notice.value = kind === 'cancel' ? '任务已取消，旧执行结果将被拒绝。' : '已请求重试。'; await refresh()
  } catch (error) { if (!stopped && selectedJob.value?.id === jobId && version === selectionVersion) showError(error) }
  finally { pending.value = '' }
}
onMounted(async () => {
  compactQuery.addEventListener('change', updateCompactLayout)
  const id = new URL(location.href).searchParams.get('job'), initialVersion = selectionVersion
  await refresh()
  if (!stopped && selectionVersion === initialVersion && id && /^[0-9a-f-]{36}$/i.test(id)) await loadDetail(id)
  if (!stopped) timer = window.setInterval(() => { if (!document.hidden) void refresh() }, 15000)
})
onUnmounted(() => { stopped = true; compactQuery.removeEventListener('change', updateCompactLayout); detailRecovery.cancel(); selectionVersion++; window.clearInterval(timer); taskStream.dispose() })
</script>

<template>
  <div class="workbench-shell" :class="{ 'sidebar-collapsed': sidebarCollapsed }" @keydown.esc="closeTasks">
    <button v-if="mobileTasksOpen" class="task-backdrop" aria-label="关闭任务列表" @click="closeTasks"></button>
    <aside id="workbench-tasks" class="workbench-sidebar" :class="{ open: mobileTasksOpen }" :role="compactLayout && mobileTasksOpen ? 'dialog' : undefined" :aria-modal="compactLayout && mobileTasksOpen ? true : undefined" aria-label="视频与分析任务" @keydown="taskDrawerKey">
      <a class="brand" href="/" aria-label="LiveCut AI 首页" @click.prevent="openNewAnalysis"><span class="brand-mark">L<span>✦</span></span><strong>LiveCut<span>AI</span></strong></a>
      <button id="workbench-task-close" class="mobile-sidebar-close" aria-label="收起任务列表" @click="closeTasks">×</button>
      <div class="sidebar-actions"><button class="primary" :disabled="!!pending" @click="chooseUpload">＋ 上传视频</button><button class="secondary" :disabled="!!pending" @click="openNewAnalysis">新建分析</button></div>
      <div class="sidebar-heading"><h2>视频与任务 <span>{{ jobs.length }}</span></h2><button class="text-button" :disabled="loading" aria-label="刷新任务列表" @click="refresh">↻</button></div>
      <div class="sidebar-counts"><span>分析中 {{ activeCount }}</span><span>待审核 {{ reviewCount }}</span></div>
      <div class="task-scope" role="group" aria-label="任务列表范围"><button :aria-pressed="taskListScope === 'recent'" @click="taskListScope = 'recent'">最近视频</button><button :aria-pressed="taskListScope === 'all'" @click="taskListScope = 'all'">全部分析 <span>{{ jobs.length }}</span></button></div>
      <label class="search sidebar-search"><span aria-hidden="true">⌕</span><input id="task-search" v-model="query" placeholder="搜索视频或任务" aria-label="搜索任务"></label>
      <select v-model="filter" class="sidebar-filter" aria-label="筛选任务状态"><option value="ALL">全部状态</option><option value="RUNNING">正在分析</option><option value="QUEUED">等待执行</option><option value="REVIEW_READY">待人工审核</option><option value="FAILED">执行失败</option><option value="CANCELLED">已取消</option></select>
      <p class="task-list-caption">{{ query.trim() ? '搜索覆盖全部历史分析' : taskListScope === 'recent' ? '每个视频只显示最近一次分析' : '保留每一次分析与处理结果' }}</p>
      <div v-if="loading" class="sidebar-empty">正在读取任务…</div>
      <div v-else-if="!visibleJobs.length" class="sidebar-empty">{{ jobs.length ? '没有匹配的任务' : '上传第一段视频，开始整理。' }}</div>
      <div v-else class="task-list" aria-label="分析任务列表"><button v-for="job in visibleJobs" :key="job.id" class="task-row" :class="{ active: selectedJob?.id === job.id }" :aria-current="selectedJob?.id === job.id ? 'true' : undefined" :disabled="!!pending" @click="openTask(job.id)"><span class="video-tile" aria-hidden="true">▷</span><span class="task-info"><strong :title="videoName(job.video_id)">{{ videoName(job.video_id) }}</strong><span>第 {{ job.generation }} 次分析 · {{ date(job.created_at) }}</span><span class="task-row-bottom"><span class="status" :class="job.status.toLowerCase()">{{ names[job.status] ?? job.status }}</span><small>#{{ job.id.slice(0, 8) }}</small></span></span></button></div>
      <div class="sidebar-footer"><span class="small-dot"></span>本地工作区 <span>{{ videos.length }} 个视频素材</span></div>
    </aside>
    <main class="workbench-main" :inert="compactLayout && mobileTasksOpen ? true : undefined">
      <header class="workbench-header">
        <button id="workbench-task-toggle" class="mobile-task-toggle secondary" :aria-label="taskListExpanded ? '收起任务列表' : '打开任务列表'" :title="taskListExpanded ? '收起任务列表' : '打开任务列表'" :aria-expanded="taskListExpanded" aria-controls="workbench-tasks" @click="toggleTasks"><svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16M13 9h4M13 13h4"/></svg><span>任务列表</span></button>
        <span class="header-divider" aria-hidden="true"></span>
        <div class="current-video"><h1 :title="selectedJob ? videoName(selectedJob.video_id) : ''">{{ selectedJob ? videoName(selectedJob.video_id) : '新建视频分析' }}</h1><p v-if="selectedJob">第 {{ selectedJob.generation }} 次分析 <span>·</span> {{ resultsAvailable ? '回看内容，整理成片' : `${completeCount}/${selectedJob.stages.length} 阶段完成` }}</p><p v-else>上传或选择视频，创建一次新的分析。</p></div>
        <div class="header-state"><span v-if="selectedJob" class="status" :class="selectedJob.status.toLowerCase()">{{ resultsAvailable ? '解析完成' : names[selectedJob.status] ?? selectedJob.status }}</span><span class="connection"><i :class="{ online }"></i>{{ online ? '服务已连接' : '服务未连接' }}</span><button class="header-upload secondary" aria-label="上传视频" title="上传视频" :disabled="!!pending" @click="chooseUpload"><span aria-hidden="true">＋</span><span class="header-upload-label"> 上传视频</span></button></div>
      </header>
      <div class="workspace-messages"><div v-if="errorMessage || connectionError" class="message error" role="alert"><span>{{ errorMessage || connectionError }}</span><button aria-label="关闭错误提示" @click="dismissErrors">×</button></div><div v-if="notice" class="message notice" role="status"><span>{{ notice }}</span><button aria-label="关闭操作提示" @click="notice = ''">×</button></div></div>
      <div class="upload-host"><UploadPanel ref="uploadPanel" :can-start="canStartUpload" @busy="uploadBusy" @started="uploadStarted" @complete="uploaded" /></div>
      <div class="workspace-tabs" role="tablist" aria-label="视频工作区" @keydown="tabKey">
        <button id="workbench-tab-agent" role="tab" aria-controls="workbench-pane-agent" :aria-selected="activeTab === 'agent'" :tabindex="activeTab === 'agent' ? 0 : -1" :disabled="!topicResultsAvailable" @click="setTab('agent')">视频对话<span v-if="agentEditor?.dirty" class="draft-dot" aria-label="有未提交输入"></span></button>
        <button id="workbench-tab-topics" role="tab" aria-controls="workbench-pane-topics" :aria-selected="activeTab === 'topics'" :tabindex="activeTab === 'topics' ? 0 : -1" :disabled="!resultsAvailable" @click="setTab('topics')">{{ selectedJob && !topicMode ? '片段审核' : '话题切分' }}<span v-if="reviewEditor?.dirty" class="draft-dot" aria-label="有未保存修改"></span></button>
        <button id="workbench-tab-clips" role="tab" aria-controls="workbench-pane-clips" :aria-selected="activeTab === 'clips'" :tabindex="activeTab === 'clips' ? 0 : -1" :disabled="!topicResultsAvailable" @click="setTab('clips')">按需剪辑<span v-if="intentEditor?.dirty" class="draft-dot" aria-label="有未提交修改"></span></button>
        <button id="workbench-tab-task" role="tab" aria-controls="workbench-pane-task" :aria-selected="activeTab === 'task'" :tabindex="activeTab === 'task' ? 0 : -1" @click="setTab('task')">{{ selectedJob ? '任务详情' : '创建分析' }}</button>
      </div>
      <div class="workbench-content">
        <div id="workbench-pane-agent" v-show="activeTab === 'agent'" class="workbench-pane conversation-pane" role="tabpanel" aria-labelledby="workbench-tab-agent">
          <VideoAgentPanel v-if="selectedJob?.status === 'REVIEW_READY' && currentVideo && topicMode" :key="`agent-${selectedJob.id}`" ref="agentEditor" :active="activeTab === 'agent'" :job-id="selectedJob.id" :video-id="currentVideo.id" :video-hash="currentVideo.content_hash" :video-url="currentVideo.content_url" :clip-target-busy="!!intentEditor?.dirty || !!intentEditor?.pending" @clip-instruction="useAgentClipInstruction" />
        </div>
        <div id="workbench-pane-topics" v-show="activeTab === 'topics'" class="workbench-pane" :class="{ 'topic-workspace-pane': topicMode }" role="tabpanel" aria-labelledby="workbench-tab-topics">
          <TopicReviewPanel v-if="selectedJob?.status === 'REVIEW_READY' && currentVideo && topicMode" :key="selectedJob.id" ref="reviewEditor" :active="activeTab === 'topics'" :job-id="selectedJob.id" :video-url="currentVideo.content_url" />
          <ReviewPanel v-else-if="selectedJob?.status === 'REVIEW_READY' && currentVideo" :key="selectedJob.id" ref="reviewEditor" :active="activeTab === 'topics'" :job-id="selectedJob.id" :video-url="currentVideo.content_url" />
        </div>
        <div id="workbench-pane-clips" v-show="activeTab === 'clips'" class="workbench-pane" role="tabpanel" aria-labelledby="workbench-tab-clips">
          <IntentClipPanel v-if="selectedJob?.status === 'REVIEW_READY' && currentVideo && topicMode" :key="`intent-${selectedJob.id}`" ref="intentEditor" :active="activeTab === 'clips'" :job-id="selectedJob.id" :video-url="currentVideo.content_url" />
        </div>
        <div id="workbench-pane-task" v-show="activeTab === 'task'" class="workbench-pane task-pane" role="tabpanel" aria-labelledby="workbench-tab-task"><div class="settings-grid">
          <section class="panel setup-panel">
            <div class="panel-heading"><h2>{{ selectedJob ? '任务详情' : '新建分析' }}</h2><span class="subtle-number">{{ selectedJob ? streamLabels[streamMode] + ' · #' + selectedJob.id.slice(0, 8) : '01 — 04' }}</span></div>
            <div class="setup-body">
              <div v-if="!videos.length" class="source-upload-empty"><strong>从一段视频开始</strong><p>上传视频后，解析话题、提问或按要求寻找片段。</p><button class="primary" :disabled="!!pending" @click="chooseUpload">上传第一段视频</button></div>
              <label class="field-label" for="source-video">视频来源</label><select id="source-video" v-model="selectedVideoId" :disabled="!!pending" @change="selectVideo"><option value="" disabled>请先上传视频</option><option v-for="video in videos" :key="video.id" :value="video.id">{{ video.original_filename }}</option></select>
              <template v-if="selectedJob">
                <p class="help" aria-label="画面取证策略">{{ samplingLabel(selectedJob.config) }}</p>
                <p v-if="selectedJob.resumed_from_job_id" class="help">本次分析继续自任务 #{{ selectedJob.resumed_from_job_id.slice(0, 8) }}。将复用已完成的音频与转写，不新增 ASR 请求；文本分析与预算独立。</p>
                <div class="detail-status"><span class="status" :class="selectedJob.status.toLowerCase()">{{ names[selectedJob.status] ?? selectedJob.status }}</span><span>{{ completeCount }} / {{ selectedJob.stages.length }} 阶段完成</span></div>
                <ol class="stages"><li v-for="(stage, index) in orderedStages" :key="stage.id" :class="stage.status.toLowerCase()"><span class="step">{{ stage.status === 'SUCCEEDED' ? '✓' : index + 1 }}</span><div><b>{{ stageLabel(stage.stage_type) }}</b><small>{{ names[stage.status] ?? stage.status }}<template v-if="stage.attempt"> · 第 {{ stage.attempt }} 次执行</template></small><TextRequestProgress v-if="stage.stage_type === 'SEGMENT'" :progress="stage.text_progress" :terminal="['SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED'].includes(stage.status)" /><FailureDiagnostic :diagnostic="stage.diagnostic" :error-code="stage.error_code" :attempt="stage.attempt" /></div></li></ol>
                <div class="budget"><span>已预留模型请求</span><b>文本 {{ selectedJob.budget.text_calls_reserved }}/{{ selectedJob.budget.max_text_calls }} <i>·</i> ASR {{ selectedJob.budget.asr_calls_reserved }}/{{ selectedJob.budget.max_asr_submissions }}</b><small>次数上限，不代表人民币费用</small></div>
                <p v-if="selectedJob.status === 'REVIEW_READY'" class="review-note">自动结果已就绪。请前往“话题切分”核对内容与边界，勾选需要切分的片段，再保存并确认。</p>
                <p v-if="selectedJob.status === 'FAILED' && !selectedJob.actions?.can_retry" class="help" role="status">{{ retryExplanation }}</p>
                <p v-else-if="selectedJob.actions?.can_retry" class="help">重试仅恢复失败阶段，每阶段最多执行 {{ selectedJob.actions.max_attempts }} 次；模型请求仍受本任务原预算限制。</p>
                <div v-if="selectedJob.actions?.can_resume_from_asr" class="analysis-recovery">
                  <label class="field-label" for="recovery-text-budget">新文本请求上限</label><div class="number-field"><input id="recovery-text-budget" v-model.number="recoveryBudget" type="number" min="1" max="100" step="1" :disabled="!!pending"><span>次 / 新分析</span></div>
                  <p class="help">将复用已完成的音频与转写，不新增 ASR 请求；使用当前文本模型重新分析，按新上限计数。原任务、审核、导出与调用记录保留。</p>
                  <button class="primary create-button" :disabled="!canResumeFromAsr || !online" @click="resumeFromAsr">{{ pending === 'resume' ? '正在创建新分析…' : '复用转写重新分析' }}</button>
                </div>
                <p v-else-if="selectedJob.actions?.resume_from_asr_block_reason === 'PREPARATION_NOT_READY'" class="help">原任务的音频或转写尚未完成，暂时无法复用转写重新分析。</p>
                <p v-else-if="selectedJob.actions?.resume_from_asr_block_reason === 'PREPARATION_SOURCE_INVALID'" class="help">原任务的音频或转写来源校验未通过，暂时无法复用，请检查源文件与已保存产物。</p>
                <div class="actions"><button v-if="selectedJob.actions?.can_cancel" class="secondary" :disabled="!!pending" @click="action('cancel')">取消任务</button><button v-if="selectedJob.actions?.can_retry" class="secondary" :disabled="!!pending" @click="action('retry')">重试失败阶段</button><button class="text-button" :disabled="!!pending || editorsDirty || editorsPending" @click="selectVideo">新建一次分析 →</button></div>
                <p class="help">“新建一次分析”会从头处理视频，并重新提交 ASR。</p>
              </template>
              <template v-else>
                <p class="help">AI 从转写中识别连续话题，生成标题、摘要与时间区间。解析后由你勾选并调整。</p>
                <label class="field-label" for="visual-provider">画面证据</label><select id="visual-provider" v-model="visualProvider"><option value="ocr">本地 OCR · 识别画面文字</option><option value="none">仅使用语音转写</option></select>
                <template v-if="visualProvider === 'ocr'">
                  <label class="field-label" for="ocr-frame-budget">画面取样上限</label><div class="number-field"><input id="ocr-frame-budget" v-model.number="maxOcrFrames" type="number" min="1" max="120" step="1"><span>帧 / 本次分析</span></div>
                  <p class="help">按视频时长在整段取样，用画面文字辅助语音判断。提高上限会增加本地识别耗时，也可能增加模型输入；取样不代表全程视觉核验。</p>
                </template>
                <label class="field-label" for="text-budget">文本请求上限</label><div class="number-field"><input id="text-budget" v-model.number="maxTextCalls" type="number" min="1" max="100" step="1"><span>次 / 本次分析</span></div><p class="help">每 10 分钟语音窗口通常需要 1 次文本请求，格式修复最多再请求 1 次。短暂服务故障会自动恢复，恢复请求也计入次数。默认上限 24 次；超长视频可提高。ASR 默认最多提交 1 次。</p>
                <button class="primary create-button" :disabled="!selectedVideoId || !!pending || !online" @click="createJob">{{ pending === 'create' ? '正在创建…' : '创建分析任务' }} <span>→</span></button>
                <p class="footnote">任务保存到 MySQL，关闭页面后仍可查看进度。</p>
              </template>
            </div>
          </section>
          <div class="settings-side"><section v-if="currentVideo" class="panel source-panel"><div class="panel-heading"><div><h2>原视频预览</h2><p>{{ currentVideo.original_filename }}</p></div><span class="subtle-number">{{ (currentVideo.size_bytes / 1024 / 1024).toFixed(1) }} MB</span></div><video ref="taskPreview" :key="currentVideo.id" :src="currentVideo.content_url" controls preload="metadata" playsinline @play="activeTab !== 'task' && taskPreview?.pause()"></video><p class="source-caption">分析、原句定位与导出均使用此视频。</p></section><ExecutorStatus :key="selectedJob?.id ?? 'workspace'" :job="selectedJob" /></div>
        </div></div>
      </div>
    </main>
  </div>
</template>
