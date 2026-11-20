<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { editJoins } from './editTimeline'
import { inspectJoinBlackFrames, type JoinSample } from './joinBlackFrames'
import { topicTime } from './topics'
import type { SequencePreviewPart } from './sequencePreview'

const props = defineProps<{ parts: SequencePreviewPart[]; videoUrl: string; activeJoin?: number; disabled: boolean }>()
const emit = defineEmits<{ audition: [index: number]; stop: []; seek: [sourceMs: number] }>()
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
watch(ranges, () => { selected.value = 0 }, { flush: 'sync' })
watch([ranges, selected, () => props.videoUrl, () => props.disabled], resetCheck, { flush: 'sync' })
onBeforeUnmount(resetCheck)
function change(event: Event) {
  if (props.activeJoin !== undefined) emit('stop')
  selected.value = Number((event.target as HTMLSelectElement).value)
}
</script>

<template>
  <section v-if="join" class="cut-timeline" aria-label="成片剪点">
    <header><strong>成片时间线</strong><span>{{ parts.length }} 段 · {{ topicTime(total) }}</span></header>
    <div class="cut-track" aria-hidden="true"><i v-for="(part, i) in parts" :key="part.start_ms"
      :class="{ left: i === selected, right: i === selected + 1 }" :style="{ flexGrow: part.end_ms - part.start_ms }"></i></div>
    <div class="cut-controls">
      <label v-if="joins.length > 1">剪点 <select aria-label="选择成片剪点" :value="selected" @change="change">
        <option v-for="(item, i) in joins" :key="item.at_ms" :value="i">{{ i + 1 }} · {{ topicTime(item.at_ms) }}</option>
      </select></label>
      <strong v-else>剪点 · {{ topicTime(join.at_ms) }}</strong>
      <button type="button" class="secondary" :disabled="disabled" :aria-pressed="activeJoin === selected"
        @click="emit('audition', selected)">{{ activeJoin === selected ? '停止试听' : '试听剪点' }}</button>
      <button type="button" class="secondary" :disabled="disabled || !videoUrl" @click="checking ? resetCheck() : check()">
        {{ checking ? '取消黑帧检查' : '检查剪点黑帧' }}
      </button>
    </div>
    <small>原片 {{ topicTime(join.from_ms) }} → {{ topicTime(join.to_ms) }} · 试听前后各最多 1.5 秒</small>
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
.cut-timeline { display: grid; gap: .55rem; min-width: 0; margin: .65rem 0; padding: .75rem; border: 1px solid #cbd7c8; border-radius: 6px; background: #f5f8f3; }
.cut-timeline header, .cut-controls { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: .45rem .8rem; }
.cut-timeline header span, .cut-timeline small { color: #485946; font-variant-numeric: tabular-nums; }
.cut-track { display: flex; height: 13px; gap: 2px; overflow: hidden; border-radius: 3px; }
.cut-track i { flex-basis: 0; min-width: 1px; background: #c1cdbf; }
.cut-track .left { background: #37604e; }
.cut-track .right { background: #76967d; }
.cut-controls label { display: inline-flex; align-items: center; gap: .4rem; }
.cut-controls strong { font-size: .85rem; font-variant-numeric: tabular-nums; }
.cut-controls button { min-height: 32px; }
.cut-check { margin: 0; font-size: .85rem; }
.cut-marks { display: flex; flex-wrap: wrap; gap: .3rem .8rem; }
.cut-marks button { font-variant-numeric: tabular-nums; }
</style>
