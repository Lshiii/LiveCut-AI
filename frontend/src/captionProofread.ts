import { copyCaptionCues, type CaptionCue, type CaptionSnapshot } from './captions.ts'
import { parseDiagnostic, type StageDiagnostic } from './diagnostics.ts'
import { parseTextProgress, type TextProgress } from './textProgress.ts'
export { AgentReadScope as CaptionProofreadReadScope } from './videoAgent.ts'

export interface CaptionProofreadSource {
  video_sha256: string; transcript_hash: string; duration_ms: number
  export_id: string; clip_id: string; caption_revision: number; caption_content_hash: string
  caption_snapshot_hash: string; caption_source: CaptionSnapshot['source']; total_cues: number
}
export interface CaptionProofreadBatch { offset: number; count: number; total_cues: number; next_offset: number | null; cue_ids: string[] }
export interface CaptionProofreadSuggestion {
  cue_id: string; before: string; after: string | null; decision: 'CORRECTION' | 'UNCERTAIN'
  reason: string; evidence_utterance_ids: string[]
}
export interface CaptionProofreadTool {
  step: number; tool_name: 'search_transcript' | 'read_context'; arguments: Record<string, string | number>; result_utterance_ids: string[]
}
export interface CaptionProofreadEvidence { utterance_id: string; start_ms: number; end_ms: number; text: string }
export interface CaptionProofreadResult {
  schema_version: '1.0'; session_id: string; turn_id: string; job_id: string; video_id: string
  source_hash: string; transcript_hash: string; instruction: string; caption_snapshot_hash: string
  export_id: string; clip_id: string; caption_revision: number; caption_content_hash: string
  coverage: CaptionProofreadBatch; suggestions: CaptionProofreadSuggestion[]; tool_calls: CaptionProofreadTool[]
  provenance: { text_model_id: string; prompt_version: 'caption-proofread-v1'; retrieval_mode: 'none' | 'bm25' | 'bm25_dense_rrf' }
}
export interface CaptionProofreadTurn {
  id: string; session_id: string; job_id: string; question: string; sequence: number
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'; state_version: number
  created_at: string; updated_at: string; budget: { max_text_calls: number; text_calls_reserved: number }
  stage: { id: string; stage_type: 'CAPTION_PROOFREAD'; status: string; attempt: number; error_code: string | null; diagnostic?: StageDiagnostic; text_progress?: TextProgress }
  batch: CaptionProofreadBatch; evidence: CaptionProofreadEvidence[]; result: CaptionProofreadResult | null
}
export interface CaptionProofreadSession { id: string; job_id: string; video_id: string; created_at: string; source: CaptionProofreadSource; turns_total: number; turns_offset: number; turns: CaptionProofreadTurn[] }
export interface CaptionProofreadTurnPage { items: CaptionProofreadTurn[]; total: number; limit: number; offset: number }
export type CaptionProofreadIdentity = Pick<CaptionProofreadSession, 'id' | 'job_id' | 'video_id' | 'source'>
export interface CaptionProofreadReceipt { input: string; key: string }
export class CaptionProofreadResponseError extends Error { constructor() { super('INVALID_CAPTION_PROOFREAD_RESPONSE') } }
export type CaptionProofreadAvailabilityStatus = 'unchecked' | 'checking' | 'ready' | 'unavailable' | 'temporary'
/** Availability is established only by the current owner's validated, read-only list response. */
export class CaptionProofreadAvailability {
  status: CaptionProofreadAvailabilityStatus = 'unchecked'
  get ready(): boolean { return this.status === 'ready' }
  reset() { this.status = 'unchecked' }
  begin() { this.status = 'checking' }
  acceptList(raw: unknown, exportId: string, clipId: string): CaptionProofreadSession[] {
    try {
      const sessions = parseCaptionProofreadSessionList(raw, exportId, clipId)
      this.status = 'ready'
      return sessions
    } catch (error) { this.status = 'temporary'; throw error }
  }
  failure(scope: 'list' | 'session' | 'turn', code: unknown) {
    if (scope !== 'list') return
    this.status = code === 'RESOURCE_NOT_FOUND' || code === 'HTTP_404' ? 'unavailable' : 'temporary'
  }
}

type Row = Record<string, unknown>
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i, ID = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/, HASH = /^[a-f0-9]{64}$/
const STAGES = new Set(['READY', 'BLOCKED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED'])
const object = (v: unknown): v is Row => v !== null && typeof v === 'object' && !Array.isArray(v)
const integer = (v: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): v is number => Number.isSafeInteger(v) && (v as number) >= min && (v as number) <= max
const text = (v: unknown, max: number): v is string => typeof v === 'string' && !!v.trim() && v.length <= max
const invalidUnicode = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\uD800-\uDFFF]/u
const safeAfter = (v: unknown): v is string => text(v, 2000) && !invalidUnicode.test(v)
const uuid = (v: unknown): v is string => typeof v === 'string' && UUID.test(v)
const hash = (v: unknown): v is string => typeof v === 'string' && HASH.test(v)
const identifier = (v: unknown): v is string => typeof v === 'string' && ID.test(v)
const date = (v: unknown): v is string => typeof v === 'string' && Number.isFinite(Date.parse(v))
const fields = (v: Row, required: string[], optional: string[] = []) => required.every(key => Object.hasOwn(v, key)) && Object.keys(v).every(key => [...required, ...optional].includes(key))
const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b)
const captionSourceKey = (s: CaptionSnapshot['source']) => [s.snapshot_hash, s.clip_sha256, s.transcript_hash, s.duration_ms, s.media_duration_ms, s.timing_basis, s.parts.map(p => [p.start_ms, p.end_ms])]
const sessionSourceKey = (s: CaptionProofreadSource) => [s.video_sha256, s.transcript_hash, s.duration_ms, s.export_id, s.clip_id, s.caption_revision, s.caption_content_hash, s.caption_snapshot_hash, captionSourceKey(s.caption_source), s.total_cues]
function invalid(): never { throw new CaptionProofreadResponseError() }
export function parseCaptionProofreadSessionList(raw: unknown, exportId: string, clipId: string): CaptionProofreadSession[] {
  if (!uuid(exportId) || !uuid(clipId) || !object(raw) || !fields(raw, ['items']) || !Array.isArray(raw.items) || raw.items.length > 100) invalid()
  const sessions = raw.items.map(value => parseCaptionProofreadSession(value, exportId, clipId))
  if (new Set(sessions.map(session => session.id)).size !== sessions.length) invalid()
  return sessions
}
function ids(raw: unknown, min: number, max: number): string[] {
  if (!Array.isArray(raw) || raw.length < min || raw.length > max || raw.some(v => !identifier(v)) || new Set(raw).size !== raw.length) invalid()
  return [...raw] as string[]
}
function source(raw: unknown, exportId: string, clipId: string): CaptionProofreadSource {
  if (!object(raw) || !fields(raw, ['video_sha256', 'transcript_hash', 'duration_ms', 'export_id', 'clip_id', 'caption_revision', 'caption_content_hash', 'caption_snapshot_hash', 'caption_source', 'total_cues'])
    || !hash(raw.video_sha256) || !hash(raw.transcript_hash) || !integer(raw.duration_ms, 1)
    || raw.export_id !== exportId || raw.clip_id !== clipId || !integer(raw.caption_revision)
    || !hash(raw.caption_content_hash) || !hash(raw.caption_snapshot_hash) || !integer(raw.total_cues, 0, 10000)
    || !object(raw.caption_source)) invalid()
  const s = raw.caption_source
  if (!fields(s, ['snapshot_hash', 'clip_sha256', 'transcript_hash', 'duration_ms', 'media_duration_ms', 'timing_basis', 'parts'])
    || !hash(s.snapshot_hash) || !hash(s.clip_sha256) || s.transcript_hash !== raw.transcript_hash
    || !integer(s.duration_ms, 1) || !integer(s.media_duration_ms, 1) || s.timing_basis !== 'SOURCE_EDL_SENTENCE_MS'
    || !Array.isArray(s.parts) || s.parts.length < 1 || s.parts.length > 12) invalid()
  let end = 0, duration = 0
  const parts = s.parts.map(value => {
    if (!object(value) || !fields(value, ['start_ms', 'end_ms']) || !integer(value.start_ms, end) || !integer(value.end_ms, value.start_ms + 1, raw.duration_ms as number)) invalid()
    duration += value.end_ms - value.start_ms; end = value.end_ms
    return { start_ms: value.start_ms, end_ms: value.end_ms }
  })
  if (!Number.isSafeInteger(duration) || duration !== s.duration_ms) invalid()
  return { ...raw, caption_source: { snapshot_hash: s.snapshot_hash, clip_sha256: s.clip_sha256, transcript_hash: s.transcript_hash,
    duration_ms: s.duration_ms, media_duration_ms: s.media_duration_ms, timing_basis: s.timing_basis, parts } } as unknown as CaptionProofreadSource
}
function batch(raw: unknown, total: number): CaptionProofreadBatch {
  if (!object(raw) || !fields(raw, ['offset', 'count', 'total_cues', 'next_offset', 'cue_ids'])
    || raw.total_cues !== total || !integer(raw.offset, 0, total - 1) || !integer(raw.count, 1, Math.min(20, total - raw.offset))) invalid()
  const end = raw.offset + raw.count
  if (raw.next_offset !== (end < total ? end : null)) invalid()
  return { offset: raw.offset, count: raw.count, total_cues: total, next_offset: raw.next_offset as number | null, cue_ids: ids(raw.cue_ids, raw.count, raw.count) }
}
function tool(raw: unknown, index: number): CaptionProofreadTool {
  if (!object(raw) || !fields(raw, ['step', 'tool_name', 'arguments', 'result_utterance_ids']) || raw.step !== index + 1 || !object(raw.arguments)) invalid()
  const a = raw.arguments
  if (raw.tool_name === 'search_transcript') {
    if (!fields(a, ['query', 'top_k']) || !text(a.query, 300) || !integer(a.top_k, 1, 8)) invalid()
  } else if (raw.tool_name === 'read_context') {
    if (!fields(a, ['utterance_id', 'before', 'after']) || !identifier(a.utterance_id) || !integer(a.before, 0, 3) || !integer(a.after, 0, 3)) invalid()
  } else invalid()
  return { step: raw.step as number, tool_name: raw.tool_name, arguments: { ...a } as Record<string, string | number>, result_utterance_ids: ids(raw.result_utterance_ids, 0, 200) }
}
function result(raw: unknown, turn: CaptionProofreadTurn, session: CaptionProofreadIdentity): CaptionProofreadResult {
  const s = session.source
  if (!object(raw) || !fields(raw, ['schema_version', 'session_id', 'turn_id', 'job_id', 'video_id', 'source_hash', 'transcript_hash', 'instruction', 'caption_snapshot_hash', 'export_id', 'clip_id', 'caption_revision', 'caption_content_hash', 'coverage', 'suggestions', 'tool_calls', 'provenance'])
    || raw.schema_version !== '1.0' || raw.session_id !== session.id || raw.turn_id !== turn.id || raw.job_id !== session.job_id || raw.video_id !== session.video_id
    || raw.source_hash !== s.video_sha256 || raw.transcript_hash !== s.transcript_hash || raw.instruction !== turn.question
    || raw.caption_snapshot_hash !== s.caption_snapshot_hash || raw.export_id !== s.export_id || raw.clip_id !== s.clip_id
    || raw.caption_revision !== s.caption_revision || raw.caption_content_hash !== s.caption_content_hash
    || !Array.isArray(raw.suggestions) || raw.suggestions.length > turn.batch.count || !Array.isArray(raw.tool_calls) || raw.tool_calls.length > 4
    || !object(raw.provenance) || !fields(raw.provenance, ['text_model_id', 'prompt_version', 'retrieval_mode'])
    || !identifier(raw.provenance.text_model_id) || raw.provenance.prompt_version !== 'caption-proofread-v1'
    || !['none', 'bm25', 'bm25_dense_rrf'].includes(raw.provenance.retrieval_mode as string)) invalid()
  const coverage = batch(raw.coverage, s.total_cues)
  if (!same(coverage, turn.batch)) invalid()
  const used = new Set<string>(), suggestions = raw.suggestions.map(value => {
    if (!object(value) || !fields(value, ['cue_id', 'before', 'after', 'decision', 'reason', 'evidence_utterance_ids'])
      || !identifier(value.cue_id) || !coverage.cue_ids.includes(value.cue_id) || used.has(value.cue_id)
      || !text(value.before, 2000) || !text(value.reason, 500)) invalid()
    used.add(value.cue_id)
    if (value.decision === 'CORRECTION') { if (!safeAfter(value.after) || value.after === value.before) invalid() }
    else if (value.decision !== 'UNCERTAIN' || value.after !== null) invalid()
    return { cue_id: value.cue_id, before: value.before, after: value.after, decision: value.decision, reason: value.reason,
      evidence_utterance_ids: ids(value.evidence_utterance_ids, 1, 24) } as CaptionProofreadSuggestion
  })
  const calls = raw.tool_calls.map(tool)
  if (calls.some(call => call.tool_name === 'search_transcript') === (raw.provenance.retrieval_mode === 'none')) invalid()
  return { ...raw, coverage, suggestions, tool_calls: calls } as unknown as CaptionProofreadResult
}
export function captionProofreadActive(turn: Pick<CaptionProofreadTurn, 'status'>) { return turn.status === 'QUEUED' || turn.status === 'RUNNING' }
export function captionProofreadBase(exportId: string, clipId: string) {
  if (!uuid(exportId) || !uuid(clipId)) invalid()
  return `/api/exports/${exportId}/clips/${clipId}/caption-proofread-sessions`
}
export function parseCaptionProofreadTurn(raw: unknown, session: CaptionProofreadIdentity, turnId?: string): CaptionProofreadTurn {
  if (!object(raw) || !fields(raw, ['id', 'session_id', 'job_id', 'question', 'sequence', 'status', 'state_version', 'created_at', 'updated_at', 'budget', 'stage', 'batch', 'evidence', 'result'])
    || !uuid(raw.id) || turnId !== undefined && raw.id !== turnId || raw.session_id !== session.id || raw.job_id !== session.job_id
    || !text(raw.question, 1000) || !integer(raw.sequence, 1, 10000) || !integer(raw.state_version, 1)
    || !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(raw.status as string) || !date(raw.created_at) || !date(raw.updated_at)
    || !object(raw.budget) || !fields(raw.budget, ['max_text_calls', 'text_calls_reserved']) || !integer(raw.budget.max_text_calls, 1, 24)
    || !integer(raw.budget.text_calls_reserved, 0, raw.budget.max_text_calls) || !object(raw.stage)
    || !fields(raw.stage, ['id', 'stage_type', 'status', 'attempt', 'error_code'], ['diagnostic', 'text_progress'])
    || !uuid(raw.stage.id) || raw.stage.stage_type !== 'CAPTION_PROOFREAD' || !STAGES.has(raw.stage.status as string) || !integer(raw.stage.attempt)
    || !(raw.stage.error_code === null || typeof raw.stage.error_code === 'string' && /^[A-Z][A-Z0-9_]{0,99}$/.test(raw.stage.error_code))
    || !Array.isArray(raw.evidence) || raw.evidence.length > 480) invalid()
  const seenEvidence = new Set<string>(); let priorEvidenceEnd = 0
  const evidence = raw.evidence.map(value => {
    if (!object(value) || !fields(value, ['utterance_id', 'start_ms', 'end_ms', 'text']) || !identifier(value.utterance_id)
      || seenEvidence.has(value.utterance_id) || !integer(value.start_ms, priorEvidenceEnd) || !integer(value.end_ms, value.start_ms + 1, session.source.duration_ms)
      || !text(value.text, 100000)) invalid()
    seenEvidence.add(value.utterance_id); priorEvidenceEnd = value.end_ms
    return { utterance_id: value.utterance_id, start_ms: value.start_ms, end_ms: value.end_ms, text: value.text }
  })
  const diagnostic = parseDiagnostic(raw.stage.diagnostic, raw.stage.attempt, raw.stage.error_code), progress = parseTextProgress(raw.stage.text_progress)
  const turn = { ...raw, evidence, batch: batch(raw.batch, session.source.total_cues), stage: { id: raw.stage.id, stage_type: 'CAPTION_PROOFREAD', status: raw.stage.status,
    attempt: raw.stage.attempt, error_code: raw.stage.error_code, ...(diagnostic ? { diagnostic } : {}), ...(progress ? { text_progress: progress } : {}) }, result: null } as unknown as CaptionProofreadTurn
  if (turn.status === 'SUCCEEDED') {
    if (turn.stage.status !== 'SUCCEEDED') invalid(); turn.result = result(raw.result, turn, session)
    const needed = new Set(turn.result.suggestions.flatMap(suggestion => suggestion.evidence_utterance_ids))
    if (needed.size !== seenEvidence.size || [...needed].some(id => !seenEvidence.has(id))) invalid()
  } else if (raw.result !== null || evidence.length) invalid()
  return turn
}
export function parseCaptionProofreadSession(raw: unknown, exportId: string, clipId: string, sessionId?: string): CaptionProofreadSession {
  if (!uuid(exportId) || !uuid(clipId) || !object(raw) || !fields(raw, ['id', 'job_id', 'video_id', 'created_at', 'source', 'turns_total', 'turns_offset', 'turns'])
    || !uuid(raw.id) || sessionId !== undefined && raw.id !== sessionId || !uuid(raw.job_id) || !uuid(raw.video_id) || !date(raw.created_at)
    || !integer(raw.turns_total, 0, 10000) || raw.turns_offset !== Math.max(0, raw.turns_total - 20)
    || !Array.isArray(raw.turns) || raw.turns.length !== Math.min(20, raw.turns_total)) invalid()
  const identity = { id: raw.id, job_id: raw.job_id, video_id: raw.video_id, source: source(raw.source, exportId, clipId) }
  const turns = raw.turns.map(value => parseCaptionProofreadTurn(value, identity)), seen = new Set<string>()
  let sequence = raw.turns_offset as number
  for (const turn of turns) { if (seen.has(turn.id) || turn.sequence !== sequence + 1) invalid(); seen.add(turn.id); sequence = turn.sequence }
  if (turns.filter(captionProofreadActive).length > 1) invalid()
  return { ...identity, created_at: raw.created_at, turns_total: raw.turns_total, turns_offset: raw.turns_offset as number, turns }
}
export function parseCaptionProofreadTurnPage(raw: unknown, session: CaptionProofreadIdentity, offset: number, limit = 20): CaptionProofreadTurnPage {
  if (!object(raw) || !fields(raw, ['items', 'total', 'limit', 'offset']) || !integer(raw.total, 0, 10000)
    || raw.limit !== limit || raw.offset !== offset || !integer(offset) || !integer(limit, 1, 100)
    || !Array.isArray(raw.items) || raw.items.length !== Math.min(limit, Math.max(0, raw.total - offset))) invalid()
  const items = raw.items.map(value => parseCaptionProofreadTurn(value, session))
  items.forEach((turn, index) => { if (turn.sequence !== offset + index + 1) invalid() })
  return { items, total: raw.total, limit, offset }
}
export function newestCaptionProofreadTurn(known: CaptionProofreadTurn | undefined, incoming: CaptionProofreadTurn): CaptionProofreadTurn {
  if (!known) return incoming
  if (known.id !== incoming.id || known.session_id !== incoming.session_id || known.job_id !== incoming.job_id || known.question !== incoming.question
    || known.sequence !== incoming.sequence || known.created_at !== incoming.created_at || known.stage.id !== incoming.stage.id
    || known.budget.max_text_calls !== incoming.budget.max_text_calls || !same(known.batch, incoming.batch)) invalid()
  if (incoming.state_version < known.state_version || !captionProofreadActive(known) && incoming.status !== known.status) return known
  if (incoming.state_version === known.state_version && incoming.status !== known.status) return known
  if (incoming.budget.text_calls_reserved < known.budget.text_calls_reserved || incoming.stage.attempt < known.stage.attempt
    || known.result && (!same(known.result, incoming.result) || !same(known.evidence, incoming.evidence))) invalid()
  return incoming
}
export function newestCaptionProofreadSession(known: CaptionProofreadSession | undefined, incoming: CaptionProofreadSession): CaptionProofreadSession {
  if (!known) return incoming
  if (known.id !== incoming.id || known.job_id !== incoming.job_id || known.video_id !== incoming.video_id || known.created_at !== incoming.created_at || !same(sessionSourceKey(known.source), sessionSourceKey(incoming.source))) invalid()
  if (incoming.turns_total < known.turns_total) return known
  const turns = new Map(known.turns.map(turn => [turn.id, turn]))
  for (const turn of incoming.turns) turns.set(turn.id, newestCaptionProofreadTurn(turns.get(turn.id), turn))
  const sorted = [...turns.values()].filter(turn => turn.sequence > incoming.turns_offset).sort((a, b) => a.sequence - b.sequence)
  if (new Set(sorted.map(turn => turn.sequence)).size !== sorted.length || sorted.filter(captionProofreadActive).length > 1) invalid()
  return { ...incoming, turns: sorted }
}
export function captionProofreadAppliesTo(snapshot: CaptionSnapshot, session: CaptionProofreadIdentity): boolean {
  const s = session.source
  return snapshot.export_id === s.export_id && snapshot.clip_id === s.clip_id && snapshot.revision === s.caption_revision
    && snapshot.content_hash === s.caption_content_hash && same(captionSourceKey(snapshot.source), captionSourceKey(s.caption_source)) && snapshot.cues.length === s.total_cues
}
export function applyCaptionProofreadSuggestions(snapshot: CaptionSnapshot, cues: readonly CaptionCue[], session: CaptionProofreadSession,
  turn: CaptionProofreadTurn, cueIds: readonly string[]): { cues: CaptionCue[] | null; error: string } {
  const fail = (error: string) => ({ cues: null, error })
  try {
    const parsed = parseCaptionProofreadSession(session, snapshot.export_id, snapshot.clip_id, session.id)
    const currentTurn = parseCaptionProofreadTurn(turn, parsed, turn.id)
    if (!captionProofreadAppliesTo(snapshot, parsed)) return fail('字幕来源或保存版本已变化，旧建议只能查看，不能覆盖当前版本。')
    if (currentTurn.status !== 'SUCCEEDED' || !currentTurn.result) return fail('本批校对尚未成功完成，不能采纳。')
    if (!Array.isArray(cueIds) || !cueIds.length || cueIds.length > 20 || new Set(cueIds).size !== cueIds.length) return fail('请勾选有效且不重复的纠错建议。')
    if (!Array.isArray(cues) || cues.length > 10000 || new Set(cues.map(cue => cue.id)).size !== cues.length) return fail('当前字幕标识无效，未修改内容。')
    const batchIds = snapshot.cues.slice(currentTurn.batch.offset, currentTurn.batch.offset + currentTurn.batch.count).map(cue => cue.id)
    if (!same(batchIds, currentTurn.batch.cue_ids)) return fail('本批字幕顺序与保存版本不符，未采纳。')
    if (snapshot.cues.slice(currentTurn.batch.offset, currentTurn.batch.offset + currentTurn.batch.count).reduce((size, cue) => size + cue.text.length + cue.source_text.length, 0) > 6000)
      return fail('本批字幕超出冻结输入上限，未采纳。')
    const current = new Map(cues.map(cue => [cue.id, cue])), originals = new Map(snapshot.cues.map(cue => [cue.id, cue]))
    const positions = new Map(snapshot.cues.map((cue, index) => [cue.id, index]))
    let prior = -1
    for (const cue of cues) {
      const index = positions.get(cue.id), original = originals.get(cue.id)
      if (!original || index === undefined || index <= prior || typeof cue.text !== 'string' || cue.text.length > 2000 || typeof cue.reviewed !== 'boolean'
        || !same([cue.part_index, cue.source_utterance_id, cue.source_start_ms, cue.source_end_ms, cue.retained_start_ms, cue.retained_end_ms, cue.source_text, cue.boundary_partial],
        [original.part_index, original.source_utterance_id, original.source_start_ms, original.source_end_ms, original.retained_start_ms, original.retained_end_ms, original.source_text, original.boundary_partial])) return fail('当前字幕来源字段或顺序发生变化，未采纳。')
      prior = index
    }
    const suggestions = new Map(currentTurn.result.suggestions.map(suggestion => [suggestion.cue_id, suggestion]))
    const changes = new Map<string, string>()
    for (const id of cueIds) {
      const suggestion = suggestions.get(id), cue = current.get(id), original = originals.get(id)
      if (!suggestion || suggestion.decision !== 'CORRECTION' || !safeAfter(suggestion.after) || !cue || !original
        || suggestion.before !== original.text || cue.text !== suggestion.before) return fail('勾选字幕已被修改、删除或仅为不确定提示，未覆盖后续编辑。')
      changes.set(id, suggestion.after)
    }
    let bytes = 0
    const encoder = new TextEncoder(), result = copyCaptionCues(cues)
    for (const cue of result) { if (changes.has(cue.id)) cue.text = changes.get(cue.id)!; bytes += encoder.encode(cue.text).byteLength }
    if (bytes > 2 * 1024 * 1024) return fail('采纳后字幕文字总量超过 2 MiB，未修改内容。')
    return { cues: result, error: '' }
  } catch { return fail('建议数据或来源不完整，未采纳到字幕。请重新读取校对记录。') }
}
/** An uncertain submission keeps exactly the same key until its response is reconciled. */
export function captionProofreadReceipt(previous: CaptionProofreadReceipt | null, payload: unknown, newKey: () => string): CaptionProofreadReceipt {
  const input = JSON.stringify(payload)
  if (typeof input !== 'string' || input.length > 12000) invalid()
  if (previous?.input === input && /^[A-Za-z0-9_-]{1,100}$/.test(previous.key)) return previous
  const key = newKey(); if (!/^[A-Za-z0-9_-]{1,100}$/.test(key)) invalid()
  return { input, key }
}
/** Empty instructions use the server default; invalid invisible characters never reach a paid request. */
export function captionProofreadInstruction(value: unknown): string | null {
  if (typeof value !== 'string' || invalidUnicode.test(value)) return null
  const trimmed = value.trim()
  return trimmed.length <= 1000 ? trimmed : null
}
/** Only codes whose named creation action rejects before inserting this request may release its receipt. */
export function captionProofreadRejectedBeforeCreate(kind: 'session' | 'turn', code: unknown): boolean {
  if (kind !== 'session' && kind !== 'turn') return false
  const codes = kind === 'session'
    ? ['INVALID_CAPTION_PROOFREAD_REQUEST', 'CAPTION_PROOFREAD_REVISION_MISMATCH', 'CAPTION_REVISION_NOT_FOUND']
    : ['INVALID_CAPTION_PROOFREAD_REQUEST', 'INVALID_CAPTION_PROOFREAD_BATCH', 'AGENT_SESSION_BUSY', 'CAPTION_PROOFREAD_SESSION_LIMIT', 'CAPTION_PROOFREAD_SOURCE_CHANGED']
  return typeof code === 'string' && codes.includes(code)
}
/** A different page may have replaced the mutable receipt slot while this request was in flight. */
export function captionProofreadStoredReceiptMatches(raw: string | null, expected: CaptionProofreadReceipt): boolean {
  if (!raw) return false
  try {
    const value = JSON.parse(raw)
    return object(value) && object(value.receipt) && value.receipt.key === expected.key && value.receipt.input === expected.input
  } catch { return false }
}
