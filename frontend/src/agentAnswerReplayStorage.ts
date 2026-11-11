import type { AgentSession, AgentSource } from './videoAgent.ts'
import type { AgentSubmissionStorage, AgentSubmissionOwner } from './agentSubmissionRecovery.ts'

export const ANSWER_REPLAY_PREFIX = 'livecut-agent-answer-replay-v1:'
export interface AnswerReplayEntry extends AgentSubmissionOwner {
  schema_version: 'agent-answer-replay-v1'; key: string; session_id: string; source_turn_id: string
  source: AgentSource; snapshot_hash: string; input: string
}
export class AnswerReplayError extends Error {
  code: string
  constructor(code = 'INVALID_AGENT_REPLAY_RESPONSE') { super(code); this.code = code }
}
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i, HASH = /^[a-f0-9]{64}$/
const row = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
const exact = (value: unknown, fields: string[]): value is Record<string, unknown> => row(value) && Object.keys(value).length === fields.length && fields.every(field => Object.hasOwn(value, field))
export function parseAnswerReplayEntry(value: unknown): AnswerReplayEntry {
  if (!exact(value, ['schema_version', 'key', 'job_id', 'video_id', 'video_sha256', 'session_id', 'source_turn_id', 'source', 'snapshot_hash', 'input'])
    || value.schema_version !== 'agent-answer-replay-v1' || !['key', 'job_id', 'video_id', 'session_id', 'source_turn_id'].every(field => typeof value[field] === 'string' && UUID.test(value[field] as string))
    || typeof value.video_sha256 !== 'string' || !HASH.test(value.video_sha256) || typeof value.snapshot_hash !== 'string' || !HASH.test(value.snapshot_hash)
    || !exact(value.source, ['video_sha256', 'transcript_hash', 'duration_ms']) || value.source.video_sha256 !== value.video_sha256
    || typeof value.source.transcript_hash !== 'string' || !HASH.test(value.source.transcript_hash) || !Number.isSafeInteger(value.source.duration_ms) || (value.source.duration_ms as number) < 1
    || typeof value.input !== 'string' || value.input.length > 256) throw new AnswerReplayError('AGENT_REPLAY_ENTRY_INVALID')
  try { const body = JSON.parse(value.input); if (!exact(body, ['snapshot_hash']) || body.snapshot_hash !== value.snapshot_hash) throw Error() }
  catch { throw new AnswerReplayError('AGENT_REPLAY_ENTRY_INVALID') }
  return value as unknown as AnswerReplayEntry
}
export function answerReplayStorageKey(entry: AnswerReplayEntry) { return `${ANSWER_REPLAY_PREFIX}${entry.job_id}:${entry.key}` }
const identity = (value: AnswerReplayEntry) => JSON.stringify([value.schema_version, value.key, value.job_id, value.video_id, value.video_sha256, value.session_id, value.source_turn_id, value.source.video_sha256, value.source.transcript_hash, value.source.duration_ms, value.snapshot_hash, value.input])
export function assertAnswerReplayStored(storage: AgentSubmissionStorage, entry: AnswerReplayEntry) {
  try {
    const raw = storage.getItem(answerReplayStorageKey(entry))
    if (raw === null || identity(parseAnswerReplayEntry(JSON.parse(raw))) !== identity(entry)) throw Error()
  } catch { throw new AnswerReplayError('AGENT_REPLAY_STORAGE_UNAVAILABLE') }
}
export function storeAnswerReplay(storage: AgentSubmissionStorage, entry: AnswerReplayEntry) {
  parseAnswerReplayEntry(entry)
  try {
    const key = answerReplayStorageKey(entry), previous = storage.getItem(key)
    if (previous !== null && identity(parseAnswerReplayEntry(JSON.parse(previous))) !== identity(entry)) throw Error()
    if (previous === null) storage.setItem(key, JSON.stringify(entry))
    assertAnswerReplayStored(storage, entry)
  } catch { throw new AnswerReplayError('AGENT_REPLAY_STORAGE_UNAVAILABLE') }
}
export function prepareAnswerReplay(storage: AgentSubmissionStorage, session: AgentSession, sourceTurnId: string, snapshotHash: string, key: string): AnswerReplayEntry {
  const entry = parseAnswerReplayEntry({ schema_version: 'agent-answer-replay-v1', key, job_id: session.job_id, video_id: session.video_id,
    video_sha256: session.source.video_sha256, session_id: session.id, source_turn_id: sourceTurnId, source: { ...session.source }, snapshot_hash: snapshotHash,
    input: JSON.stringify({ snapshot_hash: snapshotHash }) })
  storeAnswerReplay(storage, entry); return entry
}
export function removeAnswerReplay(storage: AgentSubmissionStorage, entry: AnswerReplayEntry) {
  assertAnswerReplayStored(storage, entry)
  try { storage.removeItem(answerReplayStorageKey(entry)); if (storage.getItem(answerReplayStorageKey(entry)) !== null) throw Error() }
  catch { throw new AnswerReplayError('AGENT_REPLAY_STORAGE_UNAVAILABLE') }
}
export function scanAnswerReplays(storage: AgentSubmissionStorage, owner: AgentSubmissionOwner) {
  const entries: AnswerReplayEntry[] = [], issues: string[] = []
  try {
    const keys = Array.from({ length: storage.length }, (_, index) => storage.key(index)).filter((key): key is string => !!key?.startsWith(`${ANSWER_REPLAY_PREFIX}${owner.job_id}:`))
    for (const key of keys) {
      try {
        const raw = storage.getItem(key); if (raw === null) continue
        const entry = parseAnswerReplayEntry(JSON.parse(raw))
        if (key !== answerReplayStorageKey(entry) || entry.job_id !== owner.job_id || entry.video_id !== owner.video_id || entry.video_sha256 !== owner.video_sha256) throw Error()
        entries.push(entry)
      } catch { issues.push('AGENT_REPLAY_ENTRY_INVALID') }
    }
  } catch { issues.push('AGENT_REPLAY_STORAGE_UNAVAILABLE') }
  return { entries, issues }
}
