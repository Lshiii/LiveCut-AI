<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, ApiError } from './api'
import FailureDiagnostic from './FailureDiagnostic.vue'
import TextRequestProgress from './TextRequestProgress.vue'
import { captionTime, type CaptionCue, type CaptionSnapshot } from './captions'
import { CaptionProofreadAvailability, CaptionProofreadReadScope, CaptionProofreadResponseError, captionProofreadActive, captionProofreadAppliesTo,
  captionProofreadBase, captionProofreadInstruction, captionProofreadReceipt, captionProofreadRejectedBeforeCreate, captionProofreadStoredReceiptMatches,
  newestCaptionProofreadSession, newestCaptionProofreadTurn,
  parseCaptionProofreadSession, parseCaptionProofreadTurn, parseCaptionProofreadTurnPage,
  type CaptionProofreadReceipt, type CaptionProofreadSession, type CaptionProofreadSuggestion, type CaptionProofreadTurn } from './captionProofread'

const props = defineProps<{ snapshot: CaptionSnapshot; draftCues: CaptionCue[]; captionDirty: boolean; captionPending: boolean; active: boolean }>()
const emit = defineEmits<{ apply: [value: { session: CaptionProofreadSession; turn: CaptionProofreadTurn; cueIds: string[] }] }>()
const sessions = ref<CaptionProofreadSession[]>([]), selectedId = ref(''), selectedTurnId = ref('')
const turns = ref(new Map<string, CaptionProofreadTurn>()), historyTurns = ref<CaptionProofreadTurn[]>([])
const historyOffset = ref(0), historyTotal = ref(0), historyOpen = ref(false), followTail = ref(true)
const requestOpen = ref(true), requestDetails = ref<HTMLDetailsElement | null>(null)
const submittedNotice = '本批请求已保存。每轮预算独立，只校对此批；可等待、刷新或取消。'
const action = ref(''), loading = ref(false), historyLoading = ref(false), error = ref(''), notice = ref('')
const availability = ref(new CaptionProofreadAvailability())
const availabilityTitle = computed(() => ({ unchecked: '尚未检查 AI 校对接口', checking: '正在检查 AI 校对接口', ready: '', unavailable: '当前 AI 校对入口不可用', temporary: '暂时无法核对 AI 校对接口' })[availability.value.status])
const availabilityText = computed(() => availability.value.status === 'unavailable'
  ? '当前列表接口返回 404，可能与来源地址、API 代理或后端版本有关。请检查服务后点击“重新检查接口”。已有建议、输入和待核对请求标识保留。'
  : availability.value.status === 'checking' ? '只读检查当前成片的校对记录；不会自动创建会话、重放提交或调用模型。'
    : '请点击“重新检查接口”后再提交。已有建议仍可查看和采纳到本页草稿，待核对请求将继续保留原标识。')
const instruction = ref(''), maxCalls = ref<number | string>(4), offset = ref<number | string>(0), chosen = ref<string[]>([])
const startNumber = computed<number | string>({ get: () => typeof offset.value === 'number' ? offset.value + 1 : offset.value,
  set: value => { offset.value = typeof value === 'number' ? value - 1 : value } })
interface SessionSubmission { payload: { caption_revision: number; caption_content_hash: string }; receipt: CaptionProofreadReceipt }
interface TurnSubmission { sessionId: string; payload: { offset: number; max_text_calls: number; instruction?: string }; receipt: CaptionProofreadReceipt }
const sessionSubmission = ref<SessionSubmission | null>(null), turnSubmission = ref<TurnSubmission | null>(null)
const pending = computed(() => !!action.value)
defineExpose({ pending })
const owner = computed(() => `${props.snapshot.export_id}:${props.snapshot.clip_id}`)
const head = computed(() => `${owner.value}:${props.snapshot.revision}:${props.snapshot.content_hash}:${JSON.stringify(props.snapshot.source)}`)
const base = computed(() => captionProofreadBase(props.snapshot.export_id, props.snapshot.clip_id))
const selected = computed(() => sessions.value.find(session => session.id === selectedId.value))
const selectedTurn = computed(() => turns.value.get(selectedTurnId.value))
const compatible = computed(() => !!selected.value && captionProofreadAppliesTo(props.snapshot, selected.value))
const activeTurn = computed(() => [...turns.value.values()].find(turn => turn.session_id === selectedId.value && captionProofreadActive(turn)))
const suggestions = computed(() => selectedTurn.value?.result?.suggestions ?? [])
const draftById = computed(() => new Map(props.draftCues.map(cue => [cue.id, cue])))
const savedById = computed(() => new Map(props.snapshot.cues.map(cue => [cue.id, cue])))
const names = { QUEUED: '等待校对', RUNNING: '正在校对', SUCCEEDED: '校对完成', FAILED: '校对未完成', CANCELLED: '已取消' }
const canRequest = computed(() => availability.value.ready && props.active && !props.captionDirty && !props.captionPending && !pending.value && !loading.value && !historyLoading.value && !sessionSubmission.value && !turnSubmission.value && props.snapshot.cues.length > 0)
const canStart = computed(() => canRequest.value && !(compatible.value && activeTurn.value) && validParameters())
const instructionInvalid = computed(() => captionProofreadInstruction(instruction.value) === null)
const applicableIds = computed(() => suggestions.value.filter(canChoose).map(suggestion => suggestion.cue_id))
const chosenApplicable = computed(() => chosen.value.filter(id => applicableIds.value.includes(id)))
let stopped = false, selectionVersion = 0, historyVersion = 0, timer: number | undefined, polling = false
const scope = new CaptionProofreadReadScope()
function valid(version: number, documentOwner: string, sessionId?: string) { return !stopped && props.active && version === selectionVersion && owner.value === documentOwner && (sessionId === undefined || selectedId.value === sessionId) }
function validParameters() { return typeof offset.value === 'number' && Number.isInteger(offset.value) && offset.value >= 0 && offset.value < props.snapshot.cues.length
  && typeof maxCalls.value === 'number' && Number.isInteger(maxCalls.value) && maxCalls.value >= 1 && maxCalls.value <= 24 && !instructionInvalid.value }
function storageKey(kind: string) { return `livecut-caption-proofread-${kind}-${owner.value}` }
function saveSubmission(kind: 'session' | 'turn', value: SessionSubmission | TurnSubmission) {
  try { localStorage.setItem(storageKey(kind), JSON.stringify(value)) }
  catch { notice.value = '请求标识仍在本页保留；本浏览器暂时不能持久记录，请在本页核对提交结果。' }
}
function clearSubmission(kind: 'session' | 'turn', receipt: CaptionProofreadReceipt) {
  const current = kind === 'session' ? sessionSubmission.value : turnSubmission.value
  if (current?.receipt.key === receipt.key && current.receipt.input === receipt.input) {
    if (kind === 'session') sessionSubmission.value = null; else turnSubmission.value = null
  }
  try {
    const key = storageKey(kind)
    if (captionProofreadStoredReceiptMatches(localStorage.getItem(key), receipt)) localStorage.removeItem(key)
  } catch { notice.value = '本页提交状态已核对；本浏览器暂时无法更新持久请求标识，重新打开后可再次核对记录。' }
}
function readSubmissions() {
  sessionSubmission.value = null; turnSubmission.value = null
  for (const kind of ['session', 'turn'] as const) {
    try {
      const raw = localStorage.getItem(storageKey(kind)); if (!raw) continue
      const value = JSON.parse(raw)
      if (!value || typeof value !== 'object' || !value.payload || !value.receipt || typeof value.receipt.input !== 'string'
        || typeof value.receipt.key !== 'string' || !/^[A-Za-z0-9_-]{1,100}$/.test(value.receipt.key) || value.receipt.input !== JSON.stringify(value.payload)) continue
      if (kind === 'session') {
        if (Object.keys(value.payload).length !== 2 || !Number.isSafeInteger(value.payload.caption_revision) || value.payload.caption_revision < 0
          || !/^[a-f0-9]{64}$/.test(value.payload.caption_content_hash)) continue
        sessionSubmission.value = value
      } else {
        const p = value.payload
        if (!/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(value.sessionId) || !Number.isSafeInteger(p.offset) || p.offset < 0 || p.offset > 9999
          || !Number.isInteger(p.max_text_calls) || p.max_text_calls < 1 || p.max_text_calls > 24
          || Object.keys(p).some(key => !['offset', 'max_text_calls', 'instruction'].includes(key))
          || p.instruction !== undefined && (typeof p.instruction !== 'string' || !p.instruction.trim() || p.instruction.length > 1000)) continue
        turnSubmission.value = value
      }
    } catch { /* Malformed receipts are never replayed or used as network paths. */ }
  }
}
function message(value: unknown) {
  const raw = value instanceof ApiError ? value.code : value instanceof CaptionProofreadResponseError ? 'INVALID_CAPTION_PROOFREAD_RESPONSE' : 'CLIENT_ERROR'
  const code = /^[A-Z][A-Z0-9_]{0,99}$/.test(raw) ? raw : 'CLIENT_ERROR'
  const labels: Record<string, string> = {
    NETWORK_UNAVAILABLE: '暂时无法连接服务，已有建议和输入仍保留。', REQUEST_TIMEOUT: '请求回执超时，请刷新记录或使用相同请求标识继续核对。',
    INVALID_CAPTION_PROOFREAD_RESPONSE: '返回的字幕来源、批次或建议不匹配，已保留上次有效记录。',
    INVALID_CAPTION_PROOFREAD_REQUEST: '校对要求或范围不合法，请修正后重新提交。',
    CAPTION_REVISION_CONFLICT: '字幕版本已变化，请保存或核对当前字幕后创建新校对会话。',
    AGENT_SESSION_BUSY: '本会话已有一批正在执行，请等待或取消。', CAPTION_PROOFREAD_SESSION_BUSY: '本会话已有一批正在执行，请等待或取消。',
  }
  return labels[code] ?? `AI 校对操作未完成：${code}`
}
function rememberTurn(turn: CaptionProofreadTurn) { const cache = new Map(turns.value); cache.set(turn.id, newestCaptionProofreadTurn(cache.get(turn.id), turn)); turns.value = cache }
function upsert(session: CaptionProofreadSession) {
  const value = newestCaptionProofreadSession(sessions.value.find(row => row.id === session.id), session)
  const index = sessions.value.findIndex(row => row.id === value.id)
  if (index < 0) sessions.value.unshift(value); else sessions.value[index] = value
  for (const turn of value.turns) rememberTurn(turn)
  return value
}
function showSession(session: CaptionProofreadSession) {
  selectedId.value = session.id; historyOffset.value = session.turns_offset; historyTotal.value = session.turns_total
  historyTurns.value = session.turns.map(turn => turns.value.get(turn.id) ?? turn); followTail.value = true
  selectedTurnId.value = session.turns.at(-1)?.id ?? ''; chosen.value = []; requestOpen.value = session.turns.length === 0
}
async function readSession(id: string, version: number, documentOwner: string, select = false) {
  const read = scope.begin()
  try {
    const raw = await api<unknown>(`/api/caption-proofread-sessions/${id}`, { signal: read.signal })
    if (!read.current() || !valid(version, documentOwner, select ? undefined : id)) return
    const session = upsert(parseCaptionProofreadSession(raw, props.snapshot.export_id, props.snapshot.clip_id, id))
    if (select) showSession(session)
    else if (followTail.value) { historyTurns.value = session.turns.map(turn => turns.value.get(turn.id) ?? turn); historyOffset.value = session.turns_offset; historyTotal.value = session.turns_total }
    return session
  } finally { read.release() }
}
async function refresh() {
  if (stopped || !props.active || pending.value || loading.value) return
  const version = selectionVersion, documentOwner = owner.value, read = scope.begin()
  loading.value = true; availability.value.begin(); window.clearTimeout(timer)
  let listChecked = false
  try {
    const raw = await api<unknown>(base.value, { signal: read.signal })
    if (!read.current() || !valid(version, documentOwner)) return
    const parsed = availability.value.acceptList(raw, props.snapshot.export_id, props.snapshot.clip_id)
    parsed.forEach(upsert)
    listChecked = true
    if (!selectedId.value) { const current = parsed.find(session => captionProofreadAppliesTo(props.snapshot, session)); if (current) showSession(current) }
    if (selectedId.value) await readSession(selectedId.value, version, documentOwner)
    error.value = ''
  } catch (value) {
    if (read.current() && valid(version, documentOwner)) {
      if (!listChecked) availability.value.failure('list', value instanceof ApiError ? value.code : 'INVALID_CAPTION_PROOFREAD_RESPONSE')
      error.value = availability.value.status === 'unavailable' ? '' : message(value)
    }
  } finally {
    read.release()
    if (!stopped && version === selectionVersion) {
      loading.value = false
      if (props.active && availability.value.ready) { window.clearTimeout(timer); timer = window.setTimeout(poll, 3000) }
    }
  }
}
async function chooseSession(id: string) {
  if (pending.value || loading.value || historyLoading.value || !props.active || id === selectedId.value) return
  scope.invalidate(); const version = ++selectionVersion, documentOwner = owner.value
  historyVersion++; loading.value = true; error.value = ''
  try {
    const known = sessions.value.find(session => session.id === id)
    if (!availability.value.ready && known) { showSession(known); notice.value = '正在查看本页已验证的历史记录；当前不会发起新的写请求。' }
    else await readSession(id, version, documentOwner, true)
  }
  catch (value) { if (valid(version, documentOwner)) error.value = message(value) }
  finally { if (!stopped && version === selectionVersion) loading.value = false }
}
async function historyPage(target: number) {
  const session = selected.value
  if (!session || pending.value || historyLoading.value || !props.active || target < 0) return
  const version = selectionVersion, documentOwner = owner.value, pageVersion = ++historyVersion, read = scope.begin()
  historyLoading.value = true; error.value = ''
  try {
    const raw = await api<unknown>(`/api/caption-proofread-sessions/${session.id}/turns?offset=${target}&limit=20`, { signal: read.signal })
    if (!read.current() || !valid(version, documentOwner, session.id) || pageVersion !== historyVersion) return
    const page = parseCaptionProofreadTurnPage(raw, session, target)
    page.items.forEach(rememberTurn); historyOffset.value = page.offset; historyTotal.value = page.total
    historyTurns.value = page.items.map(turn => turns.value.get(turn.id)!); followTail.value = false
    selectedTurnId.value = historyTurns.value.at(-1)?.id ?? ''; chosen.value = []
  } catch (value) { if (valid(version, documentOwner, session.id) && pageVersion === historyVersion) error.value = message(value) }
  finally { read.release(); if (!stopped && pageVersion === historyVersion) historyLoading.value = false }
}
async function submitSession(submission: SessionSubmission, version: number, documentOwner: string) {
  if (!availability.value.ready || !valid(version, documentOwner)) return
  sessionSubmission.value = submission; saveSubmission('session', submission)
  const read = scope.begin()
  try {
    const raw = await api<unknown>(base.value, { method: 'POST', signal: read.signal, headers: { 'Content-Type': 'application/json', 'Idempotency-Key': submission.receipt.key }, body: submission.receipt.input })
    if (!read.current() || !valid(version, documentOwner)) return
    const value = parseCaptionProofreadSession(raw, props.snapshot.export_id, props.snapshot.clip_id)
    if (value.source.caption_revision !== submission.payload.caption_revision || value.source.caption_content_hash !== submission.payload.caption_content_hash) throw new CaptionProofreadResponseError()
    const session = upsert(value); showSession(session); clearSubmission('session', submission.receipt); return session
  } catch (value) {
    if (read.current() && valid(version, documentOwner) && value instanceof ApiError && captionProofreadRejectedBeforeCreate('session', value.code)) {
      clearSubmission('session', submission.receipt); notice.value = '本次会话请求被服务端明确拒绝，可修正输入后重新提交；已有记录保留。'
    }
    throw value
  } finally { read.release() }
}
async function createSession(reconcile = false) {
  if (!availability.value.ready || !props.active || props.captionDirty || props.captionPending || pending.value || loading.value || historyLoading.value || turnSubmission.value) return
  if (sessionSubmission.value && !reconcile) return
  scope.invalidate(); const version = ++selectionVersion, documentOwner = owner.value
  action.value = 'session'; error.value = ''; notice.value = ''
  const payload = { caption_revision: props.snapshot.revision, caption_content_hash: props.snapshot.content_hash }
  const submission = reconcile && sessionSubmission.value ? sessionSubmission.value : { payload, receipt: captionProofreadReceipt(null, payload, () => crypto.randomUUID()) }
  try { if (await submitSession(submission, version, documentOwner)) notice.value = '已核对校对会话；尚未新增模型请求，可选择范围后开始本批。' }
  catch (value) { if (valid(version, documentOwner)) error.value = message(value) }
  finally { if (!stopped && version === selectionVersion) action.value = '' }
}
async function submitTurn(session: CaptionProofreadSession, submission: TurnSubmission, version: number, documentOwner: string) {
  if (!availability.value.ready || !valid(version, documentOwner, session.id)) return
  if (submission.payload.offset >= session.source.total_cues || submission.payload.instruction !== undefined && captionProofreadInstruction(submission.payload.instruction) === null) {
    clearSubmission('turn', submission.receipt); notice.value = '本地保留的校对范围或要求不合法，未再次发送。请修正后重新开始，旧记录仍可刷新查看。'
    throw new ApiError('INVALID_CAPTION_PROOFREAD_REQUEST')
  }
  turnSubmission.value = submission; saveSubmission('turn', submission)
  const read = scope.begin()
  try {
    const raw = await api<unknown>(`/api/caption-proofread-sessions/${session.id}/turns`, { method: 'POST', signal: read.signal,
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': submission.receipt.key }, body: submission.receipt.input })
    if (!read.current() || !valid(version, documentOwner, session.id)) return
    const turn = parseCaptionProofreadTurn(raw, session)
    if (turn.batch.offset !== submission.payload.offset || turn.budget.max_text_calls !== submission.payload.max_text_calls
      || submission.payload.instruction !== undefined && turn.question !== submission.payload.instruction) throw new CaptionProofreadResponseError()
    rememberTurn(turn); selectedTurnId.value = turn.id; chosen.value = []; requestOpen.value = false; clearSubmission('turn', submission.receipt)
    notice.value = submittedNotice
    try { await readSession(session.id, version, documentOwner) } catch { /* Accepted turn remains visible and will be polled. */ }
  } catch (value) {
    if (read.current() && valid(version, documentOwner, session.id) && value instanceof ApiError && captionProofreadRejectedBeforeCreate('turn', value.code)) {
      clearSubmission('turn', submission.receipt); notice.value = '本次校对请求被服务端明确拒绝，可修正输入后重新提交；旧调用和结果保留。'
    }
    throw value
  } finally { read.release() }
}
async function startBatch(reconcile = false) {
  if (!availability.value.ready || !props.active || props.captionDirty || props.captionPending || pending.value || loading.value || historyLoading.value || sessionSubmission.value) return
  if (!reconcile && (!canStart.value || turnSubmission.value)) return
  if (reconcile && !turnSubmission.value) return
  scope.invalidate(); const version = ++selectionVersion, documentOwner = owner.value
  action.value = 'turn'; error.value = ''; notice.value = ''
  try {
    let session = selected.value, submission = turnSubmission.value
    if (reconcile && submission) session = await readSession(submission.sessionId, version, documentOwner, true)
    if (!reconcile && (!session || !captionProofreadAppliesTo(props.snapshot, session))) {
      const payload = { caption_revision: props.snapshot.revision, caption_content_hash: props.snapshot.content_hash }
      session = await submitSession({ payload, receipt: captionProofreadReceipt(null, payload, () => crypto.randomUUID()) }, version, documentOwner)
    }
    if (!session || !valid(version, documentOwner, session.id)) return
    if (!reconcile) {
      const normalized = captionProofreadInstruction(instruction.value)
      if (normalized === null) { error.value = '校对要求最多 1000 字，不能包含不支持的控制字符或破损文字。'; return }
      const payload = { offset: Number(offset.value), max_text_calls: Number(maxCalls.value), ...(normalized ? { instruction: normalized } : {}) }
      submission = { sessionId: session.id, payload, receipt: captionProofreadReceipt(null, payload, () => crypto.randomUUID()) }
    }
    if (submission) await submitTurn(session, submission, version, documentOwner)
  } catch (value) { if (valid(version, documentOwner)) error.value = message(value) }
  finally { if (!stopped && version === selectionVersion) action.value = '' }
}
async function cancel() {
  const session = selected.value, turn = activeTurn.value
  if (!availability.value.ready || !session || !turn || pending.value || loading.value || !props.active) return
  scope.invalidate(); const version = ++selectionVersion, documentOwner = owner.value, read = scope.begin()
  action.value = 'cancel'; error.value = ''
  try {
    const raw = await api<unknown>(`/api/caption-proofread-turns/${turn.id}/cancel`, { method: 'POST', signal: read.signal })
    if (read.current() && valid(version, documentOwner, session.id)) { rememberTurn(parseCaptionProofreadTurn(raw, session, turn.id)); notice.value = '已核对取消状态，旧调用和原字幕保留。' }
  } catch (value) { if (valid(version, documentOwner, session.id)) error.value = message(value) }
  finally { read.release(); if (!stopped && version === selectionVersion) action.value = '' }
}
function canChoose(suggestion: CaptionProofreadSuggestion) { return !!selected.value && compatible.value && selectedTurn.value?.status === 'SUCCEEDED'
  && suggestion.decision === 'CORRECTION' && typeof suggestion.after === 'string' && draftById.value.get(suggestion.cue_id)?.text === suggestion.before
  && savedById.value.get(suggestion.cue_id)?.text === suggestion.before }
function chooseAll(event: Event) { chosen.value = (event.target as HTMLInputElement).checked ? [...applicableIds.value] : [] }
function chooseTurn(id: string) { if (!pending.value && !loading.value && !historyLoading.value && props.active) { selectedTurnId.value = id; chosen.value = []; requestOpen.value = false } }
function adopt() { if (props.active && !props.captionPending && !pending.value && !loading.value && !historyLoading.value && selected.value && selectedTurn.value && chosenApplicable.value.length)
  emit('apply', { session: selected.value, turn: selectedTurn.value, cueIds: [...chosenApplicable.value] }) }
function nextBatch() {
  const next = selectedTurn.value?.batch.next_offset
  if (compatible.value && props.active && next !== null && next !== undefined && next < props.snapshot.cues.length && !pending.value && !loading.value && !historyLoading.value) {
    offset.value = next; requestOpen.value = true; notice.value = `已选择从第 ${next + 1} 条开始；请明确点击“开始本批校对”。`
    const version = selectionVersion, documentOwner = owner.value
    void nextTick(() => { if (requestOpen.value && valid(version, documentOwner)) requestDetails.value?.scrollIntoView({ block: 'nearest' }) })
  }
}
function retryBatch() { if (!compatible.value || !selectedTurn.value || !canRequest.value || activeTurn.value) return; offset.value = selectedTurn.value.batch.offset; void startBatch() }
async function poll() {
  window.clearTimeout(timer)
  if (stopped || !props.active || !availability.value.ready || loading.value || polling) return
  polling = true
  const version = selectionVersion, documentOwner = owner.value, session = selected.value
  try {
    if (session && !pending.value) {
      await readSession(session.id, version, documentOwner)
      const active = activeTurn.value
      if (active && valid(version, documentOwner, session.id)) {
        const read = scope.begin()
        try { const raw = await api<unknown>(`/api/caption-proofread-turns/${active.id}`, { signal: read.signal }); if (read.current() && valid(version, documentOwner, session.id)) rememberTurn(parseCaptionProofreadTurn(raw, session, active.id)) }
        finally { read.release() }
      }
    }
  } catch (value) { if (availability.value.ready && valid(version, documentOwner)) error.value = message(value) }
  finally { polling = false; if (!stopped && props.active && availability.value.ready) timer = window.setTimeout(poll, 3000) }
}
watch(() => selectedTurn.value?.id, id => { chosen.value = []; if (id) requestOpen.value = false })
watch([selectedTurn, notice], ([turn, currentNotice]) => { if (turn && !captionProofreadActive(turn) && currentNotice === submittedNotice) notice.value = '' })
watch(() => props.draftCues, () => { chosen.value = chosenApplicable.value }, { deep: true })
watch(head, (value, previous) => {
  scope.invalidate(); selectionVersion++; historyVersion++; action.value = ''; loading.value = false; historyLoading.value = false; chosen.value = []
  availability.value.reset(); window.clearTimeout(timer)
  if (previous && value.split(':').slice(0, 2).join(':') !== previous.split(':').slice(0, 2).join(':')) { sessions.value = []; turns.value = new Map(); selectedId.value = ''; selectedTurnId.value = ''; historyTurns.value = []; historyTotal.value = 0; offset.value = 0; requestOpen.value = true }
  readSubmissions(); if (props.active) void refresh()
})
watch(() => props.active, value => {
  scope.invalidate(); selectionVersion++; historyVersion++; action.value = ''; loading.value = false; historyLoading.value = false; chosen.value = []; window.clearTimeout(timer)
  availability.value.reset()
  if (value) { void refresh(); void poll() }
}, { flush: 'sync' })
onMounted(() => { readSubmissions(); if (props.active) { void refresh(); void poll() } })
onBeforeUnmount(() => { stopped = true; selectionVersion++; historyVersion++; scope.dispose(); window.clearTimeout(timer) })
</script>

<template>
  <section class="caption-ai" aria-label="AI 字幕校对建议">
    <header class="caption-ai-toolbar"><div><h3>AI 校对建议</h3><p>基于已保存字幕 r{{ snapshot.revision }}，逐批核对术语与错字。</p></div><div class="caption-ai-actions"><button :disabled="pending || loading || !active" @click="refresh">{{ loading ? '检查中…' : availability.ready ? '刷新记录' : '重新检查接口' }}</button><button :aria-expanded="historyOpen" :disabled="pending" @click="historyOpen = !historyOpen">历史记录 {{ sessions.length }}</button></div></header>
    <p v-if="error" class="caption-ai-message error" role="alert">{{ error }}</p><p v-if="notice" class="caption-ai-message" role="status">{{ notice }}</p>
    <div v-if="sessionSubmission || turnSubmission" class="caption-ai-receipt" role="status"><p>上次提交回执尚未核对。原请求标识已保留，不会自动换标识重发。</p><button v-if="sessionSubmission" :disabled="!availability.ready || pending || loading || historyLoading || captionDirty || captionPending || !active" @click="createSession(true)">按原请求核对会话</button><button v-if="turnSubmission" :disabled="!availability.ready || pending || loading || historyLoading || captionDirty || captionPending || !active" @click="startBatch(true)">按原请求核对本批</button></div>
    <div class="caption-ai-body">
      <aside v-if="historyOpen" class="caption-ai-history" aria-label="字幕校对历史"><div class="caption-ai-history-heading"><strong>冻结版本与批次记录</strong><button :disabled="!availability.ready || pending || loading || historyLoading || captionDirty || captionPending || !active || !!sessionSubmission || !!turnSubmission" @click="createSession()">新建当前版本会话</button></div><p v-if="!sessions.length" class="caption-ai-help">{{ availability.ready ? '尚无校对记录。开始本批时会创建当前版本会话。' : '记录尚未核对；请先重新检查接口。' }}</p><button v-for="session in sessions" :key="session.id" class="caption-ai-session" :aria-pressed="session.id === selectedId" :disabled="pending || loading || historyLoading || !active" @click="chooseSession(session.id)"><strong>字幕 r{{ session.source.caption_revision }}</strong><span>{{ session.turns_total }} 轮记录 · {{ new Date(session.created_at).toLocaleString('zh-CN') }}</span></button>
        <template v-if="selected"><div class="caption-ai-history-pagination"><button :disabled="historyOffset === 0 || historyLoading || pending || !active" @click="historyPage(Math.max(0, historyOffset - 20))">前 20 轮</button><span>{{ historyTotal ? historyOffset + 1 : 0 }}—{{ historyOffset + historyTurns.length }} / {{ historyTotal }} 轮</span><button :disabled="historyOffset + historyTurns.length >= historyTotal || historyLoading || pending || !active" @click="historyPage(historyOffset + 20)">后 20 轮</button></div><button v-for="turn in historyTurns" :key="turn.id" class="caption-ai-history-turn" :aria-pressed="turn.id === selectedTurnId" :disabled="pending || loading || historyLoading || !active" @click="chooseTurn(turn.id)">第 {{ turn.sequence }} 轮 · 第 {{ turn.batch.offset + 1 }}—{{ turn.batch.offset + turn.batch.count }} 条 · {{ names[(turns.get(turn.id) ?? turn).status] }}</button></template>
      </aside>
      <div class="caption-ai-main">
        <div v-if="!availability.ready" class="caption-ai-availability" role="status"><strong>{{ availabilityTitle }}</strong><p>{{ availabilityText }}</p></div>
        <details ref="requestDetails" class="caption-ai-request" :open="requestOpen" @toggle="requestOpen = ($event.target as HTMLDetailsElement).open"><summary><span aria-hidden="true">{{ requestOpen ? '▾' : '▸' }}</span><strong>新批次范围与预算</strong><span class="caption-ai-request-summary">从第 {{ startNumber }} 条开始 · 上限 {{ maxCalls }} 次文本请求</span><small>{{ requestOpen ? '收起设置' : '展开设置' }}</small></summary><div class="caption-ai-request-body" aria-label="开始一批字幕校对"><p v-if="captionDirty" class="caption-ai-help emphasis">本页有未保存修改。请先保存后再新增 AI 校对；已有同版本建议仍可逐条采纳未改文字。</p><div class="caption-ai-request-fields"><label>从第几条开始<input v-model.number="startNumber" type="number" min="1" :max="Math.max(1, snapshot.cues.length)" :disabled="pending || !!turnSubmission || !!sessionSubmission" aria-label="AI 校对起始字幕"><small>每批最多 20 条，共 {{ snapshot.cues.length }} 条</small></label><label>文本请求上限<input v-model.number="maxCalls" type="number" min="1" max="24" :disabled="pending || !!turnSubmission || !!sessionSubmission" aria-label="AI 校对文本请求上限"><small>1—24 次，含修复及重试</small></label><button class="primary" :disabled="!canStart" @click="startBatch()">{{ action === 'turn' || action === 'session' ? '正在核对提交…' : '开始本批校对' }}</button></div><details class="caption-ai-options"><summary>校对要求与术语提示</summary><label>补充要求<textarea v-model="instruction" maxlength="1000" rows="2" placeholder="例如：技术术语保留英文，不改写数字；不确定时提示回听。" :disabled="pending || !!turnSubmission || !!sessionSubmission" aria-label="AI 字幕校对补充要求"></textarea></label></details><p v-if="instructionInvalid" class="caption-ai-help emphasis" role="alert">校对要求最多 1000 字，不能包含不支持的控制字符或破损文字，请修正后开始。</p><p class="caption-ai-help">每批最多 20 条，并受文字长度限制；只发送已保存版本，不新增 ASR。下一批需你明确发起，本批完成不代表整片均已校对。</p></div></details>
        <section v-if="selectedTurn" class="caption-ai-result" aria-label="本批字幕纠错建议"><div class="caption-ai-result-heading"><strong>{{ names[selectedTurn.status] }} · 第 {{ selectedTurn.batch.offset + 1 }}—{{ selectedTurn.batch.offset + selectedTurn.batch.count }} / {{ selectedTurn.batch.total_cues }} 条</strong><div class="caption-ai-actions"><button v-if="activeTurn" :disabled="!availability.ready || pending || loading || !active" @click="cancel">取消当前执行批次</button><button v-if="selectedTurn.batch.next_offset !== null" :disabled="pending || loading || historyLoading || !active || !compatible" @click="nextBatch">选择下一批</button></div></div><p class="caption-ai-help">本轮已预留 {{ selectedTurn.budget.text_calls_reserved }} / {{ selectedTurn.budget.max_text_calls }} 次文本请求；这是次数上限。</p><p v-if="!compatible" class="caption-ai-message">当前字幕来源或保存版本已变化，此记录仅供查看。新校对将基于当前 r{{ snapshot.revision }}。</p><TextRequestProgress :progress="selectedTurn.stage.text_progress" :terminal="!captionProofreadActive(selectedTurn)"/><FailureDiagnostic v-if="selectedTurn.stage.error_code !== 'INVALID_CAPTION_PROOFREAD_OUTPUT'" :diagnostic="selectedTurn.stage.diagnostic" :error-code="selectedTurn.stage.error_code" :attempt="selectedTurn.stage.attempt"/><p v-if="selectedTurn.status === 'FAILED' || selectedTurn.status === 'CANCELLED'" class="caption-ai-help emphasis">原字幕和旧账本保留。可在上方按原起点明确新建一轮，使用独立预算；不会自动重新提交。</p>
          <p v-if="selectedTurn.stage.error_code === 'INVALID_CAPTION_PROOFREAD_OUTPUT'" class="caption-ai-message" role="status">本批建议未通过格式、来源或引用校验，原字幕没有改动。上方保留本轮已预留的请求次数；请先核对字幕来源与校对要求，重新校对此批会建立新一轮和独立预算。</p>
          <button v-if="selectedTurn.status === 'FAILED' || selectedTurn.status === 'CANCELLED'" :disabled="!canRequest || !compatible || !!activeTurn" @click="retryBatch">重新校对此批</button>
          <template v-if="selectedTurn.result"><p class="caption-ai-help">AI 建议不等于已核验事实。请结合原句与视频判断；不确定项只能提示回听，不能直接采纳。</p><label v-if="applicableIds.length" class="caption-ai-select-all"><input type="checkbox" :checked="chosenApplicable.length === applicableIds.length" :disabled="pending || captionPending || !active" @change="chooseAll">勾选本批可采纳建议 {{ applicableIds.length }} 条</label><p v-if="!suggestions.length" class="caption-ai-empty">本批未提出纠错建议，仍需人工核对；不表示整段语义已获认证。</p><article v-for="suggestion in suggestions" :key="suggestion.cue_id" class="caption-ai-suggestion"><header><label><input v-if="suggestion.decision === 'CORRECTION'" v-model="chosen" type="checkbox" :value="suggestion.cue_id" :disabled="!canChoose(suggestion) || pending || captionPending || !active"><strong>{{ suggestion.decision === 'UNCERTAIN' ? '建议回听' : '纠错建议' }}</strong><span>{{ suggestion.cue_id }}<template v-if="savedById.get(suggestion.cue_id)"> · 成片 {{ captionTime(savedById.get(suggestion.cue_id)!.start_ms) }}</template></span></label><small v-if="suggestion.decision === 'CORRECTION' && !canChoose(suggestion)">当前文字或版本已变化</small></header><div class="caption-ai-diff"><span>本轮输入</span><p>{{ suggestion.before }}</p><template v-if="suggestion.after !== null"><span>建议改为</span><p class="after">{{ suggestion.after }}</p></template></div><p class="caption-ai-reason">{{ suggestion.reason }}</p><details class="caption-ai-evidence"><summary>依据原句 {{ suggestion.evidence_utterance_ids.length }} 条</summary><p v-for="evidence in selectedTurn.evidence.filter(item => suggestion.evidence_utterance_ids.includes(item.utterance_id))" :key="evidence.utterance_id" class="caption-ai-evidence-quote"><strong>原视频 {{ captionTime(evidence.start_ms) }}—{{ captionTime(evidence.end_ms) }}</strong><span>{{ evidence.text }}</span></p></details></article><details v-if="selectedTurn.result.tool_calls.length" class="caption-ai-evidence"><summary>查找过程 {{ selectedTurn.result.tool_calls.length }} 步</summary><p v-for="step in selectedTurn.result.tool_calls" :key="step.step">{{ step.step }} · {{ step.tool_name === 'search_transcript' ? '搜索原句' : '读取前后文' }} · 返回 {{ step.result_utterance_ids.length }} 条原句</p></details></template>
        </section>
        <p v-else class="caption-ai-empty">先选择起点与调用上限，再开始一批字幕校对。已有记录可从“历史记录”读取。</p>
      </div>
    </div>
    <footer class="caption-ai-footer"><span>已选 {{ chosenApplicable.length }} 条。采纳只进入本页文字草稿，仍需保存和确认字幕。</span><button class="primary" :disabled="!active || pending || loading || historyLoading || captionPending || !compatible || !chosenApplicable.length" @click="adopt">采纳所选到本页字幕</button></footer>
  </section>
</template>

<style scoped>
.caption-ai { display:flex; flex-direction:column; min-height:0; min-width:0; flex:1; overflow:hidden; color:#334155; font:14px/1.65 system-ui,sans-serif; }
.caption-ai h3,.caption-ai p { margin:0; }.caption-ai-toolbar { display:flex; flex:none; flex-wrap:wrap; justify-content:space-between; align-items:center; gap:8px 14px; padding:12px 18px; border-bottom:1px solid #e2e8f0; }.caption-ai-toolbar h3 { font-size:16px; }.caption-ai-toolbar p { color:#64748b; font-size:12px; }.caption-ai-actions { display:flex; flex-wrap:wrap; gap:6px; align-items:center; }
.caption-ai button { min-height:34px; max-width:100%; padding:6px 10px; border:1px solid #d9e1eb; border-radius:6px; background:white; color:#475569; font:inherit; font-size:13px; cursor:pointer; overflow-wrap:anywhere; }.caption-ai button.primary { background:#e47650; border-color:#e47650; color:white; }.caption-ai button:disabled { opacity:.45; cursor:not-allowed; }.caption-ai button:focus-visible,.caption-ai input:focus-visible,.caption-ai textarea:focus-visible { outline:2px solid #e89778; outline-offset:2px; }
.caption-ai-message,.caption-ai-receipt { flex:none; max-height:100px; overflow:auto; margin:8px 18px 0; padding:9px 12px; border-radius:6px; background:#f1f5f9; color:#475569; font-size:13px; overflow-wrap:anywhere; }.caption-ai-message.error { background:#fff1eb; color:#9a4f32; }.caption-ai-receipt { background:#fff7ed; color:#9a6337; }.caption-ai-receipt button { margin-top:6px; margin-right:7px; }
.caption-ai-body { flex:1; min-height:0; min-width:0; display:flex; overflow:hidden; }.caption-ai-main { flex:1; min-width:0; min-height:0; overflow-y:auto; overscroll-behavior:contain; padding:14px 18px; scrollbar-width:thin; }.caption-ai-history { flex:0 0 260px; min-width:0; min-height:0; overflow-y:auto; padding:12px; border-right:1px solid #e2e8f0; background:#f8fafc; font-size:12px; }.caption-ai-history-heading { display:flex; flex-wrap:wrap; gap:7px; margin-bottom:8px; }.caption-ai-session,.caption-ai-history-turn { display:block; width:100%; text-align:left; margin-top:7px; }.caption-ai-session span { display:block; color:#64748b; font-size:11px; }.caption-ai [aria-pressed=true] { border-color:#e9bcaa; background:#fff4ed; }.caption-ai-history-pagination { display:flex; flex-wrap:wrap; align-items:center; gap:5px; margin-top:14px; }.caption-ai-history-pagination button { font-size:11px; padding:4px 6px; }.caption-ai-history-pagination span { flex-basis:100%; color:#64748b; }
.caption-ai-availability { margin-bottom:14px; padding:12px; border:1px solid #e2e8f0; border-radius:7px; background:#f8fafc; color:#475569; font-size:13px; }.caption-ai-availability strong { display:block; margin-bottom:5px; color:#334155; }.caption-ai-availability p { line-height:1.8; overflow-wrap:anywhere; }
.caption-ai-request { padding:0; border:1px solid #e2e8f0; border-radius:8px; background:#f8fafc; }.caption-ai-request>summary { display:flex; align-items:center; flex-wrap:wrap; gap:5px 9px; min-height:44px; padding:10px 12px; cursor:pointer; list-style:none; color:#334155; font-size:13px; }.caption-ai-request>summary::-webkit-details-marker { display:none; }.caption-ai-request>summary:focus-visible { outline:2px solid #df7954; outline-offset:-3px; border-radius:7px; }.caption-ai-request-summary { flex:1 1 230px; color:#64748b; font-size:12px; }.caption-ai-request>summary>small { margin-left:auto; color:#64748b; font-size:12px; }.caption-ai-request-body { padding:0 12px 12px; border-top:1px solid #e2e8f0; }.caption-ai-request-fields { margin-top:10px; }.caption-ai-request-fields { display:flex; flex-wrap:wrap; align-items:flex-end; gap:8px 12px; }.caption-ai-request-fields label { display:flex; flex:0 1 160px; flex-direction:column; min-width:0; gap:3px; font-size:13px; }.caption-ai-request-fields input { width:100%; }.caption-ai-request-fields small { color:#64748b; font-size:11px; }.caption-ai-request-fields>button { margin-left:auto; }.caption-ai input:not([type=checkbox]),.caption-ai textarea { box-sizing:border-box; min-width:0; max-width:100%; padding:7px 9px; border:1px solid #cbd5e1; border-radius:5px; background:white; color:#1f2937; font:inherit; font-size:14px; }.caption-ai input[type=checkbox] { accent-color:#e47650; }.caption-ai textarea { display:block; width:100%; resize:vertical; margin-top:5px; line-height:1.7; }.caption-ai-options { margin-top:9px; color:#64748b; font-size:13px; }.caption-ai-options summary,.caption-ai-evidence summary { cursor:pointer; padding:4px 0; }
.caption-ai .caption-ai-help { margin:8px 0 0; color:#64748b; font-size:12px; line-height:1.8; overflow-wrap:anywhere; }.caption-ai .emphasis { color:#9a6337; }.caption-ai-result { margin-top:16px; }.caption-ai-result-heading { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:8px; }.caption-ai-result-heading strong { font-size:14px; }.caption-ai-select-all { display:flex; align-items:center; gap:5px; margin-top:10px; color:#475569; font-size:13px; }.caption-ai-suggestion { padding:12px; margin-top:10px; border:1px solid #e2e8f0; border-radius:7px; }.caption-ai-suggestion header { display:flex; flex-wrap:wrap; gap:4px 8px; justify-content:space-between; align-items:center; }.caption-ai-suggestion header label { display:flex; flex-wrap:wrap; gap:5px 8px; align-items:center; }.caption-ai-suggestion header strong { font-size:13px; }.caption-ai-suggestion header span,.caption-ai-suggestion header small { color:#7b8799; font-size:11px; }.caption-ai-diff { display:grid; grid-template-columns:auto minmax(0,1fr); gap:6px 10px; margin-top:9px; }.caption-ai-diff span { color:#64748b; font-size:12px; }.caption-ai-diff p { white-space:pre-wrap; overflow-wrap:anywhere; color:#64748b; }.caption-ai-diff p.after { color:#1f2937; }.caption-ai .caption-ai-reason { margin-top:8px; font-size:13px; color:#475569; overflow-wrap:anywhere; }.caption-ai-evidence { margin-top:7px; font-size:12px; color:#64748b; overflow-wrap:anywhere; }.caption-ai .caption-ai-empty { padding:24px 12px; color:#64748b; font-size:13px; line-height:1.8; }
.caption-ai-evidence-quote { padding:8px; margin-top:6px!important; border:1px solid #e2e8f0; border-radius:5px; background:#f8fafc; }.caption-ai-evidence-quote strong { display:block; font-size:11px; font-weight:500; color:#64748b; }.caption-ai-evidence-quote span { display:block; margin-top:4px; color:#475569; white-space:pre-wrap; }
.caption-ai-footer { flex:none; display:flex; flex-wrap:wrap; justify-content:space-between; align-items:center; gap:8px 12px; max-height:35%; overflow:auto; padding:10px 18px; border-top:1px solid #e2e8f0; background:white; }.caption-ai-footer span { flex:1 1 220px; color:#64748b; font-size:12px; }.caption-ai-footer button { flex:none; }
@media(max-width:760px) { .caption-ai-toolbar,.caption-ai-main { padding:10px; }.caption-ai-toolbar>div:first-child { flex-basis:100%; }.caption-ai-body { display:block; overflow-y:auto; }.caption-ai-main { overflow:visible; }.caption-ai-history { max-height:230px; overflow:auto; border-right:0; border-bottom:1px solid #e2e8f0; }.caption-ai-request-fields label { flex:1 1 90px; }.caption-ai-request-fields>button { width:100%; margin:0; }.caption-ai-footer { padding:8px 10px; }.caption-ai-footer>button { width:100%; }.caption-ai-message,.caption-ai-receipt { margin-inline:10px; }.caption-ai-diff { grid-template-columns:minmax(0,1fr); gap:3px; }.caption-ai-diff span { margin-top:5px; } }
</style>
