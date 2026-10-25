<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, ApiError, post, type AnalysisJob, type Page, type VideoAsset } from './api'
import ReviewPanel from './ReviewPanel.vue'
import UploadPanel from './UploadPanel.vue'
import { SnapshotError, SnapshotStream, isAnalysisTerminal, newestSnapshot, parseAnalysisSnapshot, streamLabels, type StreamMode } from './snapshotStream'
import './style.css'

const videos = ref<VideoAsset[]>([]), jobs = ref<AnalysisJob[]>([])
const selectedVideoId = ref(''), selectedJob = ref<AnalysisJob | null>(null)
const pending = ref(''), errorMessage = ref(''), connectionError = ref(''), notice = ref('')
const online = ref(false), loading = ref(true), query = ref(''), filter = ref('ALL')
const visualProvider = ref('ocr'), maxTextCalls = ref(12)
const uploadPanel = ref<{ chooseFile: () => void } | null>(null)
const reviewEditor = ref<{ dirty: boolean; pending: boolean } | null>(null)
const streamMode = ref<StreamMode>('idle')
let timer: number | undefined, refreshing = false, stopped = false, selectionVersion = 0
let uploadSelectionVersion = 0
const taskStream = new SnapshotStream<AnalysisJob>({
  kind: 'job', parse: parseAnalysisSnapshot, terminal: isAnalysisTerminal,
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
const visibleJobs = computed(() => jobs.value.filter(j => `${videoName(j.video_id)} ${j.id}`.toLowerCase().includes(query.value.toLowerCase()) && (filter.value === 'ALL' || j.status === filter.value)))
const currentVideo = computed(() => videos.value.find(v => v.id === (selectedJob.value?.video_id || selectedVideoId.value)))
const completeCount = computed(() => selectedJob.value?.stages.filter(s => ['SUCCEEDED', 'SKIPPED'].includes(s.status)).length ?? 0)
const retryExplanation = computed(() => {
  const actions = selectedJob.value?.actions
  if (actions?.retry_block_reason === 'ATTEMPTS_EXHAUSTED') {
    return `失败阶段已达到 ${actions.max_attempts} 次执行上限。可新建一次分析，新任务使用独立的模型调用预算。`
  }
  return '当前没有可重试的失败阶段，请刷新任务状态，或新建一次分析。'
})
function latestSnapshot(incoming: AnalysisJob) {
  const candidates = [selectedJob.value, jobs.value.find(job => job.id === incoming.id)]
  // Equal versions remain refreshable: a Java restart may change the current
  // max_attempts configuration without changing this persisted job version.
  return candidates.reduce<AnalysisJob>((latest, known) => newestSnapshot(known, latest), incoming)
}
function applySelectedSnapshot(snapshot: AnalysisJob) {
  const latest = latestSnapshot(snapshot)
  const index = jobs.value.findIndex(job => job.id === latest.id)
  if (index >= 0) jobs.value[index] = latest
  // Updating status must never unmount an editor with unsaved or in-flight changes.
  if (selectedJob.value?.id === latest.id && selectedJob.value.status === 'REVIEW_READY'
      && latest.status !== 'REVIEW_READY' && (reviewEditor.value?.dirty || reviewEditor.value?.pending)) return
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
function date(value: string) { return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }
function formatError(error: unknown) {
  const code = error instanceof ApiError ? error.code : error instanceof SnapshotError ? 'INVALID_SERVER_RESPONSE' : 'CLIENT_ERROR'
  const translated: Record<string, string> = {
    NETWORK_UNAVAILABLE: '无法连接服务，请检查 Java 服务是否正在运行。',
    REQUEST_TIMEOUT: '请求超时。创建任务可再次点击，系统会复用幂等键。',
    ATTEMPTS_EXHAUSTED: '阶段执行次数已耗尽，请创建新的分析任务。新任务使用独立的模型调用预算。',
    JOB_NOT_RETRYABLE: '任务当前不可重试，请刷新状态。',
    JOB_NOT_CANCELLABLE: '任务当前不可取消，请刷新状态。',
    INVALID_SERVER_RESPONSE: '任务返回的数据不完整或不匹配，已保留上次有效状态。',
  }
  const message = translated[code] ?? `操作未完成：${code}`
  return error instanceof ApiError && error.requestId ? `${message}（请求 ${error.requestId}）` : message
}
function showError(error: unknown) { errorMessage.value = formatError(error) }
async function loadDetail(id: string, background = false) {
  if (stopped || !canSelect(id)) return
  const version = ++selectionVersion
  if (selectedJob.value?.id !== id) taskStream.select(null)
  try {
    const raw = await api<unknown>(`/api/jobs/${encodeURIComponent(id)}`)
    if (stopped || version !== selectionVersion || !canSelect(id)) return
    const result = parseAnalysisSnapshot(raw, id)
    adoptHttpSnapshot(result)
    const url = new URL(location.href); url.searchParams.set('job', id); history.replaceState({}, '', url)
    if (!videos.value.some(v => v.id === result.video_id)) {
      const video = await api<VideoAsset>(`/api/videos/${encodeURIComponent(result.video_id)}`)
      if (!stopped && !videos.value.some(v => v.id === video.id)) videos.value.push(video)
    }
  } catch (error) {
    if (version === selectionVersion) {
      if (background) connectionError.value = formatError(error)
      else showError(error)
    }
  } finally {
    if (!stopped && version === selectionVersion && selectedJob.value && taskStream.resourceId === null) taskStream.select(selectedJob.value.id, selectedJob.value)
  }
}
function canSelect(id: string) {
  if (selectedJob.value?.id === id) return true
  if (reviewEditor.value?.pending) { errorMessage.value = '审核正在提交，请稍后再切换任务。'; return false }
  if (reviewEditor.value?.dirty) { errorMessage.value = '审核有未保存修改，请先保存或在审核区放弃修改。'; return false }
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
    jobs.value = jobPage.items.map(item => latestSnapshot(parseAnalysisSnapshot(item, item.id))); online.value = true; connectionError.value = ''
    if (!selectedVideoId.value && videos.value[0]) selectedVideoId.value = videos.value[0].id
    if (selectedAtStart && selectedJob.value?.id === selectedAtStart && selectionVersion === versionAtStart) {
      const snapshot = jobs.value.find(job => job.id === selectedAtStart)
      if (snapshot) adoptHttpSnapshot(snapshot)
    }
  } catch (error) { online.value = false; connectionError.value = formatError(error) }
  finally { refreshing = false; loading.value = false }
}
function selectVideo() {
  if (reviewEditor.value?.pending) {
    selectedVideoId.value = selectedJob.value?.video_id ?? selectedVideoId.value
    errorMessage.value = '审核正在提交，请稍后再切换任务。'; return
  }
  if (reviewEditor.value?.dirty) {
    selectedVideoId.value = selectedJob.value?.video_id ?? selectedVideoId.value
    errorMessage.value = '审核有未保存修改，请先保存或在审核区放弃修改。'; return
  }
  selectedJob.value = null; selectionVersion++; taskStream.select(null)
  const url = new URL(location.href); url.searchParams.delete('job'); history.replaceState({}, '', url)
}
function canStartUpload() {
  if (pending.value && pending.value !== 'upload') { errorMessage.value = '请等待当前操作完成，再上传视频。'; return false }
  if (reviewEditor.value?.pending) { errorMessage.value = '请等待审核提交完成，再上传新视频。'; return false }
  if (reviewEditor.value?.dirty) { errorMessage.value = '请先保存审核修改，再上传新视频。'; return false }
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
  if (selectionVersion === uploadSelectionVersion && !reviewEditor.value?.dirty && !reviewEditor.value?.pending) {
    selectedVideoId.value = video.id; selectVideo()
    notice.value = '视频已保存，可以创建分析任务。'
  } else notice.value = '视频已保存。当前审核和任务选择保持不变，可在视频来源中选择新视频。'
  await refresh()
}
async function createJob() {
  if (!selectedVideoId.value || pending.value) return
  if (!Number.isInteger(maxTextCalls.value) || maxTextCalls.value < 1 || maxTextCalls.value > 100) { errorMessage.value = '文本请求上限须为 1～100 的整数。'; return }
  const version = selectionVersion, videoId = selectedVideoId.value
  pending.value = 'create'; errorMessage.value = ''; notice.value = ''
  const payload = { video_id: videoId, config: { visual_provider: visualProvider.value, max_text_calls: maxTextCalls.value } }
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
    if (version === selectionVersion && selectedVideoId.value === videoId && !reviewEditor.value?.dirty && !reviewEditor.value?.pending) {
      await loadDetail(job.id)
      notice.value = '任务已创建。计算 Worker 在线后会自动领取。'
    } else notice.value = '任务已创建。当前选择和审核内容保持不变，可在任务列表中打开。'
    await refresh()
  } catch (error) { showError(error) }
  finally { pending.value = '' }
}
async function action(kind: 'cancel' | 'retry') {
  if (!selectedJob.value || pending.value) return
  if (kind === 'retry' && !selectedJob.value.actions?.can_retry) {
    errorMessage.value = selectedJob.value.actions?.retry_block_reason === 'ATTEMPTS_EXHAUSTED'
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
  await refresh()
  const id = new URL(location.href).searchParams.get('job')
  if (id && /^[0-9a-f-]{36}$/i.test(id)) await loadDetail(id)
  if (!stopped) timer = window.setInterval(() => { if (!document.hidden) void refresh() }, 15000)
})
onUnmounted(() => { stopped = true; selectionVersion++; window.clearInterval(timer); taskStream.dispose() })
</script>

<template>
  <div class="workspace">
    <aside class="rail">
      <a class="brand" href="/" aria-label="LiveCut AI 首页"><span class="brand-mark">L<span>✦</span></span><strong>LiveCut<span>AI</span></strong></a>
      <div class="rail-label">创作工作区</div>
      <a class="nav-item selected" href="#tasks"><span>▦</span> 视频与任务 <b>{{ jobs.length }}</b></a>
      <div class="rail-note"><span class="small-dot"></span> 本地开发环境<p>ASR · 本地 OCR · 文本模型</p></div>
      <div class="rail-bottom">直播内容，按商品整理。<br><span>LiveCut AI / 开发预览</span></div>
    </aside>
    <main>
      <header class="topbar"><span>工作区 <span class="slash">/</span> 视频与任务</span><span class="connection"><i :class="{ online }"></i>{{ online ? '服务已连接' : '服务未连接' }}</span></header>
      <div class="page">
        <section class="page-title"><div><div class="eyebrow">VIDEO WORKSPACE</div><h1>把直播，整理成商品片段<span>。</span></h1><p>上传录像，自动定位讲解区间，再由你完成审核。</p></div><button class="primary upload-button" :disabled="!!pending" @click="uploadPanel?.chooseFile()"><span>＋</span>{{ pending === 'upload' ? '正在上传…' : '上传视频' }}</button></section>
        <div v-if="errorMessage || connectionError" class="message error" role="alert"><span>{{ errorMessage || connectionError }}</span><button aria-label="关闭错误提示" @click="errorMessage = ''; connectionError = ''">×</button></div>
        <div v-if="notice" class="message notice" role="status"><span>{{ notice }}</span><button aria-label="关闭操作提示" @click="notice = ''">×</button></div>
        <UploadPanel ref="uploadPanel" :can-start="canStartUpload" @busy="uploadBusy" @started="uploadStarted" @complete="uploaded" />
        <section class="overview" aria-label="当前加载任务统计">
          <div><span>视频素材</span><strong>{{ videos.length.toString().padStart(2, '0') }}</strong><small>本地来源已留档</small></div>
          <div><span>分析进行中</span><strong>{{ activeCount.toString().padStart(2, '0') }}<i class="orange-dot"></i></strong><small>等待执行或正在处理</small></div>
          <div><span>待人工审核</span><strong>{{ reviewCount.toString().padStart(2, '0') }}</strong><small>自动结果尚未确认</small></div>
          <div class="workflow-note"><span class="workflow-icon">↗</span><p><b>先定位，再审核</b><br>自动识别保留为草稿<br>正式导出由审核版本决定</p></div>
        </section>
        <div class="content-grid">
          <section id="tasks" class="panel tasks-panel">
            <div class="panel-heading"><h2>分析任务 <span>{{ jobs.length }}</span></h2><button class="text-button" :disabled="loading" @click="refresh">↻ 刷新</button></div>
            <div class="toolbar"><label class="search"><span>⌕</span><input v-model="query" placeholder="搜索视频名称或任务 ID" aria-label="搜索任务"></label><select v-model="filter" aria-label="筛选任务状态"><option value="ALL">全部状态</option><option value="RUNNING">正在分析</option><option value="QUEUED">等待执行</option><option value="REVIEW_READY">待人工审核</option><option value="FAILED">执行失败</option><option value="CANCELLED">已取消</option></select></div>
            <div v-if="loading" class="empty">正在读取本地任务…</div>
            <div v-else-if="!visibleJobs.length" class="empty"><div>▤</div><h3>{{ jobs.length ? '没有匹配的任务' : '从第一段直播开始' }}</h3><p>{{ jobs.length ? '尝试其他名称或状态。' : '上传 MP4 视频，然后在右侧创建分析任务。' }}</p></div>
            <div v-else class="task-list">
              <button v-for="job in visibleJobs" :key="job.id" class="task-row" :class="{ active: selectedJob?.id === job.id }" @click="loadDetail(job.id)"><span class="video-tile">▷</span><span class="task-info"><strong>{{ videoName(job.video_id) }}</strong><span>第 {{ job.generation }} 次分析 <i>·</i> {{ date(job.created_at) }}</span><small>{{ job.id.slice(0, 8) }}</small></span><span class="status" :class="job.status.toLowerCase()">{{ names[job.status] ?? job.status }}</span><span class="row-arrow">›</span></button>
            </div>
            <footer class="list-footer">显示最近 {{ jobs.length }} 个任务（最多 100 个）<span>定时刷新任务列表</span></footer>
          </section>
          <section class="panel setup-panel">
            <div class="panel-heading"><h2>{{ selectedJob ? '任务详情' : '新建分析' }}</h2><span class="subtle-number">{{ selectedJob ? streamLabels[streamMode] + ' · #' + selectedJob.id.slice(0, 8) : '01 — 04' }}</span></div>
            <div class="setup-body">
              <label class="field-label" for="source-video">视频来源</label><select id="source-video" v-model="selectedVideoId" :disabled="!!pending" @change="selectVideo"><option value="" disabled>请先上传视频</option><option v-for="video in videos" :key="video.id" :value="video.id">{{ video.original_filename }}</option></select>
              <template v-if="selectedJob">
                <div class="detail-status"><span class="status" :class="selectedJob.status.toLowerCase()">{{ names[selectedJob.status] ?? selectedJob.status }}</span><span>{{ completeCount }} / {{ selectedJob.stages.length }} 阶段完成</span></div>
                <ol class="stages"><li v-for="(stage, index) in orderedStages" :key="stage.id" :class="stage.status.toLowerCase()"><span class="step">{{ stage.status === 'SUCCEEDED' ? '✓' : index + 1 }}</span><div><b>{{ stageNames[stage.stage_type] ?? stage.stage_type }}</b><small>{{ names[stage.status] ?? stage.status }}<template v-if="stage.attempt"> · 第 {{ stage.attempt }} 次执行</template></small><code v-if="stage.error_code">{{ stage.error_code }}</code></div></li></ol>
                <div class="budget"><span>已预留模型请求</span><b>文本 {{ selectedJob.budget.text_calls_reserved }}/{{ selectedJob.budget.max_text_calls }} <i>·</i> ASR {{ selectedJob.budget.asr_calls_reserved }}/{{ selectedJob.budget.max_asr_submissions }}</b><small>次数上限，不代表人民币费用</small></div>
                <p v-if="selectedJob.status === 'REVIEW_READY'" class="review-note">自动结果已就绪。请在下方审核区核对商品和边界，再确认修订版本。</p>
                <p v-if="selectedJob.status === 'FAILED' && !selectedJob.actions?.can_retry" class="help" role="status">{{ retryExplanation }}</p>
                <p v-else-if="selectedJob.actions?.can_retry" class="help">重试仅恢复失败阶段，每阶段最多执行 {{ selectedJob.actions.max_attempts }} 次；模型请求仍受本任务原预算限制。</p>
                <div class="actions"><button v-if="selectedJob.actions?.can_cancel" class="secondary" :disabled="!!pending" @click="action('cancel')">取消任务</button><button v-if="selectedJob.actions?.can_retry" class="secondary" :disabled="!!pending" @click="action('retry')">重试失败阶段</button><button class="text-button" :disabled="!!pending" @click="selectVideo">新建一次分析 →</button></div>
              </template>
              <template v-else>
                <p class="help">无需商品清单。模型从语音和画面文字中发现商品。</p>
                <label class="field-label" for="visual-provider">画面证据</label><select id="visual-provider" v-model="visualProvider"><option value="ocr">本地 OCR · 识别画面文字</option><option value="none">仅使用语音转写</option></select>
                <label class="field-label" for="text-budget">文本请求上限</label><div class="number-field"><input id="text-budget" v-model.number="maxTextCalls" type="number" min="1" max="100" step="1"><span>次 / 本次分析</span></div><p class="help">10 分钟视频至少需要约 8 次文本请求，默认 12 次。更长视频需相应提高。ASR 默认最多提交 1 次。</p>
                <button class="primary create-button" :disabled="!selectedVideoId || !!pending || !online" @click="createJob">{{ pending === 'create' ? '正在创建…' : '创建分析任务' }} <span>→</span></button>
                <p class="footnote">任务保存到 MySQL，关闭页面后仍可查看进度。</p>
              </template>
            </div>
          </section>
        </div>
        <ReviewPanel v-if="selectedJob?.status === 'REVIEW_READY' && currentVideo" :key="selectedJob.id" ref="reviewEditor" :job-id="selectedJob.id" :video-url="currentVideo.content_url" />
        <section v-else-if="currentVideo" class="panel source-panel"><div class="panel-heading"><div><h2>原视频预览</h2><p>{{ currentVideo.original_filename }}</p></div><span class="subtle-number">{{ (currentVideo.size_bytes / 1024 / 1024).toFixed(1) }} MB</span></div><div class="source-layout"><video :key="currentVideo.id" :src="currentVideo.content_url" controls preload="metadata" playsinline></video><div class="source-description"><span class="eyebrow">SOURCE VIDEO</span><h3>所有片段，共用一条时间轴。</h3><p>播放器支持按需读取原视频。ASR 时间戳映射到原视频毫秒，后续审核和切片都以此为准。</p><dl><dt>来源状态</dt><dd>{{ currentVideo.media_validation_status }}</dd><dt>文件 SHA256</dt><dd class="hash">{{ currentVideo.content_hash }}</dd></dl><small>上传时仅检查基本文件头，完整媒体探测由 Worker 执行。</small></div></div></section>
        <footer class="page-footer"><span>LiveCut AI</span> 自动预测需要人工复核 · 真实素材仅保存在本地</footer>
      </div>
    </main>
  </div>
</template>
