<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { compilePaperCut, editPaperCut, newPaperCutHistory, stepPaperCut, type PaperCutSource } from './paperCut'
import { SequencePreview, type SequencePreviewPart, type SequencePreviewState } from './sequencePreview'
import { topicTime } from './topics'
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
const preview = new SequencePreview((state, reason) => {
  playback.value = state
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
function audition(parts: SequencePreviewPart[] = cut.value.parts, id = 'paper-cut') {
  if (playback.value?.id === id) { stopPlayback(); return }
  if (!enabled.value || !ready.value || !player.value || !dialog.value?.open) return
  error.value = ''
  hasAuditioned = true
  void preview.start(player.value, { id, parts })
}
function guardPlay(event: Event) {
  const video = event.currentTarget as HTMLVideoElement
  if (!enabled.value || !dialog.value?.open || !playback.value || stopped) video.pause()
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
watch(history, stopPlayback, { flush: 'sync' })
watch(() => cut.value.parts[0]?.start_ms, showFirstFrame, { flush: 'post' })
watch(() => props.stale, stale => { if (stale) stopPlayback() }, { flush: 'sync' })
watch(() => props.videoUrl, () => {
  stopPlayback(); hasAuditioned = false; ready.value = false; mediaError.value = false
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
          <div class="paper-length"><strong>{{ topicTime(cut.durationMs) }}</strong><span>成片时长</span></div>
          <div class="paper-track" aria-hidden="true"><i v-for="part in cut.parts" :key="part.start_utterance_id"
            :style="{ flexGrow: part.end_ms - part.start_ms }"></i></div>
          <p class="paper-delta" role="status">{{ cut.parts.length }} 段 · 已剪掉 {{ history.removed.size }} 句 · 缩短 {{ topicTime(source.originalDurationMs - cut.durationMs) }}</p>
          <button class="secondary" :disabled="!enabled || !ready" @click="audition()">{{ playback?.id === 'paper-cut' ? '停止试听' : '试听草稿' }}</button>
          <p v-if="playback" class="paper-now">{{ playback.id.startsWith('row:') ? '正在试听原句' : '正在试听第 ' + (playback.part_index + 1) + ' / ' + playback.part_count + ' 段' }}</p>
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
.paper-cut { width:min(1080px,96vw); max-width:96vw; height:min(800px,92dvh); max-height:92dvh; padding:0; box-sizing:border-box; border:1px solid var(--workspace-control-border,#93897d); border-radius:12px; color:var(--workspace-text,#292621); background:#fff; }
.paper-cut[open] { display:flex; flex-direction:column; }
.paper-cut::backdrop { background:#211d18a6; }
.paper-cut header,.paper-cut footer,.paper-tools,.paper-length,.paper-script nav,.paper-search { display:flex; align-items:center; gap:8px; }
.paper-cut header,.paper-cut footer { justify-content:space-between; flex-shrink:0; padding:12px 18px; }
.paper-cut header { border-bottom:1px solid var(--workspace-border,#e4e0d8); }
.paper-cut header > div { min-width:0; }
.paper-cut header button { flex:none; }
.paper-cut h2,.paper-cut p { margin:0; }
.paper-cut h2 { font-size:1.25rem; }
.paper-cut header p,.paper-cut footer p,.paper-delta,.paper-now,.paper-search small,.paper-keyhint { font-size:.84rem; color:var(--workspace-muted,#675f56); }
.paper-cut header p { margin-top:3px; overflow-wrap:anywhere; }
.paper-body { display:grid; flex:1; grid-template-columns:minmax(230px,.77fr) minmax(0,1.23fr); min-height:0; overflow:hidden; }
.paper-rail { min-width:0; overflow:auto; border-right:1px solid var(--workspace-border,#e4e0d8); background:var(--workspace-canvas,#f4f3ef); }
.paper-monitor { padding:15px; }
.paper-monitor video { display:block; width:100%; aspect-ratio:16/9; background:var(--workspace-video,#211f1b); border-radius:6px; object-fit:contain; }
.paper-length { margin:12px 0 5px; flex-wrap:wrap; }
.paper-length strong { font:600 1.5rem var(--workspace-mono,monospace); }
.paper-length span,.paper-tools span { font-size:.85rem; color:var(--workspace-muted,#675f56); }
.paper-track { display:flex; height:5px; gap:2px; margin:8px 0; }
.paper-track i { min-width:2px; border-radius:2px; background:var(--workspace-accent,#a94424); }
.paper-monitor button { margin:10px 0 3px; }
.paper-rail :deep(.cut-instruction),.paper-rail :deep(.cut-budget) { max-height:none; overflow:visible; border-block:1px solid var(--workspace-border,#e4e0d8); padding:12px 15px; background:#fff; }
.paper-rail :deep(.cut-budget) { border-top:0; }
.paper-rail :deep(.cut-instruction label) { min-width:0; }
.paper-script { display:flex; flex-direction:column; min-height:0; min-width:0; }
.paper-search { padding:12px 14px 5px; flex-wrap:wrap; }
.paper-search label { font-weight:600; }
.paper-search input { flex:1; min-width:6em; padding:6px 8px; border:1px solid var(--workspace-control-border,#93897d); border-radius:5px; background:white; }
.paper-search small,.paper-tools span,.paper-row small,.paper-delta { font-variant-numeric:tabular-nums; }
.paper-tools { flex-wrap:wrap; padding:5px 14px 6px; gap:4px 6px; }
.paper-tools button,.paper-row-actions button { min-height:30px; }
.paper-cut .text-button { font-size:.9rem; color:var(--workspace-accent,#a94424); }
.paper-cut .secondary { min-height:34px; padding:5px 10px; border:1px solid var(--workspace-border,#e4e0d8); border-radius:6px; color:var(--workspace-text,#292621); background:#fff; font-size:.9rem; }
.paper-cut .primary { min-height:38px; padding:6px 15px; }
.paper-cut .paper-keyhint { padding:0 14px 8px; font-size:.78rem; }
.paper-rows { flex:1; overflow:auto; overscroll-behavior:contain; min-height:0; border-top:1px solid var(--workspace-border,#e4e0d8); scrollbar-gutter:stable; }
.paper-row { padding:10px 14px 7px; border-bottom:1px solid var(--workspace-border,#e4e0d8); border-left:3px solid transparent; }
.paper-select { display:flex; align-items:flex-start; gap:9px; cursor:pointer; }
.paper-select input { flex:none; width:17px; height:17px; margin:2px 0; accent-color:var(--workspace-accent,#a94424); }
.paper-row.checked { background:#fbf3ed; }
.paper-row.listening { border-left-color:var(--workspace-accent,#a94424); background:#f9f4eb; }
.paper-row small { display:flex; gap:4px 8px; flex-wrap:wrap; color:var(--workspace-muted,#675f56); }
.paper-row b { color:#62513f; font-weight:600; }
.sentence { display:block; margin-top:4px; line-height:1.55; overflow-wrap:anywhere; }
.removed .sentence { text-decoration:line-through; color:var(--workspace-muted,#675f56); }
.paper-row-actions { display:flex; align-items:center; gap:12px; padding-left:26px; }
.paper-row-actions button { padding-inline:0; }
.paper-row-actions button[aria-pressed='true'] { font-weight:600; }
.paper-empty { padding:22px 16px; }
.paper-script nav { padding:5px 12px; justify-content:flex-end; border-top:1px solid var(--workspace-border,#e4e0d8); }
.paper-cut footer { border-top:1px solid var(--workspace-border,#e4e0d8); }
.paper-cut footer button { flex-shrink:0; }
.paper-cut .paper-error { color:#a32b21; }
@media(max-width:700px) {
  .paper-cut { width:100%; max-width:100%; height:96dvh; max-height:96dvh; border-radius:9px; }
  .paper-body { display:flex; flex-direction:column; }
  .paper-rail { flex-shrink:1; max-height:34%; border-right:0; border-bottom:1px solid var(--workspace-border,#e4e0d8); }
  .paper-monitor { padding:9px 12px; }
  .paper-monitor video { float:right; width:min(34%,155px); margin-left:8px; }
  .paper-monitor::after { content:''; display:block; clear:both; }
  .paper-track { max-width:60%; }
  .paper-length { margin-top:0; }
  .paper-rail :deep(.cut-instruction),.paper-rail :deep(.cut-budget) { padding:8px 12px; }
  .paper-script { flex:1; }
  .paper-search { padding:8px 9px 3px; }
  .paper-tools { padding:4px 9px; }
  .paper-cut header,.paper-cut footer { padding:8px 10px; }
  .paper-row { padding:8px 9px 5px; }
}
@media(pointer:coarse), (max-width:650px), (max-height:600px) { .paper-cut .paper-keyhint { display:none; } }
@media(max-height:510px) { .paper-body { display:block; overflow:auto; } .paper-rail { max-height:none; } .paper-rows { overflow:visible; } }
</style>
