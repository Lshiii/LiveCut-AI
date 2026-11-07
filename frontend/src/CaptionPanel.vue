<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, ApiError, post } from './api'
import CaptionRenderPanel from './CaptionRenderPanel.vue'
import DraftBackupPanel from './DraftBackupPanel.vue'
import CaptionTextTools from './CaptionTextTools.vue'
import CaptionProofreadPanel from './CaptionProofreadPanel.vue'
import { applyCaptionProofreadSuggestions, type CaptionProofreadSession, type CaptionProofreadTurn } from './captionProofread'
import { applyCaptionTextReplacement, captionTextMatches, undoCaptionTextReplacement, type CaptionTextChange, type CaptionTextPreview } from './captionTextTools'
import { assessCaptionDraft, captionDraftValue, captionSaveCheck, restoreCaptionDraft } from './captionDraft'
import { captionBase, captionDraftInvalid, captionFileUrl, captionPayload, captionTime, captionValidation, copyCaptionCue, copyCaptionCues,
  editCaptionTime, parseCaptionSnapshot, sameCaptionSource, CaptionResponseError,
  type CaptionCue, type CaptionSnapshot, type CaptionTimeDrafts } from './captions'

const props = withDefaults(defineProps<{ exportId: string; clipId: string; contentUrl: string; active?: boolean }>(), { active: true })
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement | null>(null), player = ref<HTMLVideoElement | null>(null)
const snapshot = ref<CaptionSnapshot | null>(null), cues = ref<CaptionCue[]>([]), baseline = ref('')
const timeDrafts = ref<CaptionTimeDrafts>({}), action = ref(''), error = ref(''), notice = ref('')
const saveNeedsCheck = ref(false)
const onlyReview = ref(false), page = ref(0), acknowledged = ref(false), downloadRevision = ref<number | null>(null)
const view = ref<'edit' | 'proofread' | 'renders'>('edit'), renderEditor = ref<{ pending: boolean } | null>(null)
const proofreadEditor = ref<{ pending: boolean } | null>(null)
const editTab = ref<HTMLButtonElement | null>(null), proofreadTab = ref<HTMLButtonElement | null>(null), renderTab = ref<HTMLButtonElement | null>(null)
const selectedId = ref(''), playhead = ref(0), deleted = ref<{ cue: CaptionCue; index: number }[]>([])
const searchQuery = ref(''), searchIgnoreCase = ref(true), textContextEpoch = ref(0)
const cueList = ref<HTMLElement | null>(null)
const textToolsExpanded = ref(false)
const previewExpanded = ref(typeof window !== 'undefined' && window.matchMedia('(min-width: 761px)').matches)
const lastTextBatch = ref<{ contextKey: string; changes: CaptionTextChange[] } | null>(null)
let locateVersion = 0
let stopped = false, version = 0, previewEnd: number | null = null, initialSeek: number | null = null
const pageSize = 50
const pending = computed(() => !!action.value || !!renderEditor.value?.pending || !!proofreadEditor.value?.pending)
const dirty = computed(() => !!snapshot.value && (saveNeedsCheck.value || JSON.stringify(captionPayload(cues.value)) !== baseline.value || captionDraftInvalid(timeDrafts.value)))
const validation = computed(() => snapshot.value ? captionValidation(cues.value, snapshot.value.source.duration_ms, timeDrafts.value) : '')
const requiresReview = computed(() => cues.value.filter(cue => cue.boundary_partial && !cue.reviewed).length)
const filtered = computed(() => cues.value.filter(cue => (!onlyReview.value || cue.boundary_partial && !cue.reviewed)
  && (!searchQuery.value.trim() || captionTextMatches(cue.text, searchQuery.value, searchIgnoreCase.value) > 0)))
const textScopeIds = computed(() => filtered.value.map(cue => cue.id))
const textContextKey = computed(() => snapshot.value ? JSON.stringify({ exportId: props.exportId, clipId: props.clipId,
  contentUrl: props.contentUrl, revision: snapshot.value.revision, contentHash: snapshot.value.content_hash,
  source: snapshot.value.source, epoch: textContextEpoch.value }) : '')
const textDraftKey = computed(() => JSON.stringify({ cues: captionPayload(cues.value), times: timeDrafts.value,
  deleted: deleted.value.map(item => ({ index: item.index, cue: captionPayload([item.cue]).cues[0] })), needsCheck: saveNeedsCheck.value }))
const undoTextAvailable = computed(() => !!lastTextBatch.value && lastTextBatch.value.contextKey === textContextKey.value)
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
const visibleCues = computed(() => filtered.value.slice(page.value * pageSize, (page.value + 1) * pageSize))
const currentCue = computed(() => cues.value.find(cue => cue.start_ms <= playhead.value && playhead.value < cue.end_ms))
const base = computed(() => captionBase(props.exportId, props.clipId))
const canConfirm = computed(() => !!snapshot.value && !pending.value && !dirty.value && !validation.value && !requiresReview.value && acknowledged.value && snapshot.value.status !== 'CONFIRMED')
const backupValue = computed(() => snapshot.value && dirty.value ? captionDraftValue(snapshot.value, cues.value, timeDrafts.value, deleted.value, saveNeedsCheck.value || action.value === 'save') : null)
function assessBackup(value: unknown) { return assessCaptionDraft(value, snapshot.value) }
defineExpose({ dirty, pending })

function fail(value: unknown) {
  const raw = value instanceof ApiError ? value.code : value instanceof CaptionResponseError ? 'INVALID_CAPTION_RESPONSE' : 'CLIENT_ERROR'
  const code = typeof raw === 'string' && /^[A-Z][A-Z0-9_]{0,99}$/.test(raw) ? raw : 'CLIENT_ERROR'
  const names: Record<string, string> = {
    NETWORK_UNAVAILABLE: '暂时无法连接服务，当前字幕修改仍保留。', REQUEST_TIMEOUT: '请求超时，当前修改仍保留。可点击“核对服务器版本”确认保存状态。',
    CAPTION_REVISION_CONFLICT: '服务端已有新版本，当前修改仍保留。请先核对服务器版本，再决定如何处理。',
    INVALID_CAPTION_REVIEW: '字幕文字或时间不符合要求，请检查提示后保存。', CAPTION_REVIEW_REQUIRED: '还有边界截断句需要明确标记已核对，保存后再确认。',
    CAPTION_CONFIRMATION_REQUIRED: '请先确认字幕版本，再下载对应文件。', CAPTION_SOURCE_INVALID: '成片或原始转写来源未通过校验，未替换当前字幕。',
    CAPTION_CONTENT_INVALID: '字幕内容未通过校验，当前修改仍保留。', CAPTION_REVISION_NOT_FOUND: '所选字幕版本不存在，请核对版本。',
    CAPTIONS_NOT_INITIALIZED: '此成片尚未准备字幕稿，请关闭后重新进入。', INVALID_CAPTION_RESPONSE: '返回的字幕来源或内容不匹配，已保留当前修改。',
  }
  error.value = names[code] ?? `字幕操作未完成：${code}`
}
function parse(raw: unknown) {
  const value = parseCaptionSnapshot(raw, props.exportId, props.clipId)
  if (value.content_url !== props.contentUrl) throw new CaptionResponseError()
  if (snapshot.value) {
    sameCaptionSource(snapshot.value, value)
    if (value.revision < snapshot.value.revision || snapshot.value.confirmed_revisions.some(revision => !value.confirmed_revisions.includes(revision))) throw new CaptionResponseError()
  }
  return value
}
function apply(value: CaptionSnapshot) {
  textContextEpoch.value++; lastTextBatch.value = null; locateVersion++
  snapshot.value = value; cues.value = copyCaptionCues(value.cues); timeDrafts.value = {}; deleted.value = []; acknowledged.value = false
  saveNeedsCheck.value = false
  baseline.value = JSON.stringify(captionPayload(cues.value))
  if (!selectedId.value || !cues.value.some(cue => cue.id === selectedId.value)) selectedId.value = cues.value[0]?.id ?? ''
  if (downloadRevision.value === null || !value.confirmed_revisions.includes(downloadRevision.value)) downloadRevision.value = value.confirmed_revisions.length ? Math.max(...value.confirmed_revisions) : null
}
function current(op: number) { return !stopped && op === version }
async function initialize() {
  action.value = 'load'; error.value = ''; const op = ++version
  try { const raw = await post<unknown>(base.value); if (current(op)) apply(parse(raw)) }
  catch (value) { if (current(op)) fail(value) }
  finally { if (current(op)) action.value = '' }
}
async function reload() {
  if (!snapshot.value || pending.value) return
  action.value = 'reload'; error.value = ''; notice.value = ''; const op = ++version
  try {
    const raw = await api<unknown>(base.value)
    if (!current(op)) return
    const value = parse(raw)
    const checked = captionSaveCheck(snapshot.value!, value, cues.value, timeDrafts.value)
    if (saveNeedsCheck.value && checked === 'retry') {
      saveNeedsCheck.value = false
      notice.value = `当前核对仍为服务端 r${value.revision}，本页修改和时间输入保留。可修正后手动再保存；这不代表前次请求从未提交。`; return
    }
    if (dirty.value && checked !== 'saved') {
      error.value = `服务端最新为 r${value.revision}，与本页修改不同。本页内容仍保留；请先复制需保留的修改，或放弃本页修改后重新读取。`; return
    }
    apply(value); notice.value = `已核对服务端字幕版本 r${value.revision}。`
  } catch (value) { if (current(op)) fail(value) }
  finally { if (current(op)) action.value = '' }
}
async function save() {
  if (saveNeedsCheck.value) { await reload(); return }
  if (!snapshot.value || pending.value || !dirty.value || validation.value) return
  const revision = snapshot.value.revision, op = ++version
  action.value = 'save'; error.value = ''; notice.value = ''
  try {
    const raw = await api<unknown>(base.value, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ base_revision: revision, ...captionPayload(cues.value) }) })
    if (!current(op)) return
    const value = parse(raw); if (value.revision !== revision + 1) throw new CaptionResponseError()
    apply(value); notice.value = `字幕 r${value.revision} 已保存，核对后可确认并下载。`
  } catch (value) { if (current(op)) { saveNeedsCheck.value = true; fail(value) } }
  finally { if (current(op)) action.value = '' }
}
async function confirm() {
  if (!canConfirm.value || !snapshot.value) return
  const revision = snapshot.value.revision, op = ++version
  action.value = 'confirm'; error.value = ''; notice.value = ''
  try {
    const raw = await post<unknown>(`${base.value}/confirm`, { revision, acknowledge_review: true })
    if (!current(op)) return
    const value = parse(raw); if (value.revision !== revision || value.status !== 'CONFIRMED') throw new CaptionResponseError()
    apply(value); downloadRevision.value = revision; notice.value = `字幕 r${revision} 已确认。下载区已可获取字幕和原视频素材包。`
  } catch (value) { if (current(op)) fail(value) }
  finally { if (current(op)) action.value = '' }
}
async function discard() {
  if (pending.value || !snapshot.value || !mediaActive()) return
  if (!saveNeedsCheck.value) { apply(snapshot.value); error.value = ''; notice.value = '未保存修改已放弃。'; return }
  const owner = { exportId: props.exportId, clipId: props.clipId, contentUrl: props.contentUrl }
  const signature = JSON.stringify({ cues: captionPayload(cues.value), times: timeDrafts.value, deleted: deleted.value })
  const op = ++version
  action.value = 'discard'; error.value = ''; notice.value = ''
  try {
    const raw = await api<unknown>(base.value)
    if (!current(op) || !mediaActive() || props.exportId !== owner.exportId || props.clipId !== owner.clipId || props.contentUrl !== owner.contentUrl
      || signature !== JSON.stringify({ cues: captionPayload(cues.value), times: timeDrafts.value, deleted: deleted.value })) return
    const value = parse(raw)
    apply(value); notice.value = `已读取服务端 r${value.revision}，本页修改已明确放弃。`
  } catch (value) { if (current(op)) fail(value) }
  finally { if (current(op)) action.value = '' }
}
async function restoreBackup(raw: unknown) {
  if (!snapshot.value || pending.value || dirty.value || !mediaActive()) return
  const assessment = assessBackup(raw)
  if (!assessment.compatible) { error.value = assessment.reason ?? '此备份不能恢复到当前字幕。'; return }
  const owner = { exportId: props.exportId, clipId: props.clipId, contentUrl: props.contentUrl,
    revision: snapshot.value.revision, contentHash: snapshot.value.content_hash }
  const signature = JSON.stringify({ cues: captionPayload(cues.value), times: timeDrafts.value, deleted: deleted.value })
  const op = ++version
  action.value = 'restore'; error.value = ''; notice.value = ''
  try {
    const response = await api<unknown>(base.value)
    if (!current(op) || !mediaActive() || props.exportId !== owner.exportId || props.clipId !== owner.clipId || props.contentUrl !== owner.contentUrl
      || snapshot.value?.revision !== owner.revision || snapshot.value.content_hash !== owner.contentHash
      || dirty.value || signature !== JSON.stringify({ cues: captionPayload(cues.value), times: timeDrafts.value, deleted: deleted.value })) return
    const value = parse(response)
    const restored = restoreCaptionDraft(raw, value)
    if (!restored) {
      const latest = assessCaptionDraft(raw, value)
      apply(value); error.value = latest.reason ?? '备份与最新字幕不匹配，未恢复。'; return
    }
    apply(value)
    cues.value = restored.cues; timeDrafts.value = restored.timeDrafts; deleted.value = restored.deleted
    saveNeedsCheck.value = restored.needsCheck
    acknowledged.value = false; player.value?.pause(); previewEnd = null; initialSeek = null
    page.value = 0; onlyReview.value = false; selectedId.value = cues.value[0]?.id ?? ''
    notice.value = saveNeedsCheck.value ? '本机字幕草稿已恢复，前次保存结果仍需只读核对；未自动保存、确认或生成视频。' : '本机字幕草稿已恢复到本页，仍需修正并保存；未自动确认或生成视频。'
  } catch (value) { if (current(op) && mediaActive() && props.exportId === owner.exportId && props.clipId === owner.clipId && props.contentUrl === owner.contentUrl) fail(value) }
  finally { if (current(op)) action.value = '' }
}
function requestClose() {
  if (pending.value) { error.value = '正在提交操作，请完成后再关闭。'; return }
  if (dirty.value) { error.value = '字幕有未保存修改，请先保存或放弃修改，再关闭。'; return }
  player.value?.pause(); dialog.value?.close(); emit('close')
}
function removeCue(cue: CaptionCue) {
  const index = cues.value.findIndex(value => value.id === cue.id)
  if (pending.value || index < 0) return
  deleted.value.push({ cue: copyCaptionCue(cue), index }); cues.value.splice(index, 1); delete timeDrafts.value[cue.id]; acknowledged.value = false
}
function undoDelete() { if (!pending.value) { const item = deleted.value.pop(); if (item) cues.value.splice(Math.min(item.index, cues.value.length), 0, item.cue) } }
function applyTextBatch(value: { preview: CaptionTextPreview; contextKey: string; draftKey: string }) {
  if (!snapshot.value || pending.value || !mediaActive()) return
  error.value = ''; notice.value = ''
  if (value.contextKey !== textContextKey.value || value.draftKey !== textDraftKey.value) {
    error.value = '字幕内容或版本已变化，请重新预览替换。'; return
  }
  const result = applyCaptionTextReplacement(cues.value, value.preview)
  if (!result.cues) { error.value = result.error; return }
  lastTextBatch.value = { contextKey: textContextKey.value, changes: value.preview.changes.map(change => ({ ...change })) }
  cues.value = result.cues; acknowledged.value = false
  player.value?.pause(); previewEnd = null; initialSeek = null
  const first = value.preview.changes[0]
  if (first) void locateCue(first.id)
  notice.value = `已在本页替换 ${value.preview.changes.length} 条字幕中的 ${value.preview.occurrences} 处文字，尚未保存。已定位首条修改，可撤销最近一次替换。`
}
function undoTextBatch() {
  if (!lastTextBatch.value || pending.value || !mediaActive()) return
  error.value = ''; notice.value = ''
  if (lastTextBatch.value.contextKey !== textContextKey.value) {
    lastTextBatch.value = null; error.value = '字幕版本已变化，不能撤销旧版本的替换。'; return
  }
  const changes = lastTextBatch.value.changes
  const result = undoCaptionTextReplacement(cues.value, changes)
  if (!result.cues) { error.value = result.error; return }
  cues.value = result.cues; lastTextBatch.value = null; acknowledged.value = false
  player.value?.pause(); previewEnd = null; initialSeek = null
  if (changes[0]) void locateCue(changes[0].id)
  notice.value = `已撤销最近一次替换的 ${changes.length} 条字幕文字，其他修改保留。仍需核对并保存。`
}
function applyAiSuggestions(value: { session: CaptionProofreadSession; turn: CaptionProofreadTurn; cueIds: string[] }) {
  if (!snapshot.value || pending.value || !props.active || view.value !== 'proofread' || !dialog.value?.open || stopped) return
  error.value = ''; notice.value = ''
  const result = applyCaptionProofreadSuggestions(snapshot.value, cues.value, value.session, value.turn, value.cueIds)
  if (!result.cues) { error.value = result.error; return }
  const current = new Map(cues.value.map(cue => [cue.id, cue.text]))
  const selected = new Set(value.cueIds)
  const changes = result.cues.filter(cue => selected.has(cue.id) && current.get(cue.id) !== cue.text)
    .map(cue => ({ id: cue.id, before: current.get(cue.id)!, after: cue.text, occurrences: 1 }))
  if (!changes.length) { error.value = '没有可采纳的字幕修改。'; return }
  lastTextBatch.value = { contextKey: textContextKey.value, changes }
  cues.value = result.cues; acknowledged.value = false
  player.value?.pause(); previewEnd = null; initialSeek = null; view.value = 'edit'
  void locateCue(changes[0]!.id)
  notice.value = `已把 ${changes.length} 条 AI 建议采纳到本页草稿，尚未保存或确认。请回看核对；可撤销最近替换。`
}
async function locateCue(id: string) {
  const cue = cues.value.find(value => value.id === id)
  if (!cue || pending.value || !mediaActive()) return
  const token = ++locateVersion, context = textContextKey.value
  // Locating a change must reveal it even after replacing its old search text.
  if (!filtered.value.some(value => value.id === id)) { searchQuery.value = ''; onlyReview.value = false }
  page.value = Math.floor(filtered.value.findIndex(value => value.id === id) / pageSize); selectedId.value = id
  await nextTick()
  if (token !== locateVersion || context !== textContextKey.value || !mediaActive()) return
  const element = [...(cueList.value?.querySelectorAll<HTMLElement>('[data-caption-id]') ?? [])].find(value => value.dataset.captionId === id)
  element?.scrollIntoView({ block: 'nearest' }); element?.focus({ preventScroll: true })
}
function editTime(cue: CaptionCue, field: 'start_ms' | 'end_ms', event: Event) { editCaptionTime(cue, timeDrafts.value, field, (event.target as HTMLInputElement).value); acknowledged.value = false }
function normalizeTime(cue: CaptionCue, field: 'start_ms' | 'end_ms') { if (!captionDraftInvalid({ [cue.id]: { [field]: timeDrafts.value[cue.id]?.[field] } })) { delete timeDrafts.value[cue.id]?.[field] } }
function timeValue(cue: CaptionCue, field: 'start_ms' | 'end_ms') { return timeDrafts.value[cue.id]?.[field] ?? captionTime(cue[field]) }
function mediaActive() { return props.active && view.value === 'edit' && !!dialog.value?.open && !stopped }
function guardPlayback(event: Event) { if (!mediaActive()) (event.currentTarget as HTMLVideoElement).pause() }
function updateTime() {
  if (!player.value) return
  playhead.value = Math.round(player.value.currentTime * 1000)
  if (previewEnd !== null && playhead.value >= previewEnd) { player.value.pause(); previewEnd = null }
}
function seeking() { if (initialSeek === null || !player.value || Math.abs(player.value.currentTime * 1000 - initialSeek) > 2) previewEnd = null }
function seek(ms: number) { if (mediaActive() && player.value) { previewEnd = null; initialSeek = null; player.value.pause(); player.value.currentTime = Math.max(0, ms) / 1000 } }
function previewCue(cue: CaptionCue) {
  if (!mediaActive() || !player.value || !snapshot.value || captionValidation([cue], snapshot.value.source.duration_ms)) return
  previewExpanded.value = true
  void nextTick(() => { if (previewExpanded.value && mediaActive() && selectedId.value === cue.id) player.value?.scrollIntoView({ block: 'nearest' }) })
  selectedId.value = cue.id; const video = player.value; initialSeek = cue.start_ms; previewEnd = cue.end_ms; video.currentTime = cue.start_ms / 1000
  void video.play().then(() => { if (!mediaActive()) video.pause() }).catch(() => { if (!stopped) notice.value = '点击播放器播放按钮，可继续回看当前字幕。' })
}
function showDialog() { if (!stopped && props.active && dialog.value && !dialog.value.open) dialog.value.showModal() }
function switchView(value: 'edit' | 'proofread' | 'renders') { if (!pending.value) view.value = value }
function viewKey(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key) || pending.value) return
  event.preventDefault()
  const tabs = ['edit', 'proofread', 'renders'] as const
  const index = tabs.indexOf(view.value)
  view.value = event.key === 'Home' ? 'edit' : event.key === 'End' ? 'renders' : tabs[(index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length]!
  void nextTick(() => { if (!stopped && props.active) (view.value === 'edit' ? editTab.value : view.value === 'proofread' ? proofreadTab.value : renderTab.value)?.focus() })
}
function unload(event: BeforeUnloadEvent) { if (dirty.value || pending.value) { event.preventDefault(); event.returnValue = '' } }
watch([onlyReview, searchQuery, searchIgnoreCase], () => { page.value = 0 }, { flush: 'sync' })
watch(pages, count => { page.value = Math.min(page.value, count - 1) })
watch(view, () => { locateVersion++; player.value?.pause() }, { flush: 'sync' })
watch(() => props.active, active => { if (!active) { locateVersion++; player.value?.pause(); dialog.value?.close() } else void nextTick(showDialog) }, { flush: 'post' })
onMounted(() => { showDialog(); window.addEventListener('beforeunload', unload); void initialize() })
onBeforeUnmount(() => { stopped = true; version++; player.value?.pause(); dialog.value?.close(); window.removeEventListener('beforeunload', unload) })
</script>

<template>
  <dialog ref="dialog" class="caption-dialog" aria-label="成片字幕校对" @cancel.prevent="requestClose">
    <header class="caption-header"><div><h2>字幕校对</h2><p>{{ snapshot?.clip_title ?? '正在读取成片字幕' }}<span v-if="snapshot"> · r{{ snapshot.revision }} {{ snapshot.status === 'CONFIRMED' ? '已确认' : '草稿' }}</span></p></div><button class="caption-button" :disabled="pending" @click="requestClose">关闭</button></header>
    <p v-if="error" class="caption-message error" role="alert">{{ error }}</p><p v-if="notice" class="caption-message" role="status">{{ notice }}</p>
    <p v-if="saveNeedsCheck" class="caption-message" role="status">前次保存结果尚未核对。本页草稿仍保留，核对前不会再次提交保存或确认。</p>
    <p v-if="!snapshot" class="caption-empty">{{ pending ? '正在从已有转写准备字幕，不会重新识别语音。' : '字幕暂不可用。' }}<button v-if="!pending" class="caption-button" @click="initialize">重新读取</button></p>
    <template v-if="snapshot">
      <div class="caption-view-tabs" role="tablist" aria-label="字幕与带字幕视频" @keydown="viewKey"><button :id="`caption-tab-edit-${exportId}-${clipId}`" ref="editTab" role="tab" :aria-controls="`caption-page-edit-${exportId}-${clipId}`" :aria-selected="view === 'edit'" :tabindex="view === 'edit' ? 0 : -1" :disabled="pending" @click="switchView('edit')">校对字幕<span v-if="dirty" class="caption-draft-dot" aria-label="有未保存修改"></span></button><button :id="`caption-tab-proofread-${exportId}-${clipId}`" ref="proofreadTab" role="tab" :aria-controls="`caption-page-proofread-${exportId}-${clipId}`" :aria-selected="view === 'proofread'" :tabindex="view === 'proofread' ? 0 : -1" :disabled="pending" @click="switchView('proofread')">AI 校对建议</button><button :id="`caption-tab-renders-${exportId}-${clipId}`" ref="renderTab" role="tab" :aria-controls="`caption-page-renders-${exportId}-${clipId}`" :aria-selected="view === 'renders'" :tabindex="view === 'renders' ? 0 : -1" :disabled="pending" @click="switchView('renders')">带字幕视频</button></div>
      <div :id="`caption-page-edit-${exportId}-${clipId}`" v-show="view === 'edit'" class="caption-edit-page" role="tabpanel" :aria-labelledby="`caption-tab-edit-${exportId}-${clipId}`">
      <details class="caption-backup-tools"><summary><span>本机草稿与备份</span><span>恢复、导入与下载备份</span></summary><DraftBackupPanel kind="caption" :document-id="`${exportId}:${clipId}`" :value="backupValue" :blocked="pending || dirty || !props.active || view !== 'edit'" :assess="assessBackup" @restore="restoreBackup" /></details>
      <div class="caption-main"><details class="caption-watch" :open="previewExpanded" @toggle="previewExpanded = ($event.currentTarget as HTMLDetailsElement).open; if (!previewExpanded) player?.pause()"><summary class="caption-preview-heading"><span>成片预览</span><span>{{ previewExpanded ? '收起预览' : '展开回看' }}</span></summary><div class="caption-watch-content"><div class="caption-video"><video ref="player" :src="contentUrl" controls preload="metadata" playsinline @play="guardPlayback($event); if (!previewExpanded) player?.pause()" @timeupdate="updateTime" @seeking="seeking" @seeked="initialSeek = null"></video><p v-if="currentCue" class="caption-overlay" aria-live="off">{{ currentCue.text }}</p></div><label class="caption-seek">成片播放位置 {{ captionTime(playhead) }} / {{ captionTime(snapshot.source.duration_ms) }}<input type="range" min="0" :max="snapshot.source.duration_ms" :value="playhead" step="1" aria-label="字幕成片时间轴" @input="seek(Number(($event.target as HTMLInputElement).value))"></label><details class="caption-guide"><summary>字幕时间与预览说明</summary><p class="caption-help">字幕以成片开始为 00:00。按原句时间生成，边界截断句需逐条回看核对。</p><p class="caption-help">预览字幕只显示在本页，原 MP4 保持原样；下载包提供独立字幕文件。</p></details><details class="caption-downloads"><summary>下载已确认版本与素材包</summary><template v-if="snapshot.confirmed_revisions.length"><label>版本<select v-model.number="downloadRevision" aria-label="字幕下载版本"><option v-for="rev in snapshot.confirmed_revisions" :key="rev" :value="rev">r{{ rev }}</option></select></label><div v-if="downloadRevision !== null" class="caption-download-links"><a v-for="format in (['srt', 'vtt', 'txt', 'json'] as const)" :key="format" :href="captionFileUrl(snapshot, downloadRevision, format)" download target="_blank" rel="noopener">{{ format === 'json' ? '字幕 JSON' : format.toUpperCase() }}</a><a :href="captionFileUrl(snapshot, downloadRevision, 'package')" download target="_blank" rel="noopener">原视频＋字幕 ZIP</a></div><p v-if="dirty" class="caption-help">当前未保存修改不包含在下载内容中。</p></template><p v-else class="caption-help">保存并确认字幕后，可下载 SRT、VTT、TXT 和原视频素材包。</p></details></div></details>
        <section class="caption-editor"><div class="caption-list-toolbar"><strong>{{ searchQuery.trim() || onlyReview ? `显示 ${filtered.length} / ${cues.length}` : cues.length }} 条字幕</strong><label><input v-model="onlyReview" type="checkbox">只看待核对 {{ requiresReview }}</label><button v-if="deleted.length" class="caption-link" :disabled="pending" @click="undoDelete">撤销删除</button></div>
          <CaptionTextTools v-model:query="searchQuery" v-model:ignore-case="searchIgnoreCase" :cues="cues" :scope-ids="textScopeIds" :pending="pending" :active="props.active && view === 'edit'" :context-key="textContextKey" :draft-key="textDraftKey" :undo-available="undoTextAvailable" @apply="applyTextBatch" @undo="undoTextBatch" @locate="locateCue" @expanded="textToolsExpanded = $event" />
          <details v-if="requiresReview && !textToolsExpanded" class="caption-warning"><summary>{{ requiresReview }} 条边界截断句待核对</summary><p>这些字幕来自被剪断的原句。修改文字不会自动解除标记，请回看后勾选“已核对”。</p></details><div v-show="!textToolsExpanded" ref="cueList" class="caption-cue-list"><p v-if="!visibleCues.length" class="caption-empty">{{ cues.length ? '没有符合当前搜索和复核筛选的字幕。' : '当前版本没有字幕；确认空字幕版本前，请核对这是否符合预期。' }}</p><article v-for="cue in visibleCues" :key="cue.id" :data-caption-id="cue.id" tabindex="-1" class="caption-cue" :class="{ selected: selectedId === cue.id || currentCue?.id === cue.id }"><div class="caption-cue-heading"><button class="caption-link" @click="previewCue(cue)">回看本句 {{ captionTime(cue.start_ms) }}</button><span v-if="cue.boundary_partial" class="caption-partial">{{ cue.reviewed ? '截断句已核对' : '边界截断待核对' }}</span><button class="caption-link danger" :disabled="pending" :aria-label="`删除字幕 ${cue.id}`" @click="removeCue(cue)">删除</button></div><label class="caption-text-label">字幕文字<textarea v-model="cue.text" rows="2" maxlength="2000" :disabled="pending" :aria-label="`字幕文字 ${cue.id}`" @input="acknowledged = false"></textarea></label><div class="caption-times"><label>开始<input :value="timeValue(cue, 'start_ms')" maxlength="128" :disabled="pending" :aria-label="`字幕开始 ${cue.id}`" @input="editTime(cue, 'start_ms', $event)" @blur="normalizeTime(cue, 'start_ms')"></label><label>结束<input :value="timeValue(cue, 'end_ms')" maxlength="128" :disabled="pending" :aria-label="`字幕结束 ${cue.id}`" @input="editTime(cue, 'end_ms', $event)" @blur="normalizeTime(cue, 'end_ms')"></label></div><label v-if="cue.boundary_partial" class="caption-reviewed"><input v-model="cue.reviewed" type="checkbox" :disabled="pending" @change="acknowledged = false">我已回看并核对这条截断句</label><details class="caption-original"><summary>原始转写参考</summary><p>{{ cue.source_text }}</p></details></article></div><div v-if="pages > 1 && !textToolsExpanded" class="caption-pagination"><button class="caption-button" :disabled="page === 0" @click="page--">上一页</button><span>{{ page + 1 }} / {{ pages }} · 每页 {{ pageSize }} 条</span><button class="caption-button" :disabled="page + 1 >= pages" @click="page++">下一页</button></div></section>
      </div>
      <footer class="caption-footer">
        <p v-if="validation" class="caption-validation" role="alert">{{ validation }}</p>
        <div class="caption-footer-main">
          <div class="caption-footer-state">
            <strong>{{ dirty ? '有未保存字幕修改' : `字幕 r${snapshot.revision} ${snapshot.status === 'CONFIRMED' ? '已确认' : '待确认'}` }}</strong>
            <label class="caption-ack"><input v-model="acknowledged" type="checkbox" :disabled="pending || dirty || !!requiresReview || !!validation || snapshot.status === 'CONFIRMED'">{{ cues.length ? '我已核对字幕文字与时间' : '本版本没有字幕，仍确认空字幕版本' }}</label>
          </div>
          <div class="caption-footer-actions">
            <div class="caption-primary-actions">
              <button class="caption-button" :disabled="pending || !dirty || (!saveNeedsCheck && !!validation)" @click="save">{{ action === 'save' ? '正在保存…' : saveNeedsCheck ? '核对保存结果' : '保存字幕' }}</button>
              <button class="caption-button primary" :disabled="!canConfirm" @click="confirm">{{ action === 'confirm' ? '正在确认…' : '确认字幕版本' }}</button>
            </div>
            <details class="caption-secondary-actions"><summary>版本与草稿操作</summary><div><button class="caption-link" :disabled="pending" @click="reload">核对服务器版本</button><button v-if="dirty" class="caption-link" :disabled="pending" @click="discard">放弃未保存修改</button></div></details>
          </div>
        </div>
      </footer>
      </div>
      <div :id="`caption-page-proofread-${exportId}-${clipId}`" v-show="view === 'proofread'" class="caption-render-page" role="tabpanel" :aria-labelledby="`caption-tab-proofread-${exportId}-${clipId}`"><CaptionProofreadPanel ref="proofreadEditor" :snapshot="snapshot" :draft-cues="cues" :caption-dirty="dirty" :caption-pending="!!action || !!renderEditor?.pending" :active="props.active && view === 'proofread'" @apply="applyAiSuggestions" /></div>
      <div :id="`caption-page-renders-${exportId}-${clipId}`" v-show="view === 'renders'" class="caption-render-page" role="tabpanel" :aria-labelledby="`caption-tab-renders-${exportId}-${clipId}`"><CaptionRenderPanel ref="renderEditor" :snapshot="snapshot" :caption-dirty="dirty" :caption-pending="!!action" :active="props.active && view === 'renders'" /></div>
    </template>
  </dialog>
</template>

<style scoped>
.caption-dialog { box-sizing: border-box; width: min(1200px, calc(100vw - 32px)); height: min(860px, calc(100dvh - 32px)); max-width: none; max-height: none; margin: auto; padding: 0; border: 1px solid #cbd5e1; border-radius: 12px; background: white; color: #1f2937; font: 14px/1.65 system-ui, sans-serif; overflow: hidden; }
.caption-dialog[open] { display: flex; flex-direction: column; }.caption-dialog:not([open]) { display: none; }.caption-dialog::backdrop { background: #0f172a66; }
.caption-view-tabs { display: flex; flex: none; gap: 22px; padding: 0 18px; border-bottom: 1px solid #e2e8f0; background: white; }.caption-view-tabs > button { position: relative; min-height: 42px; padding: 10px 1px 8px; border: 0; border-bottom: 2px solid transparent; background: none; color: #64748b; font: inherit; font-size: 14px; cursor: pointer; }.caption-view-tabs > button[aria-selected=true] { border-color: #e47650; color: #bd5735; font-weight: 600; }.caption-draft-dot { display: inline-block; width: 6px; height: 6px; margin-left: 6px; border-radius: 50%; background: #d99a45; vertical-align: middle; }.caption-edit-page, .caption-render-page { display: flex; flex-direction: column; flex: 1; min-width: 0; min-height: 0; overflow: hidden; }
.caption-header { display: flex; flex: none; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 18px; border-bottom: 1px solid #e2e8f0; }.caption-header > div { min-width: 0; }.caption-header > button { flex: none; }.caption-header h2 { margin: 0; font-size: 18px; }.caption-header p { margin: 4px 0 0; color: #64748b; font-size: 14px; overflow-wrap: anywhere; }.caption-header p span { white-space: nowrap; }
.caption-message { flex: none; max-height: 70px; overflow: auto; margin: 8px 20px; padding: 9px 12px; background: #f1f5f9; border-radius: 6px; color: #475569; }.caption-message.error { background: #fff1eb; color: #9a4f32; }
.caption-main { flex: 1; display: grid; grid-template-columns: minmax(240px, .85fr) minmax(0, 1.35fr); min-height: 0; min-width: 0; gap: 16px; padding: 14px 18px; }.caption-watch { min-height: 0; min-width: 0; overflow-y: auto; overflow-x: hidden; padding-right: 3px; }.caption-video { position: relative; min-width: 0; max-width: 100%; background: #0f172a; border-radius: 7px; overflow: hidden; }.caption-video video { display: block; box-sizing: border-box; width: 100%; min-width: 0; max-width: 100%; max-height: 250px; aspect-ratio: 16/9; object-fit: contain; }.caption-overlay { pointer-events: none; position: absolute; left: 6%; right: 6%; bottom: 40px; margin: 0; padding: 4px 7px; background: #0008; color: white; font-size: 16px; text-align: center; white-space: pre-wrap; line-height: 1.5; text-shadow: 0 1px 2px black; overflow-wrap: anywhere; }.caption-seek { display: block; min-width: 0; color: #475569; font-size: 14px; margin-top: 10px; overflow-wrap: anywhere; }.caption-seek input { display: block; box-sizing: border-box; width: 100%; min-width: 0; max-width: 100%; margin: 7px 0 0; padding: 0; accent-color: #e47650; }.caption-help { font-size: 14px; color: #64748b; line-height: 1.7; margin: 8px 0; overflow-wrap: anywhere; }.caption-guide { margin-top: 10px; color: #64748b; font-size: 14px; }.caption-guide summary { cursor: pointer; padding: 4px 0; }
.caption-editor { display: flex; flex-direction: column; min-height: 0; min-width: 0; }.caption-list-toolbar { flex: none; display: flex; align-items: center; flex-wrap: wrap; gap: 8px 14px; padding-bottom: 10px; }.caption-list-toolbar strong { font-weight: 600; }.caption-list-toolbar label { color: #475569; font-size: 14px; }.caption-cue-list { flex: 1; min-height: 0; min-width: 0; overflow-y: auto; overscroll-behavior: contain; padding-right: 6px; scrollbar-gutter: stable; }.caption-cue { min-width: 0; border: 1px solid #e2e8f0; border-radius: 8px; padding: 12px; margin-bottom: 10px; }.caption-cue.selected { border-color: #e6b7a4; background: #fffcfa; }.caption-cue-heading { display: flex; align-items: center; flex-wrap: wrap; min-width: 0; gap: 4px 10px; margin-bottom: 8px; }.caption-cue-heading .danger { margin-left: auto; color: #a0533b; }.caption-partial { color: #8b632f; background: #fff5e4; border-radius: 4px; padding: 2px 6px; font-size: 12px; }.caption-text-label, .caption-times label { display: block; min-width: 0; color: #475569; font-size: 14px; }.caption-cue textarea, .caption-times input { display: block; box-sizing: border-box; width: 100%; min-width: 0; max-width: 100%; border: 1px solid #cbd5e1; border-radius: 5px; background: white; color: #1f2937; font: inherit; font-size: 14px; padding: 8px 10px; margin-top: 5px; }.caption-cue textarea { resize: vertical; line-height: 1.75; }.caption-times { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 10px; margin-top: 10px; }.caption-reviewed { display: block; margin-top: 10px; color: #8b6335; font-size: 14px; line-height: 1.7; }.caption-original { margin-top: 8px; font-size: 14px; color: #64748b; }.caption-original summary { cursor: pointer; padding: 4px 0; }.caption-original p { margin: 7px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }.caption-warning { flex: none; margin: 0 0 10px; padding: 8px 10px; color: #8b632f; background: #fff7e9; border-radius: 5px; font-size: 14px; line-height: 1.6; }.caption-empty { padding: 20px; color: #64748b; }.caption-pagination { display: flex; flex: none; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; padding-top: 8px; color: #64748b; font-size: 14px; }
.caption-downloads { margin-top: 12px; padding-top: 10px; border-top: 1px solid #e2e8f0; }.caption-downloads h3 { font-size: 14px; margin: 0 0 8px; }.caption-downloads select { margin-left: 8px; min-height: 34px; padding: 5px 8px; border: 1px solid #cbd5e1; border-radius: 4px; background: white; color: #334155; font-size: 14px; }.caption-download-links { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 10px; }.caption-download-links a { display: inline-flex; align-items: center; min-height: 36px; max-width: 100%; border: 1px solid #dce3ec; border-radius: 5px; padding: 6px 9px; color: #475569; text-decoration: none; font-size: 14px; overflow-wrap: anywhere; }
.caption-footer { flex: none; max-height: 40dvh; overflow-y: auto; padding: 10px 18px; border-top: 1px solid #e2e8f0; background: white; }.caption-footer-main { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; min-width: 0; gap: 10px 16px; }.caption-footer-main > div { min-width: 0; }.caption-footer strong { font-size: 14px; }.caption-ack { display: block; color: #475569; font-size: 14px; line-height: 1.65; margin-top: 5px; overflow-wrap: anywhere; }.caption-footer-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 12px; }.caption-validation { margin: 0 0 8px; color: #a34f30; font-size: 14px; overflow-wrap: anywhere; }.caption-button { min-height: 38px; padding: 8px 12px; border: 1px solid #cbd5e1; border-radius: 6px; background: white; color: #334155; font: inherit; font-size: 14px; line-height: 1.5; cursor: pointer; white-space: normal; }.caption-button.primary { border-color: #e47650; background: #e47650; color: white; }.caption-link { min-height: 32px; max-width: 100%; border: 0; padding: 3px 0; background: none; color: #475569; font: inherit; font-size: 14px; line-height: 1.6; cursor: pointer; white-space: normal; overflow-wrap: anywhere; }.caption-dialog button:disabled { opacity: .5; cursor: not-allowed; }.caption-dialog input[type=checkbox] { accent-color: #e47650; }.caption-dialog button:focus-visible, .caption-dialog a:focus-visible { outline: 2px solid #e89778; outline-offset: 3px; }
.caption-secondary-actions, .caption-primary-actions { display: flex; flex-wrap: wrap; align-items: center; min-width: 0; gap: 8px 12px; }
@media(max-width:760px) { .caption-dialog { width: calc(100vw - 16px); height: calc(100dvh - 16px); border-radius: 8px; }.caption-header { padding: 10px 14px; }.caption-main { display: block; overflow-y: auto; padding: 12px 14px; }.caption-watch { overflow: visible; padding-right: 0; }.caption-video video { max-height: 190px; }.caption-editor { margin-top: 16px; }.caption-cue-list { overflow: visible; padding-right: 0; scrollbar-gutter: auto; }.caption-downloads { margin-bottom: 16px; }.caption-footer { padding: 10px 14px; }.caption-footer-actions { width: 100%; gap: 6px; }.caption-secondary-actions { width: 100%; justify-content: space-between; gap: 4px 10px; }.caption-primary-actions { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); width: 100%; gap: 10px; }.caption-footer-main { gap: 7px; }.caption-footer-main > div:first-child { width: 100%; }.caption-secondary-actions > .caption-link { text-align: left; }.caption-overlay { font-size: 14px; } }
@media(max-width:380px) { .caption-header, .caption-main, .caption-footer { padding-inline: 10px; }.caption-cue { padding: 10px; }.caption-times { gap: 8px; }.caption-times input { padding-inline: 6px; }.caption-header h2 { font-size: 17px; } }
@media(min-width:761px) and (max-height:760px) { .caption-header { padding-block: 10px; }.caption-main { padding-block: 12px; }.caption-video video { max-height: 190px; } }

/* Keep editing space available while secondary tools stay one level deeper. */
.caption-dialog { width: min(1320px, calc(100vw - 32px)); height: min(900px, calc(100dvh - 32px)); border-color: #dce3eb; border-radius: 14px; box-shadow: 0 24px 80px #0f172a26; }
.caption-header { padding: 16px 24px; }
.caption-header h2 { font-size: 20px; line-height: 1.4; }
.caption-header p { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; line-height: 1.5; }
.caption-view-tabs { gap: 28px; padding-inline: 24px; }
.caption-view-tabs > button { min-width: 0; min-height: 46px; line-height: 1.45; }
.caption-message { max-height: 60px; margin: 8px 24px 0; overflow-wrap: anywhere; }
.caption-backup-tools { flex: none; min-width: 0; max-height: min(220px, 25dvh); overflow-y: auto; border-bottom: 1px solid #e2e8f0; background: #f8fafc; }
.caption-backup-tools > summary { padding: 8px 24px; color: #475569; cursor: pointer; }
.caption-backup-tools > summary > span:last-child { margin-left: 12px; color: #64748b; }
.caption-backup-tools :deep(.draft-backup) { max-height: none; padding: 8px 24px 12px; border-bottom: 0; background: transparent; font-size: 14px; }
.caption-backup-tools :deep(.draft-backup-status > span), .caption-backup-tools :deep(.draft-backup-record time) { font-size: 14px; }
.caption-backup-tools :deep(.draft-backup-status) { position: static; background: transparent; }
.caption-main { grid-template-columns: minmax(260px, .75fr) minmax(0, 1.5fr); gap: 24px; padding: 18px 24px; background: #fbfcfe; }
.caption-watch { padding-right: 4px; }
.caption-preview-heading { margin-bottom: 10px; padding: 3px 0 10px; border-bottom: 1px solid #e2e8f0; color: #334155; font-weight: 600; cursor: pointer; }
.caption-preview-heading > span:last-child { float: right; color: #64748b; font-weight: 400; }
.caption-watch-content { min-width: 0; }
.caption-video { border-radius: 9px; }
.caption-video video { max-height: 300px; }
.caption-downloads > summary { padding: 5px 0; margin-bottom: 6px; color: #475569; font-weight: 600; cursor: pointer; }
.caption-list-toolbar strong { margin-right: auto; font-size: 15px; }
.caption-list-toolbar label { display: inline-flex; align-items: center; gap: 5px; }
.caption-editor :deep(.caption-text-tools) { flex-shrink: 0; max-height: none; font-size: 14px; }
.caption-editor :deep(.caption-text-tools.expanded) { flex: 1; min-height: 0; }
.caption-editor :deep(.caption-search-row) { gap: 6px 12px; padding: 8px 10px; }
.caption-editor :deep(.caption-search-field input) { min-height: 36px; }
.caption-editor :deep(.caption-search-count), .caption-editor :deep(.caption-tools-hint), .caption-editor :deep(.caption-replace-actions > span), .caption-editor :deep(.caption-replace-apply > span), .caption-editor :deep(.caption-preview-heading > span), .caption-editor :deep(.caption-preview-pagination), .caption-editor :deep(.caption-text-before > span), .caption-editor :deep(.caption-text-after > span) { font-size: 14px; }
.caption-warning { margin-bottom: 12px; border: 1px solid #f2dfbb; border-radius: 7px; background: #fff9ed; }
.caption-warning summary { cursor: pointer; }
.caption-warning p { margin: 7px 0 0; }
.caption-cue-list { padding-right: 8px; }
.caption-cue { margin-bottom: 12px; padding: 16px; border-color: #dfe6ef; border-radius: 10px; background: #fff; font-weight: 400; }
.caption-cue.selected { border-color: #dca88e; background: #fffdfb; box-shadow: inset 3px 0 #d98a65; }
.caption-cue textarea { min-height: 80px; line-height: 1.8; }
.caption-cue textarea, .caption-times input { padding: 9px 11px; border-radius: 6px; }
.caption-times input { font-variant-numeric: tabular-nums; }
.caption-partial { font-size: 13px; }
.caption-footer { max-height: 30dvh; padding: 12px 24px; overscroll-behavior: contain; border-color: #dce3eb; box-shadow: 0 -6px 18px #0f172a04; }
.caption-footer-main { align-items: start; flex-wrap: nowrap; gap: 12px 24px; }
.caption-footer-state { flex: 1; min-width: 0; padding-top: 2px; }
.caption-ack { display: flex; align-items: start; gap: 6px; margin-top: 6px; }
.caption-ack input { flex: none; margin: 5px 0 0; }
.caption-footer-actions { flex-direction: column; align-items: end; flex: none; gap: 5px; }
.caption-primary-actions { flex-wrap: nowrap; gap: 10px; }
.caption-primary-actions > button { min-width: 116px; }
.caption-secondary-actions { display: block; max-width: 100%; color: #64748b; }
.caption-secondary-actions > summary { padding: 3px 0; cursor: pointer; text-align: right; }
.caption-secondary-actions > div { display: flex; flex-wrap: wrap; justify-content: end; gap: 3px 14px; padding-top: 3px; }
.caption-button { min-height: 40px; padding: 8px 14px; border-radius: 7px; }
.caption-dialog summary:focus-visible, .caption-dialog input:focus-visible, .caption-dialog textarea:focus-visible { outline: 2px solid #e89778; outline-offset: 3px; }
@media(max-width:760px) {
  .caption-dialog { width: calc(100vw - 16px); height: calc(100dvh - 16px); border-radius: 10px; }
  .caption-header { gap: 10px; padding: 12px 14px; }
  .caption-header h2 { font-size: 18px; }
  .caption-header p { -webkit-line-clamp: 1; }
  .caption-view-tabs { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; padding-inline: 14px; }
  .caption-view-tabs > button { min-height: 44px; padding-block: 9px; }
  .caption-backup-tools > summary { padding: 7px 14px; }
  .caption-backup-tools > summary > span:last-child { display: none; }
  .caption-backup-tools :deep(.draft-backup) { padding: 8px 14px 12px; }
  .caption-main { gap: 0; padding: 12px 14px; overscroll-behavior: contain; }
  .caption-watch { overflow: visible; padding: 0; margin-bottom: 12px; border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; }
  .caption-preview-heading { margin: 0; padding: 9px 10px; border-bottom: 0; }
  .caption-watch[open] > .caption-preview-heading { border-bottom: 1px solid #e2e8f0; }
  .caption-watch-content { padding: 10px; }
  .caption-video video { max-height: 210px; }
  .caption-editor { margin-top: 0; }
  .caption-cue-list { padding-right: 0; }
  .caption-cue { padding: 12px; }
  .caption-list-toolbar { gap: 6px 10px; }
  .caption-editor :deep(.caption-text-tools.expanded) { flex: none; }
  .caption-pagination { justify-content: center; }
  .caption-pagination > span { order: -1; width: 100%; text-align: center; }
  .caption-footer { max-height: 30dvh; padding: 9px 14px; }
  .caption-footer-main { flex-direction: column; gap: 8px; }
  .caption-footer-state, .caption-footer-actions, .caption-primary-actions { box-sizing: border-box; width: 100%; }
  .caption-footer-state { padding-top: 0; }
  .caption-ack { margin-top: 3px; }
  .caption-primary-actions { gap: 8px; }
  .caption-primary-actions > button { min-width: 0; }
  .caption-secondary-actions { align-self: start; width: 100%; }
  .caption-secondary-actions > summary { text-align: left; }
  .caption-secondary-actions > div { justify-content: start; }
  .caption-message { max-height: 48px; margin-inline: 14px; padding: 7px 10px; }
}
@media(max-width:380px) {
  .caption-header, .caption-main, .caption-footer { padding-inline: 10px; }
  .caption-view-tabs { padding-inline: 10px; gap: 4px; }
  .caption-backup-tools > summary { padding-inline: 10px; }
  .caption-cue { padding: 10px; }
  .caption-times input { padding-inline: 7px; }
  .caption-button { padding-inline: 10px; }
}
@media(min-width:761px) and (max-height:760px) {
  .caption-header { padding-block: 10px; }
  .caption-header p { -webkit-line-clamp: 1; }
  .caption-main { padding-block: 12px; gap: 20px; }
  .caption-video video { max-height: 210px; }
  .caption-footer { padding-block: 9px; }
  .caption-message { max-height: 48px; }
}
</style>
