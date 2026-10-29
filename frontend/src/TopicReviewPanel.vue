<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError, post } from './api'
import { downloadJson } from './download'
import { editTopicTime, hasTopicTimeDraft, normalizeTopicTime, parseTopicReview, splitTopic, topicPayload, topicTime, topicTimeInput, topicValidation, validTopicRange, TopicResponseError, type TopicReview, type TopicSegment, type TopicTimeDrafts, type TopicTimeField } from './topics'
import { TopicPreview, type TopicPreviewRange } from './topicPreview'
import ExportPanel from './ExportPanel.vue'

const props = defineProps<{ jobId: string; videoUrl: string }>()
const review = ref<TopicReview | null>(null), segments = ref<TopicSegment[]>([])
const baseline = ref(''), pending = ref(false), error = ref(''), notice = ref('')
const selectedId = ref(''), player = ref<HTMLVideoElement | null>(null), playhead = ref(0)
const keywordInputs = ref<Record<string, string>>({})
const timeInputs = ref<TopicTimeDrafts>({}), previewRange = ref<TopicPreviewRange | null>(null)
const preview = new TopicPreview(value => { previewRange.value = value })
const downloadRevision = ref<number | null>(null)
let version = 0, stopped = false
const payload = computed(() => topicPayload(segments.value))
const dirty = computed(() => !!review.value && (JSON.stringify(payload.value) !== baseline.value || hasTopicTimeDraft(segments.value, timeInputs.value)))
const validation = computed(() => review.value ? topicValidation(segments.value, review.value.duration_ms) : '')
const selected = computed(() => segments.value.find(segment => segment.id === selectedId.value))
const enabledCount = computed(() => segments.value.filter(segment => segment.enabled).length)
const evidence = computed(() => review.value?.utterances.filter(utterance => selected.value && utterance.end_ms > selected.value.start_ms && utterance.start_ms < selected.value.end_ms) ?? [])
defineExpose({ dirty, pending })

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
    notice.value = `r${result.revision} 已确认。请在下方选择并导出独立 MP4。`
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
  preview.cancel()
  selectedId.value = segment.id
  if (player.value && Number.isFinite(segment.start_ms)) player.value.currentTime = Math.max(0, segment.start_ms) / 1000
}
function seekEvidence(start: number) {
  preview.cancel()
  if (player.value) player.value.currentTime = start / 1000
}
async function previewSegment(segment: TopicSegment) {
  if (previewRange.value?.id === segment.id) { preview.cancel(); return }
  if (!player.value || !review.value || !validTopicRange(segment, review.value.duration_ms)) return
  selectedId.value = segment.id; error.value = ''
  const result = await preview.start(player.value, segment)
  if (result === 'failed' && !stopped) error.value = '暂时无法播放本段，请检查视频是否可播放后重试。'
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
  segments.value.push(segment); selectedId.value = segment.id
}
function split(segment: TopicSegment) {
  preview.cancel()
  const pair = splitTopic(segment, playhead.value, crypto.randomUUID())
  if (!pair) { error.value = '此片段太短或边界无效，暂时不能拆分。'; return }
  segments.value.splice(segments.value.indexOf(segment), 1, ...pair)
  delete timeInputs.value[segment.id]
  notice.value = `已在 ${topicTime(pair[1].start_ms)} 拆分，请分别核对两段标题、摘要和选择状态。`
}
function remove(segment: TopicSegment) {
  preview.cancel()
  segments.value = segments.value.filter(value => value.id !== segment.id)
  delete timeInputs.value[segment.id]; delete keywordInputs.value[segment.id]
  if (selectedId.value === segment.id) selectedId.value = segments.value[0]?.id ?? ''
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
watch(() => props.jobId, () => { preview.cancel(); review.value = null; segments.value = []; selectedId.value = ''; timeInputs.value = {}; keywordInputs.value = {}; void load() }, { immediate: true })
watch(() => props.videoUrl, () => { preview.cancel() })
onBeforeUnmount(() => { stopped = true; version++; preview.cancel(); window.removeEventListener('beforeunload', onUnload) })
</script>

<template>
  <section class="panel topic-review" aria-label="视频话题解析与人工切分">
    <div class="panel-heading"><div><div class="eyebrow">TOPICS → YOUR SELECTION</div><h2>话题解析与选择 <span v-if="review">r{{ review.revision }} · 第 {{ review.generation }} 次分析</span></h2></div><div class="actions"><span v-if="review" class="status" :class="review.status.toLowerCase()">{{ dirty ? '有未保存修改' : review.status === 'CONFIRMED' ? '本版已确认' : '待选择草稿' }}</span><button class="text-button" :disabled="pending" @click="load">{{ dirty ? '放弃修改并重新读取' : '重新读取' }}</button><a v-if="review?.confirmed_revisions.length" class="text-button" :href="`#exports-${jobId}`">到切分导出 ↓</a></div></div>
    <div v-if="error" class="message error" role="alert">{{ error }}</div><div v-if="notice" class="message notice" role="status">{{ notice }}</div>
    <p v-if="!review" class="empty">{{ pending ? '正在读取话题结果…' : '话题结果暂不可用，请重新读取。' }}</p>
    <template v-if="review">
      <div class="topic-player"><video ref="player" :src="videoUrl" controls preload="metadata" playsinline @timeupdate="timeUpdate" @seeking="seeking" @seeked="seeked" @ended="timeUpdate" @error="preview.cancel()"></video><div><span class="eyebrow">AI PARSES · YOU DECIDE</span><h3>找到讨论内容，选择想保留的段落。</h3><p>AI 按转写语义识别话题及时间区间。点击话题回看原视频和转写，可调整标题、边界、拆分或补充片段。</p><p>默认不勾选。核对后保存、确认，再将所选话题切成独立 MP4。</p><strong>{{ enabledCount }} <small>/ {{ segments.length }} 段已选择</small></strong><p v-if="previewRange" class="preview-status" role="status">本段预览：{{ topicTime(previewRange.start_ms) }} → {{ topicTime(previewRange.end_ms) }}，到结束处自动暂停。<button class="text-button" @click="preview.cancel()">停止预览</button></p><p class="help">“预览本段”只播放该段；拖动进度或调整边界会结束本段预览。拆分优先使用段内的当前播放位置，否则取中点。</p></div></div>
      <div class="topic-timeline" aria-label="话题时间轴"><button v-for="segment in segments" :key="segment.id" :style="position(segment)" :class="{ enabled: segment.enabled, focused: selectedId === segment.id }" :title="`${segment.title} ${topicTime(segment.start_ms)} 至 ${topicTime(segment.end_ms)}`" :aria-label="`定位话题 ${segment.title}`" @click="seek(segment)"></button></div>
      <div class="timeline-labels"><span>00:00</span><span>当前 {{ topicTime(playhead) }}</span><span>{{ topicTime(review.duration_ms) }}</span></div>
      <fieldset class="topic-fields" :disabled="pending"><div class="topic-layout"><div class="topic-list"><div class="topic-tools"><span>共 {{ segments.length }} 个话题 · 已选 {{ enabledCount }} 个</span><button class="secondary" @click="add">＋ 在播放位置补片段</button></div>
        <div v-if="!segments.length" class="empty">暂无片段，可以手动补充。</div>
        <article v-for="(segment, index) in segments" :key="segment.id" class="topic-card" :class="{ focused: selectedId === segment.id }">
          <div class="topic-card-heading"><label><input v-model="segment.enabled" type="checkbox" :aria-label="`选择话题 ${segment.title}`"> 第 {{ index + 1 }} 段</label><span>{{ segment.origin === 'manual' ? '人工补充' : 'AI 识别' }}</span><button class="text-button" :disabled="!validTopicRange(segment, review.duration_ms)" :aria-label="`${previewRange?.id === segment.id ? '停止预览' : '预览本段'} ${index + 1}`" @click="previewSegment(segment)">{{ previewRange?.id === segment.id ? '停止预览' : '预览本段' }}</button><button class="text-button" @click="seek(segment)">定位回看 ↗</button></div>
          <label class="topic-title">话题标题<input v-model="segment.title" :aria-label="`话题标题 ${index + 1}`" maxlength="120"></label>
          <label class="topic-summary">本段摘要<textarea v-model="segment.summary" :aria-label="`话题摘要 ${index + 1}`" rows="3" maxlength="1000"></textarea></label>
          <label class="topic-keywords">关键词（逗号分隔）<input :value="keywordInputs[segment.id] ?? segment.keywords.join('，')" :aria-label="`话题关键词 ${index + 1}`" @input="keywords(segment, $event)"></label>
          <div class="topic-times"><label>开始（分:秒.毫秒）<input :value="topicTimeInput(segment, timeInputs, 'start_ms')" :aria-label="`话题开始 ${index + 1}`" placeholder="05:00.000" @input="boundary(segment, 'start_ms', $event)" @blur="normalizeTopicTime(segment, timeInputs, 'start_ms')"></label><label>结束（分:秒.毫秒）<input :value="topicTimeInput(segment, timeInputs, 'end_ms')" :aria-label="`话题结束 ${index + 1}`" placeholder="07:00.000" @input="boundary(segment, 'end_ms', $event)" @blur="normalizeTopicTime(segment, timeInputs, 'end_ms')"></label></div>
          <div class="topic-card-footer"><span>{{ topicTime(segment.start_ms) }} → {{ topicTime(segment.end_ms) }}<small v-if="segment.boundary_source === 'manual'"> · 人工边界</small></span><button class="text-button" @click="split(segment)">拆分</button><button class="text-button" @click="remove(segment)">移除</button></div>
        </article>
      </div><aside class="topic-evidence"><h3>对应语音转写 <span>{{ evidence.length }} 句</span></h3><p v-if="selected" class="selected-topic">{{ selected.title }}</p><p v-if="!evidence.length" class="help">选择话题查看原始转写。人工添加的静音区间可能没有语句。</p><button v-for="utterance in evidence" :key="utterance.utterance_id" @click="seekEvidence(utterance.start_ms)"><span>{{ topicTime(utterance.start_ms) }} → {{ topicTime(utterance.end_ms) }}</span><p>{{ utterance.text }}</p></button></aside></div></fieldset>
      <p v-if="validation" class="topic-validation" role="alert">{{ validation }}</p>
      <div class="topic-savebar"><div><b>{{ dirty ? '修改尚未保存' : `当前修订 r${review.revision}` }}</b><small>{{ enabledCount ? `已选 ${enabledCount} 段；确认后可创建切分任务。` : '请勾选需要切分的话题。' }}</small></div><button class="secondary" :disabled="pending || !dirty || !!validation" @click="save">保存修订</button><button class="primary" :disabled="pending || dirty || !!validation || !enabledCount || review.status === 'CONFIRMED'" @click="confirm">{{ review.status === 'CONFIRMED' ? '本版已确认' : '确认所选片段' }}</button></div>
      <div v-if="review.confirmed_revisions.length" class="topic-download"><label>留档版本 <select v-model.number="downloadRevision" aria-label="下载话题确认版本" :disabled="pending"><option v-for="revision in review.confirmed_revisions" :key="revision" :value="revision">r{{ revision }}</option></select></label><button class="text-button" :disabled="pending || downloadRevision === null" @click="downloadConfirmed">下载话题 JSON ↓</button></div>
      <ExportPanel :key="review.job_id" :job-id="review.job_id" :confirmed-revisions="review.confirmed_revisions" :current-revision="review.revision" :dirty="dirty" analysis-mode="topics" />
    </template>
  </section>
</template>

<style scoped>
.topic-review { margin-top: 24px; } .topic-review > .message { margin: 0 23px 20px; }
.panel-heading { gap: 14px; flex-wrap: wrap; } .panel-heading .actions { margin: 0; flex-wrap: wrap; } .panel-heading h2 > span { display: inline-block; margin-left: 8px; }
.topic-player { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr); border-block: 1px solid #e4eadf; }
.topic-player video { width: 100%; height: 300px; background: #202720; object-fit: contain; } .topic-player > div { padding: 26px; color: #647b58; font-size: 12px; line-height: 1.9; }
.topic-player h3 { font-size: 18px; font-weight: 500; color: #485f40; } .topic-player p { margin-top: 12px; } .topic-player strong { display: block; font-size: 26px; color: #d77b51; margin-top: 16px; } .topic-player strong small { font-size: 12px; color: #7f9274; font-weight: 400; }
.topic-timeline { position: relative; height: 32px; margin: 24px 23px 10px; background: #f0f3ec; border-radius: 5px; overflow: hidden; }
.topic-timeline button { position: absolute; height: 100%; background: #e3e9dd; border: 1px solid white; border-radius: 4px; } .topic-timeline .enabled { background: #b3c99f; } .topic-timeline .focused { background: #e99b75; }
.timeline-labels { display: flex; justify-content: space-between; gap: 6px; margin: 0 23px 22px; font-size: 10px; color: #819374; }
.topic-fields { min-width: 0; margin: 0; padding: 0; border: 0; } .topic-layout { display: grid; grid-template-columns: minmax(0, 2fr) minmax(220px, 1fr); border-top: 1px solid #e4eadf; }
.topic-list { min-width: 0; padding: 22px 23px; } .topic-tools { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; justify-content: space-between; margin-bottom: 18px; font-size: 11px; color: #819374; }
.topic-card { border: 1px solid #e3e9de; border-radius: 7px; padding: 16px; margin-bottom: 14px; } .topic-card.focused { border-color: #e4ac8d; background: #fefaf5; }
.topic-card-heading { display: flex; gap: 10px; align-items: center; font-size: 12px; } .topic-card-heading label { flex: 1; } .topic-card-heading > span { font-size: 10px; color: #9aaa90; }
.topic-title, .topic-summary, .topic-keywords, .topic-times label { display: block; font-size: 10px; color: #839776; min-width: 0; margin-top: 16px; }
.topic-card input:not([type=checkbox]), .topic-card textarea { display: block; width: 100%; min-width: 0; border: 1px solid #dde6d5; border-radius: 4px; background: white; padding: 9px; font: inherit; font-size: 12px; color: #425a39; margin-top: 7px; }
.topic-card textarea { resize: vertical; line-height: 1.8; outline-offset: 4px; } .topic-card textarea:focus { outline: 2px solid #ea8d68; }
.topic-times { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; } .topic-card-footer { display: flex; align-items: center; gap: 14px; margin-top: 16px; flex-wrap: wrap; } .topic-card-footer > span { flex: 1; font-size: 10px; color: #819374; min-width: 170px; } .topic-card-footer small { font-size: 10px; }
.topic-evidence { background: #f9fbf6; border-left: 1px solid #e4eadf; padding: 24px 20px; max-height: 1100px; overflow: auto; min-width: 0; } .topic-evidence h3 { font-size: 12px; font-weight: 500; color: #637e54; } .topic-evidence h3 span { color: #9aaf8b; margin-left: 6px; font-size: 10px; } .selected-topic { margin: 15px 0; font-size: 12px; line-height: 1.8; color: #5e7750; overflow-wrap: anywhere; }
.topic-evidence button { display: block; width: 100%; text-align: left; background: none; border-bottom: 1px solid #e8eee1; padding: 13px 0; } .topic-evidence button span { color: #91a87e; font-size: 10px; } .topic-evidence button p { font-size: 12px; color: #697f5a; line-height: 1.9; margin-top: 7px; overflow-wrap: anywhere; }
.topic-validation { margin: 0 23px 20px; color: #b46642; font-size: 12px; line-height: 1.8; }
.topic-savebar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 20px 23px; border-top: 1px solid #e3e9db; background: white; position: sticky; bottom: 0; z-index: 1; } .topic-savebar > div { flex: 1; min-width: 190px; } .topic-savebar b { font-size: 12px; font-weight: 500; color: #5e7750; } .topic-savebar small { display: block; font-size: 10px; color: #97ac87; margin-top: 7px; } .topic-savebar .primary { padding: 10px 15px; }
.topic-download { display: flex; align-items: center; flex-wrap: wrap; gap: 18px; padding: 18px 23px; color: #819374; font-size: 11px; } .topic-download select { margin-left: 8px; }
@media(max-width:800px) { .topic-player, .topic-layout { grid-template-columns: 1fr; } .topic-player video { height: 280px; } .topic-evidence { max-height: 400px; border-left: 0; border-top: 1px solid #e4eadf; } }
@media(max-width:450px) { .topic-player > div, .topic-list { padding: 20px 14px; } .topic-card { padding: 12px; } .topic-card-heading > span { display: none; } .topic-savebar { padding: 16px 14px; } .topic-savebar > div { flex-basis: 100%; } .topic-timeline { margin-inline: 14px; } .timeline-labels { margin-inline: 14px; } }
</style>
