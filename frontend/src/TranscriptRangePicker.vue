<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import type { IntentOutput, IntentUtterance } from './intentClips'
import { topicTime } from './topics'
import { filterTranscriptIndexes, transcriptPage, transcriptPageForId, validateTranscriptRange } from './transcriptRange'

const props = withDefaults(defineProps<{
  output: IntentOutput
  partIndex: number
  utterances: IntentUtterance[]
  durationMs: number
  active?: boolean
  stale?: boolean
  initialTimeMs?: number
  initialField?: 'start' | 'end'
}>(), { active: true, stale: false, initialField: 'start' })
const emit = defineEmits<{ apply: [selection: { startId: string; endId: string; preview: boolean }]; close: [] }>()
const initial = props.output.parts[props.partIndex]
const initialStart = initial?.start_utterance_id ?? '', initialEnd = initial?.end_utterance_id ?? ''
const startId = ref(initialStart), endId = ref(initialEnd), activeField = ref<'start' | 'end'>(props.initialField)
const query = ref(''), page = ref(1), dialog = ref<HTMLDialogElement | null>(null), search = ref<HTMLInputElement | null>(null)
const list = ref<HTMLElement | null>(null), pickerId = useId()
const dirty = computed(() => startId.value !== initialStart || endId.value !== initialEnd)
const pending = false
const sources = computed(() => new Map(props.utterances.map((value, index) => [value.utterance_id, { value, index }])))
const start = computed(() => sources.value.get(startId.value)), end = computed(() => sources.value.get(endId.value))
const excluded = computed(() => new Map(props.output.excluded.map(value => [value.utterance_id, value.reason])))
const indexes = computed(() => filterTranscriptIndexes(props.utterances, query.value))
const shown = computed(() => transcriptPage(indexes.value, page.value, 20))
const selection = computed(() => validateTranscriptRange(props.output, props.partIndex, startId.value, endId.value, props.utterances, props.durationMs))
const canApply = computed(() => props.active && !props.stale && !!selection.value.part && !selection.value.error)
let stopped = false
defineExpose({ dirty, pending })

function rowId(index: number) { return `${pickerId}-source-${index}` }
function isBoundary(id: string) { return id === startId.value || id === endId.value }
function inRange(index: number) { return start.value !== undefined && end.value !== undefined && index >= start.value.index && index <= end.value.index }
function choose(index: number) {
  const value = props.utterances[index]
  if (!props.active || props.stale || !value || excluded.value.has(value.utterance_id)) return
  if (activeField.value === 'start') startId.value = value.utterance_id
  else endId.value = value.utterance_id
}
function cancel() {
  if (stopped) return
  startId.value = initialStart; endId.value = initialEnd
  dialog.value?.close()
  emit('close')
}
function apply(preview: boolean) {
  if (canApply.value) emit('apply', { startId: startId.value, endId: endId.value, preview })
}
function showDialog() {
  if (stopped || !props.active || !dialog.value) return
  if (!dialog.value.open) dialog.value.showModal()
  void nextTick(() => { if (!stopped && props.active && dialog.value?.open) search.value?.focus() })
}
function scrollTop() { if (list.value) list.value.scrollTop = 0 }
async function locate(field: 'start' | 'end') {
  activeField.value = field
  const target = field === 'start' ? start.value : end.value
  if (!target) return
  query.value = ''
  page.value = transcriptPageForId(props.utterances, indexes.value, target.value.utterance_id, 20)
  await nextTick()
  if (!stopped && props.active && dialog.value?.open) document.getElementById(rowId(target.index))?.scrollIntoView({ block: 'nearest' })
}
function changePage(next: number) { page.value = transcriptPage(indexes.value, next, 20).page; void nextTick(scrollTop) }
watch(query, () => { page.value = 1; void nextTick(scrollTop) }, { flush: 'sync' })
watch(() => props.active, value => {
  if (!value) dialog.value?.close()
  else void nextTick(showDialog)
}, { flush: 'post' })
onMounted(() => {
  const initialTime = props.initialTimeMs
  if (initialTime !== undefined && Number.isFinite(initialTime) && initialTime >= 0) {
    const index = props.utterances.findIndex(value => value.end_ms >= initialTime)
    const target = props.utterances[index >= 0 ? index : props.utterances.length - 1]
    if (target) page.value = transcriptPageForId(props.utterances, indexes.value, target.utterance_id, 20)
  } else {
    page.value = transcriptPageForId(props.utterances, indexes.value, activeField.value === 'start' ? startId.value : endId.value, 20)
  }
  showDialog()
})
onBeforeUnmount(() => { stopped = true; dialog.value?.close() })
</script>

<template>
  <dialog ref="dialog" class="transcript-range-picker" aria-label="选择区间原句" @cancel.prevent="cancel" @keydown.esc.stop>
    <header class="range-picker-heading"><div><h2>选择区间原句</h2><p :title="output.title">{{ output.title }} · 第 {{ partIndex + 1 }} 段</p></div><button class="range-icon-button" aria-label="取消原句选择" @click="cancel">×</button></header>
    <div class="range-picker-boundaries" aria-label="当前起止原句">
      <button class="range-boundary-summary" :class="{ active: activeField === 'start' }" :aria-pressed="activeField === 'start'" @click="activeField = 'start'"><span><strong>起句</strong><small>{{ start ? topicTime(start.value.start_ms) : '尚未选择' }}</small></span><p>{{ start?.value.text ?? '在下方选择区间的第一句' }}</p></button>
      <button class="range-boundary-summary" :class="{ active: activeField === 'end' }" :aria-pressed="activeField === 'end'" @click="activeField = 'end'"><span><strong>止句</strong><small>{{ end ? topicTime(end.value.end_ms) : '尚未选择' }}</small></span><p>{{ end?.value.text ?? '在下方选择区间的最后一句' }}</p></button>
    </div>
    <p class="range-boundary-help">按原句边界：从起句开头，到止句末尾。</p>
    <div class="range-picker-search"><label><span class="range-search-icon" aria-hidden="true">⌕</span><input ref="search" v-model="query" type="search" autocomplete="off" placeholder="搜索原句或时间，例如 02:30" aria-label="搜索原句或时间"></label><div class="range-picker-list-heading"><p>正在选择<strong>{{ activeField === 'start' ? '起句' : '止句' }}</strong> · {{ shown.total }} 条{{ query.trim() ? '匹配' : '原句' }}</p><div><button class="range-link" :disabled="!start" @click="locate('start')">定位起句</button><button class="range-link" :disabled="!end" @click="locate('end')">定位止句</button></div></div></div>
    <div ref="list" class="range-picker-list" aria-label="可选原句列表">
      <p v-if="!shown.indexes.length" class="range-picker-empty">{{ utterances.length ? '没有找到匹配原句。可换个关键词、搜索时间，或清空搜索。' : '当前没有可用转写原句，请关闭后重新读取方案。' }}</p>
      <article v-for="index in shown.indexes" :id="rowId(index)" :key="utterances[index]!.utterance_id" class="range-candidate" :class="{ boundary: isBoundary(utterances[index]!.utterance_id), retained: inRange(index), excluded: excluded.has(utterances[index]!.utterance_id) }">
        <label class="range-candidate-choice"><input type="radio" :name="`${pickerId}-${activeField}`" :checked="utterances[index]!.utterance_id === (activeField === 'start' ? startId : endId)" :disabled="stale || excluded.has(utterances[index]!.utterance_id)" :aria-label="`${activeField === 'start' ? '选择起句' : '选择止句'} ${topicTime(utterances[index]!.start_ms)} ${utterances[index]!.text}`" @change="choose(index)"><span class="range-candidate-content"><span class="range-candidate-meta"><span>{{ topicTime(utterances[index]!.start_ms) }} → {{ topicTime(utterances[index]!.end_ms) }}</span><strong v-if="utterances[index]!.utterance_id === startId">起句</strong><strong v-if="utterances[index]!.utterance_id === endId">止句</strong><em v-if="excluded.has(utterances[index]!.utterance_id)">已排除</em></span><span class="range-candidate-text">{{ utterances[index]!.text }}</span></span></label>
        <p v-if="excluded.has(utterances[index]!.utterance_id)" class="range-excluded-reason">{{ excluded.get(utterances[index]!.utterance_id) }}</p>
        <details v-if="index > 0 || index + 1 < utterances.length" class="range-candidate-context"><summary>查看前后原句</summary><div v-if="index > 0"><strong>前一句 · {{ topicTime(utterances[index - 1]!.start_ms) }}</strong><p>{{ utterances[index - 1]!.text }}</p></div><div v-if="index + 1 < utterances.length"><strong>后一句 · {{ topicTime(utterances[index + 1]!.start_ms) }}</strong><p>{{ utterances[index + 1]!.text }}</p></div></details>
      </article>
    </div>
    <nav class="range-picker-pagination" aria-label="原句分页"><button class="range-button" :disabled="shown.page <= 1" @click="changePage(shown.page - 1)">上一页</button><span>第 {{ shown.page }} / {{ shown.totalPages }} 页 · 每页 20 条</span><button class="range-button" :disabled="shown.page >= shown.totalPages" @click="changePage(shown.page + 1)">下一页</button></nav>
    <footer class="range-picker-footer"><p v-if="stale" class="range-selection-error" role="alert">原方案或片段已变化，本次选择只能查看。请取消后重新打开，再选择原句。</p><p v-else-if="selection.error" class="range-selection-error" role="status">{{ selection.error }}</p><p v-else-if="selection.part" class="range-selection-summary">所选范围 {{ topicTime(selection.part.start_ms) }} → {{ topicTime(selection.part.end_ms) }}{{ dirty ? ' · 尚未应用到方案' : '' }}</p><div class="range-picker-footer-actions"><button class="range-button" @click="cancel">取消</button><button class="range-button" :disabled="!canApply" @click="apply(false)">应用区间</button><button class="range-button primary" :disabled="!canApply" @click="apply(true)">应用并预览</button></div></footer>
  </dialog>
</template>

<style scoped src="./transcript-range-picker.css"></style>
