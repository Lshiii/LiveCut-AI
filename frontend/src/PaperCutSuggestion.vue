<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { api, ApiError, post } from './api'
import type { PaperCutSource } from './paperCut'
import type { IntentPart } from './intentClips'
import { parseSuggestion, previewSuggestion, restoredSuggestion, suggestionInput,
  type PaperCutContext, type SuggestionReceipt, type SuggestionView } from './paperCutSuggestion'
import { topicTime } from './topics'

const props = defineProps<{ context: PaperCutContext; source: PaperCutSource; removed: ReadonlySet<string>
  enabled: boolean; durationMs: number; canPlay: boolean; playing: boolean }>()
const emit = defineEmits<{ adopt: [ids: ReadonlySet<string>]; audition: [parts: IntentPart[]]; stop: [] }>()
const instruction = ref(''), feedback = ref(''), loading = ref(false), missing = ref(false)
const seconds = ref<number | string>('')
const receipt = shallowRef<SuggestionReceipt | null>(null), view = shallowRef<SuggestionView | null>(null)
let epoch = 0, timer: ReturnType<typeof setTimeout> | null = null
const storageKey = () => `livecut.paper-cut.suggestion.v1:${props.context.requestId}:${props.context.outputId}`
const live = computed(() => view.value?.status === 'QUEUED' || view.value?.status === 'RUNNING')
const proposal = computed(() => {
  if (!view.value?.result) return null
  return previewSuggestion(props.source, props.removed, view.value.result)
})
const removedRows = computed(() => props.source.rows.filter(row => proposal.value?.ids.has(row.utterance.utterance_id)))

function stopReads() {
  epoch++
  if (timer !== null) clearTimeout(timer)
  timer = null; loading.value = false
}
function clear() {
  stopReads()
  if (props.playing) emit('stop')
  view.value = null; receipt.value = null; feedback.value = ''; missing.value = false
}
function forget() {
  if (receipt.value) {
    try {
      const saved = JSON.parse(sessionStorage.getItem(storageKey()) ?? 'null')
      if (saved?.key === receipt.value.key) sessionStorage.removeItem(storageKey())
    } catch { /* the open editor still owns its in-memory receipt */ }
  }
  clear()
}
function message(error: unknown) {
  if (!(error instanceof ApiError)) return error instanceof Error ? error.message : '获取建议失败。'
  if (error.status === 409 || error.status === 422) return '当前草稿与已保存的方案不一致，或建议超出剪辑约束。请核对并保存方案后再试。'
  if (error.status === 400) return '请核对指令和保留区间，再生成建议。'
  if (error.status === 404) return '未找到建议或接口，请确认工作台已更新。'
  return `暂时无法获得建议进度（${error.code}）。可以核对原请求。`
}
function accept(raw: unknown, saved: SuggestionReceipt) {
  const next = parseSuggestion(raw, saved, view.value?.id)
  if (next.result) {
    try { previewSuggestion(props.source, props.removed, next.result) }
    catch (error) {
      view.value = { ...next, status: 'FAILED', error_code: 'INVALID_PROPOSAL', result: null }
      feedback.value = message(error)
      return
    }
  }
  view.value = next; feedback.value = ''; missing.value = false
  if (next.status === 'QUEUED' || next.status === 'RUNNING') timer = setTimeout(() => void request('check'), 1800)
}
async function request(action: 'create' | 'check' | 'cancel') {
  if (!props.enabled || loading.value) return
  let saved = receipt.value
  if (!saved) {
    if (action !== 'create') return
    try {
      const targetMs = seconds.value === '' ? undefined : Math.round(Number(seconds.value) * 1000)
      saved = { key: crypto.randomUUID(), requestId: props.context.requestId, input: suggestionInput(props.context, props.source, props.removed, instruction.value, targetMs) }
    } catch (error) { feedback.value = message(error); return }
    receipt.value = saved
    try { sessionStorage.setItem(storageKey(), JSON.stringify(saved)) } catch { /* optional across reloads */ }
  }
  stopReads()
  const active = epoch
  loading.value = true; feedback.value = ''; missing.value = false
  const base = `/api/clip-requests/${encodeURIComponent(saved.requestId)}`
  const item = view.value ? `/api/paper-cut-suggestions/${encodeURIComponent(view.value.id)}` : ''
  try {
    let raw: unknown
    if (action === 'create') raw = await post(`${base}/paper-cut-suggestions`, saved.input, { 'Idempotency-Key': saved.key })
    else if (action === 'cancel' && item) raw = await post(`${item}/cancel`)
    else raw = await api(item || `${base}/paper-cut-submissions/${encodeURIComponent(saved.key)}`)
    if (epoch === active) accept(raw, saved)
  } catch (error) {
    if (epoch !== active) return
    if (action === 'check' && !item && error instanceof ApiError && error.status === 404 && error.code === 'PAPER_CUT_SUBMISSION_NOT_FOUND') {
      missing.value = true; feedback.value = '服务端尚无这次提交，可按原请求重试。'
    } else if (action === 'create' && error instanceof ApiError && [400, 404, 409, 422].includes(error.status ?? 0)) {
      forget(); feedback.value = message(error)
    } else {
      feedback.value = message(error)
    }
  } finally { if (epoch === active) loading.value = false }
}
function restore() {
  const previous = receipt.value
  clear()
  if (!props.enabled) return
  try {
    let stored: unknown = previous
    try { stored = JSON.parse(sessionStorage.getItem(storageKey()) ?? 'null') ?? previous } catch { /* memory-only session */ }
    const saved = restoredSuggestion(stored, props.context, props.source, props.removed)
    if (saved) {
      receipt.value = saved; instruction.value = saved.input.instruction; seconds.value = saved.input.target_ms ? saved.input.target_ms / 1000 : ''
      void request('check')
    }
  } catch { /* optional across reloads */ }
}
function adopt() {
  if (!props.enabled || !proposal.value?.ids.size) return
  const ids = proposal.value.ids
  forget()
  emit('adopt', ids)
}
watch([() => props.removed, () => props.source, () => props.context.requestId, () => props.context.outputId,
  () => props.context.revision, () => props.context.planHash, () => props.enabled], () => {
  if (props.enabled) restore()
  else stopReads()
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(stopReads)
</script>

<template>
  <details class="cut-instruction">
    <summary>按要求修改当前草稿</summary>
    <form @submit.prevent="!receipt && request('create')">
      <label>改剪要求 <input v-model="instruction" maxlength="1000" placeholder="保留定义和结论，删掉例子，压到一分钟"
        :disabled="!enabled || !!receipt" /></label>
      <label class="seconds">时长上限（秒） <input v-model.number="seconds" type="number" min="0.001" step="0.001" placeholder="可选" :disabled="!enabled || !!receipt" /></label>
      <button v-if="!receipt" class="secondary" :disabled="!enabled || !instruction.trim() || loading">生成 AI 建议</button>
      <small v-if="!receipt">每次建议最多 2 次文本调用</small>
    </form>
    <div v-if="receipt && !proposal" class="cut-actions" aria-live="polite">
      <span v-if="view?.status === 'QUEUED'">等待支持草稿改剪的执行器领取…</span>
      <span v-else-if="view?.status === 'RUNNING'">正在生成删句建议…</span>
      <span v-else-if="view?.status === 'FAILED'">本次建议失败（{{ view.error_code }}）。</span>
      <span v-else-if="view?.status === 'CANCELLED'">已取消本次建议。</span>
      <span v-else-if="loading">正在核对提交…</span>
      <button v-if="live" type="button" class="text-button" :disabled="loading || !enabled" @click="request('cancel')">取消生成</button>
      <button v-if="feedback && !missing" type="button" class="text-button" :disabled="loading || !enabled" @click="request('check')">核对原请求</button>
      <button v-if="missing" type="button" class="secondary" :disabled="loading || !enabled" @click="request('create')">重试原请求</button>
    </div>
    <section v-if="proposal && view?.result" aria-label="AI 删句建议">
      <strong>当前 {{ topicTime(durationMs) }} → 建议 {{ topicTime(proposal.cut.durationMs) }}</strong>
      <span v-if="view.result.target_ms"> · 目标 {{ topicTime(view.result.target_ms) }}</span>
      <p>{{ view.result.reason }}</p>
      <details v-if="removedRows.length" open><summary>建议剪掉 {{ removedRows.length }} 句</summary>
        <ul><li v-for="{ utterance } in removedRows" :key="utterance.utterance_id">{{ topicTime(utterance.start_ms) }}　{{ utterance.text }}</li></ul>
      </details>
      <p v-else>保留当前草稿。</p>
      <div v-if="removedRows.length" class="cut-actions">
        <button type="button" class="secondary" :disabled="!canPlay || !enabled" @click="emit('audition', proposal.cut.parts)">{{ playing ? '停止试听建议' : '试听建议' }}</button>
        <button type="button" class="secondary" :disabled="!enabled" @click="adopt">采用本次删句</button>
      </div>
    </section>
    <p v-if="feedback" role="status">{{ feedback }}</p>
    <button v-if="view && !live || missing" type="button" class="text-button" :disabled="loading || !enabled" @click="forget">修改要求</button>
  </details>
</template>

<style scoped>
.cut-instruction { padding: 8px 16px; border-bottom: 1px solid #dce2dc; max-height: 36dvh; overflow: auto; flex-shrink: 0; }
.cut-instruction summary { cursor: pointer; font-weight: 600; }
.cut-instruction form,.cut-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 8px 0; }
.cut-instruction label { flex: 1 1 260px; display: flex; align-items: center; gap: 8px; }
.cut-instruction input { flex: 1; min-width: 0; padding: 6px; }
.cut-instruction .seconds { flex: 0 0 auto; }
.cut-instruction .seconds input { width: 4.8em; }
.cut-instruction ul { padding-left: 1.5em; }
.cut-instruction small { color: #526258; }
</style>
