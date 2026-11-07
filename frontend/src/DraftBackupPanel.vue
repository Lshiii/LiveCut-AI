<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { BrowserDraftStore, DRAFT_PREFIX, MAX_DRAFT_DOWNLOAD_BYTES, copyDraftData, draftFile, importDraftFile, type DraftAssessment, type DraftEntry, type DraftKind } from './browserDrafts'

const props = defineProps<{ kind: DraftKind; documentId: string; value: unknown | null; blocked: boolean; assess: (data: unknown) => DraftAssessment }>()
const emit = defineEmits<{ restore: [data: unknown] }>()
const store = new BrowserDraftStore(() => localStorage), writerId = crypto.randomUUID(), panelId = useId()
const records = ref<DraftEntry[]>([]), current = ref<DraftEntry | null>(null)
const status = ref<'idle' | 'waiting' | 'saved' | 'failed'>('idle'), writeError = ref(''), readError = ref('')
const removeTarget = ref<DraftEntry | null>(null), open = ref(false)
const fileInput = ref<HTMLInputElement | null>(null), imported = ref<DraftEntry | null>(null), importError = ref('')
interface Capture { kind: DraftKind; documentId: string; data: unknown; signature: string }
let capture: Capture | null = null, timer: number | undefined, stopped = false, identity = '', fileReadVersion = 0
const history = computed(() => records.value.filter(entry => entry.entry_id !== current.value?.entry_id))
const descriptors = computed(() => history.value.map(entry => ({ entry, assessment: assessment(entry.data) })))
const importedAssessment = computed(() => imported.value ? assessment(imported.value.data) : null)
const statusText = computed(() => status.value === 'waiting' ? '正在备份到本机…' : status.value === 'saved' && current.value
  ? `本机已备份 ${date(current.value.updated_at)}` : status.value === 'failed' ? '本机备份需要处理' : '本机草稿备份')
function date(time: number) { return new Date(time).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }) }
function assessment(value: unknown): DraftAssessment {
  try { return props.assess(value) }
  catch { return { title: '无法识别的草稿', description: '原备份仍然保留。', compatible: false, reason: '这份备份暂时不能恢复，可先下载保留。' } }
}
function refresh() {
  if (!props.documentId) { records.value = []; readError.value = ''; return }
  const result = store.list(props.kind, props.documentId)
  records.value = result.entries; readError.value = result.error
}
function clearCurrent() {
  if (!current.value) { status.value = 'idle'; writeError.value = ''; return }
  const removed = store.remove(current.value)
  if (!removed.removed) { status.value = 'failed'; writeError.value = removed.error; return }
  current.value = null; status.value = 'idle'; writeError.value = ''; refresh()
}
function flush() {
  if (stopped) return
  window.clearTimeout(timer); timer = undefined
  if (!capture) return
  const previous = current.value, result = store.save(capture.kind, capture.documentId, writerId, capture.data)
  if (!result.entry) { status.value = 'failed'; writeError.value = result.error; return }
  current.value = result.entry; status.value = 'saved'; writeError.value = ''
  if (previous) { const removed = store.remove(previous); if (!removed.removed) writeError.value = removed.error }
  refresh()
}
function recordProps() {
  if (stopped) return
  const nextIdentity = `${props.kind}:${props.documentId}`
  if (nextIdentity !== identity) {
    if (timer !== undefined) flush()
    identity = nextIdentity; capture = null; current.value = null; status.value = 'idle'; writeError.value = ''; removeTarget.value = null; open.value = false; imported.value = null; importError.value = ''; fileReadVersion++
    refresh()
  }
  if (!props.documentId || props.value === null) {
    window.clearTimeout(timer); timer = undefined; capture = null; clearCurrent(); return
  }
  try {
    const data = copyDraftData(props.value), signature = JSON.stringify(data)
    if (capture?.kind === props.kind && capture.documentId === props.documentId && capture.signature === signature) return
    capture = { kind: props.kind, documentId: props.documentId, data, signature }
    writeError.value = ''; status.value = 'waiting'; window.clearTimeout(timer)
    timer = window.setTimeout(flush, 250)
  } catch { window.clearTimeout(timer); timer = undefined; capture = null; status.value = 'failed'; writeError.value = '本机备份无法记录当前输入，原备份仍保留。请先复制需保留的文字。' }
}
function restore(entry: DraftEntry) {
  if (props.blocked || props.value !== null) return
  refresh()
  const latest = records.value.find(value => value.entry_id === entry.entry_id && value.writer_id === entry.writer_id)
  if (!latest || !assessment(latest.data).compatible) { readError.value = '这份备份已变化或与当前来源不匹配，请重新核对。'; return }
  emit('restore', copyDraftData(latest.data))
}
async function chooseFile(event: Event) {
  const input = event.currentTarget as HTMLInputElement, file = input.files?.[0], owner = identity
  input.value = ''; importError.value = ''
  if (!file) return
  const read = ++fileReadVersion
  if (file.size > MAX_DRAFT_DOWNLOAD_BYTES) { importError.value = '备份文件超过 16 MiB，未读取或修改当前内容。'; return }
  try {
    const text = await file.text()
    if (stopped || owner !== identity || read !== fileReadVersion) return
    const entry = importDraftFile(text, props.kind, props.documentId)
    if (!entry) { importError.value = '文件格式或所属视频、成片不匹配，当前内容未改变。'; return }
    imported.value = entry; open.value = true
  } catch { if (!stopped && owner === identity && read === fileReadVersion) importError.value = '暂时无法读取这份文件，当前内容未改变。' }
}
function restoreImported() {
  if (!imported.value || props.blocked || props.value !== null || !assessment(imported.value.data).compatible) return
  emit('restore', copyDraftData(imported.value.data))
}
function remove(entry: DraftEntry) {
  const result = store.remove(entry)
  removeTarget.value = null
  if (!result.removed) readError.value = result.error
  else { refresh(); readError.value = '' }
}
function download(entry?: DraftEntry) {
  try {
    const value = entry ?? { schema_version: 1 as const, kind: props.kind, document_id: props.documentId,
      writer_id: writerId, entry_id: crypto.randomUUID(), updated_at: Date.now(), data: copyDraftData(props.value) }
    const file = draftFile(value), url = URL.createObjectURL(new Blob([file.text], { type: 'application/json;charset=utf-8' })), link = document.createElement('a')
    link.href = url; link.download = file.filename; document.body.append(link)
    try { link.click() } finally { link.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 1000) }
  } catch { writeError.value = '暂时无法生成备份文件，请先复制需保留的文字。' }
}
function storageChanged(event: StorageEvent) {
  if (event.key !== null && !event.key.startsWith(DRAFT_PREFIX)) return
  refresh()
  if (current.value && !records.value.some(entry => entry.entry_id === current.value!.entry_id) && !readError.value) {
    current.value = null; status.value = 'failed'; writeError.value = '本机备份已在其他页面移除。当前修改仍在本页，继续编辑会重新备份，也可先下载。'
  }
}
function leaving() { recordProps(); if (capture && (timer !== undefined || status.value !== 'saved')) flush() }
function visibility() { if (document.hidden) leaving() }
function retry() { recordProps(); if (capture) flush(); else if (props.value === null) clearCurrent(); refresh() }
watch([() => props.kind, () => props.documentId, () => props.value], recordProps, { flush: 'post' })
watch(() => history.value.length + Number(!!imported.value), count => { if (!count) { open.value = false; removeTarget.value = null } })
onMounted(() => { recordProps(); refresh(); window.addEventListener('storage', storageChanged); window.addEventListener('beforeunload', leaving); window.addEventListener('pagehide', leaving); document.addEventListener('visibilitychange', visibility) })
onBeforeUnmount(() => { leaving(); stopped = true; window.clearTimeout(timer); window.removeEventListener('storage', storageChanged); window.removeEventListener('beforeunload', leaving); window.removeEventListener('pagehide', leaving); document.removeEventListener('visibilitychange', visibility) })
defineExpose({ flush: leaving })
</script>

<template>
  <section v-if="documentId" class="draft-backup" :class="{ 'draft-backup-idle': value === null && !history.length && !imported && !writeError && !readError && !importError }" aria-label="本机草稿备份">
    <div class="draft-backup-status"><span>{{ statusText }}</span><button v-if="value !== null" type="button" @click="download()">下载当前草稿</button><button v-if="writeError || readError" type="button" @click="retry">重试本机备份</button><button type="button" @click="fileInput?.click()">导入备份文件</button><input ref="fileInput" type="file" accept=".json,application/json" aria-label="选择本机草稿备份文件" class="draft-backup-file" @change="chooseFile"><button v-if="history.length || imported" type="button" :aria-expanded="open" :aria-controls="`${panelId}-history`" @click="open = !open">{{ open ? '收起历史草稿' : `${imported ? '查看备份' : '查看历史草稿'} ${history.length + (imported ? 1 : 0)} 份` }}</button></div>
    <p v-if="writeError || readError" class="draft-backup-error" role="status">{{ writeError || readError }}</p>
    <p v-if="importError" class="draft-backup-error" role="alert">{{ importError }}</p>
    <p v-if="history.length && !open" class="draft-backup-hint">发现本浏览器保存的草稿，可查看后选择恢复。</p>
    <div v-show="open" :id="`${panelId}-history`" class="draft-backup-history">
      <p class="draft-backup-hint">仅保存在当前浏览器，不等于保存到服务端。恢复保留原备份，仍需核对并保存。</p>
      <p v-if="blocked || value !== null" class="draft-backup-hint">请先保存或放弃当前编辑，再恢复历史草稿。</p>
      <article v-if="imported && importedAssessment" class="draft-backup-record draft-backup-imported"><div><strong>本页导入 · {{ importedAssessment.title }}</strong><time>{{ date(imported.updated_at) }}</time></div><p>{{ importedAssessment.description }}</p><p class="draft-backup-hint">文件只在本页读取，未上传或自动保存。</p><p v-if="importedAssessment.reason" class="draft-backup-hint">{{ importedAssessment.reason }}</p><div class="draft-backup-actions"><button type="button" :disabled="blocked || value !== null || !importedAssessment.compatible" @click="restoreImported">恢复导入草稿</button><button type="button" @click="imported = null">移除本页导入</button></div></article>
      <article v-for="item in descriptors" :key="item.entry.entry_id" class="draft-backup-record">
        <div><strong>{{ item.assessment.title }}</strong><time>{{ date(item.entry.updated_at) }}</time></div><p>{{ item.assessment.description }}</p><p v-if="item.assessment.reason" class="draft-backup-hint">{{ item.assessment.reason }}</p>
        <div class="draft-backup-actions"><button type="button" :disabled="blocked || value !== null || !item.assessment.compatible" @click="restore(item.entry)">恢复到编辑器</button><button type="button" @click="download(item.entry)">下载备份</button><button type="button" @click="removeTarget = item.entry">移除备份</button></div>
        <div v-if="removeTarget?.entry_id === item.entry.entry_id" class="draft-backup-remove"><p>移除这份本机备份？服务端方案保持原样，其他页面之后的备份也会保留。</p><button type="button" @click="remove(item.entry)">确认移除此备份</button><button type="button" @click="removeTarget = null">取消移除</button></div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.draft-backup { flex: none; min-width: 0; max-height: min(260px, 35%); overflow-y: auto; overscroll-behavior: contain; border-bottom: 1px solid #e2e8f0; background: #f8fafc; padding: 7px 14px; color: #64748b; font: 12px/1.7 system-ui, sans-serif; }
.draft-backup-idle { padding-block: 1px; background: #fff; }
.draft-backup-idle .draft-backup-status { padding-block: 0; background: #fff; }
.draft-backup-idle .draft-backup-status > span { font-size: 11px; }
.draft-backup-idle button { min-height: 28px; color: #64748b; }
.draft-backup p { margin: 4px 0; overflow-wrap: anywhere; }
.draft-backup-status, .draft-backup-actions, .draft-backup-record > div:first-child { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 12px; min-width: 0; }
.draft-backup-status > span { margin-right: auto; }
.draft-backup-status { position: sticky; top: 0; z-index: 1; padding-block: 2px; background: #f8fafc; }
.draft-backup button { min-height: 28px; padding: 3px 0; border: 0; background: transparent; color: #9d5b3f; font: inherit; text-align: left; cursor: pointer; }
.draft-backup button:disabled { opacity: .45; cursor: not-allowed; }
.draft-backup button:focus-visible { outline: 2px solid #e89778; outline-offset: 2px; }
.draft-backup-file { display: none; }
.draft-backup-error { color: #9a4f32; }
.draft-backup-hint { color: #64748b; }
.draft-backup-history { padding-top: 5px; }
.draft-backup-record { margin-top: 7px; padding: 8px 10px; border: 1px solid #e2e8f0; border-radius: 6px; background: #fff; }
.draft-backup-record strong { color: #475569; font-weight: 600; overflow-wrap: anywhere; }
.draft-backup-record time { margin-left: auto; color: #64748b; font-size: 11px; }
.draft-backup-remove { padding: 7px 9px; border-radius: 5px; background: #fff7ed; }
.draft-backup-remove button { margin-right: 14px; }
@media (max-width: 600px) { .draft-backup { padding-inline: 10px; max-height: min(230px, 34%); } }
@media (max-height: 480px) { .draft-backup { max-height: 130px; } }
</style>
