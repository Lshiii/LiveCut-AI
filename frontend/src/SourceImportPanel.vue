<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, type VideoAsset } from './api'
import { assertSourceReceipt, createSourceReceipt, finishSourceReceipt, loadSourceReceipts, newestSourceImport, parseSourceImport, parseSourceImports,
  sourceImportErrorText, sourceImportNotFound, sourceImportTerminal, sourceImportVideo, type ImportStorage, type SourceImportReceipt, type SourceImportSnapshot } from './sourceImports'

const props = withDefaults(defineProps<{ canStart: () => boolean; active?: boolean; toggleTarget?: string }>(), { active: true })
const emit = defineEmits<{ busy: [value: boolean]; started: []; complete: [video: VideoAsset] }>()
const expanded = ref(false), input = ref<HTMLInputElement | null>(null), url = ref(''), items = ref<SourceImportSnapshot[]>([])
const receipts = ref<SourceImportReceipt[]>([]), error = ref(''), notice = ref(''), storageError = ref(''), reading = ref(false), action = ref(''), replayKey = ref('')
const pending = computed(() => !!action.value)
let alive = true, epoch = 0, writeEpoch = 0, readController: AbortController | null = null, timer: number | undefined
let storage: ImportStorage | null = null
try { storage = window.localStorage } catch { /* A submission must not start without a durable receipt. */ }
const labels = { QUEUED: '等待导入', DOWNLOADING: '正在下载', NORMALIZING: '正在处理格式', VERIFYING: '正在验证视频', COMPLETED: '视频已保存', FAILED: '导入失败', CANCELLED: '已取消' }
function available() { return alive && props.active && expanded.value && !document.hidden }
function scan() {
  try {
    if (!storage) throw new Error('storage unavailable')
    receipts.value = loadSourceReceipts(storage); storageError.value = ''
  } catch (value) { storageError.value = sourceImportErrorText(value); if (!storage) storageError.value = '浏览器存储不可用，暂不能提交链接；已有导入仍可查看。' }
}
function stopReads() { epoch++; readController?.abort(); readController = null; reading.value = false; window.clearTimeout(timer); timer = undefined }
function upsert(value: SourceImportSnapshot, explicitRetry = false) {
  const known = items.value.find(item => item.id === value.id), result = newestSourceImport(known, value, explicitRetry)
  items.value = [result, ...items.value.filter(item => item.id !== result.id)].slice(0, 20)
}
function schedule() {
  window.clearTimeout(timer); timer = undefined
  if (available() && !pending.value && (error.value || items.value.some(item => !sourceImportTerminal(item)))) timer = window.setTimeout(() => { void refresh() }, 3000)
}
async function refresh() {
  if (!available() || pending.value) return
  stopReads(); const version = epoch, controller = new AbortController(); readController = controller; reading.value = true
  try {
    const values = parseSourceImports(await api<unknown>('/api/source-imports?limit=20', { signal: controller.signal }))
    if (!available() || version !== epoch || controller.signal.aborted) return
    items.value = values.map(value => newestSourceImport(items.value.find(item => item.id === value.id), value)); error.value = ''
  } catch (value) { if (available() && version === epoch && !controller.signal.aborted) error.value = sourceImportErrorText(value) }
  finally { if (version === epoch) { reading.value = false; readController = null; schedule() } }
}
function begin(kind: string) { stopReads(); action.value = kind; emit('busy', true); error.value = ''; notice.value = ''; return ++writeEpoch }
function current(version: number) { return available() && version === writeEpoch }
function end(version: number) { if (!alive || version !== writeEpoch || !action.value) return; action.value = ''; emit('busy', false); scan(); schedule() }
function completeReceipt(entry: SourceImportReceipt, value: SourceImportSnapshot, draft?: string) {
  if (!storage) return
  finishSourceReceipt(storage, entry); upsert(value); replayKey.value = ''; scan()
  if (draft !== undefined && url.value === draft) url.value = ''
  notice.value = '导入任务已保存。可以离开页面，完成后请明确选择“使用此视频”。'
}
async function sendReceipt(entry: SourceImportReceipt, version: number, draft?: string) {
  if (!storage) throw new Error('storage unavailable')
  assertSourceReceipt(storage, entry)
  const raw = await api<unknown>('/api/source-imports', { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': entry.key }, body: entry.input })
  const value = parseSourceImport(raw, { url: entry.url })
  if (current(version)) completeReceipt(entry, value, draft)
}
async function create() {
  if (pending.value || !available() || !props.canStart()) return
  scan(); if (storageError.value || receipts.value.length) return
  const draft = url.value
  const version = begin('create')
  try {
    if (!storage) throw new Error('storage unavailable')
    const entry = createSourceReceipt(storage, draft, () => crypto.randomUUID()); scan()
    await sendReceipt(entry, version, draft)
  } catch (value) { if (current(version)) error.value = sourceImportErrorText(value) }
  finally { end(version) }
}
async function checkReceipt(entry: SourceImportReceipt) {
  if (pending.value || !available()) return
  const version = begin(`check:${entry.key}`)
  try {
    if (!storage) throw new Error('storage unavailable')
    assertSourceReceipt(storage, entry)
    const value = parseSourceImport(await api<unknown>(`/api/source-imports/by-key/${entry.key}`), { url: entry.url })
    if (current(version)) completeReceipt(entry, value)
  } catch (value) {
    if (current(version)) {
      if (sourceImportNotFound(value)) { replayKey.value = entry.key; notice.value = '已核对原提交确实不存在。可明确重发原链接与原标识，当前新输入保持不变。' }
      else { replayKey.value = ''; error.value = sourceImportErrorText(value) }
    }
  } finally { end(version) }
}
async function replay(entry: SourceImportReceipt) {
  if (pending.value || !available() || replayKey.value !== entry.key || !props.canStart()) return
  const version = begin(`replay:${entry.key}`)
  try { await sendReceipt(entry, version) }
  catch (value) { if (current(version)) { replayKey.value = ''; error.value = sourceImportErrorText(value) } }
  finally { end(version) }
}
async function abandon(entry: SourceImportReceipt) {
  if (pending.value || !available() || replayKey.value !== entry.key) return
  const version = begin(`abandon:${entry.key}`)
  try {
    if (!storage) throw new Error('storage unavailable')
    assertSourceReceipt(storage, entry)
    const value = parseSourceImport(await api<unknown>(`/api/source-imports/by-key/${entry.key}`), { url: entry.url })
    if (current(version)) completeReceipt(entry, value)
  } catch (value) {
    if (current(version)) {
      if (sourceImportNotFound(value) && storage) {
        try { finishSourceReceipt(storage, entry); replayKey.value = ''; scan(); notice.value = '再次核对原提交确实不存在，已放弃这一条未提交链接；当前输入保留。' }
        catch (failure) { error.value = sourceImportErrorText(failure) }
      } else error.value = sourceImportErrorText(value)
    }
  } finally { end(version) }
}
async function changeStatus(item: SourceImportSnapshot, retry: boolean) {
  if (pending.value || !available() || (retry && (item.status !== 'FAILED' || item.attempt >= 3))) return
  if (retry && !props.canStart()) return
  const version = begin(`${retry ? 'retry' : 'cancel'}:${item.id}`)
  try {
    const raw = await api<unknown>(`/api/source-imports/${item.id}${retry ? '/retry' : ''}`, { method: retry ? 'POST' : 'DELETE', ...(retry ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ expected_attempt: item.attempt }) } : {}) })
    const value = parseSourceImport(raw, { id: item.id, url: item.url })
    if (current(version)) { upsert(value, retry); notice.value = retry ? '已提交一次明确重试。' : '取消结果已核对；已完成的视频不会自动被删除。' }
  } catch (value) { if (current(version)) error.value = sourceImportErrorText(value) }
  finally { end(version); if (available()) void refresh() }
}
async function useVideo(item: SourceImportSnapshot) {
  if (pending.value || !available() || item.status !== 'COMPLETED' || !item.video_id || !props.canStart()) return
  const version = begin(`use:${item.id}`); emit('started')
  try {
    const raw = await api<unknown>(`/api/videos/${item.video_id}`)
    const video = sourceImportVideo(raw, item.video_id)
    // Recheck the current editor guard after the asynchronous read.
    if (current(version) && props.canStart()) {
      end(version)
      expanded.value = false
      emit('complete', video)
    }
  } catch (value) { if (current(version)) error.value = sourceImportErrorText(value) }
  finally { end(version) }
}
async function open() { expanded.value = true; await nextTick(); input.value?.focus() }
function lifecycle() { stopReads(); if (available()) { scan(); if (!pending.value) void refresh() } }
function bytes(value: number) { return value < 1024 * 1024 ? `${(value / 1024).toFixed(1)} KiB` : `${(value / 1024 / 1024).toFixed(1)} MiB` }
watch(() => [props.active, expanded.value], lifecycle)
onMounted(() => { scan(); document.addEventListener('visibilitychange', lifecycle); window.addEventListener('storage', scan) })
onBeforeUnmount(() => { alive = false; writeEpoch++; stopReads(); document.removeEventListener('visibilitychange', lifecycle); window.removeEventListener('storage', scan); if (pending.value) emit('busy', false) })
defineExpose({ open, pending, refresh })
</script>

<template>
  <section class="source-import" :class="{ 'source-import-expanded': expanded, 'source-import-docked': !!toggleTarget }" aria-label="链接导入视频">
    <Teleport :to="toggleTarget || null" :disabled="!toggleTarget" defer>
      <button class="text-button source-toggle" :class="{ 'source-toggle-docked': !!toggleTarget }" :title="expanded ? '收起链接导入' : '打开链接导入'" :aria-expanded="expanded" aria-controls="source-import-content" @click="expanded = !expanded">
        <svg v-if="toggleTarget" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="m10 13 4-4M7.5 14.5l-1 1a3.5 3.5 0 0 0 5 5l4-4a3.5 3.5 0 0 0 0-5M16.5 9.5l1-1a3.5 3.5 0 0 0-5-5l-4 4a3.5 3.5 0 0 0 0 5"/></svg>
        <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>
        <span class="source-toggle-label">{{ expanded ? '收起链接导入' : '打开链接导入' }}</span><span v-if="receipts.length" class="source-receipt-count"><span class="source-receipt-prefix">有提交</span>待核对</span>
      </button>
    </Teleport>
    <div id="source-import-content" v-show="expanded" class="source-content">
      <p id="source-import-help" class="source-help">支持 YouTube、Bilibili、抖音的公开单条视频，仅限无需登录即可观看的内容</p>
      <form class="source-form" @submit.prevent="create">
        <label for="source-import-url">视频链接</label>
        <div class="source-input-row">
          <input id="source-import-url" ref="input" v-model="url" type="url" maxlength="2048" placeholder="粘贴一个完整视频链接" autocomplete="off" aria-describedby="source-import-help source-import-background source-import-limitations">
          <button type="submit" class="primary" :disabled="pending || !url.trim() || !!storageError || !!receipts.length">导入链接</button>
        </div>
        <p id="source-import-background" class="source-form-note">后台下载完成后，由你选择视频并开始分析</p>
      </form>
      <p id="source-import-limitations" class="source-limitations">抖音或部分 YouTube 视频可能因站点验证无法导入。请从分享文案中复制单个完整链接。</p>
      <p v-if="storageError" class="message error" role="alert">{{ storageError }}</p>
      <div v-for="entry in receipts" :key="entry.key" class="source-receipt">
        <strong>原链接提交结果待核对</strong><p>{{ entry.url }}</p>
        <div class="source-receipt-actions"><button class="secondary" :disabled="pending" @click="checkReceipt(entry)">核对原提交</button><template v-if="replayKey === entry.key"><button class="secondary" :disabled="pending" @click="replay(entry)">原样重发原提交</button><button class="text-button" :disabled="pending" @click="abandon(entry)">放弃未提交链接</button></template></div>
      </div>
      <p v-if="error" class="message error" role="alert">{{ error }}</p><p v-if="notice" class="message success" role="status">{{ notice }}</p>
      <div class="source-history-heading"><h2>最近的链接导入</h2><button class="text-button" :disabled="pending || reading" @click="refresh">{{ reading ? '正在读取…' : '刷新进度' }}</button></div>
      <p v-if="!items.length && !reading" class="source-history-empty">还没有导入记录，粘贴链接即可开始</p>
      <ol class="source-list">
        <li v-for="item in items" :key="item.id" :class="`source-item-${item.status.toLowerCase()}`">
          <div class="source-item-top"><strong>{{ labels[item.phase] }}</strong><span>{{ item.attempt === 0 ? '等待首次执行' : `第 ${item.attempt} / 3 次尝试` }}<span v-if="item.progress_percent !== null"> · {{ Math.round(item.progress_percent) }}%</span></span></div>
          <p class="source-url">{{ item.url }}</p>
          <progress v-if="item.status === 'RUNNING'" :value="item.progress_percent ?? undefined" max="100" :aria-label="labels[item.phase]"></progress>
          <p v-if="item.status === 'RUNNING'" class="source-download-note">已下载 {{ bytes(item.downloaded_bytes) }} · 关闭页面后仍在后台运行</p>
          <p v-if="item.status === 'FAILED'" class="source-failure">{{ sourceImportErrorText({ code: item.error_code }) }}</p>
          <div class="source-item-actions"><button v-if="item.status === 'QUEUED' || item.status === 'RUNNING'" class="secondary" :disabled="pending" @click="changeStatus(item, false)">取消导入</button><button v-if="item.status === 'FAILED' && item.attempt < 3" class="secondary" :disabled="pending" @click="changeStatus(item, true)">重试导入</button><button v-if="item.status === 'COMPLETED'" class="primary" :disabled="pending" @click="useVideo(item)">使用此视频</button></div>
        </li>
      </ol>
    </div>
  </section>
</template>

<style scoped>
.source-import { min-width: 0; margin-bottom: 12px; color: var(--workspace-text, #292621); }
.source-import-docked { margin-bottom: 0; }
.source-import button, .source-toggle { min-height: 36px; padding: 8px 12px; border-radius: 8px; font-size: 13px; font-weight: 500; line-height: 20px; }
.source-import .text-button, .source-toggle { color: var(--workspace-muted, #675f56); background: transparent; }
.source-import .text-button:hover:not(:disabled), .source-toggle:hover { color: var(--workspace-text, #292621); background: var(--workspace-hover, #eeece6); }
.source-import .primary { background: var(--workspace-accent, #a94424); color: var(--studio-ink); box-shadow: none; }
.source-import .primary:hover:not(:disabled) { background: var(--workspace-accent-hover); }
.source-import .secondary { border: 1px solid var(--workspace-border, #e4e0d8); background: var(--workspace-surface, #fff); color: var(--workspace-text, #292621); }
.source-import .secondary:hover:not(:disabled) { background: var(--workspace-hover, #eeece6); }
.source-import button:focus-visible, .source-toggle:focus-visible, .source-input-row input:focus-visible { outline: 2px solid var(--workspace-accent, #a94424); outline-offset: 3px; }
.source-toggle { display: inline-flex; align-items: center; flex-wrap: wrap; gap: 8px; padding-inline: 4px 8px; text-align: left; white-space: normal; }
.source-toggle svg { flex: none; width: 16px; height: 16px; transition: transform 160ms ease-out; }
.source-toggle:not(.source-toggle-docked)[aria-expanded="true"] svg { transform: rotate(180deg); }
.source-toggle-docked { flex-wrap: nowrap; padding-inline: 8px; border: 1px solid var(--workspace-border, #e4e0d8); white-space: nowrap; }
.source-toggle-docked[aria-expanded="true"] { color: var(--workspace-accent, #a94424); background: var(--workspace-accent-soft, #f9ede7); border-color: currentColor; }
.source-receipt-count { padding-left: 8px; border-left: 1px solid var(--workspace-border, #e4e0d8); color: var(--workspace-accent, #a94424); font-size: 12px; }
.source-content { padding: 16px 0; border-top: 1px solid var(--workspace-border, #e4e0d8); margin-top: 8px; }
.source-help { max-width: 72ch; font-size: 14px; line-height: 24px; color: var(--workspace-muted, #675f56); }
.source-help, .source-url, .source-receipt p, .source-limitations { overflow-wrap: anywhere; }
.source-form { margin: 16px 0 8px; }
.source-form > label { display: block; margin-bottom: 8px; font-size: 13px; line-height: 20px; font-weight: 500; }
.source-input-row { display: flex; gap: 8px; }
.source-input-row input { min-width: 0; flex: 1; min-height: 44px; padding: 10px 12px; border: 1px solid var(--workspace-control-border); border-radius: 8px; background: var(--workspace-surface, #fff); color: var(--workspace-text, #292621); font-size: 14px; line-height: 20px; }
.source-input-row input::placeholder { color: var(--workspace-muted, #675f56); }
.source-input-row > button { min-height: 44px; padding-inline: 20px; flex: none; }
.source-form-note, .source-limitations, .source-download-note, .source-history-empty { color: var(--workspace-muted, #675f56); font-size: 12px; line-height: 20px; }
.source-form-note { margin-top: 8px; }
.source-history-heading, .source-item-top { display: flex; align-items: center; justify-content: space-between; gap: 8px 16px; flex-wrap: wrap; }
.source-history-heading { margin-top: 24px; }
.source-history-heading h2 { margin: 0; font-size: 14px; line-height: 20px; font-weight: 500; }
.source-history-empty { margin-top: 8px; padding: 16px; border: 1px dashed var(--workspace-border, #e4e0d8); border-radius: 8px; }
.source-list { display: grid; gap: 12px; list-style: none; margin: 8px 0 0; padding: 0; }
.source-list li { min-width: 0; padding: 16px; border: 1px solid var(--workspace-border, #e4e0d8); border-radius: 8px; background: var(--workspace-surface, #fff); }
.source-item-top strong { display: flex; align-items: center; gap: 8px; font-size: 14px; line-height: 20px; font-weight: 500; }
.source-item-top strong::before { content: ''; flex: none; width: 6px; height: 6px; border-radius: 50%; background: var(--workspace-muted, #675f56); }
.source-item-running .source-item-top strong::before { background: var(--workspace-accent, #a94424); }
.source-item-completed .source-item-top strong::before { background: var(--workspace-accent); }
.source-item-failed .source-item-top strong::before { background: var(--studio-danger); }
.source-item-top span { color: var(--workspace-muted, #675f56); font-size: 12px; line-height: 20px; font-variant-numeric: tabular-nums; }
.source-url { margin-top: 8px; color: var(--workspace-muted, #675f56); font-size: 13px; line-height: 20px; }
.source-download-note { margin-top: 8px; font-variant-numeric: tabular-nums; }
.source-item-actions, .source-receipt-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
.source-item-actions:empty { display: none; }
.source-receipt { margin: 16px 0; padding: 16px; border: 1px solid var(--workspace-border, #e4e0d8); border-radius: 8px; background: var(--workspace-canvas, #f4f3ef); }
.source-receipt > strong { font-size: 14px; line-height: 20px; font-weight: 500; }
.source-receipt p { margin-top: 8px; color: var(--workspace-muted, #675f56); font-size: 13px; line-height: 20px; }
.source-failure { margin-top: 8px; color: var(--studio-danger); font-size: 13px; line-height: 20px; overflow-wrap: anywhere; }
.source-list progress { display: block; width: 100%; height: 8px; margin-top: 12px; accent-color: var(--workspace-accent, #a94424); }
.source-import .message { margin: 16px 0; padding: 12px 16px; font-size: 13px; line-height: 20px; overflow-wrap: anywhere; }
@media (max-width: 520px) {
  .source-input-row { flex-wrap: wrap; }
  .source-input-row input { flex-basis: 100%; font-size: 16px; }
  .source-input-row > button { width: 100%; }
  .source-list li, .source-receipt { padding: 12px; }
}
@media (max-width: 760px) {
  .source-toggle-docked { min-width: 36px; height: 36px; justify-content: center; }
  .source-toggle-docked .source-toggle-label { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
  .source-toggle-docked .source-receipt-count { padding-left: 8px; }
  .source-toggle-docked .source-receipt-prefix { display: none; }
}
@media (prefers-reduced-motion: reduce) { .source-toggle svg { transition: none; } }
</style>
