<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { editJoins } from './editTimeline'
import { inspectJoinBlackFrames, type JoinSample } from './joinBlackFrames'
import { topicTime } from './topics'
import type { SequencePreviewPart } from './sequencePreview'

const props = defineProps<{ parts: SequencePreviewPart[]; videoUrl: string; activeJoin?: number; disabled: boolean; canAdjust?: boolean }>()
const emit = defineEmits<{ audition: [index: number]; stop: []; seek: [sourceMs: number];
  'adjust-boundary': [partIndex: number, field: 'start' | 'end', event: Event] }>()
const joins = computed(() => editJoins(props.parts))
const selected = ref(0)
const join = computed(() => joins.value[selected.value])
const total = computed(() => props.parts.reduce((sum, part) => sum + part.end_ms - part.start_ms, 0))
const ranges = computed(() => props.parts.map(p => [p.start_ms, p.end_ms].join('-')).join(','))
const checking = shallowRef<AbortController | null>(null)
const marks = ref<JoinSample[] | null>(null)
const error = ref('')
function resetCheck() {
  checking.value?.abort()
  checking.value = null
  marks.value = null
  error.value = ''
}
async function check() {
  resetCheck()
  if (props.disabled || !props.videoUrl || !join.value) return
  const controller = new AbortController()
  checking.value = controller
  try {
    const result = await inspectJoinBlackFrames(props.videoUrl, join.value, controller.signal)
    if (checking.value === controller) marks.value = result
  } catch (cause) {
    if (checking.value === controller && !controller.signal.aborted) {
      error.value = cause instanceof Error && cause.name === 'Error' ? cause.message : '浏览器未能读取画面，请检查视频或跨域读取权限。'
    }
  } finally {
    if (checking.value === controller) checking.value = null
  }
}
watch(joins, (next, previous) => {
  const was = previous[selected.value]
  const same = was ? next.findIndex(item => item.from_ms === was.from_ms && item.to_ms === was.to_ms) : -1
  selected.value = same >= 0 ? same : Math.max(0, Math.min(selected.value, next.length - 1))
}, { flush: 'sync' })
watch([ranges, selected, () => props.videoUrl, () => props.disabled], resetCheck, { flush: 'sync' })
onBeforeUnmount(resetCheck)
function select(index: number) {
  if (!joins.value[index] || index === selected.value) return
  if (props.activeJoin !== undefined) emit('stop')
  selected.value = index
}
</script>

<template>
  <section v-if="join" class="cut-timeline" aria-label="成片剪点">
    <header><strong>成片时间线</strong><span>{{ parts.length }} 段 · {{ topicTime(total) }}</span></header>
    <div class="cut-track">
      <div class="cut-segments" aria-hidden="true"><i v-for="(part, i) in parts" :key="part.start_ms"
        :class="{ left: i === selected, right: i === selected + 1 }" :style="{ flexGrow: part.end_ms - part.start_ms }"></i></div>
      <button v-for="(item, i) in joins" :key="item.at_ms" type="button" class="cut-pin"
        :style="{ left: (100 * item.at_ms / total) + '%' }" :aria-pressed="selected === i"
        :aria-label="`选择剪点 ${i + 1}，成片 ${topicTime(item.at_ms)}`" :title="`剪点 ${i + 1} · ${topicTime(item.at_ms)}`"
        @click="select(i)"><span>{{ i + 1 }}</span></button>
    </div>
    <div class="cut-controls">
      <label v-if="joins.length > 1">剪点 <select aria-label="选择成片剪点" :value="selected" @change="select(Number(($event.target as HTMLSelectElement).value))">
        <option v-for="(item, i) in joins" :key="item.at_ms" :value="i">{{ i + 1 }} · {{ topicTime(item.at_ms) }}</option>
      </select></label>
      <strong v-else>剪点 · {{ topicTime(join.at_ms) }}</strong>
      <button type="button" class="secondary" :disabled="disabled" :aria-pressed="activeJoin === selected"
        @click="emit('audition', selected)">{{ activeJoin === selected ? '停止试听' : '试听剪点' }}</button>
      <button type="button" class="secondary" :disabled="disabled || !videoUrl" @click="checking ? resetCheck() : check()">
        {{ checking ? '取消黑帧检查' : '检查剪点黑帧' }}
      </button>
    </div>
    <div class="cut-locate" aria-label="剪点两侧原片定位">
      <div><button type="button" class="text-button" :disabled="disabled" title="定位到前段的最后一毫秒"
        @click="emit('seek', join.from_ms - 1)">前段出点 <time>{{ topicTime(join.from_ms) }}</time></button>
        <button v-show="canAdjust" type="button" class="secondary" :disabled="!canAdjust" @click="emit('adjust-boundary', selected, 'end', $event)">调整前段结尾</button></div>
      <span aria-hidden="true">→</span>
      <div><button type="button" class="text-button" :disabled="disabled" title="定位到后段的第一毫秒"
        @click="emit('seek', join.to_ms)">后段入点 <time>{{ topicTime(join.to_ms) }}</time></button>
        <button v-show="canAdjust" type="button" class="secondary" :disabled="!canAdjust" @click="emit('adjust-boundary', selected + 1, 'start', $event)">调整后段开头</button></div>
    </div>
    <small>试听前后各最多 1.5 秒</small>
    <p v-if="checking || error || marks" class="cut-check" role="status">
      {{ checking ? '正在抽查剪点两侧的画面…' : error || (marks?.length ? '发现疑似黑帧。点击成片时间可定位原片。' : '剪点两侧的采样中未见明显黑帧。') }}
    </p>
    <div v-if="marks?.length" class="cut-marks">
      <button v-for="mark in marks" :key="mark.editMs" type="button" class="text-button" :disabled="disabled"
        :title="'定位原片 ' + topicTime(mark.sourceMs)" @click="emit('seek', mark.sourceMs)">
        {{ mark.side === 'before' ? '剪点前' : '剪点后' }} {{ topicTime(mark.editMs) }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.cut-timeline { display: grid; gap: .55rem; min-width: 0; margin: .65rem 0; padding: .75rem; border: 1px solid var(--workspace-border,#e4e0d8); border-radius: 6px; background: var(--workspace-canvas,#f4f3ef); }
.cut-timeline header, .cut-controls { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: .45rem .8rem; }
.cut-timeline header span, .cut-timeline small { color: var(--workspace-muted,#675f56); font-variant-numeric: tabular-nums; }
.cut-track { position:relative; margin:0 15px; height:36px; }
.cut-segments { position:absolute; inset:auto 0 1px; display:flex; height:9px; overflow:hidden; border-radius:2px; background:#dbd4c9; }
.cut-segments i { flex-basis:0; min-width:0; border-right:1px solid white; box-sizing:border-box; }
.cut-segments .left { background:#905038; }
.cut-segments .right { background:#c48968; }
.cut-pin { position:absolute; top:0; z-index:1; display:grid; place-items:start center; width:29px; height:36px; padding:0; transform:translateX(-50%); border:0; background:transparent; cursor:pointer; }
.cut-pin::after { content:''; width:2px; position:absolute; inset:19px auto 0; background:#655548; }
.cut-pin span { display:grid; place-items:center; min-width:21px; height:20px; padding:0 2px; border-radius:3px; background:#fff; border:1px solid #7f7267; color:#4d433b; font:600 12px var(--workspace-mono,monospace); }
.cut-pin[aria-pressed='true'] { z-index:2; }
.cut-pin[aria-pressed='true'] span,.cut-pin[aria-pressed='true']::after { background:var(--workspace-accent,#a94424); border-color:var(--workspace-accent,#a94424); color:white; }
.cut-pin:focus-visible { z-index:3; }
.cut-controls label { display: inline-flex; align-items: center; gap: .4rem; }
.cut-controls strong { font-size: .85rem; font-variant-numeric: tabular-nums; }
.cut-controls button { min-height: 32px; }
.cut-locate { display:grid; grid-template-columns:minmax(0,1fr) auto minmax(0,1fr); align-items:start; gap:.2rem .4rem; }
.cut-locate > div { display:flex; align-items:flex-start; flex-wrap:wrap; gap:2px 6px; }
.cut-locate > span { line-height:32px; }
.cut-locate button { min-height:32px; font-size:.9rem; }
.cut-locate .text-button { padding:2px 0; color:var(--workspace-accent,#a94424); white-space:normal; text-align:left; font-variant-numeric:tabular-nums; }
.cut-locate .secondary { padding:3px 8px; }
.cut-check { margin: 0; font-size: .85rem; }
.cut-marks { display: flex; flex-wrap: wrap; gap: .3rem .8rem; }
.cut-marks button { font-variant-numeric: tabular-nums; }
</style>
