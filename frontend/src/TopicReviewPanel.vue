<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError, post } from './api'
import { downloadJson } from './download'
import { editTopicTime, hasTopicTimeDraft, normalizeTopicTime, parseTopicReview, splitTopic, topicPayload, topicTime, topicTimeInput, topicValidation, validTopicRange, TopicResponseError, type TopicReview, type TopicSegment, type TopicTimeDrafts, type TopicTimeField } from './topics'
import { TopicPreview, type TopicPreviewRange } from './topicPreview'
import ExportPanel from './ExportPanel.vue'

const props = withDefaults(defineProps<{ jobId: string; videoUrl: string; active?: boolean }>(), { active: true })
const review = ref<TopicReview | null>(null), segments = ref<TopicSegment[]>([])
const baseline = ref(''), pending = ref(false), error = ref(''), notice = ref('')
const selectedId = ref(''), editingId = ref(''), player = ref<HTMLVideoElement | null>(null), playhead = ref(0)
const topicList = ref<HTMLElement | null>(null)
const keywordInputs = ref<Record<string, string>>({})
const timeInputs = ref<TopicTimeDrafts>({}), previewRange = ref<TopicPreviewRange | null>(null)
const preview = new TopicPreview(value => { previewRange.value = value })
const downloadRevision = ref<number | null>(null)
const exportEditor = ref<{ dirty: boolean; pending: boolean } | null>(null)
const reviewView = ref<'edit' | 'exports'>('edit')
const editViewTab = ref<HTMLButtonElement | null>(null), exportViewTab = ref<HTMLButtonElement | null>(null)
const evidenceExpanded = ref(!window.matchMedia('(max-width: 800px)').matches)
const mediaAvailable = computed(() => props.active && reviewView.value === 'edit' && !stopped)
let version = 0, stopped = false
const payload = computed(() => topicPayload(segments.value))
const dirty = computed(() => !!review.value && (JSON.stringify(payload.value) !== baseline.value || hasTopicTimeDraft(segments.value, timeInputs.value)))
const validation = computed(() => review.value ? topicValidation(segments.value, review.value.duration_ms) : '')
const selected = computed(() => segments.value.find(segment => segment.id === selectedId.value))
const enabledCount = computed(() => segments.value.filter(segment => segment.enabled).length)
const evidence = computed(() => review.value?.utterances.filter(utterance => selected.value && utterance.end_ms > selected.value.start_ms && utterance.start_ms < selected.value.end_ms) ?? [])
defineExpose({ dirty: computed(() => dirty.value || !!exportEditor.value?.dirty), pending: computed(() => pending.value || !!exportEditor.value?.pending) })

function fail(value: unknown) {
  const code = value instanceof ApiError ? value.code : value instanceof TopicResponseError ? 'INVALID_SERVER_RESPONSE' : 'CLIENT_ERROR'
  const messages: Record<string, string> = {
    REVIEW_REVISION_CONFLICT: '其他页面已更新此任务。你的修改仍在本页，请复制需保留的内容后重新读取最新版本。',
    REVIEW_EMPTY_SELECTION: '请至少勾选一个需要切分的话题。',
    INVALID_TOPIC_REVIEW: '标题、摘要、关键词或时间范围无效，请检查后保存。',
    INVALID_SERVER_RESPONSE: '话题结果不完整或与当前任务不符，已保留本页内容。',
    NETWORK_UNAVAILABLE: '无法连接服务，本页修改仍然保留。',
    REQUEST_TIMEOUT: '请求超时，本页内容已保留；重新读取可确认服务端状态。',
  }
  error.value = messages[code] ?? `话题审核未完成：${code}`
}
function apply(value: TopicReview) {
  preview.cancel()
  review.value = value; segments.value = structuredClone(value.segments)
  keywordInputs.value = {}; timeInputs.value = {}
  baseline.value = JSON.stringify(payload.value)
  if (!segments.value.some(segment => segment.id === selectedId.value)) selectedId.value = segments.value[0]?.id ?? ''
  if (downloadRevision.value === null || !value.confirmed_revisions.includes(downloadRevision.value)) downloadRevision.value = value.confirmed_revisions.at(-1) ?? null
}
function active(jobId: string, requestVersion: number) { return !stopped && props.jobId === jobId && requestVersion === version }
async function load() {
  preview.cancel()
  const requestVersion = ++version, jobId = props.jobId
  pending.value = true; error.value = ''; notice.value = ''
  try {
    const raw = await api<unknown>(`/api/jobs/${jobId}/topic-review`)
    if (active(jobId, requestVersion)) apply(parseTopicReview(raw, jobId))
  } catch (value) { if (active(jobId, requestVersion)) fail(value) }
  finally { if (active(jobId, requestVersion)) pending.value = false }
}
async function save() {
  if (!review.value || pending.value || !dirty.value || validation.value) return
  const jobId = props.jobId, requestVersion = version
  pending.value = true; error.value = ''; notice.value = ''
  try {
    const raw = await api<unknown>(`/api/jobs/${jobId}/topic-review`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ base_revision: review.value.revision, ...payload.value }) })
    if (!active(jobId, requestVersion)) return
    const result = parseTopicReview(raw, jobId); apply(result)
    notice.value = `已保存 r${result.revision}，可确认本版后创建切分任务。`
  } catch (value) { if (active(jobId, requestVersion)) fail(value) }
  finally { if (active(jobId, requestVersion)) pending.value = false }
}
async function confirm() {
  if (!review.value || pending.value || dirty.value || validation.value || !enabledCount.value) return
  const jobId = props.jobId, requestVersion = version
  pending.value = true; error.value = ''; notice.value = ''
  try {
    const raw = await post<unknown>(`/api/jobs/${jobId}/topic-review/confirm`, { revision: review.value.revision })
    if (!active(jobId, requestVersion)) return
    const result = parseTopicReview(raw, jobId); apply(result); downloadRevision.value = result.revision
    reviewView.value = 'exports'
    notice.value = `r${result.revision} 已确认。请在“成片导出”中选择片段并创建导出任务。`
  } catch (value) { if (active(jobId, requestVersion)) fail(value) }
  finally { if (active(jobId, requestVersion)) pending.value = false }
}
async function downloadConfirmed() {
  if (!review.value || pending.value || downloadRevision.value === null || !review.value.confirmed_revisions.includes(downloadRevision.value)) return
  const jobId = props.jobId, requestVersion = version, revision = downloadRevision.value
  pending.value = true; error.value = ''; notice.value = ''
  try {
    await downloadJson(`/api/jobs/${jobId}/topic-review/download?revision=${revision}`)
    if (active(jobId, requestVersion)) notice.value = `已发起确认版本 r${revision} 的 JSON 下载。`
  } catch (value) { if (active(jobId, requestVersion)) fail(value) }
  finally { if (active(jobId, requestVersion)) pending.value = false }
}
function seek(segment: TopicSegment) {
  if (!mediaAvailable.value) return
  preview.cancel()
  selectedId.value = segment.id
  if (player.value && Number.isFinite(segment.start_ms)) player.value.currentTime = Math.max(0, segment.start_ms) / 1000
}
function editSegment(segment: TopicSegment) {
  preview.cancel()
  selectedId.value = segment.id
  editingId.value = editingId.value === segment.id ? '' : segment.id
}
function revealSelected() {
  const id = selectedId.value
  void nextTick(() => {
    if (!mediaAvailable.value || selectedId.value !== id) return
    const card = Array.from(topicList.value?.querySelectorAll<HTMLElement>('[data-topic-id]') ?? [])
      .find(element => element.dataset.topicId === id)
    card?.scrollIntoView({ block: 'nearest' })
  })
}
function seekEvidence(start: number) {
  if (!mediaAvailable.value) return
  preview.cancel()
  if (player.value) player.value.currentTime = start / 1000
}
async function previewSegment(segment: TopicSegment) {
  if (previewRange.value?.id === segment.id) { preview.cancel(); return }
  if (!mediaAvailable.value || !player.value || !review.value || !validTopicRange(segment, review.value.duration_ms)) return
  selectedId.value = segment.id; error.value = ''
  const result = await preview.start(player.value, segment)
  if (result === 'failed' && mediaAvailable.value) error.value = '暂时无法播放本段，请检查视频是否可播放后重试。'
}
function mediaPlay(event: Event) { if (!mediaAvailable.value) (event.target as HTMLVideoElement).pause() }
function reviewTabKey(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  reviewView.value = event.key === 'Home' ? 'edit' : event.key === 'End' ? 'exports' : reviewView.value === 'edit' ? 'exports' : 'edit'
  void nextTick(() => { if (props.active && !stopped) (reviewView.value === 'edit' ? editViewTab.value : exportViewTab.value)?.focus() })
}
function timeUpdate(event: Event) {
  const video = event.target as HTMLVideoElement
  preview.timeUpdate(video)
  playhead.value = Math.round((video.currentTime || 0) * 1000)
}
function seeking(event: Event) { preview.seeking(event.target as HTMLVideoElement) }
function seeked(event: Event) { preview.seeked(event.target as HTMLVideoElement) }
function add() {
  if (!review.value) return
  preview.cancel()
  const start = Math.min(Math.max(0, Math.round(playhead.value)), review.value.duration_ms - 1)
  const segment: TopicSegment = { id: crypto.randomUUID(), start_ms: start, end_ms: Math.min(start + 30000, review.value.duration_ms), title: '新增话题', summary: '请填写本段讨论内容。', keywords: [], enabled: false, origin: 'manual' }
  segments.value.push(segment); selectedId.value = segment.id; editingId.value = segment.id
  revealSelected()
}
function split(segment: TopicSegment) {
  preview.cancel()
  const pair = splitTopic(segment, playhead.value, crypto.randomUUID())
  if (!pair) { error.value = '此片段太短或边界无效，暂时不能拆分。'; return }
  segments.value.splice(segments.value.indexOf(segment), 1, ...pair)
  selectedId.value = pair[1].id; editingId.value = pair[1].id
  revealSelected()
  delete timeInputs.value[segment.id]
  notice.value = `已在 ${topicTime(pair[1].start_ms)} 拆分，请分别核对两段标题、摘要和选择状态。`
}
function remove(segment: TopicSegment) {
  preview.cancel()
  segments.value = segments.value.filter(value => value.id !== segment.id)
  delete timeInputs.value[segment.id]; delete keywordInputs.value[segment.id]
  if (selectedId.value === segment.id) selectedId.value = segments.value[0]?.id ?? ''
  if (editingId.value === segment.id) editingId.value = ''
}
function keywords(segment: TopicSegment, event: Event) {
  const value = (event.target as HTMLInputElement).value
  keywordInputs.value[segment.id] = value
  segment.keywords = value.split(/[,，]/).map(word => word.trim()).filter(Boolean)
}
function boundary(segment: TopicSegment, field: TopicTimeField, event: Event) {
  preview.cancel()
  editTopicTime(segment, timeInputs.value, field, (event.target as HTMLInputElement).value)
}
function position(segment: TopicSegment) {
  const duration = review.value?.duration_ms ?? 1
  const start = Number.isFinite(segment.start_ms) ? Math.max(0, Math.min(duration, segment.start_ms)) : 0
  const end = Number.isFinite(segment.end_ms) ? Math.max(start, Math.min(duration, segment.end_ms)) : start
  return { left: `${start / duration * 100}%`, width: `${Math.min(100 - start / duration * 100, Math.max(.4, (end - start) / duration * 100))}%` }
}
function onUnload(event: BeforeUnloadEvent) { if (dirty.value || pending.value) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', onUnload)
watch(() => props.jobId, () => { preview.cancel(); reviewView.value = 'edit'; review.value = null; segments.value = []; selectedId.value = ''; editingId.value = ''; timeInputs.value = {}; keywordInputs.value = {}; void load() }, { immediate: true })
watch(() => props.videoUrl, () => { preview.cancel() })
watch(() => props.active, value => { if (!value) { preview.cancel(); player.value?.pause() } }, { flush: 'sync' })
watch(reviewView, () => { preview.cancel(); player.value?.pause() }, { flush: 'sync' })
onBeforeUnmount(() => { stopped = true; version++; preview.cancel(); player.value?.pause(); window.removeEventListener('beforeunload', onUnload) })
</script>
<template>
  <section class="panel topic-review" aria-label="视频话题解析与人工切分">
    <header class="topic-toolbar"><div v-if="review" class="topic-view-switch" role="tablist" aria-label="话题审核视图" @keydown="reviewTabKey"><button :id="`topic-edit-tab-${jobId}`" ref="editViewTab" role="tab" :aria-controls="`topic-edit-view-${jobId}`" :aria-selected="reviewView === 'edit'" :tabindex="reviewView === 'edit' ? 0 : -1" @click="reviewView = 'edit'">选择与编辑<span v-if="dirty" class="topic-draft-mark">未保存</span></button><button :id="`topic-export-tab-${jobId}`" ref="exportViewTab" role="tab" :aria-controls="`topic-export-view-${jobId}`" :aria-selected="reviewView === 'exports'" :tabindex="reviewView === 'exports' ? 0 : -1" @click="reviewView = 'exports'">成片导出<span v-if="review.confirmed_revisions.length" class="topic-version-count">{{ review.confirmed_revisions.length }} 个确认版本</span></button></div><div class="topic-review-meta"><span v-if="review" class="topic-revision" :title="`第 ${review.generation} 次分析`">r{{ review.revision }}</span><span v-if="review" class="status" :class="review.status.toLowerCase()">{{ dirty ? '有未保存修改' : review.status === 'CONFIRMED' ? '本版已确认' : '待选择草稿' }}</span><button class="text-button" :disabled="pending" @click="load">{{ dirty ? '放弃修改并重新读取' : '重新读取' }}</button></div></header>
    <div v-if="error" class="message error" role="alert">{{ error }}</div><div v-if="notice" class="message notice" role="status">{{ notice }}</div>
    <p v-if="!review" class="empty">{{ pending ? '正在读取话题结果…' : '话题结果暂不可用，请重新读取。' }}</p>
    <template v-if="review">
      <div v-show="reviewView === 'edit'" :id="`topic-edit-view-${jobId}`" class="topic-edit-page" role="tabpanel" :aria-labelledby="`topic-edit-tab-${jobId}`">
        <div class="topic-review-main">
          <aside class="topic-media-column" aria-label="话题回看与原句证据">
            <div class="topic-media-heading"><h3>原视频回看</h3><span>点击话题定位原视频</span></div>
            <div class="topic-player"><video ref="player" :src="videoUrl" controls preload="metadata" playsinline @play="mediaPlay" @timeupdate="timeUpdate" @seeking="seeking" @seeked="seeked" @ended="timeUpdate" @error="preview.cancel()"></video></div>
            <div class="topic-playback-meta"><div class="topic-timeline" aria-label="话题时间轴"><button v-for="segment in segments" :key="segment.id" :style="position(segment)" :class="{ enabled: segment.enabled, focused: selectedId === segment.id }" :title="`${segment.title} ${topicTime(segment.start_ms)} 至 ${topicTime(segment.end_ms)}`" :aria-label="`定位话题 ${segment.title}`" @click="seek(segment)"></button></div><div class="timeline-labels"><span>当前 {{ topicTime(playhead) }}</span><span>{{ topicTime(review.duration_ms) }}</span></div><p v-if="previewRange" class="preview-status" role="status">本段预览至 {{ topicTime(previewRange.end_ms) }}<button class="text-button" @click="preview.cancel()">停止预览</button></p></div>
            <details class="topic-evidence" :open="evidenceExpanded" @toggle="evidenceExpanded = ($event.target as HTMLDetailsElement).open"><summary><h3>原句转写 <span>{{ evidence.length }} 句</span></h3><span>{{ evidenceExpanded ? '收起' : '展开' }}</span></summary><div class="topic-evidence-body"><p v-if="!evidence.length" class="help">选择话题查看原始转写。人工添加的静音区间可能没有语句。</p><button v-for="utterance in evidence" :key="utterance.utterance_id" @click="seekEvidence(utterance.start_ms)"><span>{{ topicTime(utterance.start_ms) }} → {{ topicTime(utterance.end_ms) }}</span><p>{{ utterance.text }}</p></button></div></details>
          </aside>
          <fieldset class="topic-fields" :disabled="pending"><div ref="topicList" class="topic-list"><div class="topic-tools"><div class="topic-list-heading"><h3>话题列表</h3><span>共 {{ segments.length }} 个话题 · 已选 {{ enabledCount }} 个</span></div><button class="secondary" @click="add">＋ 在播放位置补片段</button></div><div v-if="!segments.length" class="empty">暂无片段，可以手动补充。</div>
            <article v-for="(segment, index) in segments" :key="segment.id" :data-topic-id="segment.id" class="topic-card" :class="{ focused: selectedId === segment.id }">
          <div class="topic-row"><label class="topic-selection"><input v-model="segment.enabled" type="checkbox" :aria-label="`选择话题 ${segment.title}`"><span>{{ index + 1 }}</span></label><div class="topic-row-copy"><button class="topic-summary-title" :title="segment.title" :aria-label="`回看话题 ${segment.title}`" @click="seek(segment)">{{ segment.title }}</button><div class="topic-card-meta"><span class="topic-range">{{ topicTime(segment.start_ms) }} → {{ topicTime(segment.end_ms) }}</span><span class="topic-review-mark">{{ review.status === 'CONFIRMED' && !dirty && segment.enabled ? '已确认' : segment.origin === 'manual' ? '人工片段' : segment.boundary_source === 'manual' ? '已调边界' : 'AI 建议' }}</span></div></div><div class="topic-row-actions"><button class="text-button topic-edit-toggle" :aria-expanded="editingId === segment.id" :aria-controls="`topic-edit-${segment.id}`" :aria-label="`编辑此段 ${index + 1}`" @click="editSegment(segment)">{{ editingId === segment.id ? '收起' : '编辑' }}</button><button class="text-button" :disabled="!validTopicRange(segment, review.duration_ms)" :aria-label="`${previewRange?.id === segment.id ? '停止预览' : '预览本段'} ${index + 1}`" @click="previewSegment(segment)">{{ previewRange?.id === segment.id ? '停止预览' : '预览本段' }}</button></div></div>
          <div v-show="editingId === segment.id" :id="`topic-edit-${segment.id}`" class="topic-editor">
            <label class="topic-title">话题标题<input v-model="segment.title" :aria-label="`话题标题 ${index + 1}`" maxlength="120"></label>
            <label class="topic-summary">本段摘要<textarea v-model="segment.summary" :aria-label="`话题摘要 ${index + 1}`" rows="3" maxlength="1000"></textarea></label>
            <label class="topic-keywords">关键词（逗号分隔）<input :value="keywordInputs[segment.id] ?? segment.keywords.join('，')" :aria-label="`话题关键词 ${index + 1}`" @input="keywords(segment, $event)"></label>
            <div class="topic-times"><label>开始（分:秒.毫秒）<input :value="topicTimeInput(segment, timeInputs, 'start_ms')" :aria-label="`话题开始 ${index + 1}`" placeholder="05:00.000" @input="boundary(segment, 'start_ms', $event)" @blur="normalizeTopicTime(segment, timeInputs, 'start_ms')"></label><label>结束（分:秒.毫秒）<input :value="topicTimeInput(segment, timeInputs, 'end_ms')" :aria-label="`话题结束 ${index + 1}`" placeholder="07:00.000" @input="boundary(segment, 'end_ms', $event)" @blur="normalizeTopicTime(segment, timeInputs, 'end_ms')"></label></div>
            <div class="topic-card-footer"><span v-if="segment.boundary_source === 'manual'">人工调整边界</span><button class="text-button" @click="split(segment)">拆分</button><button class="text-button" @click="remove(segment)">移除</button></div>
          </div>
        </article>
          </div></fieldset>
        </div>
        <p v-if="validation" class="topic-validation" role="alert">{{ validation }}</p>
        <footer class="topic-savebar"><div><b>{{ dirty ? '修改尚未保存' : `当前修订 r${review.revision}` }}</b><small>{{ enabledCount ? `已选 ${enabledCount} 段；确认后可创建切分任务。` : '请勾选需要切分的话题。' }}</small></div><button class="secondary" :disabled="pending || !dirty || !!validation" @click="save">保存修订</button><button class="primary" :disabled="pending || dirty || !!validation || !enabledCount || review.status === 'CONFIRMED'" @click="confirm">{{ review.status === 'CONFIRMED' ? '本版已确认' : '确认所选片段' }}</button></footer>
      </div>
      <div v-show="reviewView === 'exports'" :id="`topic-export-view-${jobId}`" class="topic-exports-page" role="tabpanel" :aria-labelledby="`topic-export-tab-${jobId}`">
        <p v-if="dirty" class="topic-export-hint">审核页的未保存修改仍保留。这里查看的是已确认版本与已有成片。</p>
        <ExportPanel ref="exportEditor" :key="review.job_id" :job-id="review.job_id" :confirmed-revisions="review.confirmed_revisions" :current-revision="review.revision" :dirty="dirty" :active="props.active && reviewView === 'exports'" analysis-mode="topics" />
        <details v-if="review.confirmed_revisions.length" class="topic-archive"><summary>审核版本与 JSON 留档</summary><div class="topic-download"><label>留档版本 <select v-model.number="downloadRevision" aria-label="下载话题确认版本" :disabled="pending"><option v-for="revision in review.confirmed_revisions" :key="revision" :value="revision">r{{ revision }}</option></select></label><button class="text-button" :disabled="pending || downloadRevision === null" @click="downloadConfirmed">下载话题 JSON ↓</button></div></details>
      </div>
    </template>
  </section>
</template>
<style scoped>
.topic-review.panel { container: topic-review / inline-size; display: flex; flex-direction: column; height: 100%; min-height: 0; min-width: 0; margin: 0; padding: 0; overflow: hidden; border: 1px solid #e2e8f0; border-radius: 10px; box-shadow: 0 2px 6px #172b4d03; background: #fff; color: #182230; font-size: 14px; }
.topic-toolbar { flex: none; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0 14px; min-width: 0; padding: 0 12px; border-bottom: 1px solid #e6e9ed; }
.topic-view-switch { display: flex; align-items: center; gap: 20px; min-width: 0; }
.topic-view-switch > button { min-height: 44px; padding: 11px 0 9px; border: 0; border-bottom: 2px solid transparent; border-radius: 0; background: none; color: #657181; font: inherit; white-space: nowrap; }
.topic-view-switch > button[aria-selected=true] { color: #c65f3b; border-bottom-color: #e47650; font-weight: 600; }
.topic-draft-mark, .topic-version-count { margin-left: 6px; font-size: 13px; font-weight: 400; color: #64748b; }
.topic-draft-mark { color: #b06138; }
.topic-review-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-left: auto; color: #64748b; font-size: 13px; }
.topic-review-meta .status { padding: 0; border: 0; background: transparent; color: #657181; font-size: 13px; }
.topic-review-meta .text-button { min-height: 36px; padding: 7px 4px; font-size: 13px; }
.topic-review > .message { flex: none; max-height: 54px; overflow: auto; margin: 8px 12px; padding: 7px 10px; font-size: 13px; }
.topic-review .text-button, .topic-review .primary, .topic-review .secondary { font-size: 14px; }
.topic-review > .empty { margin: 0; padding: 24px 12px; }
.topic-edit-page { flex: 1; display: flex; flex-direction: column; min-height: 0; min-width: 0; overflow: hidden; }
.topic-review-main { display: grid; grid-template-columns: minmax(280px, min(32%, 400px)) minmax(0, 1fr); gap: 20px; flex: 1; min-height: 0; min-width: 0; padding: 16px; overflow: hidden; }
.topic-media-column { display: flex; flex-direction: column; gap: 12px; min-width: 0; min-height: 0; overflow: hidden; }
.topic-media-heading, .topic-list-heading { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px 10px; min-width: 0; }
.topic-media-heading { flex: none; }
.topic-media-heading h3, .topic-list-heading h3 { margin: 0; color: #334155; font-size: 14px; line-height: 1.6; font-weight: 600; }
.topic-media-heading > span, .topic-list-heading > span { color: #64748b; font-size: 13px; line-height: 1.6; }
.topic-player { position: relative; flex: none; aspect-ratio: 16 / 9; width: 100%; max-height: 240px; background: #182230; border-radius: 7px; overflow: hidden; }
.topic-player video { position: absolute; inset: 0; display: block; width: 100%; height: 100%; max-height: none; background: #182230; object-fit: contain; }
.topic-playback-meta { flex: none; min-width: 0; }
.topic-timeline { position: relative; height: 16px; margin: 0 0 5px; background: #f0f2f5; border-radius: 3px; overflow: hidden; }
.topic-timeline button { position: absolute; height: 100%; background: #dbe1e7; border: 1px solid #fff; border-radius: 2px; }
.topic-timeline .enabled { background: #b3c8b0; }
.topic-timeline .focused { background: #e47650; }
.timeline-labels { display: flex; justify-content: space-between; gap: 6px; color: #64748b; font-size: 13px; font-variant-numeric: tabular-nums; line-height: 1.5; }
.preview-status { display: flex; flex-wrap: wrap; gap: 4px 10px; align-items: center; margin: 5px 0 0; color: #657181; font-size: 13px; line-height: 1.5; }
.preview-status .text-button { min-height: 36px; padding: 6px 8px; font-size: 13px; }
.topic-evidence { position: relative; flex: 1 1 0; min-height: 80px; overflow: hidden; border: 1px solid #e6e9ed; border-radius: 7px; background: #fafbfc; }
.topic-evidence:not([open]) { flex: 0 0 40px; min-height: 40px; }
.topic-evidence > summary { min-height: 40px; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 12px; cursor: pointer; list-style: none; box-sizing: border-box; }
.topic-evidence > summary::-webkit-details-marker { display: none; }
.topic-evidence h3 { margin: 0; color: #182230; font-size: 14px; font-weight: 600; }
.topic-evidence h3 > span, .topic-evidence > summary > span { margin-left: 6px; color: #64748b; font-size: 13px; font-weight: 400; }
.topic-evidence-body { position: absolute; inset: 40px 0 0; min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding: 0 12px 10px; box-sizing: border-box; scrollbar-gutter: stable; }
.topic-evidence:not([open]) .topic-evidence-body { display: none; }
.topic-evidence .help { margin: 7px 0; font-size: 13px; color: #64748b; }
.topic-evidence button { display: block; width: 100%; min-height: 36px; text-align: left; background: none; border: 0; border-top: 1px solid #e8ebef; border-radius: 0; padding: 10px 0; }
.topic-evidence button span { color: #64748b; font-size: 13px; font-variant-numeric: tabular-nums; }
.topic-evidence button p { font-size: 14px; color: #182230; line-height: 1.65; margin: 5px 0 0; overflow-wrap: anywhere; }
.topic-fields { display: flex; flex-direction: column; height: 100%; min-width: 0; min-height: 0; margin: 0; padding: 0; border: 0; overflow: hidden; }
.topic-list { flex: 1; min-height: 0; min-width: 0; padding: 0 6px 0 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: stable; }
.topic-tools { position: sticky; top: 0; z-index: 1; display: flex; align-items: center; flex-wrap: wrap; gap: 8px 12px; justify-content: space-between; margin: 0 0 12px; padding: 0 0 12px; border-bottom: 1px solid #e6e9ed; background: #fff; font-size: 13px; color: #64748b; }
.topic-tools > .secondary { min-height: 36px; padding: 7px 10px; border-color: #dce2e8; border-radius: 6px; font-size: 13px; }
.topic-card { border: 1px solid #e2e8f0; border-left: 3px solid #e2e8f0; border-radius: 8px; padding: 16px 14px; margin: 0 0 12px; background: #fff; box-shadow: none; scroll-margin-block: 12px; }
.topic-card:last-child { margin-bottom: 0; }
.topic-card.focused { border-color: #edc7b8; border-left-color: #e47650; background: #fff8f4; }
.topic-row { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; gap: 10px; align-items: center; min-width: 0; }
.topic-selection { display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 36px; gap: 7px; margin: 0; color: #64748b; font-size: 12px; line-height: 1; cursor: pointer; }
.topic-selection input { width: 18px; height: 18px; margin: 0; accent-color: #e47650; }
.topic-row-copy { min-width: 0; }
.topic-summary-title { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; min-height: 36px; padding: 4px 0; margin: 0 0 5px; width: 100%; background: none; border: 0; border-radius: 0; text-align: left; color: #182230; font: inherit; font-size: 15px; font-weight: 600; line-height: 1.6; overflow-wrap: anywhere; }
.topic-summary-title:hover { color: #bc5834; }
.topic-card-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 5px 10px; color: #64748b; font-size: 13px; line-height: 1.6; }
.topic-range { white-space: nowrap; font-variant-numeric: tabular-nums; }
.topic-review-mark { color: #9b684d; white-space: nowrap; }
.topic-row-actions { display: flex; align-items: center; gap: 4px; padding: 3px; border: 1px solid #e2e8f0; border-radius: 7px; background: #fff; }
.topic-row-actions .text-button { min-height: 36px; padding: 7px 10px; border-radius: 5px; color: #475569; font-size: 13px; white-space: nowrap; }
.topic-row-actions .text-button:hover:not(:disabled) { background: #f1f5f9; }
.topic-editor { border-top: 1px solid #e6e9ed; margin-top: 16px; padding-top: 2px; }
.topic-title, .topic-summary, .topic-keywords, .topic-times label { display: block; font-size: 13px; color: #657181; min-width: 0; margin-top: 14px; }
.topic-card input:not([type=checkbox]), .topic-card textarea { box-sizing: border-box; display: block; width: 100%; min-width: 0; border: 1px solid #dce2e8; border-radius: 4px; background: #fff; padding: 8px 9px; font: inherit; font-size: 14px; color: #182230; margin-top: 5px; }
.topic-card textarea { resize: vertical; line-height: 1.6; }
.topic-card input:focus-visible, .topic-card textarea:focus-visible, .topic-summary-title:focus-visible, .topic-edit-toggle:focus-visible { outline: 2px solid #e47650; outline-offset: 2px; }
.topic-times { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 10px; }
.topic-card-footer { display: flex; justify-content: flex-end; align-items: center; gap: 8px; margin-top: 14px; flex-wrap: wrap; }
.topic-card-footer > span { margin-right: auto; font-size: 13px; color: #64748b; }
.topic-card-footer > .text-button { min-height: 36px; padding: 7px 10px; border: 1px solid #e2e8f0; border-radius: 6px; background: #fff; font-size: 13px; }
.topic-validation { flex: none; max-height: 48px; overflow-y: auto; margin: 0 12px 7px; color: #a95032; font-size: 13px; line-height: 1.5; }
.topic-savebar { flex: none; display: flex; flex-wrap: wrap; align-items: center; gap: 10px 12px; padding: 12px 16px; border-top: 1px solid #e6e9ed; background: #fff; position: relative; z-index: 2; }
.topic-savebar > div { flex: 1; min-width: 140px; display: flex; flex-wrap: wrap; align-items: center; gap: 3px 10px; }
.topic-savebar b { font-size: 14px; font-weight: 600; color: #182230; }
.topic-savebar small { display: block; font-size: 13px; color: #64748b; margin: 0; line-height: 1.6; }
.topic-savebar .primary, .topic-savebar .secondary { min-height: 36px; padding: 7px 11px; border-radius: 6px; font-size: 14px; }
.topic-savebar .primary { background: #e47650; }
.topic-exports-page { flex: 1; min-height: 0; overflow-y: auto; padding: 12px; overscroll-behavior: contain; }
.topic-download { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 14px; margin-bottom: 12px; color: #657181; font-size: 13px; }
.topic-download .text-button { min-height: 36px; padding: 7px 10px; }
.topic-archive { margin-top: 20px; border-top: 1px solid #e2e8f0; color: #64748b; font-size: 13px; }
.topic-archive > summary { cursor: pointer; min-height: 40px; padding: 10px 0; }
.topic-download select { margin-left: 8px; font: inherit; color: #182230; }
.topic-export-hint { color: #657181; background: #fafbfc; padding: 8px 10px; border: 1px solid #e6e9ed; border-radius: 4px; font-size: 13px; line-height: 1.6; }
.topic-exports-page :deep(.exports) { margin: 0; }
@container topic-review (max-width:900px) {
  .topic-version-count { display: none; }
  .topic-review-meta .status { display: none; }
}
@container topic-review (max-width:740px) {
  .topic-review-main { display: flex; flex-direction: column; overflow-y: auto; gap: 18px; }
  .topic-media-column { flex: none; overflow: visible; }
  .topic-player { flex: none; height: auto; aspect-ratio: 16 / 9; max-height: min(230px, 34dvh); }
  .topic-evidence, .topic-evidence:not([open]) { flex: none; height: auto; min-height: 0; overflow: visible; }
  .topic-evidence-body { position: static; overflow: visible; scrollbar-gutter: auto; }
  .topic-fields { flex: none; height: auto; overflow: visible; }
  .topic-list { flex: none; overflow: visible; padding: 0; }
  .topic-tools { position: static; }
  .topic-savebar > div { display: block; }
  .topic-savebar small { margin-top: 2px; }
}
@container topic-review (max-width:520px) {
  .topic-toolbar { padding: 0 10px 5px; gap: 0 8px; }
  .topic-view-switch { flex-basis: 100%; gap: 18px; }
  .topic-review-meta { min-height: 36px; width: 100%; justify-content: space-between; }
  .topic-review-meta .status { display: inline; margin-right: auto; }
  .topic-review-main { padding: 12px; }
  .topic-player { max-height: min(200px, 32dvh); }
  .topic-card { padding: 14px 12px; }
  .topic-row { grid-template-columns: 22px minmax(0, 1fr); gap: 5px 9px; }
  .topic-selection { align-self: start; padding-top: 4px; }
  .topic-row-actions { grid-column: 2; justify-self: start; margin-top: 8px; }
  .topic-times { grid-template-columns: 1fr; gap: 0; }
  .topic-savebar { padding: 10px 12px; }
  .topic-savebar > div { flex-basis: 100%; }
  .topic-savebar .primary, .topic-savebar .secondary { flex: 1; min-width: 0; }
  .topic-exports-page { padding: 10px; }
}
@media (max-height:600px) {
  .topic-review-main { overflow-y: auto; align-items: start; gap: 16px; padding: 12px; }
  .topic-media-column { width: 100%; overflow: visible; gap: 10px; }
  .topic-player { max-height: min(180px, 30dvh); }
  .topic-evidence, .topic-evidence:not([open]) { flex: none; height: auto; min-height: 0; overflow: visible; }
  .topic-evidence-body { position: static; overflow: visible; scrollbar-gutter: auto; }
  .topic-fields { width: 100%; height: auto; overflow: visible; }
  .topic-list { overflow: visible; padding-right: 0; }
  .topic-tools { position: static; }
  .topic-savebar { padding-block: 8px; }
}
</style>
