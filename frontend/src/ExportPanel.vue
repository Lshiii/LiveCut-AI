<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, ApiError, post, type ExportClip, type ExportJob, type Page } from './api'
import { downloadJson } from './download'
import { SnapshotError, SnapshotStream, isExportTerminal, newestSnapshot, parseExportSnapshot, streamLabels, type StreamMode } from './snapshotStream'
interface ApprovedReview {
  revision: number; status: string
  products: { id: string; name: string }[]
  segments: { id: string; start_ms: number; end_ms: number; product_id: string | null; enabled: boolean }[]
}
const props = defineProps<{ jobId: string; confirmedRevisions: number[]; currentRevision: number; dirty: boolean }>()
const revision = ref<number | ''>(''), approved = ref<ApprovedReview | null>(null)
const exportProduct = ref('')
const selectedIds = ref<string[]>([]), batches = ref<ExportJob[]>([]), selectedBatchId = ref(''), previewId = ref('')
const pending = ref(''), error = ref(''), refreshError = ref(''), notice = ref(''), loadingRevision = ref(false)
const streamMode = ref<StreamMode>('idle')
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
const candidates = computed(() => approved.value?.segments.filter(s => s.enabled && s.product_id) ?? [])
const productOptions = computed(() => approved.value?.products.filter(product => candidates.value.some(segment => segment.product_id === product.id)) ?? [])
const visibleCandidates = computed(() => candidates.value.filter(segment => !exportProduct.value || segment.product_id === exportProduct.value))
const currentBatch = computed(() => batches.value.find(batch => batch.id === selectedBatchId.value))
const preview = computed(() => currentBatch.value?.clips.find(clip => clip.id === previewId.value && clip.status === 'SUCCEEDED'))
const completed = computed(() => currentBatch.value?.clips.filter(clip => clip.status === 'SUCCEEDED').length ?? 0)
const selectedDuration = computed(() => candidates.value.filter(s => selectedIds.value.includes(s.id)).reduce((total, s) => total + s.end_ms - s.start_ms, 0))
function time(ms: number) { return `${Math.floor(ms / 60000).toString().padStart(2, '0')}:${(ms % 60000 / 1000).toFixed(3).padStart(6, '0')}` }
function productName(id: string | null) { return approved.value?.products.find(p => p.id === id)?.name ?? '未归属' }
function mergeBatch(snapshot: ExportJob) {
  const index = batches.value.findIndex(batch => batch.id === snapshot.id)
  if (index >= 0) batches.value[index] = newestSnapshot(batches.value[index], snapshot)
  else batches.value.unshift(snapshot)
}
function syncBatchSnapshot() {
  if (currentBatch.value && batchStream.resourceId === currentBatch.value.id) batchStream.acceptHttp(currentBatch.value)
}
function errorMessage(value: unknown) {
  const code = value instanceof ApiError ? value.code : value instanceof SnapshotError ? 'INVALID_SERVER_RESPONSE' : 'CLIENT_ERROR'
  const messages: Record<string, string> = {
    REVIEW_NOT_CONFIRMED: '这个审核版本尚未确认，请先确认后再导出。',
    EXPORT_REQUIRES_CONFIRMATION: '这个审核版本尚未确认，请先确认后再导出。',
    INVALID_EXPORT_SEGMENT: '请选择该确认版本中已启用、已归属商品的片段。',
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
async function loadRevision() {
  const version = ++revisionVersion, jobId = props.jobId
  approved.value = null; exportProduct.value = ''; selectedIds.value = []; loadingRevision.value = true; error.value = ''
  if (revision.value === '') { loadingRevision.value = false; return }
  try {
    const result = await api<ApprovedReview>(`/api/jobs/${jobId}/review?revision=${revision.value}`)
    if (stopped || version !== revisionVersion || jobId !== props.jobId) return
    if (result.status !== 'CONFIRMED' || result.revision !== revision.value) throw new ApiError('REVIEW_NOT_CONFIRMED')
    approved.value = result
    selectedIds.value = result.segments.filter(s => s.enabled && s.product_id).map(s => s.id)
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
watch(selectedBatchId, id => { batchStream.select(id || null, currentBatch.value) }, { flush: 'sync' })
watch(() => props.jobId, () => {
  resourceEpoch++; revisionVersion++; listVersion++; batchStream.select(null); batches.value = []; selectedBatchId.value = ''; previewId.value = ''; pending.value = ''; error.value = ''; refreshError.value = ''; notice.value = ''
  void loadRevision(); void refresh()
})
onMounted(() => { void refresh(); timer = window.setInterval(() => { if (!document.hidden && !pending.value) void refresh() }, 15000) })
onBeforeUnmount(() => { stopped = true; resourceEpoch++; revisionVersion++; listVersion++; window.clearInterval(timer); batchStream.dispose() })
</script>

<template>
  <section :id="`exports-${jobId}`" class="exports" aria-label="正式导出与下载">
    <div class="panel-heading"><div><div class="eyebrow">CONFIRMED VERSION → MP4</div><h2>正式导出与下载</h2></div><span class="subtle-number">每个片段一个 MP4</span></div>
    <div v-if="error || refreshError" class="message error" role="alert">{{ error || refreshError }}</div>
    <div v-if="notice" class="message notice" role="status">{{ notice }}</div>
    <button v-if="!approved && !loadingRevision && revision !== ''" class="text-button retry-revision" :disabled="!!pending" @click="loadRevision">重试读取确认版本</button>
    <div class="export-create">
      <div v-if="!confirmedRevisions.length" class="no-approved">先保存并确认一个审核版本，即可选择片段导出。</div>
      <template v-else>
        <div class="export-version"><label>已确认版本<select v-model="revision" aria-label="选择导出的确认版本" :disabled="!!pending || loadingRevision"><option v-for="version in confirmedRevisions" :key="version" :value="version">r{{ version }}{{ version === currentRevision ? ' · 当前版本' : ' · 历史版本' }}</option></select></label><div><b>H.264 / AAC · MP4</b><p>创建时冻结来源、时间区间和编码配置，后续编辑不会改变这个批次。</p></div></div>
        <p v-if="dirty || revision !== currentRevision" class="version-note">本次使用已确认的 r{{ revision }}。当前草稿或未保存修改不参与导出。</p>
        <p v-if="loadingRevision" class="help">正在读取确认快照…</p>
        <fieldset v-else :disabled="!!pending" class="export-selection"><legend>选择片段 · {{ selectedIds.length }} / {{ visibleCandidates.length }}</legend><div class="selection-actions"><label>导出商品 <select v-model="exportProduct" aria-label="选择导出商品"><option value="">全部商品</option><option v-for="product in productOptions" :key="product.id" :value="product.id">{{ product.name }}</option></select></label><button class="text-button" @click="selectedIds = visibleCandidates.map(segment => segment.id)">全选当前商品</button><button class="text-button" @click="selectedIds = []">清空选择</button></div><label v-for="segment in visibleCandidates" :key="segment.id"><input v-model="selectedIds" type="checkbox" :value="segment.id" :aria-label="`选择导出片段 ${segment.id}`"><strong>{{ productName(segment.product_id) }}</strong><span>{{ time(segment.start_ms) }} → {{ time(segment.end_ms) }}</span></label><p v-if="!candidates.length" class="help">这个确认版本没有可导出的启用片段。</p></fieldset>
        <div class="create-export-row"><small>已选 {{ selectedIds.length }} 段 · 选区合计 {{ (selectedDuration / 1000).toFixed(3) }} 秒</small><button class="primary" :disabled="!!pending || loadingRevision || !approved || !selectedIds.length" @click="create">{{ pending === 'create' ? '正在创建…' : '创建 / 打开导出' }} →</button></div>
      </template>
    </div>
    <div class="export-history"><div class="export-history-heading"><h3>导出批次 <span>{{ batches.length }}</span></h3><button class="text-button" :disabled="!!pending" @click="refresh">↻ 刷新</button></div><p v-if="!batches.length" class="help">还没有导出批次。任务创建后会保存到后台，关闭页面不影响执行。</p><div v-else class="batch-tabs"><button v-for="batch in batches" :key="batch.id" :class="{ active: selectedBatchId === batch.id }" :disabled="!!pending" @click="selectedBatchId = batch.id; previewId = ''">r{{ batch.approved_revision }} · {{ batch.id.slice(0, 8) }}<span class="status" :class="batch.status.toLowerCase()">{{ names[batch.status] ?? batch.status }}</span></button></div>
      <template v-if="currentBatch"><div class="batch-summary"><span>冻结版本 r{{ currentBatch.approved_revision }} · {{ completed }} / {{ currentBatch.clips.length }} 完成</span><span class="snapshot-mode" role="status">{{ streamLabels[streamMode] }}</span><code>{{ currentBatch.id }}</code><button class="text-button" :disabled="!!pending" @click="downloadManifest">{{ pending === 'manifest' ? '正在下载…' : '下载文件清单 JSON ↓' }}</button></div><div v-for="clip in currentBatch.clips" :key="clip.id" class="export-clip"><div><b>{{ clip.product_name }}</b><p>{{ time(clip.start_ms) }} → {{ time(clip.end_ms) }} <span v-if="clip.attempt">· 第 {{ clip.attempt }} 次执行</span></p><code v-if="clip.error_code">{{ clip.error_code }}</code></div><span class="status" :class="clip.status.toLowerCase()">{{ names[clip.status] ?? clip.status }}</span><small v-if="clip.size_bytes != null">{{ (clip.size_bytes / 1024 / 1024).toFixed(2) }} MB</small><div class="clip-actions"><template v-if="clip.status === 'SUCCEEDED' && clip.content_url"><button class="secondary" @click="previewId = clip.id">预览</button><a v-if="clip.download_url" class="secondary" :href="clip.download_url">下载 MP4 ↓</a></template><button v-if="clip.status === 'FAILED'" class="secondary" :disabled="!!pending" @click="retry(clip)">重试此片段</button></div></div>
        <div v-if="preview?.content_url" class="export-preview"><div><b>{{ preview.product_name }} · 成片预览</b><button class="text-button" @click="previewId = ''">关闭预览</button></div><video :key="preview.id" :src="preview.content_url" controls preload="metadata" playsinline></video></div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.exports { border-top: 1px solid #e0e8d9; }
.exports > .message { margin: 0 23px 20px; }
.retry-revision { margin: 0 23px 20px; }
.export-create { padding: 0 23px 24px; }
.export-version { display: flex; gap: 24px; align-items: center; }
.export-version label { font-size: 11px; color: #657c59; min-width: 150px; }
.export-version select { display: block; width: 100%; margin-top: 8px; }
.export-version b { font-size: 12px; font-weight: 500; color: #617a55; }
.export-version p, .no-approved { color: #839677; font-size: 11px; line-height: 1.9; margin-top: 8px; }
.version-note { margin-top: 16px; color: #b17c49; font-size: 11px; line-height: 1.9; }
.export-selection { min-width: 0; padding: 14px; border: 1px solid #e0e8d9; border-radius: 6px; margin: 22px 0 0; position: relative; }
.export-selection legend { font-size: 11px; padding: 0 8px; color: #7a916c; }
.selection-actions { display: flex; gap: 16px; margin-bottom: 12px; flex-wrap: wrap; align-items: center; }
.selection-actions label { font-size: 11px; color: #71876a; }
.selection-actions select { margin-left: 8px; }
.export-selection > label { display: flex; align-items: center; gap: 10px; padding: 10px 0; border-top: 1px solid #f0f4eb; }
.export-selection strong { font-size: 12px; font-weight: 500; color: #5e7852; }
.export-selection > label > span { margin-left: auto; font-size: 11px; color: #879d79; }
.create-export-row { display: flex; align-items: center; justify-content: space-between; gap: 15px; margin-top: 20px; }
.create-export-row small { color: #819773; font-size: 11px; }
.create-export-row .primary { padding: 11px 18px; }
.export-history { padding: 24px 23px; border-top: 1px solid #e0e8d9; background: #fafcf7; }
.export-history-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.export-history h3 { font-size: 13px; font-weight: 500; color: #59734d; }
.export-history h3 > span { color: #91a783; font-size: 11px; margin-left: 6px; }
.batch-tabs { display: flex; flex-wrap: wrap; gap: 9px; }
.batch-tabs > button { display: flex; align-items: center; gap: 10px; padding: 9px; border-radius: 6px; background: #fff; border: 1px solid #e1e8d9; font-size: 11px; color: #819574; }
.batch-tabs > button.active { border-color: #e6ac89; color: #a96e43; background: #fff8f0; }
.batch-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin: 22px 0 14px; color: #6b835f; font-size: 11px; }
.batch-summary code { color: #94a88a; font-size: 10px; overflow-wrap: anywhere; }
.export-clip { display: flex; align-items: center; flex-wrap: wrap; gap: 14px; border-top: 1px solid #e5ecdf; padding: 17px 0; }
.export-clip > div:first-child { flex: 1; min-width: 180px; }
.export-clip b { font-size: 13px; font-weight: 500; color: #5a754e; }
.export-clip p { margin-top: 8px; font-size: 11px; color: #879d7a; }
.export-clip code { display: block; margin-top: 8px; font-size: 11px; color: #ad7457; overflow-wrap: anywhere; }
.export-clip > small { font-size: 10px; color: #8c9f7e; }
.clip-actions { display: flex; gap: 9px; }
.status.partial_failed { color: #a97646; background: #faefdc; }
.export-preview { margin-top: 20px; border: 1px solid #dce6d3; border-radius: 7px; overflow: hidden; }
.export-preview > div { display: flex; justify-content: space-between; gap: 12px; padding: 15px; background: #fff; color: #637e54; font-size: 12px; }
.export-preview b { font-weight: 500; }
.export-preview video { display: block; width: 100%; max-height: 420px; background: #202720; }
@media(max-width:650px) { .export-create { padding: 0 18px 22px; } .export-version { display: block; } .export-version > div { margin-top: 16px; } .export-selection > label { flex-wrap: wrap; } .export-selection > label > span { margin-left: 26px; width: 100%; } .create-export-row { flex-wrap: wrap; } .export-history { padding: 22px 18px; } .batch-tabs > button { max-width: 100%; flex-wrap: wrap; } }
</style>
