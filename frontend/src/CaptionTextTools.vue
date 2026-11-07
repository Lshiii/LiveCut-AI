<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import { captionTextMatches, previewCaptionTextReplacement, type CaptionTextPreview } from './captionTextTools'
import { captionTime, type CaptionCue } from './captions'

const props = defineProps<{
  cues: CaptionCue[]; query: string; ignoreCase: boolean; scopeIds: string[]
  pending: boolean; active: boolean; contextKey: string; draftKey: string; undoAvailable: boolean
}>()
const emit = defineEmits<{
  'update:query': [value: string]; 'update:ignoreCase': [value: boolean]
  apply: [value: { preview: CaptionTextPreview; contextKey: string; draftKey: string }]
  undo: []; locate: [id: string]; expanded: [value: boolean]
}>()

const panelId = useId(), replaceOpen = ref(false), replacement = ref(''), scope = ref<'all' | 'filtered'>('all')
const message = ref(''), previewPage = ref(0), previewPageSize = 5
interface PreparedReplacement { preview: CaptionTextPreview; contextKey: string; draftKey: string; signature: string }
const prepared = ref<PreparedReplacement | null>(null)
const replaceBody = ref<HTMLDivElement | null>(null), previewResults = ref<HTMLDivElement | null>(null)
const matches = computed(() => {
  let cues = 0, occurrences = 0
  for (const cue of props.cues) {
    const count = captionTextMatches(cue.text, props.query, props.ignoreCase)
    if (count) { cues++; occurrences += count }
  }
  return { cues, occurrences }
})
const selectedIds = computed(() => scope.value === 'all' ? props.cues.map(cue => cue.id) : [...props.scopeIds])
const signature = computed(() => JSON.stringify({ query: props.query, replacement: replacement.value, ignoreCase: props.ignoreCase,
  scope: scope.value, ids: selectedIds.value, filteredIds: props.scopeIds }))
const preview = computed(() => prepared.value?.preview ?? null)
const previewPages = computed(() => Math.max(1, Math.ceil((preview.value?.changes.length ?? 0) / previewPageSize)))
const visibleChanges = computed(() => preview.value?.changes.slice(previewPage.value * previewPageSize, (previewPage.value + 1) * previewPageSize) ?? [])
const sourceById = computed(() => new Map(props.cues.map(cue => [cue.id, cue])))
const canApply = computed(() => props.active && !props.pending && !!prepared.value && !preview.value?.error && !!preview.value?.changes.length)

function invalidate(reason = '字幕、筛选范围或替换条件已变化，请重新预览。') {
  if (prepared.value) message.value = reason
  prepared.value = null; previewPage.value = 0
}
async function showPreview() {
  if (!props.active || props.pending) return
  message.value = ''; previewPage.value = 0
  prepared.value = { preview: previewCaptionTextReplacement(props.cues, props.query, replacement.value, props.ignoreCase, selectedIds.value),
    contextKey: props.contextKey, draftKey: props.draftKey, signature: signature.value }
  const captured = prepared.value
  await nextTick()
  if (prepared.value !== captured || !props.active || props.pending || !replaceBody.value || !previewResults.value) return
  replaceBody.value.scrollTop += previewResults.value.getBoundingClientRect().top - replaceBody.value.getBoundingClientRect().top - 8
}
function applyPreview() {
  const captured = prepared.value
  if (!canApply.value || !captured) return
  if (captured.contextKey !== props.contextKey || captured.draftKey !== props.draftKey || captured.signature !== signature.value) {
    invalidate(); return
  }
  const latest = previewCaptionTextReplacement(props.cues, props.query, replacement.value, props.ignoreCase, selectedIds.value)
  if (JSON.stringify(latest) !== JSON.stringify(captured.preview)) { invalidate(); return }
  prepared.value = null; previewPage.value = 0; message.value = ''; replaceOpen.value = false
  emit('apply', { preview: captured.preview, contextKey: captured.contextKey, draftKey: captured.draftKey })
}
function toggleReplace() {
  replaceOpen.value = !replaceOpen.value
  if (!replaceOpen.value) { invalidate('替换预览已关闭，需要应用时请重新预览。'); message.value = '' }
}
function locate(id: string) {
  if (!props.active || props.pending || !sourceById.value.has(id)) return
  invalidate('已返回字幕列表，应用替换前请重新预览。'); replaceOpen.value = false; emit('locate', id)
}
function undo() {
  if (!props.active || props.pending || !props.undoAvailable) return
  invalidate(); replaceOpen.value = false; emit('undo')
}
watch([signature, () => props.draftKey], () => invalidate(), { flush: 'sync' })
watch(() => props.contextKey, () => { invalidate(); replaceOpen.value = false }, { flush: 'sync' })
watch(replaceOpen, value => emit('expanded', value), { flush: 'sync', immediate: true })
watch(() => props.active, active => {
  if (!active) { invalidate('离开字幕校对后预览已失效，请重新预览。'); replaceOpen.value = false }
}, { flush: 'sync' })
watch(previewPages, count => { previewPage.value = Math.min(previewPage.value, count - 1) })
</script>

<template>
  <section class="caption-text-tools" :class="{ expanded: replaceOpen }" aria-label="字幕查找与批量替换">
    <div class="caption-search-row">
      <label class="caption-search-field"><span class="caption-tools-visually-hidden">查找字幕文字</span><input :value="query" type="search" maxlength="200" placeholder="查找错词或字幕文字" :disabled="pending || !active" @input="emit('update:query', ($event.target as HTMLInputElement).value)"></label>
      <button v-if="query" type="button" class="caption-tools-link" :disabled="pending || !active" @click="emit('update:query', '')">清空查找</button>
      <label class="caption-case"><input :checked="ignoreCase" type="checkbox" :disabled="pending || !active" @change="emit('update:ignoreCase', ($event.target as HTMLInputElement).checked)">忽略大小写</label>
      <button type="button" class="caption-tools-link" :aria-expanded="replaceOpen" :aria-controls="`${panelId}-replace`" :disabled="pending || !active" @click="toggleReplace">{{ replaceOpen ? '收起替换' : '批量替换' }}</button>
      <button v-if="undoAvailable" type="button" class="caption-tools-link" :disabled="pending || !active" @click="undo">撤销最近替换</button>
      <span v-if="query" class="caption-search-count" role="status">全文匹配 {{ matches.cues }} 条 · {{ matches.occurrences }} 处</span>
    </div>
    <div v-show="replaceOpen" :id="`${panelId}-replace`" ref="replaceBody" class="caption-replace-body">
      <div class="caption-replace-fields"><label>替换为<input v-model="replacement" type="text" maxlength="2000" placeholder="可留空，删除匹配文字" :disabled="pending || !active" aria-label="字幕替换文字"></label><label>替换范围<select v-model="scope" :disabled="pending || !active" aria-label="字幕替换范围"><option value="all">全部字幕</option><option value="filtered">当前筛选结果</option></select></label></div>
      <div class="caption-replace-actions"><span>范围 {{ selectedIds.length }} 条字幕，包含所有分页。</span><button type="button" class="caption-tools-button" :disabled="pending || !active" @click="showPreview">预览替换</button></div>
      <p class="caption-tools-hint">只替换字幕文字；原始转写、时间和核对标记保持原样。先预览，再应用到本页，仍需保存字幕。</p>
      <p class="caption-tools-hint">可撤销最近一次替换；保存、恢复草稿或关闭页面后，撤销记录清除。</p>
      <p v-if="message" class="caption-tools-message" role="status">{{ message }}</p>
      <div v-if="preview" ref="previewResults">
        <p v-if="preview.error" class="caption-tools-error" role="alert">{{ preview.error }}</p>
        <template v-else-if="preview.changes.length">
          <p class="caption-preview-summary" role="status">将修改 {{ preview.changes.length }} 条字幕 · {{ preview.occurrences }} 处文字</p>
          <div class="caption-replacement-preview" aria-label="替换内容预览"><article v-for="change in visibleChanges" :key="change.id"><div class="caption-preview-heading"><button type="button" class="caption-tools-link" :disabled="pending || !active" @click="locate(change.id)">{{ captionTime(sourceById.get(change.id)?.start_ms ?? 0) }} · 定位字幕</button><span>{{ change.occurrences }} 处</span></div><div class="caption-text-before"><span>替换前</span><p>{{ change.before }}</p></div><div class="caption-text-after"><span>替换后</span><p>{{ change.after || '（空文字）' }}</p></div></article></div>
          <div v-if="previewPages > 1" class="caption-preview-pagination" aria-label="替换预览分页"><button type="button" class="caption-tools-link" :disabled="previewPage === 0" @click="previewPage--">上一页预览</button><span>{{ previewPage + 1 }} / {{ previewPages }} · 每页 {{ previewPageSize }} 条</span><button type="button" class="caption-tools-link" :disabled="previewPage + 1 >= previewPages" @click="previewPage++">下一页预览</button></div>
        </template>
        <p v-else class="caption-tools-message" role="status">没有需要修改的字幕。请检查查找文字、大小写和替换范围。</p>
      </div>
    </div>
    <div v-if="replaceOpen && preview && !preview.error && preview.changes.length" class="caption-replace-apply"><span>核对后再应用，未自动保存。</span><button type="button" class="caption-tools-button primary" :disabled="!canApply" @click="applyPreview">应用到本页字幕</button></div>
  </section>
</template>

<style scoped>
.caption-text-tools { display: flex; flex: 0 1 auto; flex-direction: column; min-width: 0; min-height: 42px; max-height: 48%; margin: 0 0 10px; border: 1px solid #e2e8f0; border-radius: 7px; background: #f8fafc; color: #475569; font: 13px/1.65 system-ui, sans-serif; overflow: hidden; }
.caption-text-tools.expanded { flex: 1; min-height: 0; max-height: none; }
.caption-search-row { display: flex; flex: none; flex-wrap: wrap; align-items: center; gap: 5px 10px; min-width: 0; padding: 6px 9px; }
.caption-search-field { display: flex; flex: 1 1 170px; min-width: 100px; }
.caption-search-field input, .caption-replace-fields input, .caption-replace-fields select { box-sizing: border-box; width: 100%; min-width: 0; max-width: 100%; min-height: 32px; padding: 5px 8px; border: 1px solid #cbd5e1; border-radius: 5px; background: #fff; color: #1f2937; font: inherit; }
.caption-case { display: inline-flex; align-items: center; flex: none; gap: 4px; white-space: nowrap; }
.caption-case input { margin: 0; accent-color: #e47650; }
.caption-search-count { flex: 1 0 auto; color: #64748b; font-size: 12px; }
.caption-tools-link { min-height: 30px; max-width: 100%; padding: 3px 0; border: 0; background: transparent; color: #9d5b3f; text-align: left; font: inherit; font-size: 12px; line-height: 1.6; cursor: pointer; overflow-wrap: anywhere; }
.caption-replace-body { flex: 1; min-height: 0; min-width: 0; padding: 9px; border-top: 1px solid #e2e8f0; overflow-y: auto; overscroll-behavior: contain; scrollbar-width: thin; }
.caption-replace-fields { display: grid; grid-template-columns: minmax(0, 1fr) minmax(140px, .65fr); gap: 9px; min-width: 0; }
.caption-replace-fields > label { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.caption-replace-actions, .caption-preview-heading, .caption-preview-pagination, .caption-replace-apply { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 5px 10px; min-width: 0; }
.caption-replace-actions { margin-top: 8px; }
.caption-replace-actions > span, .caption-replace-apply > span { color: #64748b; font-size: 12px; overflow-wrap: anywhere; }
.caption-tools-button { min-height: 34px; max-width: 100%; padding: 6px 10px; border: 1px solid #cbd5e1; border-radius: 5px; background: #fff; color: #334155; font: inherit; cursor: pointer; white-space: normal; }
.caption-tools-button.primary { border-color: #e47650; background: #e47650; color: #fff; }
.caption-tools-hint { margin: 6px 0 0; color: #64748b; font-size: 12px; overflow-wrap: anywhere; }
.caption-tools-message, .caption-tools-error { margin: 8px 0 0; padding: 7px 9px; border-radius: 5px; background: #fff7ed; color: #9a6337; overflow-wrap: anywhere; }
.caption-preview-summary { margin: 10px 0 6px; font-weight: 600; color: #334155; }
.caption-replacement-preview article { margin-top: 7px; padding: 8px 10px; border: 1px solid #e2e8f0; border-radius: 6px; background: #fff; }
.caption-preview-heading > span { color: #64748b; font-size: 12px; }
.caption-text-before, .caption-text-after { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: start; gap: 8px; margin-top: 5px; min-width: 0; }
.caption-text-before > span, .caption-text-after > span { color: #64748b; font-size: 11px; }
.caption-text-before p, .caption-text-after p { margin: 0; min-width: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.caption-text-before p { color: #64748b; }
.caption-text-after p { color: #1f2937; }
.caption-preview-pagination { margin-top: 7px; font-size: 12px; color: #64748b; }
.caption-replace-apply { flex: none; margin: 0; padding: 8px 9px; border-top: 1px solid #e2e8f0; background: #f8fafc; }
.caption-text-tools button:disabled { opacity: .5; cursor: not-allowed; }
.caption-text-tools button:focus-visible, .caption-text-tools input:focus-visible, .caption-text-tools select:focus-visible { outline: 2px solid #e89778; outline-offset: 2px; }
.caption-tools-visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
@media (max-width: 760px) { .caption-text-tools { flex: none; max-height: 440px; }.caption-search-field { flex-basis: 100%; }.caption-replace-fields { grid-template-columns: minmax(0, 1fr); }.caption-search-count { flex-basis: 100%; }.caption-replace-apply > button { flex: 1; }.caption-replace-body { max-height: 320px; } }
@media (max-height: 600px) and (min-width: 761px) { .caption-text-tools { max-height: 50%; }.caption-replace-body { padding-block: 7px; } }
</style>
