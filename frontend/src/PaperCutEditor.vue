<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { compilePaperCut, editPaperCut, newPaperCutHistory, stepPaperCut, type PaperCutSource } from './paperCut'
import { SequencePreview, type SequencePreviewPart, type SequencePreviewState } from './sequencePreview'
import { topicTime } from './topics'
import CutScrubber from './CutScrubber.vue'
import { editToSource, sourceToEdit } from './editTimeline'
import PaperCutBudget from './PaperCutBudget.vue'
import PaperCutSuggestion from './PaperCutSuggestion.vue'
import type { PaperCutContext } from './paperCutSuggestion'

const props = defineProps<{ source: PaperCutSource; context: PaperCutContext; title: string; goal: string; videoUrl: string; token: number; active: boolean; stale: boolean }>()
const emit = defineEmits<{ apply: [token: number, removedIds: ReadonlySet<string>]; close: [] }>()
const dialog = ref<HTMLDialogElement | null>(null), player = ref<HTMLVideoElement | null>(null)
const page = ref(1), pageSize = 30, query = ref(''), list = ref<HTMLElement | null>(null)
let rangeStart: string | undefined
let unfilteredPosition = { page: 1, top: 0 }, nextScrollTop = 0
const selected = ref(new Set<string>()), history = shallowRef(newPaperCutHistory())
const dirty = computed(() => history.value.removed.size > 0)
defineExpose({ dirty, pending: false })
const error = ref(''), ready = ref(false), mediaError = ref(false)
const visible = ref(!document.hidden), enabled = computed(() => props.active && !props.stale && visible.value)
const cut = computed(() => compilePaperCut(props.source, history.value.removed))
const matching = computed(() => {
  const term = query.value.trim().toLocaleLowerCase()
  return term ? props.source.rows.filter(({ utterance }) => utterance.text.toLocaleLowerCase().includes(term)) : props.source.rows
})
const pages = computed(() => Math.ceil(matching.value.length / pageSize))
const rows = computed(() => matching.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const hiddenSelected = computed(() => selected.value.size - rows.value.filter(row => selected.value.has(row.utterance.utterance_id)).length)
const canRemove = computed(() => [...selected.value].some(id => !history.value.removed.has(id)))
const canRestore = computed(() => [...selected.value].some(id => history.value.removed.has(id)))
const playback = shallowRef<SequencePreviewState | null>(null)
const cursor = ref<number | null>(0)
let draftPlaybackStart = 0
const preview = new SequencePreview((state, reason) => {
  const previous = playback.value
  playback.value = state
  if (state?.id === 'paper-cut') cursor.value = Math.min(cut.value.durationMs, draftPlaybackStart + state.played_ms)
  else if (state) cursor.value = sourceToEdit(cut.value.parts, Math.round((player.value?.currentTime ?? 0) * 1000))
  else if (previous?.id === 'paper-cut' && reason === 'finished') cursor.value = cut.value.durationMs
  if (reason === 'failed') error.value = '未能播放当前草稿，请检查原视频后重试。'
})
let stopped = false, hasAuditioned = false

function toggle(id: string, event: MouseEvent) {
  const index = rows.value.findIndex(row => row.utterance.utterance_id === id)
  if (!enabled.value || index < 0 || rows.value[index]!.anchor) return
  const previous = event.shiftKey ? rows.value.findIndex(row => row.utterance.utterance_id === rangeStart) : -1
  const start = previous >= 0 ? Math.min(index, previous) : index
  const end = previous >= 0 ? Math.max(index, previous) : index
  const checked = (event.currentTarget as HTMLInputElement).checked
  for (const row of rows.value.slice(start, end + 1)) {
    if (row.anchor) continue
    if (checked) selected.value.add(row.utterance.utterance_id)
    else selected.value.delete(row.utterance.utterance_id)
  }
  rangeStart = id
}
function clearSelection() {
  selected.value.clear()
  rangeStart = undefined
}
function edit(remove: boolean, ids: ReadonlySet<string> = selected.value) {
  if (!enabled.value) return
  try {
    history.value = editPaperCut(props.source, history.value, ids, remove)
    error.value = ''
    for (const id of ids) selected.value.delete(id)
    rangeStart = undefined
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '请核对所选原句。' }
}
function step(direction: 'undo' | 'redo') {
  if (!enabled.value) return
  history.value = stepPaperCut(history.value, direction)
  error.value = ''; clearSelection()
}
function stopPlayback() { preview.cancel(); player.value?.pause() }
function isCurrentVideo(video: HTMLVideoElement) {
  if (stopped || video !== player.value || !props.videoUrl) return false
  try { return video.currentSrc === new URL(props.videoUrl, document.baseURI).href }
  catch { return false }
}
function showFirstFrame() {
  const video = player.value, firstMs = cut.value.parts[0]?.start_ms
  if (!video || !isCurrentVideo(video) || !ready.value || !enabled.value || document.hidden || !dialog.value?.open
    || hasAuditioned || playback.value || !video.paused || firstMs === undefined || Math.abs(video.currentTime * 1000 - firstMs) < 1) return
  try { video.currentTime = firstMs / 1000 }
  catch { error.value = '暂时无法定位预览，仍可按原句剪辑。' }
}
function metadata(event: Event) {
  if (!isCurrentVideo(event.currentTarget as HTMLVideoElement)) return
  ready.value = true; mediaError.value = false
  showFirstFrame()
}
function seekDraft(editMs: number) {
  const video = player.value, target = editToSource(cut.value.parts, editMs)
  if (!enabled.value || !ready.value || !dialog.value?.open || !video || !isCurrentVideo(video) || !target) return
  stopPlayback()
  hasAuditioned = true
  try {
    video.currentTime = target.sourceMs / 1000
    cursor.value = editMs; error.value = ''
  } catch { error.value = '暂时无法定位原片，请稍后重试。' }
}
function audition(parts: SequencePreviewPart[], id: string) {
  if (playback.value?.id === id) { stopPlayback(); return }
  if (!enabled.value || !ready.value || !player.value || !isCurrentVideo(player.value) || !dialog.value?.open) return
  error.value = ''
  hasAuditioned = true
  void preview.start(player.value, { id, parts, parkInsideEnd: id === 'paper-cut' })
}
function playDraft() {
  if (playback.value?.id === 'paper-cut') { stopPlayback(); return }
  const at = cursor.value !== null && cursor.value < cut.value.durationMs ? cursor.value : 0
  const target = editToSource(cut.value.parts, at)
  if (!target) return
  draftPlaybackStart = at
  const parts = cut.value.parts.slice(target.partIndex)
  audition([{ start_ms: target.sourceMs, end_ms: parts[0]!.end_ms }, ...parts.slice(1)], 'paper-cut')
}
function guardPlay(event: Event) {
  const video = event.currentTarget as HTMLVideoElement
  if (!enabled.value || !dialog.value?.open || !playback.value || !isCurrentVideo(video)) video.pause()
  else preview.timeUpdate(video)
}
function close() { stopPlayback(); emit('close') }
function escape() {
  if (!dirty.value) { close(); return }
  stopPlayback()
  error.value = '有尚未应用的修改，可应用到方案或放弃本次粗剪。'
}
function apply() {
  if (enabled.value && dirty.value && dialog.value?.open) emit('apply', props.token, new Set(history.value.removed))
}
function visibility() { visible.value = !document.hidden }
watch(query, (value, previous) => {
  if (!previous.trim() && value.trim()) unfilteredPosition = { page: page.value, top: list.value?.scrollTop ?? 0 }
  if (!value.trim() && previous.trim()) {
    page.value = unfilteredPosition.page
    nextScrollTop = unfilteredPosition.top
  } else page.value = 1
}, { flush: 'sync' })
watch([page, query], () => {
  rangeStart = undefined
  if (list.value) list.value.scrollTop = nextScrollTop
  nextScrollTop = 0
  const id = playback.value?.id
  if (id?.startsWith('row:') && !rows.value.some(row => 'row:' + row.utterance.utterance_id === id)) stopPlayback()
}, { flush: 'post' })
function syncOpen() {
  if (stopped) return
  if (!props.active || !visible.value) { stopPlayback(); dialog.value?.close(); return }
  if (dialog.value && !dialog.value.open) dialog.value.showModal()
  showFirstFrame()
}
watch(cut, value => {
  stopPlayback()
  cursor.value = hasAuditioned && player.value ? sourceToEdit(value.parts, Math.round(player.value.currentTime * 1000)) : 0
}, { flush: 'sync' })
watch(() => cut.value.parts[0]?.start_ms, showFirstFrame, { flush: 'post' })
watch(() => props.stale, stale => { if (stale) stopPlayback() }, { flush: 'sync' })
watch(() => props.videoUrl, () => {
  stopPlayback(); hasAuditioned = false; cursor.value = 0; ready.value = false; mediaError.value = false
}, { flush: 'sync' })
watch([() => props.active, visible], syncOpen, { flush: 'post' })
onMounted(() => { document.addEventListener('visibilitychange', visibility); void nextTick(syncOpen) })
onBeforeUnmount(() => { stopped = true; document.removeEventListener('visibilitychange', visibility); stopPlayback(); dialog.value?.close() })
</script>

<template>
  <dialog ref="dialog" class="paper-cut" aria-label="逐句粗剪" @cancel.prevent="escape" @keydown.esc.stop>
    <header><div><h2>逐句粗剪</h2><p>{{ title }}</p></div><button class="secondary" @click="close">{{ dirty ? '放弃本次粗剪' : '关闭' }}</button></header>
    <div class="paper-body">
      <div class="paper-rail">
        <section class="paper-monitor" aria-label="草稿预览">
          <video ref="player" :src="videoUrl" preload="metadata" playsinline
            @loadedmetadata="metadata" @error="ready = false; mediaError = true; stopPlayback()"
            @play="guardPlay" @playing="guardPlay" @timeupdate="preview.timeUpdate($event.currentTarget as HTMLVideoElement)"
            @seeking="preview.seeking($event.currentTarget as HTMLVideoElement)" @seeked="preview.seeked($event.currentTarget as HTMLVideoElement)"
            @pause="preview.pause($event.currentTarget as HTMLVideoElement)" @ended="preview.ended($event.currentTarget as HTMLVideoElement)"
            @ratechange="preview.rateChange($event.currentTarget as HTMLVideoElement)" />
          <div class="paper-transport">
            <div class="paper-length"><span>成片时长</span><strong>{{ topicTime(cut.durationMs) }}</strong></div>
            <button class="secondary paper-play" :disabled="!enabled || !ready" @click="playDraft">{{ playback?.id === 'paper-cut' ? '停止试听' : cursor && cursor < cut.durationMs ? '从游标试听' : '试听草稿' }}</button>
          </div>
          <p class="paper-delta" role="status">{{ cut.parts.length }} 段 · 已剪掉 {{ history.removed.size }} 句 · 缩短 {{ topicTime(source.originalDurationMs - cut.durationMs) }}</p>
          <CutScrubber :parts="cut.parts" :position-ms="cursor" :video-url="enabled ? videoUrl : ''"
            :source-aspect="ready && player?.videoHeight ? player.videoWidth / player.videoHeight : 0" :disabled="!enabled || !ready" @seek="seekDraft" />
          <p v-if="playback && playback.id !== 'paper-cut'" class="paper-now">{{ playback.id.startsWith('row:') ? '正在试听原句' : '正在试听删句建议' }}</p>
          <p v-if="mediaError" role="status">无法载入原视频。仍可按原句编辑。</p>
        </section>
        <PaperCutSuggestion :context="context" :source="source" :removed="history.removed" :enabled="enabled"
          :duration-ms="cut.durationMs" :can-play="ready" :playing="playback?.id === 'paper-cut-proposal'"
          @audition="audition($event, 'paper-cut-proposal')" @stop="stopPlayback" @adopt="edit(true, $event)" />
        <PaperCutBudget :source="source" :removed="history.removed" :goal="goal" :duration-ms="cut.durationMs" :enabled="enabled"
          @adopt="edit(true, $event)" />
      </div>
      <section class="paper-script" aria-label="粗剪原句">
        <div class="paper-search"><label for="paper-script-search">查找原句</label>
          <input id="paper-script-search" v-model="query" type="search" placeholder="输入原话关键词">
          <small>{{ query.trim() ? '匹配 ' + matching.length : '共 ' + source.rows.length }} 句</small>
        </div>
        <div class="paper-tools"><span>已选 {{ selected.size }} 句<small v-if="hiddenSelected">（含未显示 {{ hiddenSelected }} 句）</small></span>
          <button class="secondary" :disabled="!enabled || !canRemove" @click="edit(true)">剪掉选中</button>
          <button class="secondary" :disabled="!enabled || !canRestore" @click="edit(false)">恢复选中</button>
          <button v-if="selected.size" class="text-button" @click="clearSelection">清除选择</button>
          <button class="text-button" :disabled="!enabled || !history.past.length" @click="step('undo')">撤销</button>
          <button class="text-button" :disabled="!enabled || !history.future.length" @click="step('redo')">重做</button>
        </div>
        <p class="paper-keyhint">勾选首尾两句时按住 Shift，可连选本页原句。</p>
        <div ref="list" class="paper-rows"><article v-for="row in rows" :key="row.utterance.utterance_id" class="paper-row"
          :class="{ removed: history.removed.has(row.utterance.utterance_id), checked: selected.has(row.utterance.utterance_id),
            listening: playback?.id === 'row:' + row.utterance.utterance_id }">
          <label class="paper-select">
          <input type="checkbox" :checked="selected.has(row.utterance.utterance_id)" :disabled="!enabled || row.anchor"
            :aria-label="`选择 ${topicTime(row.utterance.start_ms)} ${row.utterance.text}`" @click="toggle(row.utterance.utterance_id, $event)">
          <span><small>原片 {{ topicTime(row.utterance.start_ms) }} – {{ topicTime(row.utterance.end_ms) }}
            <b v-if="row.anchor">回答锚句 · 必留</b><b v-else-if="row.evidence">依据原句</b>
            <b v-if="history.removed.has(row.utterance.utterance_id)">已剪掉</b></small>
            <span class="sentence" :id="'paper-sentence-' + row.utterance.utterance_id">{{ row.utterance.text }}</span></span></label>
          <div class="paper-row-actions">
            <button type="button" class="text-button" :aria-pressed="playback?.id === 'row:' + row.utterance.utterance_id"
              :aria-describedby="'paper-sentence-' + row.utterance.utterance_id" :disabled="!enabled || !ready"
              @click="audition([row.utterance], 'row:' + row.utterance.utterance_id)">
              {{ playback?.id === 'row:' + row.utterance.utterance_id ? '停止试听原句' : '试听原句' }}</button>
            <button v-if="!row.anchor" type="button" class="text-button" :disabled="!enabled" :aria-describedby="'paper-sentence-' + row.utterance.utterance_id"
              @click="edit(!history.removed.has(row.utterance.utterance_id), new Set([row.utterance.utterance_id]))">
              {{ history.removed.has(row.utterance.utterance_id) ? '恢复此句' : '剪掉此句' }}</button>
          </div>
        </article>
        <div v-if="!matching.length" class="paper-empty"><p>没有找到匹配的原句。</p><button class="text-button" @click="query = ''">显示全部原句</button></div>
        </div>
        <nav v-if="pages > 1" aria-label="粗剪原句分页"><button class="text-button" :disabled="page <= 1" @click="page--">上一页</button>
          <span>{{ page }} / {{ pages }}</span><button class="text-button" :disabled="page >= pages" @click="page++">下一页</button></nav>
      </section>
    </div>
    <footer><div aria-live="polite"><p v-if="stale" class="paper-error">方案已变化，请重新打开逐句粗剪。</p>
      <p v-else-if="error" class="paper-error">{{ error }}</p><p v-else>按转写句界剪辑，应用后可在核对页保存。</p></div>
      <button class="primary" :disabled="!enabled || !dirty" @click="apply">应用到方案</button></footer>
  </dialog>
</template>

<style scoped>
.paper-cut {
  color-scheme:dark; width:min(1160px,96vw); max-width:96vw; height:min(830px,94dvh); max-height:94dvh; padding:0;
  border:1px solid var(--workspace-control-border,#49505b); border-radius:12px; background:var(--workspace-surface,#1b1e24);
  color:var(--workspace-text,#eff2f4); box-shadow:0 24px 80px #0008;
}
.paper-cut[open] { display:flex; flex-direction:column; overflow:hidden; }
.paper-cut::backdrop { background:#080a0dcc; }
.paper-cut header,.paper-cut footer,.paper-search,.paper-tools,.paper-transport,.paper-script nav { display:flex; align-items:center; gap:8px; }
.paper-cut header,.paper-cut footer { justify-content:space-between; flex:none; padding:12px 18px; }
.paper-cut header { border-bottom:1px solid var(--workspace-border,#363b44); }
.paper-cut header > div { min-width:0; display:flex; flex-wrap:wrap; align-items:baseline; gap:2px 14px; }
.paper-cut header button,.paper-cut footer button { flex:none; }
.paper-cut h2,.paper-cut p { margin:0; }
.paper-cut h2 { color:var(--workspace-text,#eff2f4); font-size:1.25rem; font-weight:650; }
.paper-cut header p,.paper-cut footer p,.paper-delta,.paper-now,.paper-search small,.paper-keyhint { font-size:.84rem; color:var(--workspace-muted,#a5adb7); }
.paper-cut header p { overflow-wrap:anywhere; }
.paper-body { display:grid; flex:1; grid-template-columns:minmax(300px,.85fr) minmax(0,1.15fr); min-height:0; overflow:hidden; }
.paper-rail { min-width:0; overflow:auto; border-right:1px solid var(--workspace-border,#363b44); background:var(--studio-well,#101215); }
.paper-monitor { padding:14px 16px; }
.paper-monitor video { display:block; width:100%; max-height:33dvh; aspect-ratio:16/9; object-fit:contain; background:#08090c; border:1px solid #ffffff12; border-radius:4px; }
.paper-transport { justify-content:space-between; margin:10px 0 5px; }
.paper-length { display:flex; align-items:baseline; gap:.6rem; flex-wrap:wrap; }
.paper-length strong { color:var(--workspace-text,#eff2f4); font:600 1.28rem var(--workspace-mono,monospace); }
.paper-length span,.paper-tools span { color:var(--workspace-muted,#a5adb7); font-size:.85rem; }
.paper-monitor .paper-delta { margin:0 0 12px; }
.paper-rail :deep(.cut-instruction),.paper-rail :deep(.cut-budget) { max-height:none; overflow:visible; padding:12px 16px; border:0; border-top:1px solid var(--workspace-border,#363b44); background:var(--workspace-surface,#1b1e24); color:var(--workspace-text,#eff2f4); }
.paper-rail :deep(.cut-instruction small) { color:var(--workspace-muted,#a5adb7); }
.paper-rail :deep(.cut-instruction label) { min-width:0; }
.paper-rail :deep(input:not([type=checkbox])),.paper-search input { padding:6px 9px; border:1px solid var(--workspace-control-border,#49505b); border-radius:5px; background:var(--studio-well,#101215); color:var(--workspace-text,#eff2f4); }
.paper-rail :deep(.secondary),.paper-cut .secondary { min-height:34px; padding:5px 10px; border:1px solid var(--workspace-control-border,#49505b); border-radius:5px; background:var(--studio-raised,#252a31); color:var(--workspace-text,#eff2f4); font-size:.9rem; }
.paper-rail :deep(.text-button),.paper-cut .text-button { background:transparent; color:var(--workspace-accent,#70e3cd); font-size:.88rem; }
.paper-cut button:hover:not(:disabled),.paper-rail :deep(button:hover:not(:disabled)) { border-color:var(--workspace-accent,#70e3cd); }
.paper-cut :focus-visible { outline:2px solid var(--workspace-accent,#70e3cd); outline-offset:2px; }
.paper-script { display:flex; flex-direction:column; min-height:0; min-width:0; }
.paper-search { padding:14px 16px 7px; flex-wrap:wrap; }
.paper-search label { font-weight:650; }
.paper-search input { flex:1; min-width:6em; }
.paper-search small,.paper-tools span,.paper-row small,.paper-delta,.paper-script nav { font-variant-numeric:tabular-nums; }
.paper-tools { flex-wrap:wrap; padding:0 16px 6px; gap:4px 8px; }
.paper-tools button,.paper-row-actions button { min-height:30px; }
.paper-cut .paper-keyhint { padding:0 16px 8px; font-size:.78rem; }
.paper-rows { flex:1; overflow:auto; overscroll-behavior:contain; min-height:0; border-top:1px solid var(--workspace-border,#363b44); scrollbar-gutter:stable; }
.paper-row { padding:11px 16px 7px; border-bottom:1px solid var(--workspace-border,#363b44); border-left:3px solid transparent; }
.paper-select { display:flex; align-items:flex-start; gap:10px; cursor:pointer; }
.paper-select input { flex:none; width:17px; height:17px; margin:2px 0; accent-color:var(--workspace-accent,#70e3cd); }
.paper-row.checked,.paper-row.listening { border-left-color:var(--workspace-accent,#70e3cd); background:var(--workspace-accent-soft,#21413e); }
.paper-row small { display:flex; align-items:baseline; flex-wrap:wrap; gap:4px 8px; color:var(--workspace-muted,#a5adb7); font:.8rem var(--workspace-mono,monospace); }
.paper-row b { color:var(--workspace-text,#eff2f4); font-family:var(--workspace-font,sans-serif); font-weight:550; }
.sentence { display:block; margin-top:4px; color:var(--workspace-text,#eff2f4); font-size:1rem; line-height:1.6; overflow-wrap:anywhere; }
.removed .sentence { text-decoration:line-through; color:var(--workspace-muted,#a5adb7); }
.paper-row-actions { display:flex; align-items:center; flex-wrap:wrap; gap:4px 14px; padding-left:27px; }
.paper-row-actions button { padding-inline:0; }
.paper-row-actions button[aria-pressed=true] { font-weight:650; }
.paper-empty { padding:22px 16px; }
.paper-script nav { padding:5px 14px; justify-content:flex-end; border-top:1px solid var(--workspace-border,#363b44); }
.paper-cut footer { border-top:1px solid var(--workspace-border,#363b44); }
.paper-cut footer .primary { min-height:38px; padding:6px 15px; border:0; border-radius:5px; background:var(--workspace-accent,#70e3cd); color:var(--studio-ink,#092320); font-weight:650; }
.paper-cut .paper-error { color:var(--studio-danger,#ffabad); }
@media(max-width:700px) {
  .paper-cut { width:100%; max-width:100%; height:98dvh; max-height:98dvh; border-radius:8px; }
  .paper-body { display:flex; flex-direction:column; }
  .paper-rail { flex:none; max-height:36%; border-right:0; border-bottom:1px solid var(--workspace-border,#363b44); }
  .paper-monitor { padding:10px 12px; }
  .paper-monitor video { float:right; width:min(36%,145px); margin-left:9px; }
  .paper-monitor::after { content:''; display:block; clear:both; }
  .paper-transport { display:block; margin-top:0; }
  .paper-length { gap:3px 6px; }
  .paper-play { margin-top:5px; }
  .paper-monitor :deep(.cut-scrubber) { clear:both; }
  .paper-monitor .paper-delta { margin:5px 0 9px; }
  .paper-rail :deep(.cut-instruction),.paper-rail :deep(.cut-budget) { padding:8px 12px; }
  .paper-script { flex:1; }
  .paper-search { padding:8px 10px 4px; }
  .paper-tools { padding:2px 10px 6px; }
  .paper-cut header,.paper-cut footer { padding:8px 12px; }
  .paper-row { padding:9px 10px 5px; }
}
@media(pointer:coarse),(max-width:650px),(max-height:600px) { .paper-cut .paper-keyhint { display:none; } }
@media(max-height:510px) {
  .paper-cut[open] { display:block; overflow:auto; height:98dvh; max-height:98dvh; }
  .paper-body { height:auto; min-height:0; overflow:visible; }
  .paper-script,.paper-rows,.paper-rail { max-height:none; overflow:visible; }
  .paper-rail { align-self:start; }
  .paper-monitor video { max-height:38dvh; }
}
</style>
