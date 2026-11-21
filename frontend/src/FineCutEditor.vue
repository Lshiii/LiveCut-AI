<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import type { IntentPart } from './intentClips'
import { editSpans, editToSource } from './editTimeline'
import { copyCuts, fineDraftStore, playWindow } from './fineCutDraft'
import { buildFineCutProject, type FineCutOwner } from './fineCutProject'
import { SequencePreview, type SequencePreviewPart, type SequencePreviewState } from './sequencePreview'
import { topicTime } from './topics'
import CutScrubber from './CutScrubber.vue'
import FineCutBounds from './FineCutBounds.vue'

const props = defineProps<{ parts: readonly IntentPart[]; owner: FineCutOwner; videoUrl: string; timelineUrl: string;
  projectKey: string; title: string; active: boolean }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement | null>(null), player = ref<HTMLVideoElement | null>(null)
const cuts = shallowRef(copyCuts(props.parts)), selected = ref(0)
const cursor = ref<number | null>(0), sourceHead = ref(cuts.value[0]!.start_ms)
const spans = computed(() => editSpans(cuts.value)), total = computed(() => spans.value.at(-1)?.editEndMs ?? 0)
const originalMs = props.parts.reduce((sum, part) => sum + part.end_ms - part.start_ms, 0)
const current = computed(() => cuts.value[selected.value]!), original = computed(() => props.parts[selected.value]!)
const sourceTrackStyle = computed(() => {
  const base = original.value, cut = current.value
  const percent = (ms: number) => (100 * (ms - base.start_ms) / (base.end_ms - base.start_ms)) + '%'
  return { '--source-in': percent(cut.start_ms), '--source-out': percent(cut.end_ms) }
})
const joinAfter = computed(() => spans.value[selected.value + 1] ?? (selected.value > 0 ? spans.value[selected.value] : undefined))
const ready = ref(false), mediaFailed = ref(false), invalidInput = ref(false), working = ref(false)
const saved = ref(''), status = ref(''), downloadName = ref(''), unsaved = ref(false), closing = ref(false)
const originalDownload = computed(() => {
  const url = new URL(props.videoUrl, document.baseURI)
  url.searchParams.set('download', 'original')
  return url.href
})
const past = shallowRef<{ cuts: SequencePreviewPart[]; index: number }[]>([])
let store: ReturnType<typeof fineDraftStore> | undefined, saveTimer: number | undefined, controller: AbortController | undefined
let drag: 'none' | 'begin' | 'changed' = 'none', disposed = false, playFrom = 0, playUntil = 0
let sourcePreview = false
const playback = shallowRef<SequencePreviewState | null>(null)
const sequence = new SequencePreview((state, reason) => {
  playback.value = state
  if (state) {
    const position = Math.min(playUntil - 1, playFrom + state.played_ms)
    if (sourcePreview) placeSourceHead(position)
    else placeEditHead(position)
  } else if (reason === 'finished') {
    if (sourcePreview) placeSourceHead(playUntil - 1)
    else placeEditHead(playUntil === total.value ? playUntil : playUntil - 1)
  }
  else if (reason === 'failed') status.value = '试听中断，请重新载入原片或再试一次。'
})
function interactive() { return !disposed && props.active && !document.hidden && !!dialog.value?.open }
function stop() { sequence.cancel(); player.value?.pause() }
function placeEditHead(at: number) {
  const point = editToSource(cuts.value, at)
  if (!point) return
  cursor.value = at; sourceHead.value = point.sourceMs; selected.value = point.partIndex
}
function placeSourceHead(ms: number) {
  sourceHead.value = ms
  const span = spans.value[selected.value]!
  cursor.value = ms >= span.start_ms && ms < span.end_ms ? span.editStartMs + ms - span.start_ms : null
}
function showFrame() {
  if (ready.value && player.value) {
    // The exclusive coarse end is a valid cut boundary, but its picture belongs to the next interval.
    try { player.value.currentTime = Math.min(sourceHead.value, original.value.end_ms - 1) / 1000 }
    catch { status.value = '暂时无法定位原片。' }
  }
}
function seek(at: number) {
  if (!interactive()) return
  stop(); placeEditHead(at); showFrame()
}
function seekSource(ms: number) {
  if (!interactive() || !ready.value || working.value || invalidInput.value || !Number.isSafeInteger(ms)
    || ms < original.value.start_ms || ms > original.value.end_ms) return
  stop(); placeSourceHead(ms); showFrame()
}
function choose(index: number) {
  if (spans.value[index]) seek(spans.value[index]!.editStartMs)
}
function playableWindow(start: number, end: number) {
  if (!ready.value || invalidInput.value || !interactive() || !player.value) return
  const parts = playWindow(cuts.value, start, end)
  if (!parts.length) return
  sourcePreview = false; playFrom = start; playUntil = end; status.value = ''
  void sequence.start(player.value, { id: 'fine-cut', parts, parkInsideEnd: true })
}
function listen() {
  if (playback.value && !sourcePreview) { stop(); return }
  const at = cursor.value ?? spans.value[selected.value]!.editStartMs
  playableWindow(at < total.value ? at : 0, total.value)
}
function listenSource() {
  if (!interactive() || !ready.value || working.value || invalidInput.value || !player.value) return
  if (playback.value && sourcePreview) { sequence.timeUpdate(player.value); stop(); return }
  const base = original.value
  sourcePreview = true; playFrom = sourceHead.value < base.end_ms ? sourceHead.value : base.start_ms
  playUntil = base.end_ms; status.value = ''
  void sequence.start(player.value, { id: 'fine-cut-source', parts: [{ start_ms: playFrom, end_ms: playUntil }], parkInsideEnd: true })
}
function listenJoin() {
  const join = joinAfter.value
  if (!join) return
  const previous = spans.value[join.partIndex - 1]!
  playableWindow(Math.max(previous.editStartMs, join.editStartMs - 1500), Math.min(join.editEndMs, join.editStartMs + 1500))
}
function listenEdge(edge: 'start_ms' | 'end_ms') {
  const span = spans.value[selected.value]!
  if (edge === 'start_ms') playableWindow(span.editStartMs, Math.min(span.editStartMs + 1500, span.editEndMs))
  else playableWindow(Math.max(span.editStartMs, span.editEndMs - 1500), span.editEndMs)
}
function guardPlay(event: Event) {
  const video = event.currentTarget as HTMLVideoElement
  if (!interactive() || video !== player.value || !playback.value) video.pause()
  else sequence.timeUpdate(video)
}
function flush() {
  window.clearTimeout(saveTimer)
  if (!unsaved.value || !store) return
  try { store.write(cuts.value); unsaved.value = false; saved.value = '剪点已存到本机' }
  catch (e) { saved.value = e instanceof Error ? e.message : '本机存储不可用。可下载当前时间线。' }
}
function record() {
  if (drag === 'changed') return
  past.value = [...past.value.slice(-39), { cuts: cuts.value, index: selected.value }]
  if (drag === 'begin') drag = 'changed'
}
function changed() {
  stop(); status.value = ''; closing.value = false; unsaved.value = true
  if (store) {
    saved.value = '正在保存剪点…'
    window.clearTimeout(saveTimer)
    saveTimer = window.setTimeout(flush, 180)
  }
}
function edit(edge: keyof SequencePreviewPart, ms: number, keepSourceHead = false) {
  if (!interactive() || working.value || !Number.isSafeInteger(ms)) return
  const i = selected.value, part = { ...current.value, [edge]: ms }
  if (part.start_ms < original.value.start_ms || part.end_ms > original.value.end_ms || part.end_ms <= part.start_ms) return
  if (current.value[edge] !== ms) {
    record(); cuts.value = cuts.value.map((old, index) => index === i ? part : old); changed()
  }
  const span = spans.value[i]!
  if (keepSourceHead) { placeSourceHead(ms); showFrame() }
  else seek(edge === 'start_ms' ? span.editStartMs : span.editEndMs - 1)
}
function canMark(edge: keyof SequencePreviewPart) {
  if (!ready.value || working.value || invalidInput.value || current.value[edge] === sourceHead.value) return false
  return edge === 'start_ms' ? sourceHead.value < current.value.end_ms : sourceHead.value > current.value.start_ms
}
function mark(edge: keyof SequencePreviewPart) {
  if (!interactive() || !ready.value || working.value || invalidInput.value) return
  if (playback.value && player.value) sequence.timeUpdate(player.value)
  if (!canMark(edge)) return
  const ms = sourceHead.value
  edit(edge, ms, true)
  status.value = '第 ' + (selected.value + 1) + ' 段' + (edge === 'start_ms' ? '入点' : '出点') + '已设为 ' + topicTime(ms)
}
function markKey(event: KeyboardEvent) {
  if (event.defaultPrevented || event.isComposing || event.repeat || event.altKey || event.shiftKey || event.ctrlKey || event.metaKey) return
  const key = event.key.toLowerCase()
  if (key !== 'i' && key !== 'o') return
  const field = event.target as HTMLElement | null
  if (field?.closest('textarea,select,input:not([type="range"]),[contenteditable]:not([contenteditable="false"])')) return
  event.preventDefault()
  mark(key === 'i' ? 'start_ms' : 'end_ms')
}
function undo() {
  const previous = past.value.at(-1)
  if (!previous || working.value || !interactive()) return
  past.value = past.value.slice(0, -1); cuts.value = previous.cuts
  changed(); choose(previous.index)
  status.value = '已撤销第 ' + (previous.index + 1) + ' 段的上次调整。'
}
function restore() {
  if (working.value || !interactive()) return
  record(); cuts.value = cuts.value.map((part, i) => i === selected.value ? copyCuts([original.value])[0]! : part)
  changed(); choose(selected.value)
  status.value = '第 ' + (selected.value + 1) + ' 段已还原为粗剪区间。'
}
function pointerReleased() { drag = 'none' }
function close() {
  flush()
  if ((unsaved.value || invalidInput.value) && !closing.value) {
    closing.value = true; stop(); status.value = invalidInput.value ? '仍有未提交的时间输入。' : '剪点尚未保存，可先下载精剪时间线。'; return
  }
  stop(); dialog.value?.close(); emit('close')
}
function beforeUnload(event: BeforeUnloadEvent) {
  flush()
  if (unsaved.value || invalidInput.value) event.preventDefault()
}
function syncVisibility() {
  if (disposed) return
  if (!props.active || document.hidden) { stop(); flush(); controller?.abort(); dialog.value?.close(); return }
  if (!dialog.value?.open) dialog.value?.showModal()
  showFrame()
}
function metadata() {
  ready.value = true; mediaFailed.value = false
  if (interactive()) showFrame()
}
async function download() {
  if (!interactive() || working.value || invalidInput.value) return
  working.value = true; status.value = ''; stop(); flush()
  const request = new AbortController()
  controller = request
  const timeout = window.setTimeout(() => request.abort(), 20000)
  try {
    const response = await fetch(props.timelineUrl, { cache: 'no-store', signal: request.signal })
    if (!response.ok) throw new Error('无法读取这个确认版本的时间线，请在核对方案页刷新后重试。')
    const raw = await response.json()
    const project = buildFineCutProject(raw, props.owner, props.parts, cuts.value)
    if (request.signal.aborted || !interactive()) return
    downloadName.value = String(raw.metadata.livecut.source_filename)
    const url = URL.createObjectURL(new Blob([JSON.stringify(project, null, 2)], { type: 'application/json' }))
    const link = document.createElement('a')
    link.href = url
    link.download = (props.title.replace(/[\\/:*?"<>|\u0000-\u001f]/g, ' ').trim().slice(0, 80) || 'LiveCut') + '-精剪.otio'
    document.body.append(link)
    try { link.click() } finally { link.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 1000) }
    status.value = '已导出精剪时间线，请将它和原片放在同一目录。'
  } catch (e) {
    if (interactive()) status.value = request.signal.aborted ? '读取时间线超时，请重试。'
      : e instanceof Error ? e.message : '时间线下载失败，请重试。'
  } finally {
    window.clearTimeout(timeout)
    if (controller === request) { controller = undefined; working.value = false }
  }
}
watch(() => props.active, syncVisibility, { flush: 'post' })
onMounted(() => {
  try {
    store = fineDraftStore(localStorage, props.projectKey, props.parts)
    cuts.value = store.cuts
    placeEditHead(0)
    saved.value = cuts.value.some((p, i) => p.start_ms !== props.parts[i]!.start_ms || p.end_ms !== props.parts[i]!.end_ms)
      ? '已载入本机剪点' : '修改后自动存到本机'
  } catch { saved.value = '本机草稿不可用，关闭前请下载当前时间线。' }
  document.addEventListener('visibilitychange', syncVisibility)
  window.addEventListener('pointerup', pointerReleased); window.addEventListener('pointercancel', pointerReleased)
  window.addEventListener('beforeunload', beforeUnload); syncVisibility()
})
onBeforeUnmount(() => {
  disposed = true; stop(); flush(); controller?.abort(); dialog.value?.close()
  document.removeEventListener('visibilitychange', syncVisibility)
  window.removeEventListener('pointerup', pointerReleased); window.removeEventListener('pointercancel', pointerReleased)
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <dialog ref="dialog" class="fine-editor" aria-label="人工精剪" @cancel.prevent="close" @keydown.esc.stop @keydown="markKey">
    <header><div><h2>人工精剪</h2><p>{{ title }}</p></div>
      <button class="secondary" @click="close">{{ closing ? '放弃未保存内容并关闭' : '关闭' }}</button></header>
    <div class="fine-workspace">
      <div class="fine-body">
        <section class="fine-monitor" aria-label="精剪预览">
          <div class="fine-monitor-head">
            <label v-if="cuts.length > 1" class="fine-segment">调整片段
              <select :value="selected" :disabled="working || invalidInput" @change="choose(Number(($event.target as HTMLSelectElement).value))">
                <option v-for="(part, i) in cuts" :key="i" :value="i">第 {{ i + 1 }} 段 · {{ topicTime(part.end_ms - part.start_ms) }}</option>
              </select>
            </label>
            <strong v-else>第 1 段原片</strong><time>原片 {{ topicTime(sourceHead) }}</time>
          </div>
          <video ref="player" :src="videoUrl" preload="metadata" playsinline @loadedmetadata="metadata"
            @error="ready = false; mediaFailed = true; stop()" @play="guardPlay" @playing="guardPlay"
            @timeupdate="sequence.timeUpdate($event.currentTarget as HTMLVideoElement)" @seeking="sequence.seeking($event.currentTarget as HTMLVideoElement)"
            @seeked="sequence.seeked($event.currentTarget as HTMLVideoElement)" @pause="sequence.pause($event.currentTarget as HTMLVideoElement)"
            @ended="sequence.ended($event.currentTarget as HTMLVideoElement)" @ratechange="sequence.rateChange($event.currentTarget as HTMLVideoElement)" />
          <p v-if="mediaFailed" role="status">原片载入失败。可编辑剪点并下载时间线。</p>
          <div class="fine-source-seek" :style="sourceTrackStyle">
            <div><time>{{ topicTime(original.start_ms) }}</time><span>{{ cursor === null ? '保留区间外 · 可重新设点' : '拖动预览本段原片' }}</span><time>{{ topicTime(original.end_ms) }}</time></div>
            <input type="range" aria-label="本段原片播放头" :aria-valuetext="topicTime(sourceHead)"
              :min="original.start_ms" :max="original.end_ms" :value="sourceHead" step="1" :disabled="!ready || invalidInput || working"
              @input="seekSource(($event.target as HTMLInputElement).valueAsNumber)" />
          </div>
          <div class="fine-actions fine-marks">
            <button class="secondary" :disabled="!ready || invalidInput || working" @click="listenSource">{{ playback?.id === 'fine-cut-source' ? '暂停原片' : '播放本段原片' }}</button>
            <button class="secondary" :disabled="!canMark('start_ms')" aria-keyshortcuts="I" @click="mark('start_ms')">设为入点 <kbd>I</kbd></button>
            <button class="secondary" :disabled="!canMark('end_ms')" aria-keyshortcuts="O" @click="mark('end_ms')">设为出点 <kbd>O</kbd></button>
          </div>
        </section>
        <section class="fine-timeline" aria-label="精剪成片时间线">
          <CutScrubber :parts="cuts" :position-ms="cursor" :video-url="active ? videoUrl : ''"
            :source-aspect="ready && player?.videoHeight ? player.videoWidth / player.videoHeight : 0" :disabled="working || invalidInput" @seek="seek" />
          <div class="fine-actions fine-transport"><button class="secondary" :disabled="!ready || invalidInput || working" @click="listen">{{ playback?.id === 'fine-cut' ? '停止试听' : cursor === null ? '从本段试听' : cursor === total ? '从头试听' : '从游标试听' }}</button>
            <button v-if="joinAfter" class="secondary" :disabled="!ready || invalidInput || working" @click="listenJoin">试听第 {{ joinAfter.partIndex }} 个剪点</button></div>
        </section>
        <section v-if="current && original" class="fine-inspector" aria-label="调整片段">
          <div class="fine-monitor-head"><strong>剪点调整</strong><span>本段保留 {{ topicTime(current.end_ms - current.start_ms) }}</span></div>
          <p>先在监视器找到剪点，再按 I 或 O。出点是停止保留的位置，也可在下方输入原片时间。</p>
          <FineCutBounds :key="selected" :base="original" :cut="current" :disabled="working"
            :can-listen="ready && !invalidInput" @edge="edit" @listen="listenEdge"
            @invalid="invalidInput = $event" @begin-drag="drag = 'begin'" />
          <div class="fine-actions"><button class="secondary" :disabled="!past.length || working" @click="undo">撤销上次调整</button>
            <button class="secondary" :disabled="working || (!invalidInput && current.start_ms === original.start_ms && current.end_ms === original.end_ms)" @click="restore">还原本段</button></div>
        </section>
      </div>
    </div>
    <footer><div><strong>精剪时长 {{ topicTime(total) }}</strong> <span>· 已缩短 {{ topicTime(originalMs - total) }}</span>
      <p>{{ saved }} · 精剪交付为 OTIO 工程</p><p v-if="status" role="status">{{ status }}</p>
      <p v-if="downloadName"><a :href="originalDownload" :download="downloadName">下载对应原片</a> · 配套文件名 {{ downloadName }}</p></div>
      <button class="primary" :disabled="working || invalidInput" @click="download">{{ working ? '正在导出…' : '下载精剪时间线 (.otio)' }}</button></footer>
  </dialog>
</template>

<style scoped>
.fine-editor {
  color-scheme:dark; width:min(1190px,96vw); max-width:96vw; height:min(840px,94dvh); max-height:94dvh; padding:0;
  color:var(--workspace-text,#eff2f4); background:var(--workspace-surface,#1b1e24);
  border:1px solid var(--workspace-control-border,#49505b); border-radius:12px; box-shadow:0 24px 80px #0008;
}
.fine-editor[open] { display:flex; flex-direction:column; overflow:hidden; }
.fine-editor::backdrop { background:#080a0dcc; }
.fine-editor header,.fine-editor footer,.fine-actions,.fine-segment,.fine-monitor-head { display:flex; align-items:center; gap:10px; }
.fine-editor header,.fine-editor footer { justify-content:space-between; flex:none; padding:12px 20px; background:var(--workspace-surface,#1b1e24); }
.fine-editor header { border-bottom:1px solid var(--workspace-border,#363b44); }
.fine-editor header > div { display:flex; align-items:baseline; flex-wrap:wrap; gap:2px 14px; }
.fine-editor header > div,.fine-editor footer > div { min-width:0; overflow-wrap:anywhere; }
.fine-editor header button,.fine-editor footer button { flex:none; }
.fine-editor h2,.fine-editor p { margin:0; }
.fine-editor h2 { color:var(--workspace-text,#eff2f4); font-size:1.25rem; font-weight:650; }
.fine-editor p,.fine-editor footer span { color:var(--workspace-muted,#a5adb7); font-size:.86rem; line-height:1.5; }
.fine-workspace { display:flex; flex:1; min-height:0; }
.fine-body { display:grid; flex:1; min-width:0; min-height:0; grid-template:"monitor inspector" minmax(0,1fr) "timeline timeline" auto / minmax(0,1.58fr) minmax(330px,1fr); }
.fine-monitor,.fine-inspector { min-width:0; min-height:0; overflow:auto; padding:14px 18px; }
.fine-monitor { grid-area:monitor; display:flex; flex-direction:column; gap:10px; background:var(--studio-well,#101215); }
.fine-monitor-head { flex-wrap:wrap; justify-content:space-between; font-size:.88rem; color:var(--workspace-muted,#a5adb7); }
.fine-monitor-head strong { color:var(--workspace-text); }
.fine-monitor-head time { color:var(--workspace-accent,#70e3cd); font:500 .9rem var(--workspace-mono,monospace); }
.fine-monitor video { flex:1 1 0; width:100%; min-height:70px; max-height:100%; object-fit:contain; border:1px solid #ffffff12; border-radius:4px; background:#08090c; }
.fine-source-seek { --rail:linear-gradient(to right,var(--workspace-control-border) 0 var(--source-in),var(--workspace-accent) var(--source-in) var(--source-out),var(--workspace-control-border) var(--source-out)); }
.fine-source-seek > div { display:flex; justify-content:space-between; gap:6px; color:var(--workspace-muted); font-size:.8rem; }
.fine-source-seek time { font-family:var(--workspace-mono); }
.fine-source-seek input { display:block; width:100%; min-height:26px; margin:0; padding:0; background:transparent; cursor:ew-resize; appearance:none; }
.fine-source-seek input::-webkit-slider-runnable-track { height:5px; border-radius:3px; background:var(--rail); }
.fine-source-seek input::-moz-range-track { height:5px; border-radius:3px; background:var(--rail); }
.fine-source-seek input::-webkit-slider-thumb { appearance:none; width:13px; height:17px; margin-top:-6px; border:1px solid var(--studio-ink); border-radius:3px; background:var(--workspace-text); }
.fine-source-seek input::-moz-range-thumb { width:12px; height:16px; border:1px solid var(--studio-ink); border-radius:3px; background:var(--workspace-text); }
.fine-marks kbd { margin-left:3px; font:.8em var(--workspace-mono); color:var(--workspace-muted); }
.fine-transport { margin-top:7px; }
.fine-inspector { grid-area:inspector; display:grid; align-content:start; gap:12px; border-left:1px solid var(--workspace-border,#363b44); }
.fine-actions { flex-wrap:wrap; gap:6px 8px; }
.fine-inspector > .fine-actions { padding-top:8px; border-top:1px solid var(--workspace-border,#363b44); }
.fine-segment { justify-content:space-between; flex-wrap:wrap; font-weight:650; }
.fine-segment select { max-width:100%; }
.fine-editor .secondary,.fine-segment select { min-height:34px; padding:5px 10px; border:1px solid var(--workspace-control-border,#49505b); border-radius:5px; background:var(--studio-raised,#252a31); color:var(--workspace-text,#eff2f4); font:inherit; font-size:.9rem; }
.fine-editor .secondary:hover:not(:disabled) { border-color:var(--workspace-accent,#70e3cd); }
.fine-timeline { grid-area:timeline; min-width:0; padding:12px 18px; border-block:1px solid var(--workspace-border,#363b44); background:var(--studio-well,#101215); }
.fine-editor footer strong { font-variant-numeric:tabular-nums; }
.fine-editor footer .primary { min-height:38px; padding:6px 14px; border:0; border-radius:5px; background:var(--workspace-accent,#70e3cd); color:var(--studio-ink,#092320); font-weight:650; }
.fine-editor footer a { color:var(--workspace-accent,#70e3cd); text-decoration:underline; }
.fine-editor :focus-visible { outline:2px solid var(--workspace-accent,#70e3cd); outline-offset:2px; }
@media(max-width:740px) {
  .fine-editor { width:100%; max-width:100%; height:98dvh; max-height:98dvh; border-radius:8px; }
  .fine-workspace { display:block; overflow:auto; }
  .fine-body { display:block; }
  .fine-monitor,.fine-inspector { overflow:visible; padding:10px 12px; gap:9px; }
  .fine-monitor video { flex:none; height:clamp(105px,26dvh,230px); }
  .fine-inspector { border-left:0; border-top:1px solid var(--workspace-border,#363b44); }
  .fine-editor header,.fine-editor footer { padding:8px 12px; }
  .fine-editor footer { flex-wrap:wrap; }
  .fine-timeline { padding:10px 12px; }
}
@media(max-height:500px) {
  .fine-editor[open] { display:block; overflow:auto; height:98dvh; max-height:98dvh; }
  .fine-workspace { display:block; overflow:visible; }
  .fine-monitor,.fine-inspector { overflow:visible; }
  .fine-monitor video { flex:0 0 auto; height:clamp(105px,43dvh,190px); }
}
</style>
