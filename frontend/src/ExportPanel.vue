<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, ApiError, post, type ExportClip, type ExportJob, type Page } from './api'
import FailureDiagnostic from './FailureDiagnostic.vue'
import { downloadJson } from './download'
import { SnapshotError, SnapshotStream, isExportTerminal, newestSnapshot, parseExportSnapshot, streamLabels, type StreamMode } from './snapshotStream'
import { parseTopicReview, TopicResponseError } from './topics'
import CaptionPanel from './CaptionPanel.vue'
import { CaptionBatchError, prepareCaptionBatch } from './captions'
interface ApprovedReview {
  revision: number; status: string
  products?: { id: string; name: string }[]
  segments: { id: string; start_ms: number; end_ms: number; product_id?: string | null; title?: string; enabled: boolean }[]
}
const props = defineProps<{ jobId: string; confirmedRevisions: number[]; currentRevision: number; dirty: boolean; analysisMode?: 'topics' | 'commerce'; active?: boolean }>()
const topicMode = computed(() => props.analysisMode === 'topics')
const revision = ref<number | ''>(''), approved = ref<ApprovedReview | null>(null)
const exportProduct = ref('')
const selectedIds = ref<string[]>([]), batches = ref<ExportJob[]>([]), selectedBatchId = ref(''), previewId = ref('')
const pending = ref(''), error = ref(''), refreshError = ref(''), notice = ref(''), loadingRevision = ref(false)
const streamMode = ref<StreamMode>('idle')
const previewPlayer = ref<HTMLVideoElement | null>(null)
const captionTarget = ref<{ exportId: string; clipId: string; contentUrl: string } | null>(null)
const captionEditor = ref<{ dirty: boolean; pending: boolean } | null>(null)
const packageBusy = ref(false), packageUrl = ref('')
let packageVersion = 0
defineExpose({ dirty: computed(() => !!captionEditor.value?.dirty), pending: computed(() => !!pending.value || !!captionEditor.value?.pending) })
let timer: number | undefined, stopped = false, refreshing = false, revisionVersion = 0, listVersion = 0, resourceEpoch = 0
const batchStream = new SnapshotStream<ExportJob>({
  kind: 'export', parse: (raw, id) => parseExportSnapshot(raw, id, props.jobId), terminal: isExportTerminal,
  fetchSnapshot: (id, signal) => api<unknown>(`/api/exports/${id}`, { signal }),
  onMode: mode => { streamMode.value = mode },
  onSnapshot: snapshot => {
    if (stopped || snapshot.job_id !== props.jobId || snapshot.id !== selectedBatchId.value) return
    mergeBatch(snapshot)
  },
})
const names: Record<string, string> = { QUEUED: '等待执行', READY: '等待领取', RUNNING: '正在导出', RETRY_WAIT: '等待重试', SUCCEEDED: '已完成', FAILED: '导出失败', PARTIAL_FAILED: '部分失败', CANCELLED: '已取消' }
const candidates = computed(() => approved.value?.segments.filter(s => s.enabled && (topicMode.value || s.product_id)) ?? [])
const productOptions = computed(() => approved.value?.products?.filter(product => candidates.value.some(segment => segment.product_id === product.id)) ?? [])
const visibleCandidates = computed(() => candidates.value.filter(segment => !exportProduct.value || segment.product_id === exportProduct.value))
const currentBatch = computed(() => batches.value.find(batch => batch.id === selectedBatchId.value))
const preview = computed(() => currentBatch.value?.clips.find(clip => clip.id === previewId.value && clip.status === 'SUCCEEDED'))
const completed = computed(() => currentBatch.value?.clips.filter(clip => clip.status === 'SUCCEEDED').length ?? 0)
const selectedDuration = computed(() => candidates.value.filter(s => selectedIds.value.includes(s.id)).reduce((total, s) => total + s.end_ms - s.start_ms, 0))
function time(ms: number) { return `${Math.floor(ms / 60000).toString().padStart(2, '0')}:${(ms % 60000 / 1000).toFixed(3).padStart(6, '0')}` }
function productName(id: string | null | undefined) { return approved.value?.products?.find(p => p.id === id)?.name ?? '未归属' }
function mergeBatch(snapshot: ExportJob) {
  const index = batches.value.findIndex(batch => batch.id === snapshot.id)
  if (index >= 0) batches.value[index] = newestSnapshot(batches.value[index], snapshot)
  else batches.value.unshift(snapshot)
}
function syncBatchSnapshot() {
  if (currentBatch.value && batchStream.resourceId === currentBatch.value.id) batchStream.acceptHttp(currentBatch.value)
}
function errorMessage(value: unknown) {
  const code = value instanceof ApiError ? value.code : value instanceof SnapshotError || value instanceof TopicResponseError ? 'INVALID_SERVER_RESPONSE' : 'CLIENT_ERROR'
  const messages: Record<string, string> = {
    REVIEW_NOT_CONFIRMED: '这个审核版本尚未确认，请先确认后再导出。',
    EXPORT_REQUIRES_CONFIRMATION: '这个审核版本尚未确认，请先确认后再导出。',
    INVALID_EXPORT_SEGMENT: '请选择该确认版本中已启用的有效片段。',
    ATTEMPTS_EXHAUSTED: '该片段已用尽重试次数。请修正输入后创建新的审核版本和导出。',
    EXPORT_CLIP_NOT_RETRYABLE: '该片段当前不能重试，请刷新查看最新状态。',
    NETWORK_UNAVAILABLE: '无法连接服务，已经创建的后台任务会继续保留。',
    REQUEST_TIMEOUT: '请求超时，可以重试；相同的导出输入会复用同一批次。',
    ARTIFACT_NOT_FOUND: '成片文件缺失，文件清单未下载，请检查本地存储。',
    ARTIFACT_HASH_MISMATCH: '成片完整性校验未通过，文件清单未下载，请检查本地存储。',
    EXPORT_CONTENT_INTEGRITY_ERROR: '成片与登记信息不一致，文件清单未下载。',
    EXPORT_SNAPSHOT_INVALID: '导出快照无效，文件清单未下载。',
    INVALID_SERVER_RESPONSE: '导出返回的数据不完整或不匹配，已保留上次有效状态。',
  }
  return messages[code] ?? `导出操作未完成：${code}`
}
function fail(value: unknown) { error.value = errorMessage(value) }
function openCaptions(clip: ExportClip) {
  if (!currentBatch.value || clip.status !== 'SUCCEEDED' || !clip.content_url || pending.value || captionEditor.value?.pending) return
  if (captionEditor.value?.dirty) { error.value = '字幕有未保存修改，请先保存或放弃字幕修改。'; return }
  previewPlayer.value?.pause(); previewId.value = ''
  captionTarget.value = { exportId: currentBatch.value.id, clipId: clip.id, contentUrl: clip.content_url }
}
async function downloadBatchPackage() {
  const batch = currentBatch.value
  if (!batch || packageBusy.value) return
  const op = ++packageVersion, epoch = resourceEpoch
  packageBusy.value = true; packageUrl.value = ''; error.value = ''
  try {
    const prepared = await prepareCaptionBatch(batch, url => api<unknown>(url))
    if (stopped || op !== packageVersion || epoch !== resourceEpoch || currentBatch.value?.id !== batch.id) return
    packageUrl.value = prepared.url
    notice.value = `已核对 ${prepared.items.length} 个成片的确认字幕版本，素材包已准备好。请点击“下载本批素材包”开始下载。`
  } catch (value) {
    if (!stopped && op === packageVersion && epoch === resourceEpoch) {
      if (value instanceof CaptionBatchError) error.value = value.code === 'BATCH_INCOMPLETE' ? `本批有 ${value.clips.length} 个片段尚未完成，不能整批打包。请先处理失败或未完成项。` : `有 ${value.clips.length} 个成片${value.code === 'CAPTIONS_MISSING' ? '尚未校对字幕' : '字幕尚未确认'}：${value.clips.slice(0, 3).join('、')}。请逐片校对并确认后再打包。`
      else fail(value)
    }
  } finally { if (op === packageVersion) packageBusy.value = false }
}
async function loadRevision() {
  const version = ++revisionVersion, jobId = props.jobId
  approved.value = null; exportProduct.value = ''; selectedIds.value = []; loadingRevision.value = true; error.value = ''
  if (revision.value === '') { loadingRevision.value = false; return }
  try {
    const raw = await api<ApprovedReview>(`/api/jobs/${jobId}/${topicMode.value ? 'topic-review' : 'review'}?revision=${revision.value}`)
    if (stopped || version !== revisionVersion || jobId !== props.jobId) return
    const result = topicMode.value ? parseTopicReview(raw, jobId) : raw
    if (result.status !== 'CONFIRMED' || result.revision !== revision.value) throw new ApiError('REVIEW_NOT_CONFIRMED')
    approved.value = result
    selectedIds.value = result.segments.filter(s => s.enabled && (topicMode.value || ('product_id' in s && s.product_id))).map(s => s.id)
  } catch (value) { if (version === revisionVersion) fail(value) }
  finally { if (version === revisionVersion) loadingRevision.value = false }
}
async function refresh() {
  if (refreshing || stopped || pending.value === 'create') return
  refreshing = true
  const version = ++listVersion, jobId = props.jobId
  try {
    const result = await api<Page<ExportJob>>(`/api/exports?job_id=${jobId}&limit=100&offset=0`)
    if (stopped || version !== listVersion || jobId !== props.jobId) return
    const incoming = result.items.map(batch => parseExportSnapshot(batch, batch.id, jobId))
    const selected = currentBatch.value
    refreshError.value = ''
    batches.value = incoming.map(batch => newestSnapshot(batches.value.find(known => known.id === batch.id), batch))
    if (selected && !batches.value.some(batch => batch.id === selected.id)) batches.value.push(selected)
    if (!batches.value.some(batch => batch.id === selectedBatchId.value)) selectedBatchId.value = batches.value[0]?.id ?? ''
    syncBatchSnapshot()
  } catch (value) { if (version === listVersion && !stopped) refreshError.value = errorMessage(value) }
  finally { refreshing = false }
}
async function create() {
  if (pending.value || !approved.value || !selectedIds.value.length) return
  const jobId = props.jobId, epoch = resourceEpoch
  pending.value = 'create'; error.value = ''; notice.value = ''; listVersion++
  try {
    const raw = await post<ExportJob>('/api/exports', { job_id: jobId, approved_revision: approved.value.revision, segment_ids: [...selectedIds.value], profile: 'h264-aac-v1' })
    if (stopped || epoch !== resourceEpoch || jobId !== props.jobId) return
    const result = parseExportSnapshot(raw, raw.id, jobId)
    mergeBatch(result)
    selectedBatchId.value = result.id; previewId.value = ''
    syncBatchSnapshot()
    notice.value = `已打开 r${result.approved_revision} 的导出批次。相同版本、选择和编码配置会复用同一任务。`
  } catch (value) { if (!stopped && epoch === resourceEpoch && jobId === props.jobId) fail(value) }
  finally { if (!stopped && epoch === resourceEpoch) pending.value = '' }
}
async function retry(clip: ExportClip) {
  if (!currentBatch.value || pending.value) return
  const batchId = currentBatch.value.id, jobId = props.jobId, epoch = resourceEpoch
  pending.value = clip.id; error.value = ''; notice.value = ''; listVersion++
  try {
    const raw = await post<unknown>(`/api/exports/${batchId}/clips/${clip.id}/retry`)
    if (stopped || epoch !== resourceEpoch || jobId !== props.jobId || selectedBatchId.value !== batchId) return
    mergeBatch(parseExportSnapshot(raw, batchId, jobId)); syncBatchSnapshot()
    notice.value = '已请求重试这个片段；已完成的片段保持不变。'
    await refresh()
  } catch (value) { if (!stopped && epoch === resourceEpoch && jobId === props.jobId && selectedBatchId.value === batchId) fail(value) }
  finally { if (!stopped && epoch === resourceEpoch) pending.value = '' }
}
async function downloadManifest() {
  if (!currentBatch.value || pending.value) return
  const batchId = currentBatch.value.id, jobId = props.jobId, version = ++listVersion
  pending.value = 'manifest'; error.value = ''; notice.value = ''
  try {
    await downloadJson(`/api/exports/${batchId}/manifest`)
    if (!stopped && jobId === props.jobId && version === listVersion && currentBatch.value?.id === batchId) {
      notice.value = '已请求下载此批次的文件清单 JSON。未完成或失败项会保留实际状态。'
    }
  } catch (value) {
    if (!stopped && jobId === props.jobId && version === listVersion && currentBatch.value?.id === batchId) fail(value)
  } finally {
    if (!stopped && pending.value === 'manifest') pending.value = ''
  }
}
watch(() => props.confirmedRevisions.join(','), () => {
  if (revision.value === '' || !props.confirmedRevisions.includes(revision.value)) revision.value = props.confirmedRevisions.at(-1) ?? ''
}, { immediate: true })
watch(revision, () => { void loadRevision() }, { immediate: true })
watch(exportProduct, () => { selectedIds.value = visibleCandidates.value.map(segment => segment.id) })
watch(selectedBatchId, id => { packageVersion++; packageUrl.value = ''; packageBusy.value = false; batchStream.select(id || null, currentBatch.value) }, { flush: 'sync' })
watch(() => props.jobId, () => {
  resourceEpoch++; revisionVersion++; listVersion++; batchStream.select(null); batches.value = []; selectedBatchId.value = ''; previewId.value = ''; pending.value = ''; error.value = ''; refreshError.value = ''; notice.value = ''
  void loadRevision(); void refresh()
})
onMounted(() => { void refresh(); timer = window.setInterval(() => { if (!document.hidden && !pending.value) void refresh() }, 15000) })
function guardPlayback(event: Event) { if (props.active === false || stopped || captionTarget.value) (event.currentTarget as HTMLVideoElement).pause() }
watch(() => props.active, value => { if (value === false) previewPlayer.value?.pause() })
onBeforeUnmount(() => { stopped = true; previewPlayer.value?.pause(); resourceEpoch++; revisionVersion++; listVersion++; window.clearInterval(timer); batchStream.dispose() })
</script>

<template>
  <section :id="`exports-${jobId}`" class="exports" aria-label="正式导出与下载">
    <div v-if="!batches.length" class="panel-heading"><div><h2>生成与下载成片</h2><p>选择已确认片段，生成独立 MP4。</p></div></div>
    <div v-if="error || refreshError" class="message error" role="alert">{{ error || refreshError }}</div>
    <div v-if="notice" class="message notice" role="status">{{ notice }}</div>
    <button v-if="!approved && !loadingRevision && revision !== ''" class="text-button retry-revision" :disabled="!!pending" @click="loadRevision">重试读取确认版本</button>
    <details class="export-create" :open="!batches.length"><summary>新建导出 <span>选择已确认的片段，生成 MP4</span></summary><div class="export-create-body">
      <div v-if="!confirmedRevisions.length" class="no-approved">先保存并确认一个审核版本，即可选择片段导出。</div>
      <template v-else>
        <div class="export-version"><label>已确认版本<select v-model="revision" aria-label="选择导出的确认版本" :disabled="!!pending || loadingRevision"><option v-for="version in confirmedRevisions" :key="version" :value="version">r{{ version }}{{ version === currentRevision ? ' · 当前版本' : ' · 历史版本' }}</option></select></label><div><b>H.264 / AAC · MP4</b><p>本批使用确认版本，后续编辑不影响已创建的成片。</p></div></div>
        <p v-if="dirty || revision !== currentRevision" class="version-note">本次使用已确认的 r{{ revision }}。当前草稿或未保存修改不参与导出。</p>
        <p v-if="loadingRevision" class="help">正在读取确认快照…</p>
        <fieldset v-else :disabled="!!pending" class="export-selection"><legend>选择片段 · {{ selectedIds.length }} / {{ visibleCandidates.length }}</legend><div class="selection-actions"><label v-if="!topicMode">导出商品 <select v-model="exportProduct" aria-label="选择导出商品"><option value="">全部商品</option><option v-for="product in productOptions" :key="product.id" :value="product.id">{{ product.name }}</option></select></label><button class="text-button" @click="selectedIds = visibleCandidates.map(segment => segment.id)">{{ topicMode ? '全选片段' : '全选当前商品' }}</button><button class="text-button" @click="selectedIds = []">清空选择</button></div><label v-for="segment in visibleCandidates" :key="segment.id"><input v-model="selectedIds" type="checkbox" :value="segment.id" :aria-label="`选择导出片段 ${segment.id}`"><strong>{{ segment.title || productName(segment.product_id) }}</strong><span>{{ time(segment.start_ms) }} → {{ time(segment.end_ms) }}</span></label><p v-if="!candidates.length" class="help">这个确认版本没有可导出的启用片段。</p></fieldset>
        <div class="create-export-row"><small>已选 {{ selectedIds.length }} 段 · 选区合计 {{ (selectedDuration / 1000).toFixed(3) }} 秒</small><button class="primary" :disabled="!!pending || loadingRevision || !approved || !selectedIds.length" @click="create">{{ pending === 'create' ? '正在创建…' : '创建 / 打开导出' }} →</button></div>
      </template>
    </div></details>
    <div class="export-history"><div class="export-history-heading"><h3>导出批次 <span>{{ batches.length }}</span></h3><button class="text-button" :disabled="!!pending" @click="refresh">↻ 刷新</button></div><p v-if="!batches.length" class="help">还没有导出批次。创建后可在这里查看进度和下载。</p><div v-else class="batch-tabs" aria-label="选择导出批次"><button v-for="batch in batches" :key="batch.id" :class="{ active: selectedBatchId === batch.id }" :disabled="!!pending" @click="selectedBatchId = batch.id; previewId = ''">r{{ batch.approved_revision }} · {{ batch.id.slice(0, 8) }}<span class="status" :class="batch.status.toLowerCase()">{{ names[batch.status] ?? batch.status }}</span></button></div>
      <template v-if="currentBatch">
        <div class="batch-summary">
          <div class="batch-summary-title"><strong>确认版本 r{{ currentBatch.approved_revision }}</strong><span class="status" :class="currentBatch.status.toLowerCase()">{{ names[currentBatch.status] ?? currentBatch.status }}</span></div>
          <div class="batch-summary-meta"><span>{{ completed }} / {{ currentBatch.clips.length }} 个成片完成</span><span class="snapshot-mode" role="status">{{ streamLabels[streamMode] }}</span><code>{{ currentBatch.id }}</code></div>
          <div class="batch-tools"><button class="text-button" :disabled="!!pending" @click="downloadManifest">{{ pending === 'manifest' ? '正在下载…' : '下载文件清单 JSON ↓' }}</button><button class="secondary" :disabled="packageBusy || !!pending" @click="downloadBatchPackage">{{ packageBusy ? '正在核对字幕版本…' : '核对并准备素材包' }}</button><a v-if="packageUrl" class="text-button" :href="packageUrl" download target="_blank" rel="noopener">下载本批素材包</a></div>
          <p class="package-note">素材包包含原 MP4 与确认字幕，需本批全部完成并确认字幕。</p>
        </div>
        <div class="export-clips-heading"><h4>成片列表</h4><span>每段可独立预览、校对字幕和下载</span></div>
        <div v-for="clip in currentBatch.clips" :key="clip.id" class="export-clip"><div><b>{{ clip.title || clip.product_name }}</b><p>{{ time(clip.start_ms) }} → {{ time(clip.end_ms) }} <span v-if="clip.attempt">· 第 {{ clip.attempt }} 次执行</span></p><FailureDiagnostic :diagnostic="clip.diagnostic" :error-code="clip.error_code" :attempt="clip.attempt" /></div><span class="status" :class="clip.status.toLowerCase()">{{ names[clip.status] ?? clip.status }}</span><small v-if="clip.size_bytes != null">{{ (clip.size_bytes / 1024 / 1024).toFixed(2) }} MB</small><div class="clip-actions"><template v-if="clip.status === 'SUCCEEDED' && clip.content_url"><button class="secondary" @click="previewId = clip.id">预览</button><button class="secondary" :disabled="!!pending" @click="openCaptions(clip)">校对字幕</button><a v-if="clip.download_url" class="secondary" :href="clip.download_url" target="_blank" rel="noopener">下载 MP4 ↓</a></template><button v-if="clip.status === 'FAILED'" class="secondary" :disabled="!!pending" @click="retry(clip)">重试此片段</button></div></div>
        <div v-if="preview?.content_url" class="export-preview"><div><b>{{ preview.title || preview.product_name }} · 成片预览</b><button class="text-button" @click="previewId = ''">关闭预览</button></div><video ref="previewPlayer" :key="preview.id" :src="preview.content_url" controls preload="metadata" playsinline @play="guardPlayback"></video></div>
      </template>
    </div>
  <CaptionPanel v-if="captionTarget" :key="`${captionTarget.exportId}:${captionTarget.clipId}`" ref="captionEditor" :export-id="captionTarget.exportId" :clip-id="captionTarget.clipId" :content-url="captionTarget.contentUrl" :active="props.active !== false" @close="captionTarget = null" />
  </section>
</template>

<style scoped>
.exports { min-width: 0; border-top: 1px solid #e2e8f0; color: #182230; background: #fff; font-size: 14px; line-height: 1.65; }
.exports > .panel-heading { min-width: 0; padding: 18px 20px 16px; }
.exports > .panel-heading h2, .exports h3, .exports h4 { margin: 0; color: #182230; font-size: 16px; line-height: 1.5; font-weight: 600; }
.exports > .panel-heading p { margin-top: 5px; color: #64748b; font-size: 14px; line-height: 1.65; }
.exports > .message { margin: 0 20px 16px; font-size: 14px; }
.exports .help, .exports .no-approved { margin: 10px 0; color: #64748b; font-size: 14px; line-height: 1.75; }
.exports .text-button { color: #64748b; font-size: 14px; line-height: 1.5; white-space: normal; }
.exports .text-button:hover { color: #c65d37; }
.exports .primary { padding: 10px 16px; background: #e47650; color: #fff; font-size: 14px; line-height: 1.5; }
.exports .primary:hover:not(:disabled) { background: #cf633d; }
.exports .secondary { border: 1px solid #e2e8f0; border-radius: 6px; padding: 8px 12px; color: #182230; background: #fff; font-size: 14px; line-height: 1.5; }
.exports .secondary:hover:not(:disabled) { border-color: #cbd5e1; background: #f8fafc; }
.exports select { min-width: 0; max-width: 100%; min-height: 40px; border-color: #e2e8f0; color: #182230; font-size: 14px; }
.exports .status { flex: none; padding: 4px 8px; border-radius: 5px; color: #475569; background: #f1f5f9; font-size: 14px; line-height: 1.5; }
.exports .status.running, .exports .status.retry_wait { color: #ad512f; background: #fff1e9; }
.exports .status.failed, .exports .status.partial_failed { color: #b54745; background: #fef3f2; }
.retry-revision { margin: 0 20px 16px; }
.export-create { min-width: 0; padding: 0 20px; }
.export-create > summary { padding: 14px 0; cursor: pointer; color: #182230; font-size: 14px; font-weight: 600; }
.export-create > summary > span { margin-left: 10px; color: #64748b; font-size: 13px; font-weight: 400; }
.export-create-body { padding: 4px 0 20px; }
.export-version { display: grid; grid-template-columns: minmax(150px, 220px) minmax(0, 1fr); gap: 20px; align-items: center; }
.export-version label { min-width: 0; color: #182230; font-size: 14px; }
.export-version select { display: block; width: 100%; margin-top: 7px; }
.export-version b { color: #182230; font-size: 14px; font-weight: 600; }
.export-version p { margin-top: 5px; color: #64748b; font-size: 14px; line-height: 1.75; }
.version-note { margin-top: 14px; padding: 10px 12px; border-radius: 6px; color: #9a4f2e; background: #fff7ed; font-size: 14px; line-height: 1.75; }
.export-selection { position: relative; min-width: 0; margin: 18px 0 0; padding: 12px 0 0; border: 0; border-top: 1px solid #e2e8f0; }
.export-selection legend { padding: 0 12px 0 0; color: #182230; font-size: 14px; font-weight: 600; }
.selection-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 18px; margin-bottom: 8px; }
.selection-actions label { min-width: 0; color: #64748b; font-size: 14px; }
.selection-actions select { margin-left: 8px; }
.selection-actions .text-button { min-height: 32px; }
.export-selection > label { display: grid; grid-template-columns: 16px minmax(0, 1fr) auto; align-items: start; gap: 10px; padding: 12px 0; border-top: 1px solid #f1f5f9; }
.export-selection input[type=checkbox] { width: 16px; height: 16px; margin: 4px 0 0; accent-color: #e47650; }
.export-selection strong { min-width: 0; color: #182230; font-size: 14px; font-weight: 500; overflow-wrap: anywhere; }
.export-selection > label > span { color: #64748b; font-size: 14px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.create-export-row { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin-top: 16px; }
.create-export-row small { color: #64748b; font-size: 14px; }
.export-history { min-width: 0; padding: 20px; border-top: 1px solid #e2e8f0; background: #fff; }
.export-history-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.export-history h3 > span { margin-left: 6px; color: #64748b; font-size: 14px; font-weight: 400; }
.batch-tabs { display: flex; max-width: 100%; gap: 8px; padding: 0 0 8px; overflow-x: auto; overscroll-behavior-x: contain; scrollbar-width: thin; }
.batch-tabs > button { display: flex; flex: none; align-items: center; gap: 10px; max-width: 100%; min-height: 42px; border: 1px solid #e2e8f0; border-radius: 6px; padding: 8px 12px; color: #64748b; background: #fff; font-size: 14px; line-height: 1.5; }
.batch-tabs > button.active { border-color: #e47650; color: #a74c2b; background: #fff5ef; }
.batch-summary { min-width: 0; margin: 12px 0 20px; padding: 14px; border-radius: 7px; background: #f8fafc; }
.batch-summary-title { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.batch-summary-title strong { color: #182230; font-size: 16px; font-weight: 600; }
.batch-summary-meta { display: flex; flex-wrap: wrap; gap: 4px 14px; margin-top: 8px; color: #64748b; font-size: 14px; }
.batch-summary-meta code { min-width: 0; color: #64748b; font-size: 14px; overflow-wrap: anywhere; }
.batch-tools { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; margin-top: 12px; }
.batch-tools > * { max-width: 100%; overflow-wrap: anywhere; }
.batch-tools > a { color: #b75732; font-weight: 600; }
.package-note { margin-top: 10px; color: #64748b; font-size: 14px; line-height: 1.7; }
.export-clips-heading { display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px 14px; padding-bottom: 8px; }
.export-clips-heading > span { color: #64748b; font-size: 14px; }
.export-clip { display: grid; grid-template-columns: minmax(0, 1fr) auto auto auto; align-items: center; gap: 10px 14px; border-top: 1px solid #e2e8f0; padding: 16px 0; }
.export-clip > div:first-child { min-width: 0; }
.export-clip b { color: #182230; font-size: 14px; line-height: 1.65; font-weight: 600; overflow-wrap: anywhere; }
.export-clip p { margin-top: 5px; color: #64748b; font-size: 14px; line-height: 1.7; overflow-wrap: anywhere; }
.export-clip > small { color: #64748b; font-size: 14px; white-space: nowrap; }
.clip-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; min-width: 0; }
.clip-actions > * { display: inline-flex; align-items: center; justify-content: center; max-width: 100%; min-height: 38px; }
.clip-actions > a.secondary { border-color: #e47650; background: #e47650; color: #fff; }
.clip-actions > a.secondary:hover { border-color: #cf633d; background: #cf633d; }
.export-clip :deep(.failure-diagnostic) { background: #fff7ed; border-color: #f0b693; color: #64748b; }
.export-clip :deep(.failure-diagnostic p), .export-clip :deep(.failure-details), .export-clip :deep(.failure-title > span), .export-clip :deep(.failure-diagnostic code) { color: #64748b; font-size: 14px; line-height: 1.7; }
.export-preview { min-width: 0; margin-top: 18px; border: 1px solid #e2e8f0; border-radius: 7px; overflow: hidden; }
.export-preview > div { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 14px; padding: 12px 14px; background: #f8fafc; color: #182230; font-size: 14px; }
.export-preview b { min-width: 0; font-size: 16px; font-weight: 600; overflow-wrap: anywhere; }
.export-preview video { display: block; width: 100%; max-height: min(420px, 60dvh); background: #0f172a; object-fit: contain; }
@media (max-width: 850px) {
  .export-clip { grid-template-columns: minmax(0, 1fr) auto auto; }
  .clip-actions { grid-column: 1 / -1; }
}
@media (max-width: 650px) {
  .exports > .panel-heading { padding: 16px 14px; }
  .exports > .message, .retry-revision { margin-inline: 14px; }
  .export-create { padding: 0 14px; }
  .export-create > summary > span { display: block; margin: 3px 0 0 14px; }
  .export-version { grid-template-columns: minmax(0, 1fr); gap: 12px; }
  .export-selection > label { grid-template-columns: 16px minmax(0, 1fr); gap: 4px 10px; }
  .export-selection > label > span { grid-column: 2; white-space: normal; overflow-wrap: anywhere; }
  .export-history { padding: 18px 14px; }
  .export-clip > div:first-child { grid-column: 1 / -1; }
  .export-clip { gap: 8px 12px; }
  .export-clip > .status { justify-self: start; }
  .export-clip > small { grid-column: 2 / -1; justify-self: end; }
  .batch-summary { padding: 12px; }
  .batch-summary-meta code { flex-basis: 100%; }
  .batch-tabs > button { flex-wrap: wrap; }
}
@media (max-width: 420px) {
  .create-export-row { align-items: stretch; flex-direction: column; }
  .create-export-row .primary { width: 100%; }
  .selection-actions label { display: flex; flex-wrap: wrap; gap: 6px; width: 100%; }
  .selection-actions select { width: 100%; margin-left: 0; }
  .clip-actions > * { flex: 1 1 auto; }
}
</style>
