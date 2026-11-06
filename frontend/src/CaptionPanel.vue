<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, ApiError, post } from './api'
import CaptionRenderPanel from './CaptionRenderPanel.vue'
import { captionBase, captionDraftInvalid, captionFileUrl, captionPayload, captionTime, captionValidation, copyCaptionCue, copyCaptionCues,
  editCaptionTime, parseCaptionSnapshot, sameCaptionSource, CaptionResponseError,
  type CaptionCue, type CaptionSnapshot, type CaptionTimeDrafts } from './captions'

const props = withDefaults(defineProps<{ exportId: string; clipId: string; contentUrl: string; active?: boolean }>(), { active: true })
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement | null>(null), player = ref<HTMLVideoElement | null>(null)
const snapshot = ref<CaptionSnapshot | null>(null), cues = ref<CaptionCue[]>([]), baseline = ref('')
const timeDrafts = ref<CaptionTimeDrafts>({}), action = ref(''), error = ref(''), notice = ref('')
const onlyReview = ref(false), page = ref(0), acknowledged = ref(false), downloadRevision = ref<number | null>(null)
const view = ref<'edit' | 'renders'>('edit'), renderEditor = ref<{ pending: boolean } | null>(null)
const editTab = ref<HTMLButtonElement | null>(null), renderTab = ref<HTMLButtonElement | null>(null)
const selectedId = ref(''), playhead = ref(0), deleted = ref<{ cue: CaptionCue; index: number }[]>([])
let stopped = false, version = 0, previewEnd: number | null = null, initialSeek: number | null = null
const pageSize = 50
const pending = computed(() => !!action.value || !!renderEditor.value?.pending)
const dirty = computed(() => !!snapshot.value && (JSON.stringify(captionPayload(cues.value)) !== baseline.value || captionDraftInvalid(timeDrafts.value)))
const validation = computed(() => snapshot.value ? captionValidation(cues.value, snapshot.value.source.duration_ms, timeDrafts.value) : '')
const requiresReview = computed(() => cues.value.filter(cue => cue.boundary_partial && !cue.reviewed).length)
const filtered = computed(() => onlyReview.value ? cues.value.filter(cue => cue.boundary_partial && !cue.reviewed) : cues.value)
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
const visibleCues = computed(() => filtered.value.slice(page.value * pageSize, (page.value + 1) * pageSize))
const currentCue = computed(() => cues.value.find(cue => cue.start_ms <= playhead.value && playhead.value < cue.end_ms))
const base = computed(() => captionBase(props.exportId, props.clipId))
const canConfirm = computed(() => !!snapshot.value && !pending.value && !dirty.value && !validation.value && !requiresReview.value && acknowledged.value && snapshot.value.status !== 'CONFIRMED')
defineExpose({ dirty, pending })

function fail(value: unknown) {
  const raw = value instanceof ApiError ? value.code : value instanceof CaptionResponseError ? 'INVALID_CAPTION_RESPONSE' : 'CLIENT_ERROR'
  const code = typeof raw === 'string' && /^[A-Z][A-Z0-9_]{0,99}$/.test(raw) ? raw : 'CLIENT_ERROR'
  const names: Record<string, string> = {
    NETWORK_UNAVAILABLE: '暂时无法连接服务，当前字幕修改仍保留。', REQUEST_TIMEOUT: '请求超时，当前修改仍保留。可点击“核对服务器版本”确认保存状态。',
    CAPTION_REVISION_CONFLICT: '服务端已有新版本，当前修改仍保留。请先核对服务器版本，再决定如何处理。',
    INVALID_CAPTION_REVIEW: '字幕文字或时间不符合要求，请检查提示后保存。', CAPTION_REVIEW_REQUIRED: '还有边界截断句需要明确标记已核对，保存后再确认。',
    CAPTION_CONFIRMATION_REQUIRED: '请先确认字幕版本，再下载对应文件。', CAPTION_SOURCE_INVALID: '成片或原始转写来源未通过校验，未替换当前字幕。',
    CAPTION_CONTENT_INVALID: '字幕内容未通过校验，当前修改仍保留。', CAPTION_REVISION_NOT_FOUND: '所选字幕版本不存在，请核对版本。',
    CAPTIONS_NOT_INITIALIZED: '此成片尚未准备字幕稿，请关闭后重新进入。', INVALID_CAPTION_RESPONSE: '返回的字幕来源或内容不匹配，已保留当前修改。',
  }
  error.value = names[code] ?? `字幕操作未完成：${code}`
}
function parse(raw: unknown) {
  const value = parseCaptionSnapshot(raw, props.exportId, props.clipId)
  if (value.content_url !== props.contentUrl) throw new CaptionResponseError()
  if (snapshot.value) sameCaptionSource(snapshot.value, value)
  return value
}
function apply(value: CaptionSnapshot) {
  snapshot.value = value; cues.value = copyCaptionCues(value.cues); timeDrafts.value = {}; deleted.value = []; acknowledged.value = false
  baseline.value = JSON.stringify(captionPayload(cues.value))
  if (!selectedId.value || !cues.value.some(cue => cue.id === selectedId.value)) selectedId.value = cues.value[0]?.id ?? ''
  if (downloadRevision.value === null || !value.confirmed_revisions.includes(downloadRevision.value)) downloadRevision.value = value.confirmed_revisions.length ? Math.max(...value.confirmed_revisions) : null
}
function current(op: number) { return !stopped && op === version }
async function initialize() {
  action.value = 'load'; error.value = ''; const op = ++version
  try { const raw = await post<unknown>(base.value); if (current(op)) apply(parse(raw)) }
  catch (value) { if (current(op)) fail(value) }
  finally { if (current(op)) action.value = '' }
}
async function reload() {
  if (pending.value) return
  action.value = 'reload'; error.value = ''; notice.value = ''; const op = ++version
  try {
    const raw = await api<unknown>(base.value)
    if (!current(op)) return
    const value = parse(raw)
    if (dirty.value && (captionDraftInvalid(timeDrafts.value) || JSON.stringify(captionPayload(value.cues)) !== JSON.stringify(captionPayload(cues.value)))) {
      error.value = `服务端最新为 r${value.revision}，与本页修改不同。本页内容仍保留；请先复制需保留的修改，或放弃本页修改后重新读取。`; return
    }
    apply(value); notice.value = `已核对服务端字幕版本 r${value.revision}。`
  } catch (value) { if (current(op)) fail(value) }
  finally { if (current(op)) action.value = '' }
}
async function save() {
  if (!snapshot.value || pending.value || !dirty.value || validation.value) return
  const revision = snapshot.value.revision, op = ++version
  action.value = 'save'; error.value = ''; notice.value = ''
  try {
    const raw = await api<unknown>(base.value, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ base_revision: revision, ...captionPayload(cues.value) }) })
    if (!current(op)) return
    const value = parse(raw); if (value.revision !== revision + 1) throw new CaptionResponseError()
    apply(value); notice.value = `字幕 r${value.revision} 已保存，核对后可确认并下载。`
  } catch (value) { if (current(op)) fail(value) }
  finally { if (current(op)) action.value = '' }
}
async function confirm() {
  if (!canConfirm.value || !snapshot.value) return
  const revision = snapshot.value.revision, op = ++version
  action.value = 'confirm'; error.value = ''; notice.value = ''
  try {
    const raw = await post<unknown>(`${base.value}/confirm`, { revision, acknowledge_review: true })
    if (!current(op)) return
    const value = parse(raw); if (value.revision !== revision || value.status !== 'CONFIRMED') throw new CaptionResponseError()
    apply(value); downloadRevision.value = revision; notice.value = `字幕 r${revision} 已确认。下载区已可获取字幕和原视频素材包。`
  } catch (value) { if (current(op)) fail(value) }
  finally { if (current(op)) action.value = '' }
}
function discard() { if (!pending.value && snapshot.value) { apply(snapshot.value); error.value = ''; notice.value = '未保存修改已放弃。' } }
function requestClose() {
  if (pending.value) { error.value = '正在提交操作，请完成后再关闭。'; return }
  if (dirty.value) { error.value = '字幕有未保存修改，请先保存或放弃修改，再关闭。'; return }
  player.value?.pause(); dialog.value?.close(); emit('close')
}
function removeCue(cue: CaptionCue) {
  const index = cues.value.findIndex(value => value.id === cue.id)
  if (pending.value || index < 0) return
  deleted.value.push({ cue: copyCaptionCue(cue), index }); cues.value.splice(index, 1); delete timeDrafts.value[cue.id]; acknowledged.value = false
}
function undoDelete() { if (!pending.value) { const item = deleted.value.pop(); if (item) cues.value.splice(Math.min(item.index, cues.value.length), 0, item.cue) } }
function editTime(cue: CaptionCue, field: 'start_ms' | 'end_ms', event: Event) { editCaptionTime(cue, timeDrafts.value, field, (event.target as HTMLInputElement).value); acknowledged.value = false }
function normalizeTime(cue: CaptionCue, field: 'start_ms' | 'end_ms') { if (!captionDraftInvalid({ [cue.id]: { [field]: timeDrafts.value[cue.id]?.[field] } })) { delete timeDrafts.value[cue.id]?.[field] } }
function timeValue(cue: CaptionCue, field: 'start_ms' | 'end_ms') { return timeDrafts.value[cue.id]?.[field] ?? captionTime(cue[field]) }
function mediaActive() { return props.active && view.value === 'edit' && !!dialog.value?.open && !stopped }
function guardPlayback(event: Event) { if (!mediaActive()) (event.currentTarget as HTMLVideoElement).pause() }
function updateTime() {
  if (!player.value) return
  playhead.value = Math.round(player.value.currentTime * 1000)
  if (previewEnd !== null && playhead.value >= previewEnd) { player.value.pause(); previewEnd = null }
}
function seeking() { if (initialSeek === null || !player.value || Math.abs(player.value.currentTime * 1000 - initialSeek) > 2) previewEnd = null }
function seek(ms: number) { if (mediaActive() && player.value) { previewEnd = null; initialSeek = null; player.value.pause(); player.value.currentTime = Math.max(0, ms) / 1000 } }
function previewCue(cue: CaptionCue) {
  if (!mediaActive() || !player.value || !snapshot.value || captionValidation([cue], snapshot.value.source.duration_ms)) return
  selectedId.value = cue.id; const video = player.value; initialSeek = cue.start_ms; previewEnd = cue.end_ms; video.currentTime = cue.start_ms / 1000
  void video.play().then(() => { if (!mediaActive()) video.pause() }).catch(() => { if (!stopped) notice.value = '点击播放器播放按钮，可继续回看当前字幕。' })
}
function showDialog() { if (!stopped && props.active && dialog.value && !dialog.value.open) dialog.value.showModal() }
function switchView(value: 'edit' | 'renders') { if (!pending.value) view.value = value }
function viewKey(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key) || pending.value) return
  event.preventDefault()
  view.value = event.key === 'Home' ? 'edit' : event.key === 'End' ? 'renders' : view.value === 'edit' ? 'renders' : 'edit'
  void nextTick(() => { if (!stopped && props.active) (view.value === 'edit' ? editTab.value : renderTab.value)?.focus() })
}
function unload(event: BeforeUnloadEvent) { if (dirty.value || pending.value) { event.preventDefault(); event.returnValue = '' } }
watch(onlyReview, () => { page.value = 0 })
watch(pages, count => { page.value = Math.min(page.value, count - 1) })
watch(view, () => { player.value?.pause() }, { flush: 'sync' })
watch(() => props.active, active => { if (!active) { player.value?.pause(); dialog.value?.close() } else void nextTick(showDialog) }, { flush: 'post' })
onMounted(() => { showDialog(); window.addEventListener('beforeunload', unload); void initialize() })
onBeforeUnmount(() => { stopped = true; version++; player.value?.pause(); dialog.value?.close(); window.removeEventListener('beforeunload', unload) })
</script>

<template>
  <dialog ref="dialog" class="caption-dialog" aria-label="成片字幕校对" @cancel.prevent="requestClose">
    <header class="caption-header"><div><h2>字幕校对</h2><p>{{ snapshot?.clip_title ?? '正在读取成片字幕' }}<span v-if="snapshot"> · r{{ snapshot.revision }} {{ snapshot.status === 'CONFIRMED' ? '已确认' : '草稿' }}</span></p></div><button class="caption-button" :disabled="pending" @click="requestClose">关闭</button></header>
    <p v-if="error" class="caption-message error" role="alert">{{ error }}</p><p v-if="notice" class="caption-message" role="status">{{ notice }}</p>
    <p v-if="!snapshot" class="caption-empty">{{ pending ? '正在从已有转写准备字幕，不会重新识别语音。' : '字幕暂不可用。' }}<button v-if="!pending" class="caption-button" @click="initialize">重新读取</button></p>
    <template v-if="snapshot">
      <div class="caption-view-tabs" role="tablist" aria-label="字幕与带字幕视频" @keydown="viewKey"><button :id="`caption-tab-edit-${exportId}-${clipId}`" ref="editTab" role="tab" :aria-controls="`caption-page-edit-${exportId}-${clipId}`" :aria-selected="view === 'edit'" :tabindex="view === 'edit' ? 0 : -1" :disabled="pending" @click="switchView('edit')">校对字幕<span v-if="dirty" class="caption-draft-dot" aria-label="有未保存修改"></span></button><button :id="`caption-tab-renders-${exportId}-${clipId}`" ref="renderTab" role="tab" :aria-controls="`caption-page-renders-${exportId}-${clipId}`" :aria-selected="view === 'renders'" :tabindex="view === 'renders' ? 0 : -1" :disabled="pending" @click="switchView('renders')">带字幕视频</button></div>
      <div :id="`caption-page-edit-${exportId}-${clipId}`" v-show="view === 'edit'" class="caption-edit-page" role="tabpanel" :aria-labelledby="`caption-tab-edit-${exportId}-${clipId}`">
      <div class="caption-main"><section class="caption-watch"><div class="caption-video"><video ref="player" :src="contentUrl" controls preload="metadata" playsinline @play="guardPlayback" @timeupdate="updateTime" @seeking="seeking" @seeked="initialSeek = null"></video><p v-if="currentCue" class="caption-overlay" aria-live="off">{{ currentCue.text }}</p></div><label class="caption-seek">成片播放位置 {{ captionTime(playhead) }} / {{ captionTime(snapshot.source.duration_ms) }}<input type="range" min="0" :max="snapshot.source.duration_ms" :value="playhead" step="1" aria-label="字幕成片时间轴" @input="seek(Number(($event.target as HTMLInputElement).value))"></label><details class="caption-guide"><summary>字幕时间与预览说明</summary><p class="caption-help">字幕以成片开始为 00:00。按原句时间生成，边界截断句需逐条回看核对。</p><p class="caption-help">预览字幕只显示在本页，原 MP4 保持原样；下载包提供独立字幕文件。</p></details><div class="caption-downloads"><h3>下载已确认版本</h3><template v-if="snapshot.confirmed_revisions.length"><label>版本<select v-model.number="downloadRevision" aria-label="字幕下载版本"><option v-for="rev in snapshot.confirmed_revisions" :key="rev" :value="rev">r{{ rev }}</option></select></label><div v-if="downloadRevision !== null" class="caption-download-links"><a v-for="format in (['srt', 'vtt', 'txt', 'json'] as const)" :key="format" :href="captionFileUrl(snapshot, downloadRevision, format)" download target="_blank" rel="noopener">{{ format === 'json' ? '字幕 JSON' : format.toUpperCase() }}</a><a :href="captionFileUrl(snapshot, downloadRevision, 'package')" download target="_blank" rel="noopener">原视频＋字幕 ZIP</a></div><p v-if="dirty" class="caption-help">当前未保存修改不包含在下载内容中。</p></template><p v-else class="caption-help">保存并确认字幕后，可下载 SRT、VTT、TXT 和原视频素材包。</p></div></section>
        <section class="caption-editor"><div class="caption-list-toolbar"><strong>{{ cues.length }} 条字幕</strong><label><input v-model="onlyReview" type="checkbox">只看待核对 {{ requiresReview }}</label><button v-if="deleted.length" class="caption-link" :disabled="pending" @click="undoDelete">撤销删除</button></div><p v-if="requiresReview" class="caption-warning">有 {{ requiresReview }} 条字幕来自被剪断的原句。修改文字不会自动解除标记，请回看后勾选“已核对”。</p><div class="caption-cue-list"><p v-if="!visibleCues.length" class="caption-empty">{{ cues.length ? '没有待核对的字幕。' : '当前版本没有字幕；确认空字幕版本前，请核对这是否符合预期。' }}</p><article v-for="cue in visibleCues" :key="cue.id" class="caption-cue" :class="{ selected: selectedId === cue.id || currentCue?.id === cue.id }"><div class="caption-cue-heading"><button class="caption-link" @click="previewCue(cue)">回看本句 {{ captionTime(cue.start_ms) }}</button><span v-if="cue.boundary_partial" class="caption-partial">{{ cue.reviewed ? '截断句已核对' : '边界截断待核对' }}</span><button class="caption-link danger" :disabled="pending" :aria-label="`删除字幕 ${cue.id}`" @click="removeCue(cue)">删除</button></div><label class="caption-text-label">字幕文字<textarea v-model="cue.text" rows="2" maxlength="2000" :disabled="pending" :aria-label="`字幕文字 ${cue.id}`" @input="acknowledged = false"></textarea></label><div class="caption-times"><label>开始<input :value="timeValue(cue, 'start_ms')" :disabled="pending" :aria-label="`字幕开始 ${cue.id}`" @input="editTime(cue, 'start_ms', $event)" @blur="normalizeTime(cue, 'start_ms')"></label><label>结束<input :value="timeValue(cue, 'end_ms')" :disabled="pending" :aria-label="`字幕结束 ${cue.id}`" @input="editTime(cue, 'end_ms', $event)" @blur="normalizeTime(cue, 'end_ms')"></label></div><label v-if="cue.boundary_partial" class="caption-reviewed"><input v-model="cue.reviewed" type="checkbox" :disabled="pending" @change="acknowledged = false">我已回看并核对这条截断句</label><details class="caption-original"><summary>原始转写参考</summary><p>{{ cue.source_text }}</p></details></article></div><div v-if="pages > 1" class="caption-pagination"><button class="caption-button" :disabled="page === 0" @click="page--">上一页</button><span>{{ page + 1 }} / {{ pages }} · 每页 {{ pageSize }} 条</span><button class="caption-button" :disabled="page + 1 >= pages" @click="page++">下一页</button></div></section>
      </div>
      <footer class="caption-footer"><p v-if="validation" class="caption-validation" role="alert">{{ validation }}</p><div class="caption-footer-main"><div><strong>{{ dirty ? '有未保存字幕修改' : `字幕 r${snapshot.revision} ${snapshot.status === 'CONFIRMED' ? '已确认' : '待确认'}` }}</strong><label class="caption-ack"><input v-model="acknowledged" type="checkbox" :disabled="pending || dirty || !!requiresReview || !!validation || snapshot.status === 'CONFIRMED'">{{ cues.length ? '我已核对字幕文字与时间' : '本版本没有字幕，仍确认空字幕版本' }}</label></div><div class="caption-footer-actions"><div class="caption-secondary-actions"><button class="caption-link" :disabled="pending" @click="reload">核对服务器版本</button><button v-if="dirty" class="caption-link" :disabled="pending" @click="discard">放弃未保存修改</button></div><div class="caption-primary-actions"><button class="caption-button" :disabled="pending || !dirty || !!validation" @click="save">{{ action === 'save' ? '正在保存…' : '保存字幕' }}</button><button class="caption-button primary" :disabled="!canConfirm" @click="confirm">{{ action === 'confirm' ? '正在确认…' : '确认字幕版本' }}</button></div></div></div></footer>
      </div>
      <div :id="`caption-page-renders-${exportId}-${clipId}`" v-show="view === 'renders'" class="caption-render-page" role="tabpanel" :aria-labelledby="`caption-tab-renders-${exportId}-${clipId}`"><CaptionRenderPanel ref="renderEditor" :snapshot="snapshot" :caption-dirty="dirty" :caption-pending="!!action" :active="props.active && view === 'renders'" /></div>
    </template>
  </dialog>
</template>

<style scoped>
.caption-dialog { box-sizing: border-box; width: min(1200px, calc(100vw - 32px)); height: min(860px, calc(100dvh - 32px)); max-width: none; max-height: none; margin: auto; padding: 0; border: 1px solid #cbd5e1; border-radius: 12px; background: white; color: #1f2937; font: 14px/1.65 system-ui, sans-serif; overflow: hidden; }
.caption-dialog[open] { display: flex; flex-direction: column; }.caption-dialog:not([open]) { display: none; }.caption-dialog::backdrop { background: #0f172a66; }
.caption-view-tabs { display: flex; flex: none; gap: 22px; padding: 0 18px; border-bottom: 1px solid #e2e8f0; background: white; }.caption-view-tabs > button { position: relative; min-height: 42px; padding: 10px 1px 8px; border: 0; border-bottom: 2px solid transparent; background: none; color: #64748b; font: inherit; font-size: 14px; cursor: pointer; }.caption-view-tabs > button[aria-selected=true] { border-color: #e47650; color: #bd5735; font-weight: 600; }.caption-draft-dot { display: inline-block; width: 6px; height: 6px; margin-left: 6px; border-radius: 50%; background: #d99a45; vertical-align: middle; }.caption-edit-page, .caption-render-page { display: flex; flex-direction: column; flex: 1; min-width: 0; min-height: 0; overflow: hidden; }
.caption-header { display: flex; flex: none; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 18px; border-bottom: 1px solid #e2e8f0; }.caption-header > div { min-width: 0; }.caption-header > button { flex: none; }.caption-header h2 { margin: 0; font-size: 18px; }.caption-header p { margin: 4px 0 0; color: #64748b; font-size: 13px; overflow-wrap: anywhere; }.caption-header p span { white-space: nowrap; }
.caption-message { flex: none; max-height: 70px; overflow: auto; margin: 8px 20px; padding: 9px 12px; background: #f1f5f9; border-radius: 6px; color: #475569; }.caption-message.error { background: #fff1eb; color: #9a4f32; }
.caption-main { flex: 1; display: grid; grid-template-columns: minmax(240px, .85fr) minmax(0, 1.35fr); min-height: 0; min-width: 0; gap: 16px; padding: 14px 18px; }.caption-watch { min-height: 0; min-width: 0; overflow-y: auto; overflow-x: hidden; padding-right: 3px; }.caption-video { position: relative; min-width: 0; max-width: 100%; background: #0f172a; border-radius: 7px; overflow: hidden; }.caption-video video { display: block; box-sizing: border-box; width: 100%; min-width: 0; max-width: 100%; max-height: 250px; aspect-ratio: 16/9; object-fit: contain; }.caption-overlay { pointer-events: none; position: absolute; left: 6%; right: 6%; bottom: 40px; margin: 0; padding: 4px 7px; background: #0008; color: white; font-size: 16px; text-align: center; white-space: pre-wrap; line-height: 1.5; text-shadow: 0 1px 2px black; overflow-wrap: anywhere; }.caption-seek { display: block; min-width: 0; color: #475569; font-size: 13px; margin-top: 10px; overflow-wrap: anywhere; }.caption-seek input { display: block; box-sizing: border-box; width: 100%; min-width: 0; max-width: 100%; margin: 7px 0 0; padding: 0; accent-color: #e47650; }.caption-help { font-size: 13px; color: #64748b; line-height: 1.7; margin: 8px 0; overflow-wrap: anywhere; }.caption-guide { margin-top: 10px; color: #64748b; font-size: 13px; }.caption-guide summary { cursor: pointer; padding: 4px 0; }
.caption-editor { display: flex; flex-direction: column; min-height: 0; min-width: 0; }.caption-list-toolbar { flex: none; display: flex; align-items: center; flex-wrap: wrap; gap: 8px 14px; padding-bottom: 10px; }.caption-list-toolbar strong { font-weight: 600; }.caption-list-toolbar label { color: #475569; font-size: 13px; }.caption-cue-list { flex: 1; min-height: 0; min-width: 0; overflow-y: auto; overscroll-behavior: contain; padding-right: 6px; scrollbar-gutter: stable; }.caption-cue { min-width: 0; border: 1px solid #e2e8f0; border-radius: 8px; padding: 12px; margin-bottom: 10px; }.caption-cue.selected { border-color: #e6b7a4; background: #fffcfa; }.caption-cue-heading { display: flex; align-items: center; flex-wrap: wrap; min-width: 0; gap: 4px 10px; margin-bottom: 8px; }.caption-cue-heading .danger { margin-left: auto; color: #a0533b; }.caption-partial { color: #8b632f; background: #fff5e4; border-radius: 4px; padding: 2px 6px; font-size: 12px; }.caption-text-label, .caption-times label { display: block; min-width: 0; color: #475569; font-size: 13px; }.caption-cue textarea, .caption-times input { display: block; box-sizing: border-box; width: 100%; min-width: 0; max-width: 100%; border: 1px solid #cbd5e1; border-radius: 5px; background: white; color: #1f2937; font: inherit; font-size: 14px; padding: 8px 10px; margin-top: 5px; }.caption-cue textarea { resize: vertical; line-height: 1.75; }.caption-times { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 10px; margin-top: 10px; }.caption-reviewed { display: block; margin-top: 10px; color: #8b6335; font-size: 13px; line-height: 1.7; }.caption-original { margin-top: 8px; font-size: 13px; color: #64748b; }.caption-original summary { cursor: pointer; padding: 4px 0; }.caption-original p { margin: 7px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }.caption-warning { flex: none; margin: 0 0 10px; padding: 8px 10px; color: #8b632f; background: #fff7e9; border-radius: 5px; font-size: 13px; line-height: 1.6; }.caption-empty { padding: 20px; color: #64748b; }.caption-pagination { display: flex; flex: none; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; padding-top: 8px; color: #64748b; font-size: 13px; }
.caption-downloads { margin-top: 12px; padding-top: 10px; border-top: 1px solid #e2e8f0; }.caption-downloads h3 { font-size: 14px; margin: 0 0 8px; }.caption-downloads select { margin-left: 8px; min-height: 34px; padding: 5px 8px; border: 1px solid #cbd5e1; border-radius: 4px; background: white; color: #334155; font-size: 14px; }.caption-download-links { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 10px; }.caption-download-links a { display: inline-flex; align-items: center; min-height: 36px; max-width: 100%; border: 1px solid #dce3ec; border-radius: 5px; padding: 6px 9px; color: #475569; text-decoration: none; font-size: 13px; overflow-wrap: anywhere; }
.caption-footer { flex: none; max-height: 40dvh; overflow-y: auto; padding: 10px 18px; border-top: 1px solid #e2e8f0; background: white; }.caption-footer-main { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; min-width: 0; gap: 10px 16px; }.caption-footer-main > div { min-width: 0; }.caption-footer strong { font-size: 14px; }.caption-ack { display: block; color: #475569; font-size: 13px; line-height: 1.65; margin-top: 5px; overflow-wrap: anywhere; }.caption-footer-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 12px; }.caption-validation { margin: 0 0 8px; color: #a34f30; font-size: 13px; overflow-wrap: anywhere; }.caption-button { min-height: 38px; padding: 8px 12px; border: 1px solid #cbd5e1; border-radius: 6px; background: white; color: #334155; font: inherit; font-size: 14px; line-height: 1.5; cursor: pointer; white-space: normal; }.caption-button.primary { border-color: #e47650; background: #e47650; color: white; }.caption-link { min-height: 32px; max-width: 100%; border: 0; padding: 3px 0; background: none; color: #475569; font: inherit; font-size: 13px; line-height: 1.6; cursor: pointer; white-space: normal; overflow-wrap: anywhere; }.caption-dialog button:disabled { opacity: .5; cursor: not-allowed; }.caption-dialog input[type=checkbox] { accent-color: #e47650; }.caption-dialog button:focus-visible, .caption-dialog a:focus-visible { outline: 2px solid #e89778; outline-offset: 3px; }
.caption-secondary-actions, .caption-primary-actions { display: flex; flex-wrap: wrap; align-items: center; min-width: 0; gap: 8px 12px; }
@media(max-width:760px) { .caption-dialog { width: calc(100vw - 16px); height: calc(100dvh - 16px); border-radius: 8px; }.caption-header { padding: 10px 14px; }.caption-main { display: block; overflow-y: auto; padding: 12px 14px; }.caption-watch { overflow: visible; padding-right: 0; }.caption-video video { max-height: 190px; }.caption-editor { margin-top: 16px; }.caption-cue-list { overflow: visible; padding-right: 0; scrollbar-gutter: auto; }.caption-downloads { margin-bottom: 16px; }.caption-footer { padding: 10px 14px; }.caption-footer-actions { width: 100%; gap: 6px; }.caption-secondary-actions { width: 100%; justify-content: space-between; gap: 4px 10px; }.caption-primary-actions { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); width: 100%; gap: 10px; }.caption-footer-main { gap: 7px; }.caption-footer-main > div:first-child { width: 100%; }.caption-secondary-actions > .caption-link { text-align: left; }.caption-overlay { font-size: 14px; } }
@media(max-width:380px) { .caption-header, .caption-main, .caption-footer { padding-inline: 10px; }.caption-cue { padding: 10px; }.caption-times { gap: 8px; }.caption-times input { padding-inline: 6px; }.caption-header h2 { font-size: 17px; } }
@media(min-width:761px) and (max-height:760px) { .caption-header { padding-block: 10px; }.caption-main { padding-block: 12px; }.caption-video video { max-height: 190px; } }
</style>
