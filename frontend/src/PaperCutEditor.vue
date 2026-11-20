<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { compilePaperCut, editPaperCut, newPaperCutHistory, stepPaperCut, type PaperCutSource } from './paperCut'
import { SequencePreview, type SequencePreviewState } from './sequencePreview'
import { topicTime } from './topics'
import PaperCutBudget from './PaperCutBudget.vue'
import PaperCutSuggestion from './PaperCutSuggestion.vue'
import type { PaperCutContext } from './paperCutSuggestion'
import type { IntentPart } from './intentClips'

const props = defineProps<{ source: PaperCutSource; context: PaperCutContext; title: string; goal: string; videoUrl: string; token: number; active: boolean; stale: boolean }>()
const emit = defineEmits<{ apply: [token: number, removedIds: ReadonlySet<string>]; close: [] }>()
const dialog = ref<HTMLDialogElement | null>(null), player = ref<HTMLVideoElement | null>(null)
const page = ref(1), pageSize = 30
const selected = ref(new Set<string>()), history = shallowRef(newPaperCutHistory())
const dirty = computed(() => history.value.removed.size > 0)
defineExpose({ dirty, pending: false })
const error = ref(''), ready = ref(false), mediaError = ref(false)
const visible = ref(!document.hidden), enabled = computed(() => props.active && !props.stale && visible.value)
const cut = computed(() => compilePaperCut(props.source, history.value.removed))
const pages = computed(() => Math.ceil(props.source.rows.length / pageSize))
const rows = computed(() => props.source.rows.slice((page.value - 1) * pageSize, page.value * pageSize))
const canRemove = computed(() => [...selected.value].some(id => !history.value.removed.has(id)))
const canRestore = computed(() => [...selected.value].some(id => history.value.removed.has(id)))
const playback = shallowRef<SequencePreviewState | null>(null)
const preview = new SequencePreview((state, reason) => {
  playback.value = state
  if (reason === 'failed') error.value = '未能播放当前草稿，请检查原视频后重试。'
})
let stopped = false

function toggle(id: string) {
  if (!enabled.value) return
  if (selected.value.has(id)) selected.value.delete(id)
  else selected.value.add(id)
}
function edit(remove: boolean, ids: ReadonlySet<string> = selected.value) {
  if (!enabled.value) return
  try {
    history.value = editPaperCut(props.source, history.value, ids, remove)
    error.value = ''; selected.value.clear()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '请核对所选原句。' }
}
function step(direction: 'undo' | 'redo') {
  if (!enabled.value) return
  history.value = stepPaperCut(history.value, direction)
  error.value = ''; selected.value.clear()
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
    || playback.value || !video.paused || firstMs === undefined || Math.abs(video.currentTime * 1000 - firstMs) < 1) return
  try { video.currentTime = firstMs / 1000 }
  catch { error.value = '暂时无法定位预览，仍可按原句剪辑。' }
}
function metadata(event: Event) {
  if (!isCurrentVideo(event.currentTarget as HTMLVideoElement)) return
  ready.value = true; mediaError.value = false
  showFirstFrame()
}
function audition(parts: IntentPart[] = cut.value.parts, id = 'paper-cut') {
  if (playback.value?.id === id) { stopPlayback(); return }
  if (!enabled.value || !ready.value || !player.value || !dialog.value?.open) return
  error.value = ''
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
function syncOpen() {
  if (stopped) return
  if (!props.active || !visible.value) { stopPlayback(); dialog.value?.close(); return }
  if (dialog.value && !dialog.value.open) dialog.value.showModal()
  showFirstFrame()
}
watch(history, stopPlayback, { flush: 'sync' })
watch(() => cut.value.parts[0]?.start_ms, showFirstFrame, { flush: 'post' })
watch(() => props.stale, stale => { if (stale) stopPlayback() }, { flush: 'sync' })
watch([() => props.active, visible], syncOpen, { flush: 'post' })
onMounted(() => { document.addEventListener('visibilitychange', visibility); void nextTick(syncOpen) })
onBeforeUnmount(() => { stopped = true; document.removeEventListener('visibilitychange', visibility); stopPlayback(); dialog.value?.close() })
</script>

<template>
  <dialog ref="dialog" class="paper-cut" aria-label="逐句粗剪" @cancel.prevent="escape" @keydown.esc.stop>
    <header><div><h2>逐句粗剪</h2><p>{{ title }}</p></div><button class="secondary" @click="close">{{ dirty ? '放弃本次粗剪' : '关闭' }}</button></header>
    <PaperCutSuggestion :context="context" :source="source" :removed="history.removed" :enabled="enabled"
      :duration-ms="cut.durationMs" :can-play="ready" :playing="playback?.id === 'paper-cut-proposal'"
      @audition="audition($event, 'paper-cut-proposal')" @stop="stopPlayback" @adopt="edit(true, $event)" />
    <PaperCutBudget :source="source" :removed="history.removed" :goal="goal" :duration-ms="cut.durationMs" :enabled="enabled"
      @adopt="edit(true, $event)" />
    <div class="paper-body">
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
        <p v-if="playback">正在播放第 {{ playback.part_index + 1 }} 段</p>
        <p v-if="mediaError" role="status">无法载入原视频。仍可按原句编辑。</p>
      </section>
      <section class="paper-script" aria-label="粗剪原句">
        <div class="paper-tools"><span>已选 {{ selected.size }} 句</span>
          <button class="secondary" :disabled="!enabled || !canRemove" @click="edit(true)">剪掉选中</button>
          <button class="secondary" :disabled="!enabled || !canRestore" @click="edit(false)">恢复选中</button>
          <button class="text-button" :disabled="!enabled || !history.past.length" @click="step('undo')">撤销</button>
          <button class="text-button" :disabled="!enabled || !history.future.length" @click="step('redo')">重做</button>
        </div>
        <div class="paper-rows"><label v-for="row in rows" :key="row.utterance.utterance_id" class="paper-row"
          :class="{ removed: history.removed.has(row.utterance.utterance_id) }">
          <input type="checkbox" :checked="selected.has(row.utterance.utterance_id)" :disabled="!enabled || row.anchor"
            :aria-label="`选择 ${topicTime(row.utterance.start_ms)} ${row.utterance.text}`" @change="toggle(row.utterance.utterance_id)">
          <span><small>原片 {{ topicTime(row.utterance.start_ms) }} – {{ topicTime(row.utterance.end_ms) }}
            <b v-if="row.anchor">回答锚句 · 必留</b><b v-else-if="row.evidence">依据原句</b>
            <b v-if="history.removed.has(row.utterance.utterance_id)">已剪掉</b></small>
            <span class="sentence">{{ row.utterance.text }}</span></span>
        </label></div>
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
.paper-cut { width:min(940px, 94vw); max-width:94vw; max-height:90dvh; padding:0; box-sizing:border-box; border:1px solid #ccd4ca; border-radius:12px; color:#203127; background:#fff; }
.paper-cut[open] { display:flex; flex-direction:column; }
.paper-cut::backdrop { background:#14221999; }
.paper-cut header,.paper-cut footer,.paper-tools,.paper-length,.paper-script nav { display:flex; align-items:center; gap:10px; }
.paper-cut header,.paper-cut footer { justify-content:space-between; flex-shrink:0; padding:14px 18px; }
.paper-cut header { border-bottom:1px solid #dce2dc; }
.paper-cut h2,.paper-cut p { margin:0; }
.paper-cut h2 { font-size:1.25rem; }
.paper-cut header p,.paper-cut footer p,.paper-delta { font-size:.84rem; color:#526258; }
.paper-cut header p { margin-top:3px; overflow-wrap:anywhere; }
.paper-body { display:grid; grid-template-columns:minmax(180px, .65fr) minmax(0, 1.35fr); min-height:0; overflow:auto; }
.paper-monitor { padding:16px; background:#f3f6f1; }
.paper-monitor video { width:100%; aspect-ratio:16 / 9; background:#17221b; border-radius:6px; object-fit:contain; }
.paper-length { margin:12px 0 6px; flex-wrap:wrap; }
.paper-length strong { font:600 1.35rem ui-monospace,monospace; font-variant-numeric:tabular-nums; }
.paper-length span,.paper-tools span { font-size:.8rem; color:#526258; }
.paper-track { display:flex; height:10px; gap:2px; margin:8px 0; }
.paper-track i { min-width:3px; border-radius:2px; background:#467053; }
.paper-monitor button { margin:12px 0 4px; }
.paper-script { display:flex; flex-direction:column; min-height:0; }
.paper-tools { flex-wrap:wrap; flex-shrink:0; padding:10px 14px; border-bottom:1px solid #dde4dd; }
.paper-rows { overflow:auto; min-height:0; max-height:52dvh; }
.paper-row { display:flex; align-items:flex-start; gap:10px; cursor:pointer; border-bottom:1px solid #edf0ec; padding:12px 16px; }
.paper-row input { flex:none; width:16px; height:16px; margin:2px 0; accent-color:#34734d; }
.paper-row small { display:flex; gap:7px; flex-wrap:wrap; color:#506057; font-variant-numeric:tabular-nums; }
.paper-row b { color:#385e45; font-weight:600; }
.sentence { display:block; margin-top:4px; line-height:1.5; overflow-wrap:anywhere; }
.removed .sentence { text-decoration:line-through; color:#667067; }
.paper-script nav { padding:6px 12px; justify-content:flex-end; }
.paper-cut footer { border-top:1px solid #dce2dc; }
.paper-cut footer button { flex-shrink:0; }
.paper-cut .paper-error { color:#a32b21; }
@media(max-width:650px) {
  .paper-body { display:block; }
  .paper-monitor { padding:9px 12px; }
  .paper-monitor video { float:right; width:min(32%,140px); margin-left:10px; }
  .paper-monitor::after { content:''; display:block; clear:both; }
  .paper-track { max-width:62%; }
  .paper-monitor .paper-length { margin-top:0; }
  .paper-rows { max-height:none; }
  .paper-tools { position:sticky; top:0; background:#fff; }
  .paper-cut header,.paper-cut footer { padding:10px 12px; }
}
</style>
