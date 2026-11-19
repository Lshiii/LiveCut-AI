<script setup lang="ts">
import { computed } from 'vue'
import type { IntentSnapshot, IntentOutput } from './intentClips.ts'
import { replaceComponent, selectedComponents, type ComponentSelection, type MontageComponent } from './componentCandidates.ts'
import ProductFrameRanking from './ProductFrameRanking.vue'
import { topicTime } from './topics.ts'

const props = defineProps<{ snapshot: IntentSnapshot; output: IntentOutput; disabled: boolean; active: boolean }>()
const emit = defineEmits<{ apply: [pick: ComponentSelection]; preview: [pick: ComponentSelection]; seek: [ms: number] }>()
const groups = computed(() => props.snapshot.plan?.component_candidates ?? [])
const selected = computed(() => selectedComponents(props.output, groups.value))
const words = computed(() => new Map(props.snapshot.utterances.map(u => [u.utterance_id, u.text])))
function selection(g: MontageComponent, choice: number): ComponentSelection {
  return { request: props.snapshot.id, revision: props.snapshot.revision, hash: props.snapshot.plan_hash!, component: g.component_id, choice }
}
function alternatives(g: MontageComponent) {
  return g.choices.map((c, i) => ({ id: String(i), parts: c.parts, text: c.evidence_utterance_ids.map(id => words.value.get(id)).join(' ') }))
}
function choose(g: MontageComponent, i: number) {
  if (props.disabled || !props.active) return
  const pick = selection(g, i)
  if (replaceComponent(props.snapshot, props.output, pick)) emit('apply', pick)
}
</script>

<template>
  <section v-if="groups.length" class="replacement-panel" aria-label="按商品或素材替换区间">
    <h4>按商品或素材换段</h4>
    <p v-if="!selected" class="help">当前区间经过单独编辑。恢复已保存的候选组合后可按素材替换。</p>
    <details v-for="(g, gi) in groups" :key="g.component_id">
      <summary>{{ g.title }} <small>{{ g.choices.length - 1 }} 个备选</small></summary>
      <p v-if="g.choices.length === 1" class="help">这项素材只找到当前选段。</p>
      <div v-for="(c, ci) in g.choices" :key="ci" class="replacement-choice">
        <b>{{ ci ? '备选 ' + ci : '原方案' }}<span v-if="selected?.[gi] === ci"> · 当前</span></b>
        <div v-for="p in c.parts" :key="p.start_utterance_id">
          <small>{{ topicTime(p.start_ms) }}–{{ topicTime(p.end_ms) }}</small> {{ words.get(p.start_utterance_id) }}
        </div>
        <button type="button" class="text-button" :disabled="disabled || !active" @click="emit('preview', selection(g, ci))">回看这组选段</button>
        <button v-if="ci !== selected?.[gi]" type="button" class="secondary"
          :disabled="disabled || !selected || !active || !replaceComponent(snapshot, output, selection(g, ci))"
          @click="choose(g, ci)">只替换这项素材</button>
      </div>
      <ProductFrameRanking v-if="alternatives(g).length > 1" :snapshot="snapshot" :label="g.title" :candidates="alternatives(g)"
        :active="active && !disabled" :can-choose="!!selected" @choose="choose(g, Number($event))" @seek="emit('seek', $event)" />
    </details>
  </section>
</template>

<style scoped>
.replacement-panel { margin-block: .65rem; }
.replacement-panel h4 { margin: 0 0 .35rem; }
.replacement-panel details { padding-block: .4rem; border-bottom: 1px solid var(--line, #ddd); }
.replacement-panel summary { cursor: pointer; }
.replacement-panel summary small { margin-left: .4rem; }
.replacement-choice { padding: .55rem 0; }
.replacement-choice small { font-variant-numeric: tabular-nums; }
.replacement-choice button { margin: .35rem .6rem 0 0; }
</style>
