import test from 'node:test'
import assert from 'node:assert/strict'
import { newestAgentTurn, parseAgentTextCoverageCapability, parseAgentTurn, type AgentTurn } from '../videoAgent.ts'
import { agentDraftMatchesSubmission, agentSubmissionInput, createAgentSubmission, parseAgentSubmissionEntry, parseAgentSubmissionResponse, resolveAgentSubmission, sameAgentSubmission } from '../agentSubmissionRecovery.ts'
import { clone, entry, MemoryStorage, owner, session, turn } from './support/agentSubmissionFixture.ts'

function covered(): AgentTurn {
  const value = turn(); value.qa_policy = 'coverage_v1'; value.stage.required_worker_protocol = 6
  value.result!.provenance.prompt_version = 'video-agent-coverage-v1'
  return value
}
test('text coverage capability requires exact true and protocol6 rather than an inferred or malformed feature', () => {
  assert.equal(parseAgentTextCoverageCapability({ text_coverage_supported: true, worker_protocol: 6 }), true)
  for (const raw of [null, {}, { text_coverage_supported: false, worker_protocol: 6 }, { text_coverage_supported: true, worker_protocol: 1 },
    { text_coverage_supported: 'true', worker_protocol: 6 }, { text_coverage_supported: true, worker_protocol: 6, extra: true }]) assert.throws(() => parseAgentTextCoverageCapability(raw))
})
test('new plain turn keeps schema1.0 but binds coverage policy to protocol6 and its exact prompt version', () => {
  assert.equal(parseAgentTurn(covered(), session()).result!.provenance.prompt_version, 'video-agent-coverage-v1')
  assert.equal(parseAgentTurn(turn(), session()).result!.provenance.prompt_version, 'video-agent-v1')
  for (const mutate of [(v: any) => { delete v.stage.required_worker_protocol }, (v: any) => { v.stage.required_worker_protocol = 4 },
    (v: any) => { v.qa_policy = 'other' }, (v: any) => { v.qa_policy = null }, (v: any) => { delete v.qa_policy },
    (v: any) => { v.visual_mode = 'frames' }, (v: any) => { v.focus_timestamp_ms = 0 }, (v: any) => { v.evidence_mode = 'structured' },
    (v: any) => { v.evidence_policy = 'speech_lookup_v1' }, (v: any) => { v.result.provenance.prompt_version = 'video-agent-v1' },
    (v: any) => { v.result.schema_version = '1.6' }, (v: any) => { v.result.question_parts = [] }, (v: any) => { v.result.coverage = [] }]) {
    const value = covered(); mutate(value); assert.throws(() => parseAgentTurn(value, session()))
  }
  const legacy = turn(); legacy.result!.provenance.prompt_version = 'video-agent-coverage-v1'; assert.throws(() => parseAgentTurn(legacy, session()))
})
test('coverage citations must have been acquired in this turn and late snapshots cannot change its immutable route', () => {
  const value = covered(); value.result!.tool_calls[0]!.result_utterance_ids = []; assert.throws(() => parseAgentTurn(value, session()))
  const changed = covered(); delete changed.qa_policy; delete changed.stage.required_worker_protocol; changed.result!.provenance.prompt_version = 'video-agent-v1'
  assert.throws(() => newestAgentTurn(covered(), changed)); assert.throws(() => newestAgentTurn(turn(), covered()))
})
test('coverage input is optional only for pure text and original durable bytes cannot be upgraded in place', () => {
  const body = { question: '原问题A', max_text_calls: 8, qa_policy: 'coverage_v1' }
  assert.deepEqual(agentSubmissionInput('TURN', body), body)
  for (const change of [{ qa_policy: 'none' }, { qa_policy: null }, { visual_mode: 'frames' }, { focus_timestamp_ms: 0 },
    { evidence_mode: 'structured' }, { evidence_policy: 'speech_lookup_v1' }]) assert.equal(agentSubmissionInput('TURN', { ...body, ...change }), null)
  const store = new MemoryStorage(), original = entry(), created = createAgentSubmission(store, owner, 'TURN', JSON.stringify(body), () => 'coverage-key', [session()], session())
  assert.ok(parseAgentSubmissionEntry(created)); assert.notEqual(created.input, original.input); assert.equal(sameAgentSubmission(original, { ...original, input: created.input }), false)
  const raw = covered(); assert.equal(parseAgentSubmissionResponse(raw, created, session()).kind, 'TURN')
  assert.throws(() => parseAgentSubmissionResponse(turn(), created, session())); assert.throws(() => parseAgentSubmissionResponse(raw, original, session()))
  assert.equal(agentDraftMatchesSubmission(created, { session_id: session().id, question: body.question, budget: 8, qa_policy: 'coverage_v1' }), true)
  assert.equal(agentDraftMatchesSubmission(created, { session_id: session().id, question: body.question, budget: 8 }), false)
})
test('legacy unknown replay posts its exact old body after business absence and never acquires a new policy or key', async () => {
  const original = clone(entry()), posted: string[] = []
  const result = await resolveAgentSubmission(original, { get: async () => { throw Object.assign(new Error('missing'), { code: 'AGENT_TURN_SUBMISSION_NOT_FOUND', status: 404 }) },
    post: async () => { posted.push(original.input); return turn() }, readSession: async () => session(), beforePost: () => {}, current: () => true })
  assert.equal(result?.kind, 'TURN'); assert.deepEqual(posted, [entry().input]); assert.ok(!posted[0]!.includes('qa_policy'))
})
