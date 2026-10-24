<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError, post } from './api'
import ExportPanel from './ExportPanel.vue'

interface Product { id: string; name: string; identity_source?: string }
interface Segment { id: string; start_ms: number; end_ms: number; product_id: string | null; enabled: boolean; origin?: string; boundary_source?: string; identity_source?: string }
interface Utterance { utterance_id: string; start_ms: number; end_ms: number; text: string }
interface Review {
  job_id: string; video_id: string; generation: number; duration_ms: number; revision: number
  status: string; data_kind: string; source_hash: string; confirmed_revisions: number[]
  products: Product[]; segments: Segment[]; utterances: Utterance[]
  predictions: { id: string; content_kind: string; identity_status: string; review_reasons: string[]; audio_evidence_utterance_ids: string[] }[]
}
const props = defineProps<{ jobId: string; videoUrl: string }>()
const review = ref<Review | null>(null), products = ref<Product[]>([]), segments = ref<Segment[]>([])
const baseline = ref(''), pending = ref(false), error = ref(''), notice = ref('')
const selectedId = ref(''), productFilter = ref(''), player = ref<HTMLVideoElement | null>(null)
const playhead = ref(0)
let loadVersion = 0, stopped = false
const payload = computed(() => ({
  products: products.value.map(({ id, name }) => ({ id, name })),
  segments: segments.value.map(({ id, start_ms, end_ms, product_id, enabled }) => ({ id, start_ms, end_ms, product_id, enabled })),
}))
const dirty = computed(() => !!review.value && JSON.stringify(payload.value) !== baseline.value)
defineExpose({ dirty, pending })
const selected = computed(() => segments.value.find(s => s.id === selectedId.value))
const visible = computed(() => segments.value.filter(s => !productFilter.value || s.product_id === productFilter.value))
const evidence = computed(() => review.value?.utterances.filter(u => selected.value && u.end_ms > selected.value.start_ms && u.start_ms < selected.value.end_ms) ?? [])
const unresolved = computed(() => segments.value.filter(s => s.enabled && !s.product_id).length)
const enabledCount = computed(() => segments.value.filter(s => s.enabled).length)
function time(ms: number) { return `${Math.floor(ms / 60000).toString().padStart(2,'0')}:${Math.floor(ms % 60000 / 1000).toString().padStart(2,'0')}.${Math.floor(ms % 1000).toString().padStart(3,'0')}` }
function name(id: string | null) { return products.value.find(p => p.id === id)?.name ?? '未归属商品' }
function fail(value: unknown) {
  const code = value instanceof ApiError ? value.code : 'CLIENT_ERROR'
  error.value = code === 'REVIEW_REVISION_CONFLICT'
    ? '其他页面已更新此任务。你的修改仍保留在本页，请先复制需要保留的内容，再点击“重新读取”取得新版本。'
    : `审核操作未完成：${code}`
}
function apply(value: Review) {
  review.value = value
  products.value = structuredClone(value.products); segments.value = structuredClone(value.segments)
  baseline.value = JSON.stringify(payload.value)
  if (!segments.value.some(s => s.id === selectedId.value)) selectedId.value = segments.value[0]?.id ?? ''
}
async function load() {
  const version = ++loadVersion, jobId = props.jobId
  pending.value = true; error.value = ''; notice.value = ''
  try {
    const result = await api<Review>(`/api/jobs/${jobId}/review`)
    if (active(jobId, version) && result.job_id === jobId) apply(result)
  } catch (value) { if (active(jobId, version)) fail(value) }
  finally { if (active(jobId, version)) pending.value = false }
}
function active(jobId: string, version: number) { return !stopped && props.jobId === jobId && version === loadVersion }
async function save() {
  if (!review.value || pending.value || !dirty.value) return
  const jobId = props.jobId, version = loadVersion
  pending.value = true; error.value = ''; notice.value = ''
  try {
    const result = await api<Review>(`/api/jobs/${jobId}/review`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ base_revision: review.value.revision, ...payload.value }),
    })
    if (!active(jobId, version) || result.job_id !== jobId) return
    apply(result); notice.value = `已保存修订 r${result.revision}。原模型结果保持留档。`
  } catch (value) { if (active(jobId, version)) fail(value) }
  finally { if (active(jobId, version)) pending.value = false }
}
async function confirm() {
  if (!review.value || dirty.value || pending.value) return
  const jobId = props.jobId, version = loadVersion
  pending.value = true; error.value = ''; notice.value = ''
  try {
    const result = await post<Review>(`/api/jobs/${jobId}/review/confirm`, { revision: review.value.revision })
    if (!active(jobId, version) || result.job_id !== jobId) return
    apply(result); notice.value = `修订 r${result.revision} 已确认。后续修改将另存为新草稿。`
  } catch (value) { if (active(jobId, version)) fail(value) }
  finally { if (active(jobId, version)) pending.value = false }
}
function seek(segment: Segment) {
  selectedId.value = segment.id
  if (player.value) player.value.currentTime = segment.start_ms / 1000
}
function add() {
  if (!review.value) return
  const start = Math.min(Math.max(0, Math.round(playhead.value)), review.value.duration_ms - 1)
  const segment: Segment = { id: crypto.randomUUID(), start_ms: start, end_ms: Math.min(start + 30000, review.value.duration_ms), product_id: productFilter.value || null, enabled: false, origin: 'manual' }
  segments.value.push(segment); selectedId.value = segment.id
}
function split(segment: Segment) {
  const point = playhead.value > segment.start_ms && playhead.value < segment.end_ms
    ? Math.round(playhead.value) : Math.floor((segment.start_ms + segment.end_ms) / 2)
  if (!(point > segment.start_ms && point < segment.end_ms)) return
  const index = segments.value.indexOf(segment)
  segments.value.splice(index + 1, 0, { ...segment, id: crypto.randomUUID(), start_ms: point, origin: 'manual' })
  segment.end_ms = point
}
function remove(segment: Segment) { segments.value = segments.value.filter(s => s.id !== segment.id) }
function addProduct() { products.value.push({ id: crypto.randomUUID(), name: '新商品', identity_source: 'manual' }) }
function onUnload(event: BeforeUnloadEvent) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', onUnload)
watch(() => props.jobId, () => { review.value = null; void load() }, { immediate: true })
onBeforeUnmount(() => { stopped = true; loadVersion++; window.removeEventListener('beforeunload', onUnload) })
</script>

<template>
  <section class="panel review-panel" aria-label="商品片段审核">
    <div class="panel-heading"><div><div class="eyebrow">HUMAN REVIEW</div><h2>商品片段审核 <span v-if="review">r{{ review.revision }} · 第 {{ review.generation }} 次分析</span></h2></div><div class="actions"><span v-if="review" class="status" :class="review.status.toLowerCase()">{{ dirty ? '有未保存修改' : review.status === 'CONFIRMED' ? '本版已确认' : '待审核草稿' }}</span><button class="text-button" :disabled="pending" @click="load">{{ dirty ? '放弃修改并重新读取' : '重新读取' }}</button><a v-if="review?.confirmed_revisions.length" class="text-button" :href="`#exports-${jobId}`">跳到正式导出 ↓</a></div></div>
    <div v-if="error" class="message error" role="alert">{{ error }}</div>
    <div v-if="notice" class="message notice" role="status">{{ notice }}</div>
    <p v-if="!review" class="empty">{{ pending ? '正在读取分析结果…' : '审核数据暂不可用。' }}</p>
    <template v-if="review">
      <div class="review-video-layout"><video ref="player" :src="videoUrl" controls preload="metadata" playsinline @timeupdate="playhead = Math.round((($event.target as HTMLVideoElement).currentTime || 0) * 1000)"></video><div class="review-instructions"><span class="eyebrow">MODEL RESULT → YOUR DECISION</span><h3>校对归属，再确认边界。</h3><p>点击片段定位原视频，结合右侧语音证据核对商品。可改名、调整毫秒边界、拆分或补上漏检片段。</p><p>勾选的片段才进入导出候选。未归属商品的片段需先补充归属，或取消勾选。</p><div class="review-count"><b>{{ enabledCount }}</b> 段已勾选 <span v-if="unresolved">· {{ unresolved }} 段待归属</span></div><small>模型原始结果独立保留；人工修改有来源标记。</small></div></div>
      <div class="review-timeline" aria-label="商品讲解时间轴"><button v-for="segment in segments" :key="segment.id" :style="{ left: `${segment.start_ms / review.duration_ms * 100}%`, width: `${Math.max(.4, (segment.end_ms - segment.start_ms) / review.duration_ms * 100)}%` }" :class="{ disabled: !segment.enabled, focused: selectedId === segment.id }" :aria-label="`${name(segment.product_id)} ${time(segment.start_ms)} 至 ${time(segment.end_ms)}`" :title="`${name(segment.product_id)} ${time(segment.start_ms)}`" @click="seek(segment)"></button></div>
      <div class="timeline-labels"><span>00:00</span><span>当前 {{ time(playhead) }}</span><span>{{ time(review.duration_ms) }}</span></div>
      <fieldset class="review-fields" :disabled="pending">
      <details class="products-editor"><summary>本场商品 · {{ products.length }} 个</summary><div class="product-edit-list"><label v-for="product in products" :key="product.id"><span>{{ product.identity_source === 'manual' ? '人工新增' : '自动发现' }}</span><input v-model="product.name" :aria-label="`商品名称 ${product.id}`" maxlength="120"><button class="text-button" :disabled="segments.some(s => s.product_id === product.id)" @click="products = products.filter(p => p.id !== product.id)">移除</button></label><button class="secondary" @click="addProduct">＋ 添加商品</button></div></details>
      <div class="review-edit-layout">
        <div><div class="review-tools"><select v-model="productFilter" aria-label="筛选审核商品"><option value="">全部片段</option><option v-for="product in products" :key="product.id" :value="product.id">{{ product.name }}</option></select><button class="secondary" @click="add">＋ 在播放位置补片段</button></div>
          <div v-if="!visible.length" class="empty">暂无片段，可以手动补充。</div>
          <div v-for="segment in visible" :key="segment.id" class="segment-card" :class="{ focused: selectedId === segment.id }">
            <div class="segment-heading"><label><input v-model="segment.enabled" type="checkbox" :aria-label="`导出候选 ${segment.id}`"> {{ name(segment.product_id) }}</label><span>{{ segment.origin === 'manual' ? '人工片段' : '模型定位' }}</span><button class="text-button" @click="seek(segment)">定位 ↗</button></div>
            <div class="segment-inputs"><label>开始（ms）<input v-model.number="segment.start_ms" type="number" min="0" :max="review.duration_ms" step="1"></label><label>结束（ms）<input v-model.number="segment.end_ms" type="number" min="1" :max="review.duration_ms" step="1"></label><label>商品归属<select v-model="segment.product_id"><option :value="null">待确认</option><option v-for="product in products" :key="product.id" :value="product.id">{{ product.name }}</option></select></label></div>
            <div class="segment-footer"><span>{{ time(segment.start_ms) }} → {{ time(segment.end_ms) }}<template v-if="segment.boundary_source === 'manual'"> · 人工边界</template></span><button class="text-button" @click="split(segment)">拆分</button><button class="text-button" @click="remove(segment)">移除</button></div>
            <p v-if="review.predictions.find(p => p.id === segment.id)?.review_reasons.length" class="prediction-risk">原预测待核对：{{ review.predictions.find(p => p.id === segment.id)?.review_reasons.join(' · ') }}</p>
          </div>
        </div>
        <aside class="evidence-panel"><h3>语音证据 <span>{{ evidence.length }}</span></h3><p v-if="!evidence.length" class="help">选择片段后查看对应的 ASR 语句。人工补充的静音区间可能没有转写。</p><button v-for="utterance in evidence" :key="utterance.utterance_id" class="utterance" @click="player && (player.currentTime = utterance.start_ms / 1000)"><span>{{ time(utterance.start_ms) }}</span><p>{{ utterance.text }}</p></button></aside>
      </div>
      </fieldset>
      <div class="review-savebar"><div><b>{{ dirty ? '修改尚未保存' : `当前修订 r${review.revision}` }}</b><small>保存新版本；确认后才允许创建正式导出。</small></div><button class="secondary" :disabled="pending || !dirty" @click="save">{{ pending ? '处理中…' : '保存修订' }}</button><button class="primary" :disabled="pending || dirty || unresolved > 0 || review.status === 'CONFIRMED'" @click="confirm">{{ review.status === 'CONFIRMED' ? '本版已确认' : '确认此版本' }}</button></div>
      <ExportPanel :key="review.job_id" :job-id="review.job_id" :confirmed-revisions="review.confirmed_revisions" :current-revision="review.revision" :dirty="dirty" />
    </template>
  </section>
</template>

<style scoped>
.review-panel { margin-top: 24px; }
.review-fields { min-width: 0; margin: 0; padding: 0; border: 0; }
.review-panel > .message { margin: 0 23px 20px; }
.panel-heading .actions { margin: 0; }
.review-video-layout { display: grid; grid-template-columns: 1fr 1fr; border-block: 1px solid #e4eadf; }
.review-video-layout video { width: 100%; height: 300px; background: #202720; object-fit: contain; }
.review-instructions { padding: 28px; font-size: 12px; line-height: 1.9; color: #647b58; }
.review-instructions h3 { color: #485f40; font-size: 18px; font-weight: 500; }
.review-instructions p { margin: 12px 0; }
.review-instructions small { font-size: 10px; }
.review-count { margin: 18px 0 9px; } .review-count b { font-size: 24px; margin-right: 6px; color: #d77b51; }
.review-timeline { position: relative; height: 30px; margin: 24px 23px 10px; background: #f0f3ec; border-radius: 5px; }
.review-timeline button { position: absolute; height: 100%; background: #c6d8b9; border: 1px solid white; border-radius: 4px; }
.review-timeline .disabled { background: #e6e9e1; } .review-timeline .focused { background: #e99b75; }
.timeline-labels { display: flex; justify-content: space-between; margin: 0 23px 22px; color: #819374; font-size: 10px; }
.products-editor { border-block: 1px solid #e4eadf; padding: 18px 23px; color: #667c59; font-size: 12px; }
.products-editor summary { cursor: pointer; }
.product-edit-list { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: 18px; }
.product-edit-list label { display: flex; align-items: center; gap: 8px; }
.product-edit-list span { font-size: 10px; color: #90a383; }
.product-edit-list input { width: 120px; padding: 7px; border: 1px solid #dbe5d4; border-radius: 4px; }
.review-edit-layout { display: grid; grid-template-columns: minmax(0, 2fr) minmax(210px, 1fr); }
.review-edit-layout > div { padding: 22px 23px; }
.review-tools { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 18px; }
.review-tools select { max-width: 55%; }
.segment-card { border: 1px solid #e3e9de; border-radius: 7px; padding: 15px; margin-bottom: 12px; }
.segment-card.focused { border-color: #e4ac8d; background: #fefaf5; }
.segment-heading { display: flex; align-items: center; gap: 12px; font-size: 12px; }
.segment-heading label { flex: 1; overflow-wrap: anywhere; }
.segment-heading > span { font-size: 10px; color: #9aaa90; }
.segment-inputs { display: grid; grid-template-columns: 1fr 1fr 1.2fr; gap: 12px; margin-top: 18px; }
.segment-inputs label { font-size: 10px; color: #839776; min-width: 0; }
.segment-inputs input, .segment-inputs select { width: 100%; padding: 8px; border: 1px solid #dde6d5; border-radius: 4px; font-size: 12px; margin-top: 7px; }
.segment-footer { display: flex; gap: 15px; align-items: center; margin-top: 14px; }
.segment-footer > span { flex: 1; font-size: 10px; color: #92a282; }
.prediction-risk { color: #b4895c; font-size: 10px; margin-top: 13px; overflow-wrap: anywhere; line-height: 1.8; }
.evidence-panel { padding: 24px 20px; background: #f9fbf6; border-left: 1px solid #e4eadf; max-height: 900px; overflow: auto; }
.evidence-panel h3 { font-size: 12px; font-weight: 500; color: #637e54; margin-bottom: 18px; }
.evidence-panel h3 span { color: #9aaf8b; margin-left: 6px; font-size: 10px; }
.utterance { display: block; width: 100%; text-align: left; background: none; padding: 12px 0; border-bottom: 1px solid #e8eee1; }
.utterance span { color: #91a87e; font-size: 10px; } .utterance p { color: #697f5a; font-size: 12px; line-height: 1.9; margin-top: 7px; }
.review-savebar { display: flex; align-items: center; gap: 12px; padding: 20px 23px; border-top: 1px solid #e3e9db; background: #fff; position: sticky; bottom: 0; }
.review-savebar > div { flex: 1; } .review-savebar b { font-size: 12px; font-weight: 500; color: #5e7750; }
.review-savebar small { display: block; margin-top: 7px; color: #97ac87; font-size: 10px; }
.review-savebar .primary { padding: 10px 15px; }
@media(max-width:800px) { .review-video-layout { grid-template-columns: 1fr; } .review-video-layout video { height: 280px; } .review-edit-layout { grid-template-columns: 1fr; } .evidence-panel { max-height: 340px; border: 0; border-top: 1px solid #e4eadf; } }
@media(max-width:450px) { .review-panel .panel-heading { flex-wrap: wrap; } .review-instructions { padding: 22px 18px; } .review-edit-layout > div { padding: 18px 14px; } .segment-inputs { grid-template-columns: 1fr 1fr; } .segment-inputs label:last-child { grid-column: 1 / -1; } .segment-heading > span { display: none; } .review-savebar { flex-wrap: wrap; padding: 18px 14px; } .review-savebar > div { width: 100%; flex-basis: 100%; } .review-tools { flex-wrap: wrap; } }
</style>
