<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import { editSpans, editToSource } from './editTimeline'
import { topicTime } from './topics'
import type { SequencePreviewPart } from './sequencePreview'

const props = defineProps<{ parts: readonly SequencePreviewPart[]; positionMs: number | null; disabled?: boolean }>()
const emit = defineEmits<{ seek: [editMs: number] }>()
const track = ref<HTMLElement | null>(null), hintId = useId()
const spans = computed(() => editSpans(props.parts))
const total = computed(() => spans.value.at(-1)?.editEndMs ?? 0)
const zoomLevel = ref(0), offset = ref(0)
const maxLevel = computed(() => Math.max(0, Math.ceil(Math.log2(Math.max(1, total.value / 1000)))))
const windowMs = computed(() => Math.min(total.value, Math.max(1000, Math.ceil(total.value / (2 ** zoomLevel.value)))))
const start = computed(() => Math.min(offset.value, total.value - windowMs.value))
const end = computed(() => start.value + windowMs.value)
const draggedTo = ref<number | null>(null)
let pointer: number | null = null
const position = computed(() => {
  const value = draggedTo.value ?? props.positionMs
  return value !== null && Number.isSafeInteger(value) && value >= 0 && value <= total.value ? value : null
})
const current = computed(() => position.value === null ? null : editToSource(props.parts, position.value))
const viewportSegments = computed(() => spans.value
  .filter(part => part.editEndMs > start.value && part.editStartMs < end.value)
  .map(part => {
    const left = Math.max(start.value, part.editStartMs), right = Math.min(end.value, part.editEndMs)
    return { index: part.partIndex, left: percent(left), width: 100 * (right - left) / windowMs.value }
  }))
const cursorVisible = computed(() => position.value !== null && position.value >= start.value && position.value <= end.value)
const boundsSignature = computed(() => spans.value.map(part => `${part.start_ms}-${part.end_ms}`).join(','))
function percent(editMs: number) { return 100 * (editMs - start.value) / windowMs.value }
function moveWindow(left: number) { offset.value = Math.max(0, Math.min(Math.round(left), total.value - windowMs.value)) }
function zoom(level: number) {
  const center = cursorVisible.value && position.value !== null ? position.value : start.value + windowMs.value / 2
  zoomLevel.value = Math.min(maxLevel.value, Math.max(0, level))
  moveWindow(center - windowMs.value / 2)
}
function reveal(editMs: number) {
  if (editMs < start.value || editMs > end.value) moveWindow(editMs - windowMs.value / 2)
}
function seek(editMs: number) {
  if (props.disabled || !total.value || !Number.isSafeInteger(editMs)) return
  const ms = Math.min(total.value, Math.max(0, editMs))
  reveal(ms)
  emit('seek', ms)
}
function keydown(event: KeyboardEvent) {
  if (event.altKey || event.metaKey || event.ctrlKey || props.disabled) return
  const ms = position.value ?? start.value, step = event.shiftKey ? 100 : 1
  let next: number
  switch (event.key) {
    case 'ArrowLeft': case 'ArrowDown': next = ms - step; break
    case 'ArrowRight': case 'ArrowUp': next = ms + step; break
    case 'PageDown': next = ms - 1000; break
    case 'PageUp': next = ms + 1000; break
    case 'Home': next = 0; break
    case 'End': next = total.value; break
    default: return
  }
  event.preventDefault()
  seek(next)
}
function dragTo(event: PointerEvent) {
  if (pointer !== event.pointerId || props.disabled || !track.value) return
  const bounds = track.value.getBoundingClientRect()
  if (!bounds.width) return
  const fraction = Math.max(0, Math.min(1, (event.clientX - bounds.left) / bounds.width))
  const ms = start.value + Math.round(fraction * windowMs.value)
  if (draggedTo.value === ms) return
  draggedTo.value = ms
  seek(ms)
}
function begin(event: PointerEvent) {
  if (props.disabled || !total.value || event.button !== 0 || !event.isPrimary || pointer !== null || !track.value) return
  event.preventDefault()
  track.value.focus({ preventScroll: true })
  pointer = event.pointerId
  track.value.setPointerCapture(pointer)
  dragTo(event)
}
function release() {
  const captured = pointer
  pointer = null
  draggedTo.value = null
  if (captured !== null && track.value?.hasPointerCapture(captured)) track.value.releasePointerCapture(captured)
}
function finish(event: PointerEvent) {
  if (event.pointerId !== pointer) return
  if (event.type === 'pointerup') dragTo(event)
  release()
}
function selectPart(event: Event) {
  const part = spans.value[Number((event.target as HTMLSelectElement).value)]
  if (part) seek(part.editStartMs)
}
watch(() => props.positionMs, value => {
  if (pointer === null && value !== null && Number.isSafeInteger(value) && value >= 0 && value <= total.value) reveal(value)
}, { flush: 'sync' })
watch(boundsSignature, () => {
  release()
  zoomLevel.value = Math.min(zoomLevel.value, maxLevel.value)
  moveWindow(offset.value)
  if (position.value !== null) reveal(position.value)
}, { flush: 'sync' })
watch(() => props.disabled, disabled => { if (disabled) release() }, { flush: 'sync' })
onBeforeUnmount(release)
</script>

<template>
  <section v-if="total" class="cut-scrubber" aria-label="成片时间轴">
    <div class="scrub-head">
      <div class="scrub-time"><strong>成片</strong> <output aria-live="off">{{ position === null ? '—' : topicTime(position) }}</output>
        <small>/ {{ topicTime(total) }}</small></div>
      <small v-if="current" class="scrub-source">原片 {{ topicTime(current.sourceMs) }}</small>
      <small v-else class="scrub-source">原片位置在保留区间外</small>
    </div>
    <div class="scrub-scale" aria-hidden="true"><span>{{ topicTime(start) }}</span>
      <span v-if="windowMs > 1">{{ topicTime(start + Math.round(windowMs / 2)) }}</span><span>{{ topicTime(end) }}</span></div>
    <div ref="track" class="scrub-track" role="slider" aria-label="成片播放位置" aria-orientation="horizontal"
      :tabindex="disabled ? -1 : 0" :aria-disabled="!!disabled" :aria-valuemin="0" :aria-valuemax="total"
      :aria-valuenow="position ?? start" :aria-valuetext="current ? '成片 ' + topicTime(position!) + '，原片 ' + topicTime(current.sourceMs) : '原片位置在保留区间外'"
      :aria-describedby="hintId" @keydown="keydown" @pointerdown="begin" @pointermove="dragTo" @pointerup="finish"
      @pointercancel="finish" @lostpointercapture="finish">
      <span v-for="part in viewportSegments" :key="part.index" aria-hidden="true" class="scrub-segment"
        :class="{ alternate: part.index % 2, current: current?.partIndex === part.index }"
        :style="{ left: part.left + '%', width: part.width + '%' }"><span v-if="part.width >= 13">{{ part.index + 1 }}</span></span>
      <i v-if="cursorVisible" aria-hidden="true" class="scrub-cursor" :style="{ left: percent(position!) + '%' }"></i>
    </div>
    <div class="scrub-tools">
      <label v-if="spans.length > 1">保留段
        <select aria-label="定位保留段" :value="current?.partIndex ?? ''" :disabled="disabled" @change="selectPart">
          <option v-if="!current" value="" disabled>选择</option>
          <option v-for="part in spans" :key="part.partIndex" :value="part.partIndex">第 {{ part.partIndex + 1 }} 段</option>
        </select></label>
      <div v-if="maxLevel" class="scrub-zoom" role="group" aria-label="时间轴缩放">
        <button type="button" aria-label="缩小时间轴" title="缩小时间轴" :disabled="disabled || !zoomLevel" @click="zoom(zoomLevel - 1)">−</button>
        <button type="button" aria-label="放大时间轴" title="放大时间轴" :disabled="disabled || zoomLevel >= maxLevel" @click="zoom(zoomLevel + 1)">＋</button>
        <button v-if="zoomLevel" type="button" :disabled="disabled" @click="zoom(0)">显示全片</button>
      </div>
      <div v-if="zoomLevel" class="scrub-pan" role="group" aria-label="移动时间轴视窗">
        <button type="button" aria-label="前一时间窗口" title="前一时间窗口" :disabled="disabled || !start" @click="moveWindow(start - windowMs)">‹</button>
        <button type="button" aria-label="后一时间窗口" title="后一时间窗口" :disabled="disabled || end === total" @click="moveWindow(start + windowMs)">›</button>
      </div>
    </div>
    <small :id="hintId" class="scrub-hint">拖动定位 · ← → 微调 1 毫秒，按住 Shift 调整 100 毫秒</small>
  </section>
</template>

<style scoped>
.cut-scrubber { display:grid; gap:5px; min-width:0; container-type:inline-size; color:var(--workspace-text,#292621); }
.scrub-head,.scrub-time,.scrub-tools,.scrub-tools label,.scrub-zoom,.scrub-pan { display:flex; align-items:center; flex-wrap:wrap; }
.scrub-head { justify-content:space-between; gap:2px 8px; }
.scrub-time { gap:3px 6px; }
.scrub-time output,.scrub-time small,.scrub-source,.scrub-scale { font-family:var(--workspace-mono,monospace); font-variant-numeric:tabular-nums; }
.scrub-time output { font-weight:600; }
.scrub-source,.scrub-time small,.scrub-hint,.scrub-tools label,.scrub-scale { color:var(--workspace-muted,#675f56); font-size:.8rem; }
.scrub-scale { display:flex; justify-content:space-between; margin-top:4px; gap:4px; }
.scrub-track { position:relative; height:34px; margin:0 1px; cursor:crosshair; touch-action:none; user-select:none; background:#e5ded4; border-radius:3px; }
.scrub-track:focus-visible,.scrub-tools :focus-visible { outline:2px solid var(--workspace-accent,#a94424); outline-offset:2px; }
.scrub-track[aria-disabled='true'] { cursor:default; opacity:.65; }
.scrub-segment { position:absolute; inset-block:3px; display:grid; place-items:center; overflow:hidden; background:#d8c8b5; color:#3e3227; box-shadow:inset 1px 0 #fff; pointer-events:none; font-size:.78rem; font-weight:600; }
.scrub-segment.alternate { background:#c4b5a2; }
.scrub-segment.current { background:#9a5031; color:#fff; }
.scrub-cursor { pointer-events:none; position:absolute; inset-block:-2px; width:2px; background:#231b14; box-shadow:0 0 0 1px #fff; transform:translateX(-50%); }
.scrub-cursor::before { content:''; position:absolute; left:50%; transform:translateX(-50%); top:0; border-inline:4px solid transparent; border-top:6px solid #231b14; }
.scrub-tools { gap:4px 8px; }
.scrub-tools:empty { display:none; }
.scrub-tools label { gap:5px; }
.scrub-zoom,.scrub-pan { gap:3px; }
.scrub-tools button,.scrub-tools select { min-height:30px; min-width:30px; padding:2px 6px; border:1px solid var(--workspace-border,#d8cfc2); border-radius:4px; background:#fff; color:inherit; font:inherit; }
.scrub-tools button:disabled { opacity:.48; }
.scrub-hint { line-height:1.4; }
@container (max-width:225px) { .scrub-scale > span:nth-child(2):not(:last-child) { visibility:hidden; width:0; overflow:hidden; } }
@media(pointer:coarse) { .scrub-track { height:42px; } .scrub-tools button,.scrub-tools select { min-height:36px; } }
</style>
