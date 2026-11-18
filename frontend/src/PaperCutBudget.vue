<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import type { PaperCutSource } from './paperCut'
import type { suggestPaperCutBudget } from './paperCutBudget'
import { topicTime } from './topics'

const props = defineProps<{ source: PaperCutSource; removed: ReadonlySet<string>; goal: string; enabled: boolean; durationMs: number }>()
const emit = defineEmits<{ adopt: [ids: ReadonlySet<string>] }>()
const seconds = ref<number | string>(Math.max(1, Math.min(60, Math.floor(props.durationMs * .8 / 1000))))
const result = shallowRef<ReturnType<typeof suggestPaperCutBudget> | null>(null)
const error = ref(''), busy = ref(false)
const target = computed(() => {
  const ms = Math.round(Number(seconds.value) * 1000)
  return /^\d+(?:\.\d{1,3})?$/.test(String(seconds.value)) && Number.isSafeInteger(ms) && ms > 0 ? ms : 0
})
const suggestion = computed(() => result.value?.status === 'suggested' ? result.value : null)
const newlyRemoved = computed(() => {
  const proposal = suggestion.value
  return proposal ? props.source.rows.filter(({ utterance }) => proposal.removedIds.has(utterance.utterance_id) && !props.removed.has(utterance.utterance_id)) : []
})
let current: Worker | null = null
function clear() {
  current?.terminate(); current = null
  busy.value = false; result.value = null; error.value = ''
}
function generate() {
  clear()
  if (!target.value || !props.enabled) return
  try {
    const worker = new Worker(new URL('./paperCutBudget.worker.ts', import.meta.url), { type: 'module' })
    current = worker; busy.value = true
    worker.onmessage = event => {
      if (current !== worker) return
      clear()
      result.value = event.data
    }
    worker.onerror = () => {
      if (current !== worker) return
      clear(); error.value = '时长建议计算失败，可以重试或逐句选择。'
    }
    // Vue can proxy nested ASR rows. Only transfer the plain fields required by the local solver.
    const rows = props.source.rows.map(({ utterance, ...row }) => ({ ...row, utterance: { ...utterance } }))
    worker.postMessage({ source: { rows, originalDurationMs: props.source.originalDurationMs }, removed: new Set(props.removed), targetMs: target.value, goal: props.goal })
  } catch {
    clear(); error.value = '无法启动本地时长计算，可以逐句选择。'
  }
}
function adopt() {
  if (props.enabled && suggestion.value) {
    emit('adopt', new Set(newlyRemoved.value.map(row => row.utterance.utterance_id)))
    clear()
  }
}
watch([seconds, () => props.source, () => props.removed, () => props.enabled, () => props.goal], clear, { flush: 'sync' })
onBeforeUnmount(clear)
</script>

<template>
  <section class="cut-budget" aria-label="按目标时长粗剪">
    <div class="budget-controls">
      <label>目标时长（秒） <input v-model.number="seconds" type="number" min=".001" step=".001" :disabled="!enabled"></label>
      <button class="secondary" :disabled="!target || !enabled || busy" @click="generate">生成时长建议</button>
      <button v-if="busy || result" class="text-button" @click="clear">{{ busy ? '取消计算' : '收起建议' }}</button>
      <span v-if="busy" role="status">正在计算…</span>
    </div>
    <div v-if="suggestion" class="budget-result">
      <strong>建议成片 {{ topicTime(suggestion.cut.durationMs) }}</strong>
      <span>保留 {{ suggestion.keptEvidenceCount }} 句依据 · {{ suggestion.keptAnchorCount }} 句锚点 · {{ suggestion.cut.parts.length }} 段</span>
      <span v-if="suggestion.matchedTerms.length">命中目标词 {{ suggestion.matchedTerms.slice(0, 4).join('、') }}</span>
      <span v-if="suggestion.approximate">已从部分候选组合中择优</span>
      <details><summary>查看建议剪掉的 {{ newlyRemoved.length }} 句</summary>
        <ol><li v-for="{ utterance } in newlyRemoved" :key="utterance.utterance_id"><time>{{ topicTime(utterance.start_ms) }}</time> {{ utterance.text }}</li></ol>
      </details>
      <button class="secondary" :disabled="!enabled" @click="adopt">采用建议</button>
    </div>
    <p v-else-if="result?.status === 'already_fits'" role="status">当前草稿已符合目标时长。</p>
    <p v-else-if="result?.status === 'infeasible'" role="status">目标时长无法容纳必留内容。满足当前剪辑约束至少需要 {{ topicTime(result.minimumMs) }}。</p>
    <p v-else-if="result?.status === 'limit' || result?.status === 'invalid'" role="status">{{ result.message }}</p>
    <p v-else-if="error" role="status">{{ error }}</p>
  </section>
</template>

<style scoped>
.cut-budget { padding: .55rem 1rem; border-bottom: 1px solid #d3ded1; background: #f5f8f3; overflow-y: auto; max-height: 33dvh; flex-shrink: 0; }
.budget-controls, .budget-result { display: flex; align-items: center; gap: .4rem .75rem; flex-wrap: wrap; }
.budget-controls label { display: flex; gap: .45rem; align-items: center; font-size: .9rem; }
.budget-controls input { width: 5.5rem; min-height: 30px; }
.budget-controls button, .budget-result button { min-height: 30px; }
.budget-result, .cut-budget p { margin: .45rem 0 0; font-size: .85rem; }
.budget-result details { max-width: 100%; }
.budget-result details[open] { flex-basis: 100%; }
.budget-result ol { margin: .35rem 0; padding-left: 1.4rem; }
.budget-result li { margin: .2rem 0; }
.budget-result time { font-variant-numeric: tabular-nums; }
.budget-result summary { cursor: pointer; }
</style>
