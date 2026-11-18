<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { editJoins } from './editTimeline'
import { topicTime } from './topics'
import type { SequencePreviewPart } from './sequencePreview'

const props = defineProps<{ parts: SequencePreviewPart[]; activeJoin?: number; disabled: boolean }>()
const emit = defineEmits<{ audition: [index: number]; stop: [] }>()
const joins = computed(() => editJoins(props.parts))
const selected = ref(0)
const join = computed(() => joins.value[selected.value])
const total = computed(() => props.parts.reduce((sum, part) => sum + part.end_ms - part.start_ms, 0))
watch(() => props.parts.map(p => [p.start_ms, p.end_ms].join('-')).join(','), () => { selected.value = 0 })
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
    </div>
    <small>原片 {{ topicTime(join.from_ms) }} → {{ topicTime(join.to_ms) }} · 试听前后各最多 1.5 秒</small>
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
</style>
