import { parseDiagnostic, type StageDiagnostic } from './diagnostics.ts'
import { parseTextProgress, type TextProgress } from './textProgress.ts'

export interface AgentSource { video_sha256: string; transcript_hash: string; duration_ms: number }
export interface AgentCitation { utterance_id: string; start_ms: number; end_ms: number; text: string }
export type AgentToolCall = {
  step: number; tool_name: 'search_transcript' | 'read_context' | 'list_topics'
  arguments: Record<string, string | number>; result_utterance_ids: string[]
}
export interface AgentResult {
  schema_version: '1.0'; session_id: string; turn_id: string; job_id: string; video_id: string
  source_hash: string; transcript_hash: string; question: string; answer: string
  answer_scope: 'VIDEO' | 'GENERAL' | 'INSUFFICIENT'; citations: AgentCitation[]; tool_calls: AgentToolCall[]
  provenance: { text_model_id: string; prompt_version: 'video-agent-v1'; retrieval_mode: 'none' | 'bm25' | 'bm25_dense_rrf' }
  suggested_clip_instruction: string | null
}
export interface AgentTurn {
  id: string; session_id: string; job_id: string; question: string; sequence: number
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'; state_version: number
  created_at: string; updated_at: string; budget: { max_text_calls: number; text_calls_reserved: number }
  stage: { id: string; stage_type: 'SEGMENT'; status: string; attempt: number; error_code: string | null; diagnostic?: StageDiagnostic; text_progress?: TextProgress }
  result: AgentResult | null
}
export interface AgentSession { id: string; job_id: string; video_id: string; created_at: string; source: AgentSource; turns: AgentTurn[] }
export type AgentSessionIdentity = Pick<AgentSession, 'id' | 'job_id' | 'video_id' | 'source'>
export interface AgentReceipt { input: string; key: string }
export class AgentResponseError extends Error { constructor() { super('INVALID_AGENT_RESPONSE') } }
type Row = Record<string, unknown>
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i
const ID = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/
const HASH = /^[a-f0-9]{64}$/
const STAGES = new Set(['READY', 'BLOCKED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED'])
function row(v: unknown): v is Row { return v !== null && typeof v === 'object' && !Array.isArray(v) }
function integer(v: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): v is number { return Number.isSafeInteger(v) && (v as number) >= min && (v as number) <= max }
function text(v: unknown, max: number): v is string { return typeof v === 'string' && !!v.trim() && v.length <= max }
function uuid(v: unknown): v is string { return typeof v === 'string' && UUID.test(v) }
function identifier(v: unknown): v is string { return typeof v === 'string' && ID.test(v) }
function hash(v: unknown): v is string { return typeof v === 'string' && HASH.test(v) }
function date(v: unknown) { return typeof v === 'string' && Number.isFinite(Date.parse(v)) }
function invalid(): never { throw new AgentResponseError() }
function fields(value: Row, expected: string[]) { return Object.keys(value).length === expected.length && expected.every(key => Object.hasOwn(value, key)) }
export function agentTurnActive(turn: Pick<AgentTurn, 'status'>) { return turn.status === 'QUEUED' || turn.status === 'RUNNING' }

function tool(raw: unknown, index: number): AgentToolCall {
  if (!row(raw) || raw.step !== index + 1 || !row(raw.arguments) || !Array.isArray(raw.result_utterance_ids)
    || raw.result_utterance_ids.length > 200 || raw.result_utterance_ids.some(id => !identifier(id))
    || new Set(raw.result_utterance_ids).size !== raw.result_utterance_ids.length) invalid()
  const args = raw.arguments
  if (raw.tool_name === 'search_transcript') {
    if (!fields(args, ['query', 'top_k']) || !text(args.query, 300) || !integer(args.top_k, 1, 8)) invalid()
  } else if (raw.tool_name === 'read_context') {
    if (!fields(args, ['utterance_id', 'before', 'after']) || !identifier(args.utterance_id)
      || !integer(args.before, 0, 3) || !integer(args.after, 0, 3)) invalid()
  } else if (raw.tool_name === 'list_topics') {
    if (!fields(args, ['offset', 'limit']) || !integer(args.offset) || !integer(args.limit, 1, 12)) invalid()
  } else invalid()
  return { step: raw.step as number, tool_name: raw.tool_name as AgentToolCall['tool_name'],
    arguments: { ...args } as AgentToolCall['arguments'], result_utterance_ids: [...raw.result_utterance_ids] as string[] }
}
function result(raw: unknown, turn: AgentTurn, session: AgentSessionIdentity): AgentResult {
  if (!row(raw) || raw.schema_version !== '1.0' || raw.session_id !== session.id || raw.turn_id !== turn.id
    || raw.job_id !== session.job_id || raw.video_id !== session.video_id || raw.source_hash !== session.source.video_sha256
    || raw.transcript_hash !== session.source.transcript_hash || raw.question !== turn.question || !text(raw.answer, 6000)
    || !['VIDEO', 'GENERAL', 'INSUFFICIENT'].includes(raw.answer_scope as string)
    || !Array.isArray(raw.citations) || raw.citations.length > 24 || !Array.isArray(raw.tool_calls) || raw.tool_calls.length > 4
    || !row(raw.provenance) || !text(raw.provenance.text_model_id, 128) || raw.provenance.prompt_version !== 'video-agent-v1'
    || !['none', 'bm25', 'bm25_dense_rrf'].includes(raw.provenance.retrieval_mode as string)
    || !(raw.suggested_clip_instruction === null || text(raw.suggested_clip_instruction, 2000))) invalid()
  const calls = raw.tool_calls.map(tool), ids = new Set<string>(); let previous = 0
  const citations = raw.citations.map(value => {
    if (!row(value) || !identifier(value.utterance_id) || ids.has(value.utterance_id) || !text(value.text, 100000)
      || !integer(value.start_ms, previous) || !integer(value.end_ms, value.start_ms + 1, session.source.duration_ms)) invalid()
    previous = value.end_ms; ids.add(value.utterance_id)
    return { utterance_id: value.utterance_id, start_ms: value.start_ms, end_ms: value.end_ms, text: value.text }
  })
  if (raw.answer_scope === 'VIDEO' && !citations.length) invalid()
  const search = calls.some(call => call.tool_name === 'search_transcript')
  if (search === (raw.provenance.retrieval_mode === 'none')) invalid()
  return { ...raw, tool_calls: calls, citations } as unknown as AgentResult
}
export function parseAgentTurn(raw: unknown, session: AgentSessionIdentity, turnId?: string): AgentTurn {
  if (!row(raw) || !uuid(raw.id) || (turnId !== undefined && raw.id !== turnId) || raw.session_id !== session.id
    || raw.job_id !== session.job_id || !text(raw.question, 2000) || !integer(raw.sequence, 1, 100)
    || !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(raw.status as string)
    || !integer(raw.state_version, 1) || !date(raw.created_at) || !date(raw.updated_at)
    || !row(raw.budget) || !integer(raw.budget.max_text_calls, 1, 24) || !integer(raw.budget.text_calls_reserved, 0, raw.budget.max_text_calls)
    || !row(raw.stage) || !uuid(raw.stage.id) || raw.stage.stage_type !== 'SEGMENT' || !STAGES.has(raw.stage.status as string)
    || !integer(raw.stage.attempt) || !(raw.stage.error_code === null || (typeof raw.stage.error_code === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(raw.stage.error_code)))) invalid()
  const { diagnostic: rawDiagnostic, text_progress: rawProgress, ...stage } = raw.stage
  const diagnostic = parseDiagnostic(rawDiagnostic, raw.stage.attempt, raw.stage.error_code)
  const progress = parseTextProgress(rawProgress)
  const turn = { ...raw, stage: { ...stage, ...(diagnostic ? { diagnostic } : {}), ...(progress ? { text_progress: progress } : {}) }, result: null } as unknown as AgentTurn
  if (raw.status === 'SUCCEEDED') turn.result = result(raw.result, turn, session)
  else if (raw.result !== null) invalid()
  return turn
}
export function parseAgentSession(raw: unknown, jobId: string, videoId: string, videoHash: string, sessionId?: string): AgentSession {
  if (!row(raw) || !uuid(raw.id) || (sessionId !== undefined && raw.id !== sessionId) || raw.job_id !== jobId || !uuid(jobId)
    || raw.video_id !== videoId || !uuid(videoId) || !date(raw.created_at) || !row(raw.source)
    || raw.source.video_sha256 !== videoHash || !hash(videoHash) || !hash(raw.source.transcript_hash) || !integer(raw.source.duration_ms, 1)
    || !Array.isArray(raw.turns) || raw.turns.length > 100) invalid()
  const source: AgentSource = { video_sha256: videoHash, transcript_hash: raw.source.transcript_hash, duration_ms: raw.source.duration_ms }
  const identity: AgentSessionIdentity = { id: raw.id, job_id: jobId, video_id: videoId, source }
  const turns = raw.turns.map(value => parseAgentTurn(value, identity)), ids = new Set<string>()
  let previous = 0, active = 0
  for (const turn of turns) {
    if (ids.has(turn.id) || turn.sequence <= previous) invalid()
    ids.add(turn.id); previous = turn.sequence; if (agentTurnActive(turn)) active++
  }
  if (active > 1) invalid()
  return { ...raw, source, turns } as unknown as AgentSession
}
export function newestAgentTurn(known: AgentTurn | undefined, incoming: AgentTurn): AgentTurn {
  if (!known) return incoming
  if (known.id !== incoming.id || known.session_id !== incoming.session_id || known.job_id !== incoming.job_id
    || known.sequence !== incoming.sequence || known.question !== incoming.question || known.created_at !== incoming.created_at
    || known.stage.id !== incoming.stage.id || known.budget.max_text_calls !== incoming.budget.max_text_calls) invalid()
  if (incoming.state_version < known.state_version || (!agentTurnActive(known) && incoming.status !== known.status)
    || (incoming.state_version === known.state_version && incoming.status !== known.status)) return known
  return incoming
}
export function newestAgentSession(known: AgentSession | undefined, incoming: AgentSession): AgentSession {
  if (!known) return incoming
  if (known.id !== incoming.id || known.job_id !== incoming.job_id || known.video_id !== incoming.video_id
    || known.source.video_sha256 !== incoming.source.video_sha256 || known.source.transcript_hash !== incoming.source.transcript_hash
    || known.source.duration_ms !== incoming.source.duration_ms || known.created_at !== incoming.created_at) invalid()
  const turns = new Map(known.turns.map(turn => [turn.id, turn]))
  for (const turn of incoming.turns) turns.set(turn.id, newestAgentTurn(turns.get(turn.id), turn))
  const sorted = [...turns.values()].sort((a, b) => a.sequence - b.sequence)
  if (new Set(sorted.map(turn => turn.sequence)).size !== sorted.length || sorted.filter(agentTurnActive).length > 1) invalid()
  return { ...incoming, turns: sorted }
}
export function agentTurnReceipt(previous: AgentReceipt | null, question: string, maxCalls: number, newKey: () => string): AgentReceipt {
  if (!text(question, 2000) || question !== question.trim() || !integer(maxCalls, 1, 24)) invalid()
  const input = JSON.stringify({ question, max_text_calls: maxCalls })
  return previous?.input === input && typeof previous.key === 'string' && /^[A-Za-z0-9_-]{1,100}$/.test(previous.key)
    ? previous : { input, key: newKey() }
}
export function canPrefillClipInstruction(value: unknown, dirty: boolean, pending: boolean): value is string {
  return !dirty && !pending && text(value, 2000)
}
/** A session selection owns its reads; aborted or late data cannot select another session. */
export class AgentReadScope {
  private epoch = 0
  private stopped = false
  private controllers = new Set<AbortController>()
  begin() {
    const controller = new AbortController(), epoch = this.epoch
    if (this.stopped) controller.abort()
    this.controllers.add(controller)
    return { signal: controller.signal, current: () => !this.stopped && epoch === this.epoch && !controller.signal.aborted,
      release: () => { this.controllers.delete(controller) } }
  }
  invalidate() { this.epoch++; for (const controller of this.controllers) controller.abort(); this.controllers.clear() }
  dispose() { this.stopped = true; this.invalidate() }
}
