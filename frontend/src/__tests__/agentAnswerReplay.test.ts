import test from 'node:test'
import assert from 'node:assert/strict'
import { answerReplayCandidate, answerReplaySubmissionMissing, parseAnswerReplayCapability, parseAnswerReplayPreview, parseAnswerReplaySubmission } from '../agentAnswerReplay.ts'
import { AnswerReplayError, answerReplayStorageKey, assertAnswerReplayStored, prepareAnswerReplay, removeAnswerReplay, scanAnswerReplays, storeAnswerReplay } from '../agentAnswerReplayStorage.ts'
import { newestAgentTurn, parseAgentSession, parseAgentTurn } from '../videoAgent.ts'
import { agentNoteOwner } from '../agentNote.ts'
import { agentFrameFollowupTarget } from '../agentFrameFollowup.ts'
import { MemoryStorage } from './support/agentSubmissionFixture.ts'
import { answerReplaySample } from './support/agentAnswerReplayFixture.ts'
const KEY = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
test('zero-budget children keep a distinct source identity and consume the unchanged strict 1.4 result', () => {
  const { session, source, child } = answerReplaySample()
  const value = parseAgentSession({ ...session, turns: [source, child] }, session.job_id, session.video_id, session.source.video_sha256)
  assert.equal(value.turns[0]!.status, 'FAILED'); assert.equal(value.turns[0]!.result, null)
  assert.equal(value.turns[1]!.budget.max_text_calls, 0); assert.equal(value.turns[1]!.stage.required_worker_protocol, 3)
  assert.equal(agentNoteOwner(value, child).turn_id, child.id)
  const cited = child.result!.visual_citations![0]!
  assert.equal(agentFrameFollowupTarget(value, child.id, cited.frame_id, { owner: { job_id: value.job_id, video_id: value.video_id, source_hash: value.source.video_sha256, duration_ms: value.source.duration_ms }, session_id: value.id,
    transcript_hash: value.source.transcript_hash, active: true, visible: true, pending: false, visual_available: true, focus_supported: true }).turn_id, child.id)
})
test('zero budget cannot leak into an ordinary turn or an incomplete or foreign recovery identity', () => {
  const { session, source, child } = answerReplaySample()
  for (const change of [(v: any) => delete v.replay_source_turn_id, (v: any) => delete v.replay_snapshot_hash, (v: any) => delete v.stage.required_worker_protocol,
    (v: any) => v.stage.required_worker_protocol = 2, (v: any) => v.budget.max_text_calls = 1, (v: any) => v.budget.text_calls_reserved = 1,
    (v: any) => v.replay_source_turn_id = v.id, (v: any) => v.evidence_policy = undefined, (v: any) => v.result.citations[0].text = 'changed']) {
    const value = structuredClone(child); change(value)
    if (value.result?.citations[0]?.text === 'changed') { value.result.citations[0].utterance_id = 'unseen-source' }
    assert.throws(() => parseAgentTurn(value, session))
  }
  assert.throws(() => parseAgentTurn({ ...source, budget: { max_text_calls: 0, text_calls_reserved: 0 } }, session))
  assert.throws(() => parseAgentSession({ ...session, turns: [child] }, session.job_id, session.video_id, session.source.video_sha256))
  const duplicate = { ...structuredClone(child), id: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', sequence: 3 }
  duplicate.result!.turn_id = duplicate.id
  assert.throws(() => parseAgentSession({ ...session, turns: [source, child, duplicate] }, session.job_id, session.video_id, session.source.video_sha256))
  assert.throws(() => newestAgentTurn(child, { ...child, replay_snapshot_hash: 'b'.repeat(64) }))
  assert.equal(newestAgentTurn(source, { ...source, status: 'SUCCEEDED', state_version: 100 }).status, 'FAILED')
})
test('only exact capability and eligible preview prove a snapshot; another child never proves this key', () => {
  const { session, source, child, preview } = answerReplaySample()
  assert.equal(answerReplayCandidate(source), true)
  assert.equal(parseAnswerReplayCapability({ cached_answer_replay_supported: true, worker_protocol: 3 }), true)
  for (const raw of [{ cached_answer_replay_supported: true, worker_protocol: 2 }, { cached_answer_replay_supported: true, worker_protocol: 3, extra: true }]) assert.throws(() => parseAnswerReplayCapability(raw))
  assert.equal(parseAnswerReplayPreview(preview, session, source).snapshot_hash, preview.snapshot_hash)
  for (const raw of [{ ...preview, extra: true }, { ...preview, source_turn_id: child.id }, { ...preview, snapshot_hash: null }, { ...preview, source_budget: { max_text_calls: 9, text_calls_reserved: 3 } }, { ...preview, existing_turn: child }, { ...preview, eligible: false, block_reason: 'UNRECOGNIZED', snapshot_hash: null }]) assert.throws(() => parseAnswerReplayPreview(raw, session, source))
  assert.equal(parseAnswerReplayPreview({ ...preview, eligible: false, block_reason: 'ALREADY_EXISTS', snapshot_hash: null, existing_turn: child }, session, source).existing_turn!.id, child.id)
  assert.throws(() => parseAnswerReplayPreview({ ...preview, eligible: false, block_reason: 'CACHE_UNAVAILABLE', snapshot_hash: null, existing_turn: child }, session, source))
})
test('independent immutable local entries preserve concurrent writers and clearing only the acknowledged key', () => {
  const { session, source, preview, owner } = answerReplaySample(), storage = new MemoryStorage()
  storage.setItem('livecut-agent-submission-v1:foreign', 'old raw bytes')
  const first = prepareAnswerReplay(storage, session, source.id, preview.snapshot_hash, KEY)
  const second = prepareAnswerReplay(storage, session, source.id, preview.snapshot_hash, 'dddddddd-dddd-4ddd-8ddd-dddddddddddd')
  assert.equal(scanAnswerReplays(storage, owner).entries.length, 2)
  assertAnswerReplayStored(storage, first); removeAnswerReplay(storage, first)
  assert.deepEqual(scanAnswerReplays(storage, owner).entries, [second]); assert.equal(storage.getItem('livecut-agent-submission-v1:foreign'), 'old raw bytes')
  assert.throws(() => removeAnswerReplay(storage, first)); assertAnswerReplayStored(storage, second)
  assert.throws(() => storeAnswerReplay(storage, { ...second, input: JSON.stringify({ snapshot_hash: 'b'.repeat(64) }), snapshot_hash: 'b'.repeat(64) }))
  assertAnswerReplayStored(storage, second)
})
test('malformed or externally changed entries fail closed and a write without read-back never permits posting', () => {
  const { session, source, preview, owner } = answerReplaySample(), storage = new MemoryStorage()
  const entry = prepareAnswerReplay(storage, session, source.id, preview.snapshot_hash, KEY)
  storage.setItem(answerReplayStorageKey(entry), '{}'); assert.throws(() => assertAnswerReplayStored(storage, entry))
  assert.deepEqual(scanAnswerReplays(storage, owner).issues, ['AGENT_REPLAY_ENTRY_INVALID'])
  const broken = new MemoryStorage(); broken.getItem = () => null
  assert.throws(() => prepareAnswerReplay(broken, session, source.id, preview.snapshot_hash, KEY), AnswerReplayError)
})
test('receipt binds original key body source and snapshot and never regresses a fresher child', () => {
  const { session, source, child, preview } = answerReplaySample(), storage = new MemoryStorage()
  const entry = prepareAnswerReplay(storage, session, source.id, preview.snapshot_hash, KEY)
  const old = { ...structuredClone(child), status: 'RUNNING', state_version: 1, result: null, stage: { ...child.stage, status: 'RUNNING' } }
  const verified = parseAnswerReplaySubmission(old, entry, { ...session, turns: [source, child] })
  assert.equal(verified.turns[1]!.status, 'SUCCEEDED')
  for (const value of [{ ...child, replay_snapshot_hash: 'b'.repeat(64) }, { ...child, replay_source_turn_id: child.id }]) assert.throws(() => parseAnswerReplaySubmission(value, entry, session))
  assert.throws(() => parseAnswerReplaySubmission(child, entry, { ...session, source: { ...session.source, transcript_hash: 'c'.repeat(64) } }))
  assert.equal(answerReplaySubmissionMissing({ status: 404, code: 'AGENT_RESPONSE_REPLAY_SUBMISSION_NOT_FOUND' }), true)
  for (const value of [{ status: 404, code: 'HTTP_404' }, { status: 503, code: 'AGENT_RESPONSE_REPLAY_SUBMISSION_NOT_FOUND' }, { code: 'AGENT_RESPONSE_REPLAY_SUBMISSION_NOT_FOUND' }]) assert.equal(answerReplaySubmissionMissing(value), false)
})
