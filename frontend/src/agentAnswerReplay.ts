import { newestAgentTurn, parseAgentSession, parseAgentTurn, type AgentSession, type AgentTurn } from './videoAgent.ts'
import { AnswerReplayError, type AnswerReplayEntry } from './agentAnswerReplayStorage.ts'

export interface AnswerReplayPreview {
  schema_version: 1; source_turn_id: string; eligible: boolean; block_reason: string | null; snapshot_hash: string | null
  source_budget: AgentTurn['budget']; existing_turn: AgentTurn | null
}
const row = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
const exact = (value: unknown, fields: string[]): value is Record<string, unknown> => row(value) && Object.keys(value).length === fields.length && fields.every(field => Object.hasOwn(value, field))
const hash = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
function invalid(): never { throw new AnswerReplayError() }
export function answerReplayCandidate(turn: AgentTurn) {
  return turn.status === 'FAILED' && turn.evidence_policy === 'speech_lookup_v1' && turn.stage.error_code === 'INVALID_AGENT_OUTPUT' && turn.stage.attempt === 1 && !turn.replay_source_turn_id
}
export function parseAnswerReplayCapability(raw: unknown) {
  if (!exact(raw, ['cached_answer_replay_supported', 'worker_protocol']) || raw.cached_answer_replay_supported !== true || raw.worker_protocol !== 3) invalid()
  return true
}
export function parseAnswerReplayChild(raw: unknown, session: AgentSession, sourceId: string, snapshotHash?: string) {
  const turn = parseAgentTurn(raw, session)
  const source = session.turns.find(turn => turn.id === sourceId)
  if (!source || !answerReplayCandidate(source) || turn.replay_source_turn_id !== sourceId || turn.question !== source.question
    || turn.sequence <= source.sequence || turn.focus_timestamp_ms !== source.focus_timestamp_ms || snapshotHash !== undefined && turn.replay_snapshot_hash !== snapshotHash) invalid()
  return turn
}
export function parseAnswerReplayPreview(raw: unknown, session: AgentSession, source: AgentTurn): AnswerReplayPreview {
  if (!answerReplayCandidate(parseAgentTurn(source, session)) || !exact(raw, ['schema_version', 'source_turn_id', 'eligible', 'block_reason', 'snapshot_hash', 'source_budget', 'existing_turn'])
    || raw.schema_version !== 1 || raw.source_turn_id !== source.id || typeof raw.eligible !== 'boolean'
    || !exact(raw.source_budget, ['max_text_calls', 'text_calls_reserved']) || raw.source_budget.max_text_calls !== source.budget.max_text_calls || raw.source_budget.text_calls_reserved !== source.budget.text_calls_reserved) invalid()
  if (raw.eligible ? !hash(raw.snapshot_hash) || raw.block_reason !== null || raw.existing_turn !== null
    : raw.snapshot_hash !== null || typeof raw.block_reason !== 'string' || !['INELIGIBLE_SOURCE', 'SESSION_BUSY', 'SESSION_LIMIT', 'ALREADY_EXISTS', 'SOURCE_UNAVAILABLE', 'CACHE_UNAVAILABLE', 'CALLS_NOT_REPLAYABLE', 'FIRST_ANSWER_UNSUPPORTED', 'CONFIG_MISMATCH'].includes(raw.block_reason)) invalid()
  const existing = raw.existing_turn === null ? null : parseAnswerReplayChild(raw.existing_turn, session, source.id)
  if ((raw.block_reason === 'ALREADY_EXISTS') !== (existing !== null)) invalid()
  return Object.freeze({ schema_version: 1, source_turn_id: source.id, eligible: raw.eligible, block_reason: raw.block_reason as string | null,
    snapshot_hash: raw.snapshot_hash as string | null, source_budget: { ...source.budget }, existing_turn: existing })
}
export function parseAnswerReplaySubmission(raw: unknown, entry: AnswerReplayEntry, rawSession: unknown) {
  const session = parseAgentSession(rawSession, entry.job_id, entry.video_id, entry.video_sha256, entry.session_id)
  if (session.source.transcript_hash !== entry.source.transcript_hash || session.source.duration_ms !== entry.source.duration_ms) invalid()
  const incoming = parseAnswerReplayChild(raw, session, entry.source_turn_id, entry.snapshot_hash)
  const turn = newestAgentTurn(session.turns.find(turn => turn.id === incoming.id), incoming)
  return parseAgentSession({ ...session, turns: [...session.turns.filter(value => value.id !== turn.id), turn].sort((a, b) => a.sequence - b.sequence) }, entry.job_id, entry.video_id, entry.video_sha256, entry.session_id)
}
export function answerReplaySubmissionMissing(value: unknown) {
  return row(value) && value.status === 404 && value.code === 'AGENT_RESPONSE_REPLAY_SUBMISSION_NOT_FOUND'
}
export function answerReplayMessage(value: unknown) {
  const code = row(value) && typeof value.code === 'string' ? value.code : ''
  const messages: Record<string, string> = {
    AGENT_REPLAY_STORAGE_UNAVAILABLE: '无法保存并复读本机恢复回执，未发送新的恢复动作。已有条目保留。',
    AGENT_REPLAY_ENTRY_INVALID: '本机恢复回执无法核对；原记录保留，暂不能创建新的恢复动作。',
    AGENT_RESPONSE_REPLAY_ALREADY_EXISTS: '此失败轮次已有派生恢复，请重新核对并查看已有轮次；未把它认作本 key 的回执。',
    AGENT_SOURCE_CHANGED: '视频来源已变化，可恢复证明已撤销；原失败和恢复回执保留。',
    HTTP_404: '当前服务未提供恢复路由，未自动重发；已有恢复回执保留。',
    RESOURCE_NOT_FOUND: '当前服务未提供恢复路由，未自动重发；已有恢复回执保留。',
  }
  return messages[code] ?? '暂不能核对恢复结果。原失败、当前草稿和原恢复标识均保留，不会改为重新请求模型。'
}
