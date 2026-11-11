<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, ApiError, type ExportJob, type ExportClip } from './api'
import { AgentReadScope } from './videoAgent'
import { newestSnapshot, parseExportSnapshot } from './snapshotStream'
import { parseTranscriptContext, sameTranscriptSource, transcriptContextUrl, transcriptTime, type TranscriptOwner, type TranscriptSource, type TranscriptUtterance } from './transcriptSearch'
import { ManualClipError, manualClipCanonical, manualClipDetailUrl, manualClipDraftError, manualClipInput, manualClipListUrl, manualClipRangeUrl,
  manualClipRevisionInput, manualClipSubmissionPlanMatches, parseManualClipList, parseManualClipPlan, parseManualClipRange, parseManualClipSubmission,
  verifyManualClipPlan, verifyManualClipSubmission, type ManualClipList, type ManualClipPlan, type ManualClipRangeResult, type ManualClipSubmission } from './manualClips'
import { assertManualClipPending, clearManualClipPending, createManualClipReceiptStore, manualClipReceiptSlot, manualClipSubmissionMissing, manualClipSubmissionUrl,
  manualClipWriteUrl, parseManualClipExportInput, prepareManualClipPending, scanManualClipPending, type ManualClipPending } from './manualClipReceipts'
import ManualClipPreview from './ManualClipPreview.vue'
import CaptionPanel from './CaptionPanel.vue'
import VideoPackagePanel from './VideoPackagePanel.vue'
import FailureDiagnostic from './FailureDiagnostic.vue'

const props = defineProps<{ jobId: string; videoId: string; generation: number; videoHash: string; videoUrl: string; videoName: string; active: boolean }>()
const source = ref<TranscriptSource | null>(null), start = ref<TranscriptUtterance | null>(null), end = ref<TranscriptUtterance | null>(null), title = ref('')
const plan = ref<ManualClipPlan | null>(null), baseline = ref(''), rangeProof = ref<ManualClipRangeResult | null>(null)
const history = ref<ManualClipList | null>(null), historyOffset = ref(0), batches = ref<ExportJob[]>([])
const error = ref(''), notice = ref(''), historyError = ref(''), detailError = ref(''), rangeError = ref(''), exportsError = ref('')
const action = ref(''), listLoading = ref(false), detailLoading = ref(false), rangeLoading = ref(false), exportsLoading = ref(false)
const accepted = ref(false), visible = ref(!document.hidden), pendingEntry = ref<ManualClipPending | null>(null), receiptAbsent = ref(false), storageError = ref('')
const pendingEntries = ref<ManualClipPending[]>([]), recoveredPlan = ref<ManualClipPlan | null>(null), recoveredExport = ref<ExportJob | null>(null)
const confirmNeedsCheck = ref<{ id: string; revision: number; plan_hash: string } | null>(null)
const captionTarget = ref<{ exportId: string; clipId: string; contentUrl: string } | null>(null), captionEditor = ref<{ dirty: boolean; pending: boolean } | null>(null)
const previewEditor = ref<{ stop(): void } | null>(null), deliveryPreview = ref<{ batch: string; clip: string; url: string } | null>(null)
const deliveryPlayer = ref<HTMLVideoElement | null>(null)
const listScope = new AgentReadScope(), detailScope = new AgentReadScope(), rangeScope = new AgentReadScope(), exportScope = new AgentReadScope(), receiptScope = new AgentReadScope()
let alive = true, operation = 0, timer: number | undefined, freshEntry: ManualClipPending | null = null
const owner = (): TranscriptOwner => ({ job_id: props.jobId, video_id: props.videoId, generation: props.generation, source_video_sha256: props.videoHash })
const slot = manualClipReceiptSlot(props.jobId)
const store = createManualClipReceiptStore({ get length() { return localStorage.length }, key: index => localStorage.key(index),
  getItem: key => localStorage.getItem(key), setItem: (key, value) => localStorage.setItem(key, value), removeItem: key => localStorage.removeItem(key) }, props.jobId, props.videoId)
const available = computed(() => alive && props.active && visible.value)
const draftSignature = computed(() => manualClipCanonical({ source: source.value, title: title.value, start: start.value, end: end.value }))
baseline.value = draftSignature.value
const draftDirty = computed(() => draftSignature.value !== baseline.value)
const pending = computed(() => !!action.value || !!captionEditor.value?.pending)
const unresolved = computed(() => !!pendingEntry.value || !!storageError.value || !!confirmNeedsCheck.value)
const dirty = computed(() => draftDirty.value || unresolved.value || !!captionEditor.value?.dirty)
const sourceMismatch = computed(() => !!source.value && (source.value.job_id !== props.jobId || source.value.video_id !== props.videoId
  || source.value.generation !== props.generation || source.value.source_video_sha256 !== props.videoHash))
const draftError = computed(() => manualClipDraftError(source.value, title.value, start.value, end.value))
const rangeKey = computed(() => manualClipCanonical({ source: source.value, start: start.value, end: end.value }))
const proofUsable = computed(() => !sourceMismatch.value && !!rangeProof.value && !!source.value && !!start.value && !!end.value
  && sameTranscriptSource(rangeProof.value.source, source.value) && rangeProof.value.start_utterance_id === start.value.utterance_id
  && rangeProof.value.end_utterance_id === end.value.utterance_id)
const canEdit = computed(() => available.value && !pending.value && !unresolved.value && !captionTarget.value && !detailLoading.value && !sourceMismatch.value)
const canWrite = computed(() => canEdit.value && !draftError.value && proofUsable.value && (!plan.value || plan.value.revision === plan.value.latest_revision) && draftDirty.value)
const canConfirm = computed(() => canEdit.value && !!plan.value && !draftDirty.value && accepted.value && plan.value.status === 'DRAFT' && plan.value.revision === plan.value.latest_revision && proofUsable.value)
const canExport = computed(() => canEdit.value && !!plan.value && !draftDirty.value && plan.value.status === 'CONFIRMED' && proofUsable.value)
const summary = computed(() => ({ title: title.value, start: start.value, end: end.value, hasRange: !!start.value || !!end.value, planId: plan.value?.id ?? null }))
defineExpose({ dirty, pending, unresolved, summary, message: error, setBoundary })
function code(value: unknown) { return value && typeof value === 'object' && 'code' in value ? String(value.code) : '' }
function fail(value: unknown): string {
  const names: Record<string, string> = {
    MANUAL_CLIP_INVALID: '标题或人工方案参数无效，请核对后重试。', MANUAL_CLIP_INVALID_RANGE: '起止原句无效或倒置，请明确调整，不会自动交换。',
    MANUAL_CLIP_SOURCE_CHANGED: '原句来源已变化，当前草稿仍保留，请回原句搜索重新核对来源。', MANUAL_CLIP_SOURCE_INVALID: '原句来源暂未通过校验，当前草稿仍保留。',
    TRANSCRIPT_SOURCE_CHANGED: '转写来源已变化，范围核对未通过。请回原句搜索重新核对。', TRANSCRIPT_SOURCE_INVALID: '转写产物暂未通过校验，范围草稿保留。',
    MANUAL_CLIP_REVISION_CONFLICT: '服务端已有新版本。当前草稿仍保留，请先核对最新版本，不能覆盖旧base。',
    MANUAL_CLIP_CONFIRMATION_REQUIRED: '请先明确确认该人工版本，再生成成片。', MANUAL_CLIP_PENDING_EXISTS: '已有原提交待核对，不能创建新key或替换其内容。',
    MANUAL_CLIP_STORAGE_UNAVAILABLE: '本机回执无法可靠保存或复读，本次没有发起新的提交。请恢复浏览器存储后重试。',
    MANUAL_CLIP_LOCAL_RECEIPT_INVALID: '本机原提交记录无法核验，已保留原记录；不能用新key绕过。',
    IDEMPOTENCY_CONFLICT: '原key与服务端归属或内容冲突，未自动更换key。请保留原记录核对。', MANUAL_CLIP_SUBMISSION_INVALID: '原提交回执未通过验证，不能认领为当前方案。',
    NETWORK_UNAVAILABLE: '暂时无法连接服务。草稿与原提交标识保留。', REQUEST_TIMEOUT: '操作结果可能未知。请先核对原提交，不要新建重复方案。',
  }
  return names[code(value)] ?? '人工方案响应未通过来源或内容核验；本页草稿与原提交仍保留，请重试核对。'
}
function stopMedia() { previewEditor.value?.stop(); deliveryPlayer.value?.pause(); deliveryPreview.value = null }
const sourceFailureCodes = new Set(['TRANSCRIPT_SOURCE_CHANGED', 'TRANSCRIPT_SOURCE_INVALID', 'MANUAL_CLIP_SOURCE_CHANGED', 'MANUAL_CLIP_SOURCE_INVALID'])
function revokeSource(value: unknown) {
  if (!sourceFailureCodes.has(code(value))) return
  rangeScope.invalidate(); rangeLoading.value = false; rangeProof.value = null; accepted.value = false; rangeError.value = fail(value); stopMedia()
}
function setBoundary(field: 'start' | 'end', selectedSource: TranscriptSource, item: TranscriptUtterance): boolean {
  if (!alive || pending.value || unresolved.value || captionTarget.value || detailLoading.value) { error.value = '人工方案仍有提交、回执或字幕需要处理，所选原句未覆盖当前草稿。'; return false }
  const currentOwner = owner()
  if (selectedSource.job_id !== currentOwner.job_id || selectedSource.video_id !== currentOwner.video_id || selectedSource.generation !== currentOwner.generation
    || selectedSource.source_video_sha256 !== currentOwner.source_video_sha256 || source.value && !sameTranscriptSource(source.value, selectedSource)) {
    error.value = '所选原句与人工草稿不是同一完整转写来源。请明确清空草稿或打开对应方案后再选。'; return false
  }
  stopMedia(); rangeScope.invalidate(); rangeLoading.value = false; rangeProof.value = null; rangeError.value = ''; error.value = ''
  source.value = Object.freeze({ ...selectedSource })
  if (field === 'start') start.value = Object.freeze({ ...item }); else end.value = Object.freeze({ ...item })
  if (!title.value) title.value = '人工片段'
  notice.value = `已明确设为${field === 'start' ? '起句' : '止句'}；范围保留在人工草稿，尚未保存。`; return true
}
function resetDraft() {
  if (pending.value || unresolved.value || captionEditor.value?.dirty) return
  stopMedia(); detailScope.invalidate(); rangeScope.invalidate(); exportScope.invalidate(); plan.value = null; source.value = null; start.value = null; end.value = null
  title.value = ''; rangeProof.value = null; batches.value = []; error.value = ''; detailError.value = ''; rangeError.value = ''; accepted.value = false
  baseline.value = draftSignature.value; notice.value = '已明确清空本页人工草稿；已保存方案和成片不变。'
}
function restorePending() {
  try {
    const entries = scanManualClipPending(store, props.jobId, props.videoId); pendingEntries.value = entries
    const own = pendingEntry.value && entries.find(entry => entry.key === pendingEntry.value!.key)
    if (own && pendingEntry.value && manualClipCanonical(own) !== manualClipCanonical(pendingEntry.value)) { storageError.value = '同一原key的本机内容已变化，保留两方信息，不能重发。'; return }
    if (!pendingEntry.value && entries[0]) { pendingEntry.value = entries[0]; freshEntry = null; receiptAbsent.value = false }
    storageError.value = ''
  } catch (value) { storageError.value = fail(value) }
}
function finishOwnEntry(entry: ManualClipPending) {
  if (!clearManualClipPending(store, entry) && store.read(entry) !== null) throw new ManualClipError('MANUAL_CLIP_LOCAL_RECEIPT_INVALID')
  if (pendingEntry.value?.key === entry.key) pendingEntry.value = null
  freshEntry = null; receiptAbsent.value = false; restorePending()
}
async function readHistory(offset = historyOffset.value) {
  if (!available.value) return
  listScope.invalidate(); const read = listScope.begin(); listLoading.value = true; historyError.value = ''
  try {
    const value = parseManualClipList(await api<unknown>(manualClipListUrl(props.jobId, offset, 20), { signal: read.signal }), owner(), { limit: 20, offset })
    if (available.value && read.current()) { history.value = value; historyOffset.value = offset }
  } catch (value) { if (available.value && read.current()) historyError.value = fail(value) }
  finally { if (read.current()) listLoading.value = false; read.release() }
}
async function endpoints(value: ManualClipPlan, signal: AbortSignal): Promise<[TranscriptUtterance, TranscriptUtterance]> {
  async function read(id: string) {
    const raw = await api<unknown>(transcriptContextUrl(owner(), value.source, id), { signal })
    if (!raw || typeof raw !== 'object' || !('center' in raw) || !raw.center || typeof raw.center !== 'object' || !('utterance_id' in raw.center) || raw.center.utterance_id !== id) throw new ManualClipError()
    // parseTranscriptContext validates the entire network center; the immutable manual range binds its exposed boundary/text.
    return parseTranscriptContext(raw, owner(), value.source, raw.center as TranscriptUtterance).center
  }
  const first = await read(value.start_utterance_id), last = value.start_utterance_id === value.end_utterance_id ? first : await read(value.end_utterance_id)
  if (first.start_ms !== value.range.start_ms || first.text !== value.range.start_text || last.end_ms !== value.range.end_ms || last.text !== value.range.end_text) throw new ManualClipError()
  return [first, last]
}
function adopt(value: ManualClipPlan, first: TranscriptUtterance, last: TranscriptUtterance) {
  stopMedia(); plan.value = value; source.value = value.source; title.value = value.title; start.value = first; end.value = last
  rangeProof.value = { schema_version: '1.0', data_kind: 'transcript_range', source: value.source, start_utterance_id: value.start_utterance_id, end_utterance_id: value.end_utterance_id, range: value.range }
  baseline.value = draftSignature.value; accepted.value = false; detailError.value = ''; rangeError.value = ''; confirmNeedsCheck.value = null
}
async function loadPlan(id: string, revision?: number, explicitDiscard = false) {
  if (!available.value || pending.value || unresolved.value || captionEditor.value?.dirty || draftDirty.value && !explicitDiscard) { error.value = '请先保存或明确清空人工草稿，并核对原提交后再打开其他版本。'; return }
  stopMedia(); detailScope.invalidate(); const read = detailScope.begin(); detailLoading.value = true; detailError.value = ''; const before = draftSignature.value
  try {
    const value = await verifyManualClipPlan(parseManualClipPlan(await api<unknown>(manualClipDetailUrl(id, revision), { signal: read.signal }), owner(), { id, ...(revision ? { revision } : {}) }))
    const [first, last] = await endpoints(value, read.signal)
    if (!available.value || !read.current() || before !== draftSignature.value) return
    adopt(value, first, last); batches.value = []; notice.value = value.latest_revision > value.revision ? `正在查看原修订 r${value.revision}；最新为 r${value.latest_revision}，不会自动采用。` : `已核对人工方案 r${value.revision}。`
    void refreshExports()
  } catch (value) { if (available.value && read.current() && before === draftSignature.value) { if (plan.value?.id === id) revokeSource(value); detailError.value = fail(value) } }
  finally { if (read.current()) detailLoading.value = false; read.release() }
}
async function checkRange() {
  if (!canEdit.value || draftError.value || !source.value || !start.value || !end.value) return
  stopMedia(); rangeScope.invalidate(); const read = rangeScope.begin(), key = rangeKey.value; rangeLoading.value = true; rangeError.value = ''; rangeProof.value = null
  try {
    const value = parseManualClipRange(await api<unknown>(manualClipRangeUrl(source.value, start.value.utterance_id, end.value.utterance_id), { signal: read.signal }), owner(), source.value, start.value, end.value)
    if (available.value && read.current() && key === rangeKey.value) { rangeProof.value = value; notice.value = `已核对完整范围 ${value.range.utterance_count} 句；尚未保存或确认。` }
  } catch (value) { if (available.value && read.current() && key === rangeKey.value) rangeError.value = fail(value) }
  finally { if (read.current()) rangeLoading.value = false; read.release() }
}
async function verifyOriginal(entry: ManualClipPending, raw: unknown) {
  if (entry.operation === 'EXPORT') {
    const body = parseManualClipExportInput(JSON.parse(entry.input))
    if (!raw || typeof raw !== 'object' || !('id' in raw) || typeof raw.id !== 'string') throw new ManualClipError()
    const batch = parseExportSnapshot(raw, raw.id, props.jobId)
    if (batch.manual_clip_plan_id !== entry.plan_id || batch.video_id !== props.videoId || batch.approved_revision !== body.revision) throw new ManualClipError()
    return batch
  }
  return verifyManualClipSubmission(parseManualClipSubmission(raw, entry), entry)
}
async function acceptOriginal(entry: ManualClipPending, value: ManualClipSubmission | ExportJob) {
  if (!available.value || pendingEntry.value?.key !== entry.key) return
  const read = receiptScope.begin()
  try {
    if (entry.operation === 'EXPORT') {
      const body = parseManualClipExportInput(JSON.parse(entry.input)), batch = value as ExportJob
      const exact = await verifyManualClipPlan(parseManualClipPlan(await api<unknown>(manualClipDetailUrl(entry.plan_id!, body.revision), { signal: read.signal }), owner(), { id: entry.plan_id!, revision: body.revision }))
      if (exact.plan_hash !== body.plan_hash || !exact.confirmed_revisions.includes(body.revision) || batch.clips.some(clip => {
        const extra = clip as ExportClip & { parts?: Array<{ start_ms: number; end_ms: number }>; duration_ms?: number }
        return clip.start_ms !== exact.range.start_ms || clip.end_ms !== exact.range.end_ms || clip.title !== exact.title
          || extra.duration_ms !== undefined && extra.duration_ms !== exact.range.end_ms - exact.range.start_ms
          || extra.parts !== undefined && (!Array.isArray(extra.parts) || extra.parts.length !== 1 || extra.parts[0]?.start_ms !== exact.range.start_ms || extra.parts[0]?.end_ms !== exact.range.end_ms)
      })) throw new ManualClipError()
      if (!available.value || !read.current() || pendingEntry.value?.key !== entry.key) return
      if (plan.value?.id === entry.plan_id) mergeBatch(batch); else recoveredExport.value = batch
      finishOwnEntry(entry); notice.value = `已核对原导出 #${batch.id.slice(0, 8)}，可查看真实执行状态。`
    } else {
      const submission = value as ManualClipSubmission
      const exact = await verifyManualClipPlan(parseManualClipPlan(await api<unknown>(manualClipDetailUrl(submission.plan_id, submission.revision), { signal: read.signal }), owner(), { id: submission.plan_id, revision: submission.revision }))
      if (!manualClipSubmissionPlanMatches(exact, submission)) throw new ManualClipError()
      const [first, last] = await endpoints(exact, read.signal)
      if (!available.value || !read.current() || pendingEntry.value?.key !== entry.key) return
      const matchesDraft = !draftDirty.value || !!source.value && sameTranscriptSource(source.value, exact.source) && title.value === submission.input.title
        && start.value?.utterance_id === submission.input.start_utterance_id && end.value?.utterance_id === submission.input.end_utterance_id
      if (matchesDraft) adopt(exact, first, last); else recoveredPlan.value = exact
      finishOwnEntry(entry); notice.value = !matchesDraft ? `原提交 r${exact.revision} 已核对；本页不同的人工草稿仍保留，未替换。` : exact.latest_revision > exact.revision
        ? `已核对原提交 r${exact.revision}；最新已到 r${exact.latest_revision}，请明确核对后采用。` : `人工方案 r${exact.revision} 已保存，尚需明确确认。`
      void readHistory(); void refreshExports()
    }
  } finally { read.release() }
}
const rollbackCodes = new Set(['MANUAL_CLIP_INVALID', 'MANUAL_CLIP_INVALID_RANGE', 'MANUAL_CLIP_SOURCE_CHANGED', 'MANUAL_CLIP_SOURCE_INVALID', 'MANUAL_CLIP_REVISION_CONFLICT', 'MANUAL_CLIP_CONFIRMATION_REQUIRED'])
async function sendOriginal(entry: ManualClipPending, firstAttempt: boolean) {
  const version = ++operation; action.value = entry.operation === 'EXPORT' ? 'export' : 'save'; error.value = ''; stopMedia()
  try {
    assertManualClipPending(store, entry)
    const raw = await api<unknown>(manualClipWriteUrl(entry), { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': entry.key }, body: entry.input })
    const value = await verifyOriginal(entry, raw); freshEntry = null
    if (alive && version === operation && available.value) await acceptOriginal(entry, value)
    else if (alive && version === operation) notice.value = '原提交已返回；回到人工剪辑后只读核对该修订，不会采用新head。'
  } catch (value) {
    if (!alive || version !== operation) return
    const input = JSON.parse(entry.input)
    if (input.source_fingerprint === source.value?.source_fingerprint || entry.operation === 'EXPORT' && entry.plan_id === plan.value?.id) revokeSource(value)
    if (firstAttempt && freshEntry === entry && value instanceof ApiError && [400, 409].includes(value.status ?? 0) && rollbackCodes.has(value.code)) {
      try { finishOwnEntry(entry) } catch (storage) { storageError.value = fail(storage) }
    } else freshEntry = null
    error.value = fail(value)
  } finally { if (version === operation) action.value = '' }
}
async function saveDraft() {
  if (!canWrite.value || !source.value) return
  restorePending(); if (unresolved.value) return
  try {
    const input = manualClipInput(source.value, title.value, start.value, end.value), operation = plan.value ? 'REVISION' : 'CREATE'
    const body = plan.value ? manualClipRevisionInput(plan.value, input) : input
    const entry = prepareManualClipPending(store, props.jobId, props.videoId, operation, plan.value?.id ?? null, body, () => crypto.randomUUID())
    pendingEntry.value = entry; freshEntry = entry; receiptAbsent.value = false; await sendOriginal(entry, true)
  } catch (value) { error.value = fail(value); restorePending() }
}
async function checkOriginal() {
  if (!available.value || pending.value) return
  restorePending(); const entry = pendingEntry.value; if (!entry) return
  const version = ++operation, read = receiptScope.begin(); action.value = 'check'; error.value = ''; receiptAbsent.value = false; freshEntry = null
  try {
    const value = await verifyOriginal(entry, await api<unknown>(manualClipSubmissionUrl(entry), { signal: read.signal }))
    if (available.value && read.current() && version === operation) await acceptOriginal(entry, value)
  } catch (value) {
    if (available.value && read.current() && version === operation) {
      if (manualClipSubmissionMissing(value, entry)) { receiptAbsent.value = true; notice.value = '已核对原业务提交确切不存在；可明确按原key和原内容重试。' }
      else { const input = JSON.parse(entry.input); if (input.source_fingerprint === source.value?.source_fingerprint || entry.operation === 'EXPORT' && entry.plan_id === plan.value?.id) revokeSource(value); error.value = fail(value) }
    }
  } finally { if (version === operation) action.value = ''; read.release() }
}
function retryOriginal() { if (available.value && !pending.value && receiptAbsent.value && pendingEntry.value) { receiptAbsent.value = false; void sendOriginal(pendingEntry.value, false) } }
function selectOriginal(entry: ManualClipPending) { if (pending.value || !available.value) return; pendingEntry.value = entry; freshEntry = null; receiptAbsent.value = false; void checkOriginal() }
async function confirmPlan() {
  if (!canConfirm.value || !plan.value) return
  const original = plan.value, version = ++operation; action.value = 'confirm'; error.value = ''; stopMedia()
  confirmNeedsCheck.value = { id: original.id, revision: original.revision, plan_hash: original.plan_hash }
  try {
    const value = await verifyManualClipPlan(parseManualClipPlan(await api<unknown>(`/api/manual-clip-plans/${original.id}/confirm`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ revision: original.revision, plan_hash: original.plan_hash, accept_sentence_boundaries: true }) }), owner(), { id: original.id, revision: original.revision, source: original.source }))
    if (value.plan_hash !== original.plan_hash || value.status !== 'CONFIRMED') throw new ManualClipError()
    if (alive && version === operation) { plan.value = value; confirmNeedsCheck.value = null; accepted.value = false; notice.value = `人工 r${value.revision} 已确认；还未创建导出。` }
  } catch (value) { if (alive && version === operation) { revokeSource(value); if (value instanceof ApiError && [400, 409].includes(value.status ?? 0) && rollbackCodes.has(value.code)) confirmNeedsCheck.value = null; error.value = fail(value) } }
  finally { if (version === operation) action.value = '' }
}
async function checkConfirmation() {
  const original = confirmNeedsCheck.value
  if (!available.value || pending.value || !original) return
  const read = detailScope.begin(); detailLoading.value = true
  try {
    const value = await verifyManualClipPlan(parseManualClipPlan(await api<unknown>(manualClipDetailUrl(original.id, original.revision), { signal: read.signal }), owner(), { id: original.id, revision: original.revision }))
    if (value.plan_hash !== original.plan_hash) throw new ManualClipError()
    if (!available.value || !read.current()) return
    if (plan.value?.id === original.id && plan.value.revision === original.revision) plan.value = value
    confirmNeedsCheck.value = null; notice.value = value.status === 'CONFIRMED' ? '已核对原版本确认成功。' : '已核对原版本尚未确认，可再次明确确认该版本。'
  } catch (value) { if (available.value && read.current()) { revokeSource(value); error.value = fail(value) } }
  finally { if (read.current()) detailLoading.value = false; read.release() }
}
async function createExport() {
  if (!canExport.value || !plan.value) return
  restorePending(); if (unresolved.value) return
  try {
    const entry = prepareManualClipPending(store, props.jobId, props.videoId, 'EXPORT', plan.value.id,
      { revision: plan.value.revision, plan_hash: plan.value.plan_hash, profile: 'h264-aac-v1' }, () => crypto.randomUUID())
    pendingEntry.value = entry; freshEntry = entry; receiptAbsent.value = false; await sendOriginal(entry, true)
  } catch (value) { error.value = fail(value); restorePending() }
}
function mergeBatch(value: ExportJob) { const index = batches.value.findIndex(batch => batch.id === value.id); if (index < 0) batches.value.unshift(value); else batches.value[index] = newestSnapshot(batches.value[index], value) }
async function refreshExports() {
  const id = plan.value?.id; if (!available.value || !id) return
  exportScope.invalidate(); const read = exportScope.begin(); exportsLoading.value = true; exportsError.value = ''
  try {
    const raw = await api<unknown>(`/api/manual-clip-plans/${id}/exports`, { signal: read.signal })
    if (!raw || typeof raw !== 'object' || !('items' in raw) || !Array.isArray(raw.items) || !('limit' in raw) || raw.limit !== 20 || !('offset' in raw) || raw.offset !== 0 || raw.items.length > 20) throw new ManualClipError()
    const values = raw.items.map(item => { if (!item || typeof item !== 'object' || !('id' in item) || typeof item.id !== 'string') throw new ManualClipError(); const batch = parseExportSnapshot(item, item.id, props.jobId); if (batch.manual_clip_plan_id !== id || batch.video_id !== props.videoId) throw new ManualClipError(); return batch })
    if (new Set(values.map(value => value.id)).size !== values.length) throw new ManualClipError()
    if (available.value && read.current() && plan.value?.id === id) values.forEach(mergeBatch)
  } catch (value) { if (available.value && read.current() && plan.value?.id === id) { revokeSource(value); exportsError.value = fail(value) } }
  finally { if (read.current()) exportsLoading.value = false; read.release() }
}
async function retryClip(batch: ExportJob, clip: ExportClip) {
  if (!canEdit.value || clip.status !== 'FAILED') return
  const version = ++operation; action.value = 'retry-clip'; error.value = ''
  try {
    const value = parseExportSnapshot(await api<unknown>(`/api/exports/${batch.id}/clips/${clip.id}/retry`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' }), batch.id, props.jobId)
    if (value.manual_clip_plan_id !== batch.manual_clip_plan_id) throw new ManualClipError()
    if (alive && version === operation) mergeBatch(value)
  } catch (value) { if (alive && version === operation) error.value = fail(value) }
  finally { if (version === operation) action.value = '' }
}
function openCaptions(batch: ExportJob, clip: ExportClip) {
  if (!canEdit.value || clip.status !== 'SUCCEEDED' || !clip.content_url) return
  stopMedia(); captionTarget.value = { exportId: batch.id, clipId: clip.id, contentUrl: clip.content_url }
}
function openDelivery(batch: ExportJob, clip: ExportClip) { if (canEdit.value && clip.status === 'SUCCEEDED' && clip.content_url) { stopMedia(); deliveryPreview.value = { batch: batch.id, clip: clip.id, url: clip.content_url } } }
function guardDelivery(event: Event) { if (event.currentTarget !== deliveryPlayer.value || !available.value || sourceMismatch.value || captionTarget.value || !deliveryPreview.value) (event.currentTarget as HTMLVideoElement).pause() }
function cancelReads() {
  for (const scope of [listScope, detailScope, rangeScope, exportScope, receiptScope]) scope.invalidate()
  listLoading.value = false; detailLoading.value = false; rangeLoading.value = false; exportsLoading.value = false
}
function schedule() { window.clearTimeout(timer); if (!available.value) return; timer = window.setTimeout(async () => { await refreshExports(); schedule() }, batches.value.some(batch => ['QUEUED', 'RUNNING'].includes(batch.status)) ? 5000 : 60000) }
function availability() {
  visible.value = !document.hidden
  if (!available.value) { cancelReads(); stopMedia(); window.clearTimeout(timer); return }
  restorePending(); void readHistory()
  if (!pending.value && pendingEntry.value) void checkOriginal()
  else if (!pending.value && confirmNeedsCheck.value) void checkConfirmation()
  else void refreshExports()
  schedule()
}
watch(() => props.active, availability, { immediate: true, flush: 'sync' })
watch(rangeKey, () => { rangeScope.invalidate(); rangeLoading.value = false; rangeProof.value = null; accepted.value = false; stopMedia() }, { flush: 'sync' })
watch(draftSignature, () => { accepted.value = false }, { flush: 'sync' })
watch(sourceMismatch, mismatch => { if (mismatch) { cancelReads(); stopMedia(); error.value = '原视频来源已变化，本页人工草稿保留为只读；不能转移到新来源。' } }, { flush: 'sync' })
function unload(event: BeforeUnloadEvent) { if (dirty.value || pending.value) { event.preventDefault(); event.returnValue = '' } }
function storageChanged(event: StorageEvent) { if (event.key?.startsWith(slot) || event.key === null) restorePending() }
onMounted(() => { restorePending(); document.addEventListener('visibilitychange', availability); window.addEventListener('storage', storageChanged); window.addEventListener('beforeunload', unload) })
onBeforeUnmount(() => { alive = false; operation++; stopMedia(); window.clearTimeout(timer); for (const scope of [listScope, detailScope, rangeScope, exportScope, receiptScope]) scope.dispose(); document.removeEventListener('visibilitychange', availability); window.removeEventListener('storage', storageChanged); window.removeEventListener('beforeunload', unload) })
</script>

<template>
  <section class="manual-clip-panel" aria-label="按原句人工剪辑">
    <header><div><h2>人工剪辑</h2><p>一个连续区间生成一个成片；直接使用既有 ASR，不请求 AI 生成候选。</p></div><button class="text-button" :disabled="pending || unresolved || !!captionEditor?.dirty" @click="resetDraft">清空本页人工草稿</button></header>
    <p v-if="error" class="manual-error" role="alert">{{ error }}</p><p v-if="notice" class="manual-notice" role="status">{{ notice }}</p><p v-if="storageError" class="manual-error" role="alert">{{ storageError }}<button class="text-button" :disabled="pending" @click="restorePending">重新核对本机记录</button></p>
    <section v-if="pendingEntry" class="manual-receipt" aria-label="人工原提交待核对"><strong>原提交待核对 · {{ pendingEntry.operation }} · #{{ pendingEntry.key.slice(0, 8) }}</strong><p>{{ pendingEntry.input }}</p><p>原 key 和内容保留；回执不作为确认或导出凭据。</p><button class="secondary" :disabled="!available || pending" @click="checkOriginal">核对原提交</button><button v-if="receiptAbsent" class="secondary" :disabled="!available || pending" @click="retryOriginal">按原内容重试</button><div v-if="pendingEntries.length > 1"><p>本机保留 {{ pendingEntries.length }} 条独立原提交，请逐条核对。</p><button v-for="entry in pendingEntries" :key="entry.key" class="secondary" :disabled="!available || pending || entry.key === pendingEntry.key" @click="selectOriginal(entry)">核对 {{ entry.operation }} #{{ entry.key.slice(-8) }}</button></div></section>
    <section v-if="recoveredPlan" class="manual-receipt"><p>原提交的人工 r{{ recoveredPlan.revision }} 已核对。本页不同草稿仍保留；明确清空或保存草稿后可打开。</p><button class="secondary" :disabled="!available || pending || unresolved || draftDirty" @click="loadPlan(recoveredPlan.id, recoveredPlan.revision)">打开已核对原提交版本</button></section>
    <section v-if="recoveredExport" class="manual-receipt"><p>原导出 #{{ recoveredExport.id.slice(0, 8) }} 已核对。本页草稿未替换，打开对应人工版本后可查看交付状态。</p><button class="secondary" :disabled="!available || pending || unresolved || draftDirty" @click="loadPlan(recoveredExport.manual_clip_plan_id!, recoveredExport.approved_revision)">打开原导出所属版本</button></section>
    <section v-if="confirmNeedsCheck" class="manual-receipt"><p>人工 r{{ confirmNeedsCheck.revision }} 的确认结果待核对，未自动再次确认或导出。</p><button class="secondary" :disabled="!available || pending || detailLoading" @click="checkConfirmation">核对确认结果</button></section>
    <div class="manual-layout"><div class="manual-editor">
      <p v-if="plan" class="manual-version">人工方案 #{{ plan.id.slice(0, 8) }} · r{{ plan.revision }} · {{ plan.status === 'CONFIRMED' ? '该版本已确认' : '尚未确认' }}<span v-if="draftDirty"> · 本页有修改</span></p>
      <div v-if="plan && plan.latest_revision > plan.revision" class="manual-receipt"><p>当前为已核对 r{{ plan.revision }}；最新为 r{{ plan.latest_revision }}。旧提交不会自动认领新head。</p><button class="secondary" :disabled="!available || pending || unresolved || detailLoading" @click="loadPlan(plan.id, undefined, true)">{{ draftDirty ? '放弃本页修改并采用最新版本' : '读取并采用最新版本' }}</button></div>
      <label class="manual-title">成片标题<input v-model="title" maxlength="120" :disabled="!canEdit" aria-label="人工成片标题" placeholder="为人工片段填写标题"></label>
      <div class="manual-boundaries"><article><h3>起句</h3><p v-if="start">{{ transcriptTime(start.start_ms, true) }} → {{ transcriptTime(start.end_ms, true) }}</p><blockquote>{{ start?.text ?? '回到搜索与回看，选择原句后明确设为起句。' }}</blockquote></article><article><h3>止句</h3><p v-if="end">{{ transcriptTime(end.start_ms, true) }} → {{ transcriptTime(end.end_ms, true) }}</p><blockquote>{{ end?.text ?? '可以跨页或换搜索词，再明确设为止句。' }}</blockquote></article></div>
      <p v-if="draftError" class="manual-help">{{ draftError }}</p><p class="manual-help">起句开头至止句结尾之间的全部音画、原句和静音都会保留，不只拼接搜索命中项。</p>
      <button class="secondary" :disabled="!canEdit || !!draftError || rangeLoading" @click="checkRange">{{ rangeLoading ? '正在核对完整范围…' : '核对完整范围' }}</button><p v-if="rangeError" class="manual-error" role="alert">{{ rangeError }}</p>
      <p v-if="proofUsable" class="manual-range" role="status">已核对完整范围：{{ rangeProof!.range.utterance_count }} 句 · {{ transcriptTime(rangeProof!.range.start_ms, true) }} → {{ transcriptTime(rangeProof!.range.end_ms, true) }} · 预计 {{ transcriptTime(rangeProof!.range.end_ms - rangeProof!.range.start_ms, true) }}</p><p v-else class="manual-help">起止均选好后，明确核对完整范围以取得真实句数；尚未核对时不能预览或创建。</p>
      <div class="manual-primary-actions"><button class="primary" :disabled="!canWrite" @click="saveDraft">{{ action === 'save' ? '正在保存人工方案…' : plan ? '保存为新修订' : '创建人工剪辑方案' }}</button><label v-if="plan && !draftDirty && plan.status !== 'CONFIRMED'"><input v-model="accepted" type="checkbox" :disabled="!canEdit">我已回看并接受句级边界及所选内容</label><button v-if="plan" class="secondary" :disabled="!canConfirm" @click="confirmPlan">确认人工 r{{ plan.revision }}</button><button v-if="plan" class="primary" :disabled="!canExport" @click="createExport">生成该版本 MP4</button></div>
      <p class="manual-help">创建和保存只产生人工方案；确认不自动导出。普通范围草稿仅在当前任务页面保留，未声称刷新后已备份。</p>
      <p v-if="detailLoading" role="status" class="manual-help">正在核对完整人工方案与起止原句…</p><p v-if="detailError" class="manual-error" role="alert">{{ detailError }}</p>
    </div><ManualClipPreview ref="previewEditor" :video-url="videoUrl" :source-key="`${jobId}:${source?.source_fingerprint ?? ''}`" :range="proofUsable ? rangeProof!.range : null" :active="available && proofUsable && !pending && !captionTarget" /></div>
    <details class="manual-history"><summary>已保存人工方案 · {{ history?.total ?? 0 }}</summary><div><button class="text-button" :disabled="!available || listLoading" @click="readHistory()">刷新人工方案列表</button><p v-if="listLoading" class="manual-help">正在读取人工方案元数据…</p><p v-if="historyError" class="manual-error" role="alert">{{ historyError }}</p><p v-if="history && !history.items.length" class="manual-help">本页没有已保存人工方案。</p><ul><li v-for="item in history?.items ?? []" :key="item.id"><button class="manual-history-item" :disabled="!available || pending || unresolved || detailLoading" @click="loadPlan(item.id)"><strong>{{ item.title }}</strong><span>人工 r{{ item.revision }} · {{ item.status === 'CONFIRMED' ? '已确认' : '待确认' }} · {{ item.created_at }}</span></button></li></ul><nav><button class="secondary" :disabled="!available || listLoading || historyOffset === 0" @click="readHistory(Math.max(0, historyOffset - 20))">上一页人工方案</button><span>{{ historyOffset + 1 }} 项起 / {{ history?.total ?? 0 }} 项</span><button class="secondary" :disabled="!available || listLoading || !history || historyOffset + history.items.length >= history.total" @click="readHistory(historyOffset + (history?.items.length ?? 0))">下一页人工方案</button></nav></div></details>
    <section v-if="plan" class="manual-deliveries" aria-label="人工成片交付"><header><h3>人工成片与字幕</h3><button class="text-button" :disabled="!available || exportsLoading" @click="refreshExports">刷新人工成片</button></header><p v-if="exportsError" class="manual-error" role="alert">{{ exportsError }}</p><p v-if="!batches.length" class="manual-help">尚无成片；确认人工版本后，明确点击生成 MP4。</p><article v-for="batch in batches" :key="batch.id" class="manual-batch"><p>人工确认 r{{ batch.approved_revision }} · {{ batch.status }} · #{{ batch.id.slice(0, 8) }}</p><button class="text-button" :disabled="!canEdit || draftDirty" @click="loadPlan(batch.manual_clip_plan_id!, batch.approved_revision)">查看此版本方案 r{{ batch.approved_revision }}</button><VideoPackagePanel :batch="batch" :source-key="`${jobId}:${batch.manual_clip_plan_id}`" :active="available && !captionTarget" :busy="pending" /><div v-for="clip in batch.clips" :key="clip.id" class="manual-export"><strong>{{ clip.title }}</strong><span>{{ clip.status }}</span><FailureDiagnostic :diagnostic="clip.diagnostic" :error-code="clip.error_code" :attempt="clip.attempt" /><div><button class="secondary" :disabled="!canEdit || clip.status !== 'SUCCEEDED'" @click="openDelivery(batch, clip)">预览人工成片</button><button class="secondary" :disabled="!canEdit || clip.status !== 'SUCCEEDED'" @click="openCaptions(batch, clip)">校对人工成片字幕</button><a v-if="clip.status === 'SUCCEEDED' && clip.download_url" class="secondary" :href="clip.download_url" download target="_blank" rel="noopener">下载人工 MP4</a><button v-if="clip.status === 'FAILED'" class="secondary" :disabled="!canEdit" @click="retryClip(batch, clip)">重试人工导出</button></div></div></article><video v-if="deliveryPreview" ref="deliveryPlayer" :key="deliveryPreview.clip" :src="deliveryPreview.url" controls preload="metadata" playsinline class="manual-delivery-video" @play="guardDelivery"></video></section>
    <CaptionPanel v-if="captionTarget" :key="`${captionTarget.exportId}:${captionTarget.clipId}`" ref="captionEditor" :export-id="captionTarget.exportId" :clip-id="captionTarget.clipId" :content-url="captionTarget.contentUrl" :active="available" @close="captionTarget = null" />
  </section>
</template>

<style scoped>
.manual-clip-panel{min-width:0}.manual-clip-panel>header,.manual-deliveries>header{display:flex;justify-content:space-between;align-items:flex-start;gap:12px}.manual-clip-panel h2{font-size:19px;margin:0 0 7px}.manual-clip-panel header p,.manual-help,.manual-notice,.manual-version,.manual-range{font-size:12px;line-height:1.8;color:#64748b;overflow-wrap:anywhere;margin:8px 0}.manual-layout{display:grid;grid-template-columns:minmax(0,1.1fr) minmax(280px,1fr);gap:20px;align-items:start;margin-top:18px}.manual-title{display:block;font-size:12px;color:#475569}.manual-title input{display:block;width:100%;margin:6px 0 12px;padding:10px;border:1px solid #cbd5e1;border-radius:7px;background:#fff}.manual-boundaries{display:grid;gap:9px}.manual-boundaries article{padding:11px;border:1px solid #dbe4ed;border-radius:7px;background:#f8fafc;min-width:0}.manual-boundaries h3{font-size:12px;margin:0 0 5px}.manual-boundaries p{font-size:11px;color:#64748b;margin:0 0 5px}.manual-boundaries blockquote{margin:0;font-size:12px;line-height:1.8;white-space:pre-wrap;overflow-wrap:anywhere;max-height:180px;overflow:auto}.manual-primary-actions{display:flex;flex-wrap:wrap;align-items:center;gap:10px;margin:15px 0}.manual-primary-actions label{flex-basis:100%;font-size:12px;line-height:1.8}.manual-clip-panel button,.manual-clip-panel a{max-width:100%;white-space:normal;font-size:12px;line-height:1.7}.manual-error{color:#985c32;font-size:12px;line-height:1.8;overflow-wrap:anywhere;margin:9px 0}.manual-receipt{border:1px solid #ead3b8;background:#fff8ed;padding:12px;border-radius:7px;margin:12px 0;font-size:12px;line-height:1.8;overflow-wrap:anywhere}.manual-receipt button{margin:6px 8px 0 0}.manual-receipt p{margin:5px 0}.manual-history{border-top:1px solid #e2e8f0;padding-top:14px;margin-top:20px}.manual-history summary{font-size:13px;cursor:pointer}.manual-history ul{list-style:none;padding:0;margin:8px 0}.manual-history-item{width:100%;display:flex;justify-content:space-between;gap:10px;text-align:left;padding:10px;border-bottom:1px solid #e2e8f0;background:#fff;overflow-wrap:anywhere}.manual-history-item span{color:#64748b;font-size:11px}.manual-history nav{display:flex;flex-wrap:wrap;gap:10px;align-items:center;font-size:11px}.manual-deliveries{border-top:1px solid #e2e8f0;padding-top:18px;margin-top:22px}.manual-deliveries h3{font-size:15px;margin:0}.manual-batch{border:1px solid #dbe4ed;border-radius:8px;padding:12px;margin:12px 0;min-width:0}.manual-batch>p{font-size:12px;color:#64748b;overflow-wrap:anywhere}.manual-export{font-size:12px;line-height:1.8;overflow-wrap:anywhere}.manual-export>span{margin-left:8px;color:#64748b}.manual-export>div{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px}.manual-delivery-video{width:100%;max-height:420px;background:#151a21;border-radius:7px}@media(max-width:1000px){.manual-layout{grid-template-columns:minmax(0,1fr)}.manual-history-item{flex-direction:column;gap:3px}}@media(max-width:600px){.manual-clip-panel>header{display:block}.manual-layout{gap:14px}.manual-clip-panel h2{font-size:17px}.manual-batch{padding:10px}}
</style>
