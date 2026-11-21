<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { parseTopicTime, topicTime } from './topics'
import type { SequencePreviewPart } from './sequencePreview'

type Edge = keyof SequencePreviewPart
const props = defineProps<{ base: SequencePreviewPart; cut: SequencePreviewPart; disabled: boolean; canListen?: boolean }>()
const emit = defineEmits<{ edge: [edge: Edge, value: number]; invalid: [value: boolean]; beginDrag: []; listen: [edge: Edge] }>()
const edges = [{ id: 'start_ms', label: '入点' }, { id: 'end_ms', label: '出点' }] as const
const step = ref(100), messages = ref({ start_ms: '', end_ms: '' })
const inputs = ref({ start_ms: '', end_ms: '' })
const invalidEdge = computed(() => edges.find(edge => !!messages.value[edge.id])?.id)
function sync() {
  inputs.value = { start_ms: topicTime(props.cut.start_ms), end_ms: topicTime(props.cut.end_ms) }
  messages.value = { start_ms: '', end_ms: '' }
  emit('invalid', false)
}
watch(() => [props.base, props.cut], sync, { immediate: true })
function limits(edge: Edge) {
  return edge === 'start_ms' ? [props.base.start_ms, props.cut.end_ms - 1] as const
    : [props.cut.start_ms + 1, props.base.end_ms] as const
}
function clamp(edge: Edge, value: number) {
  const [low, high] = limits(edge)
  return Math.max(low, Math.min(high, value))
}
function drag(edge: Edge, event: Event) {
  const range = event.target as HTMLInputElement
  const value = clamp(edge, range.valueAsNumber)
  range.value = String(value)
  if (!props.disabled && !invalidEdge.value) emit('edge', edge, value)
}
function typeTime(edge: Edge, event: Event) {
  inputs.value[edge] = (event.target as HTMLInputElement).value
  const value = parseTopicTime(inputs.value[edge]), [low, high] = limits(edge)
  messages.value[edge] = value === null || value < low || value > high
    ? '请输入 ' + topicTime(low) + ' 至 ' + topicTime(high) + ' 之间的原片时间。' : ''
  emit('invalid', edges.some(({ id }) => !!messages.value[id] || inputs.value[id] !== topicTime(props.cut[id])))
}
function commit(edge: Edge) {
  const value = parseTopicTime(inputs.value[edge])
  if (props.disabled || (invalidEdge.value && invalidEdge.value !== edge) || value === null || value !== clamp(edge, value)) return
  emit('edge', edge, value)
  if (value === props.cut[edge]) sync()
}
const highlight = computed(() => {
  const pct = (v: number) => 100 * (v - props.base.start_ms) / (props.base.end_ms - props.base.start_ms) + '%'
  return { '--in': pct(props.cut.start_ms), '--out': pct(props.cut.end_ms) }
})
</script>

<template>
  <div class="bounds" :style="highlight">
    <div class="bound-scale"><span>{{ topicTime(base.start_ms) }}</span><span>原片时间</span><span>{{ topicTime(base.end_ms) }}</span></div>
    <div class="handles">
      <div class="range-bar" aria-hidden="true"><i /></div>
      <input v-for="edge in edges" :key="edge.id" class="grip" :class="edge.id" type="range"
        :aria-label="'拖动' + edge.label" :aria-valuetext="topicTime(cut[edge.id])"
        :min="base.start_ms" :max="base.end_ms" step="1" :value="cut[edge.id]" :disabled="disabled || !!invalidEdge"
        @pointerdown="emit('beginDrag')" @input="drag(edge.id, $event)">
    </div>
    <div class="bound-controls">
      <label class="fine-step">微调步长
        <select v-model.number="step" :disabled="disabled"><option :value="1">1 毫秒</option><option :value="10">10 毫秒</option>
          <option :value="100">100 毫秒</option><option :value="1000">1 秒</option></select>
      </label>
      <div v-for="edge in edges" :key="edge.id" class="bound-row">
        <label>{{ edge.label }} <input type="text" :aria-label="edge.label + '原片时间'" :value="inputs[edge.id]"
          :disabled="disabled || (!!invalidEdge && invalidEdge !== edge.id)" :aria-invalid="!!messages[edge.id]" spellcheck="false"
          @input="typeTime(edge.id, $event)" @change="commit(edge.id)" @keydown.enter.prevent="commit(edge.id)"
          @keydown.esc.stop.prevent="sync"></label>
        <button type="button" :aria-label="edge.label + '提前'" :disabled="disabled || !!invalidEdge || cut[edge.id] <= limits(edge.id)[0]"
          @click="emit('edge', edge.id, clamp(edge.id, cut[edge.id] - step))">−</button>
        <button type="button" :aria-label="edge.label + '推迟'" :disabled="disabled || !!invalidEdge || cut[edge.id] >= limits(edge.id)[1]"
          @click="emit('edge', edge.id, clamp(edge.id, cut[edge.id] + step))">＋</button>
        <button type="button" class="bound-listen" :disabled="disabled || !canListen" @click="emit('listen', edge.id)">{{ edge.id === 'start_ms' ? '试听开头' : '试听结尾' }}</button>
        <small v-if="messages[edge.id]" role="alert">{{ messages[edge.id] }}</small>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bounds { min-width:0; }
.bound-scale,.fine-step,.bound-row,.bound-row label { display:flex; align-items:center; gap:8px; }
.bound-scale { justify-content:space-between; color:var(--workspace-muted,#a5adb7); font:.8rem var(--workspace-mono,monospace); }
.handles { position:relative; height:52px; margin:5px 0 12px; background:var(--studio-well,#101215); border-radius:5px; }
.range-bar { position:absolute; inset:13px 9px; border-block:1px solid var(--workspace-control-border,#49505b); background:repeating-linear-gradient(125deg,transparent 0 6px,#ffffff0e 6px 8px); }
.range-bar i { position:absolute; inset-block:0; left:var(--in); right:calc(100% - var(--out)); background:var(--workspace-accent-soft,#21413e); border-inline:1px solid var(--workspace-accent,#70e3cd); }
.grip { position:absolute; left:0; margin:0; width:100%; height:26px; padding:0; appearance:none; border:0; background:transparent; cursor:ew-resize; }
.grip.start_ms { top:0; } .grip.end_ms { bottom:0; }
.grip::-webkit-slider-runnable-track { height:26px; background:transparent; }
.grip::-moz-range-track { height:26px; background:transparent; }
.grip::-webkit-slider-thumb { appearance:none; width:16px; height:22px; border:2px solid var(--studio-ink,#092320); border-radius:4px; background:var(--workspace-accent,#70e3cd); box-shadow:0 0 0 1px var(--workspace-accent,#70e3cd); }
.grip::-moz-range-thumb { width:12px; height:18px; border:2px solid var(--studio-ink,#092320); border-radius:4px; background:var(--workspace-accent,#70e3cd); box-shadow:0 0 0 1px var(--workspace-accent,#70e3cd); }
.grip:focus-visible,.bounds :focus-visible { outline:2px solid var(--workspace-accent,#70e3cd); outline-offset:2px; }
.grip:disabled { opacity:.45; cursor:default; }
.bound-controls { display:grid; gap:9px; }
.bound-row { flex-wrap:wrap; column-gap:5px; }
.bound-row label { flex:1 0 auto; justify-content:space-between; font-weight:600; max-width:165px; margin-right:auto; }
.bound-row input { box-sizing:content-box; width:9ch; font:.95rem var(--workspace-mono,monospace); }
.bound-row small { flex-basis:100%; color:var(--studio-danger,#ffabad); }
.fine-step { justify-content:space-between; margin-bottom:2px; color:var(--workspace-muted,#a5adb7); font-size:.85rem; }
.bounds input[type=text],.bounds select,.bound-row button { padding:4px 7px; border:1px solid var(--workspace-control-border,#49505b); border-radius:5px; background:var(--studio-raised,#252a31); color:var(--workspace-text,#eff2f4); }
.bounds input[type=text] { background:var(--studio-well,#101215); }
.bounds input[aria-invalid=true] { border-color:var(--studio-danger,#ffabad); }
.bound-row button { min-width:32px; min-height:32px; font-size:1.1rem; }
.bound-row button:hover:not(:disabled) { border-color:var(--workspace-accent,#70e3cd); }
.bound-row .bound-listen { font-size:.83rem; }
</style>
