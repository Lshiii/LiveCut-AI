import test from 'node:test'
import assert from 'node:assert/strict'
import { parseAgentTurn, newestAgentTurn } from '../videoAgent.ts'
import { parseAgentScreenSource, parseAgentScreenSourceResponse, parseAgentScreenSearchCapability, screenSearchTokens, screenRequestedTime } from '../agentScreenSearch.ts'
import { agentSubmissionInput, createAgentSubmission, parseAgentSubmissionResponse, agentDraftAfterSubmission } from '../agentSubmissionRecovery.ts'
import { agentNoteOwner } from '../agentNote.ts'
import { reviewAgentEvidence } from '../agentEvidenceReview.ts'
import { agentFrameFollowupTarget } from '../agentFrameFollowup.ts'
import { MemoryStorage } from './support/agentSubmissionFixture.ts'
import { screenFixture, screenSample } from './support/agentScreenSearchFixture.ts'

assert.equal(screenFixture.data_kind, 'synthetic_fixture')
for (const source of screenFixture.cases) test(`shared screen answer ${source.case_id}`, () => {
  const { session, turn } = screenSample(source.case_id), before = JSON.stringify(turn)
  if (!source.expected_valid) assert.throws(() => parseAgentTurn(turn, session))
  else { const parsed = parseAgentTurn(turn, session); assert.deepEqual(parsed.result, turn.result); assert.equal(parsed.stage.required_worker_protocol, 4); assert.equal(parsed.replay_source_turn_id, undefined); assert.ok(reviewAgentEvidence(parsed.result)) }
  assert.equal(JSON.stringify(turn), before)
})
for (const sample of screenFixture.query_cases) test(`shared screen query ${sample.case_id}`, () => {
  const args = sample.arguments
  const parse = () => { const tokens = screenSearchTokens(args.query); assert.ok(Number.isSafeInteger(args.top_k) && args.top_k >= 1 && args.top_k <= 3); return tokens }
  if (sample.expected_valid) assert.ok(parse().length)
  else assert.throws(parse)
})
test('screen capability/source envelopes are exact; source sampling uses safe integer arithmetic and immutable identity', () => {
  assert.equal(parseAgentScreenSearchCapability({ screen_search_supported: true, worker_protocol: 4 }), true)
  for (const v of [{ screen_search_supported: true, worker_protocol: 3 }, { screen_search_supported: true, worker_protocol: 4, extra: 1 }, null]) assert.throws(() => parseAgentScreenSearchCapability(v))
  const { turn, session } = screenSample(), source = turn.screen_source!
  assert.deepEqual(parseAgentScreenSourceResponse({ screen_source: source }, session.source.duration_ms), source)
  for (const v of [{ ...source, extra: 1 }, { ...source, duration_ms: source.duration_ms + 1 }, { ...source, frame_count: 121 }, { ...source, coverage_truncated: true }, { ...source, text_frame_count: source.frame_count + 1 }]) assert.throws(() => parseAgentScreenSource(v, session.source.duration_ms))
  assert.throws(() => parseAgentScreenSourceResponse({ screen_source: source, extra: 1 }))
  const huge = parseAgentScreenSource({ ...source, duration_ms: Number.MAX_SAFE_INTEGER, frame_count: 120 })
  assert.equal(screenRequestedTime(huge, 119), Number(239n * BigInt(Number.MAX_SAFE_INTEGER) / 240n))
  assert.throws(() => parseAgentScreenSource({ ...source, duration_ms: 1000, frame_count: 120, text_frame_count: 0 }))
  assert.throws(() => parseAgentScreenSource({ ...source, duration_ms: 90000, frame_count: 3 }))
  const changed = structuredClone(turn); changed.screen_source!.index_hash = 'a'.repeat(64)
  assert.throws(() => newestAgentTurn(turn, changed))
})
test('new screen source stage identity is a lowercase canonical UUID without widening old identifier rules', () => {
  const source = screenSample().turn.screen_source!, id = 'abcdefab-cdef-abcd-efab-cdefabcdefab'
  assert.equal(parseAgentScreenSource({ ...source, segment_stage_id: id }).segment_stage_id, id)
  for (const segment_stage_id of ['segment-1', id.toUpperCase(), ' ' + id, id + ' ']) assert.throws(() => parseAgentScreenSource({ ...source, segment_stage_id }))
})
test('legacy OCR locator may round one millisecond outside fresh request bounds while fresh Agent frames retain their own strict PTS checks', () => {
  for (const edge of ['before-request', 'after-limit']) {
    const { session, turn } = screenSample(), result = turn.result!, hit = result.screen_searches![0]!.hits[0]!, frame = result.visual_evidence!.find(frame => frame.frame_id === hit.frame_id)!
    const actual = hit.requested_ms + (edge === 'after-limit' ? 1000 : 0)
    frame.timestamp_ms = actual; frame.source_pts = actual; frame.source_time_base = '1/1000'
    hit.source_pts = actual; hit.source_time_base = '1/1000'; hit.ocr_timestamp_ms = actual + (edge === 'before-request' ? -1 : 1)
    assert.deepEqual(parseAgentTurn(turn, session).result, result)
    hit.ocr_timestamp_ms += edge === 'before-request' ? -1 : 1
    assert.throws(() => parseAgentTurn(turn, session))
    hit.ocr_timestamp_ms = actual
    frame.timestamp_ms = hit.requested_ms + 1001; frame.source_pts = frame.timestamp_ms; hit.source_pts = frame.source_pts; hit.ocr_timestamp_ms = frame.timestamp_ms
    assert.throws(() => parseAgentTurn(turn, session))
  }
})
test('screen opt-in binds durable raw request and current draft separately; legacy no-policy body remains exact', () => {
  const { turn, session } = screenSample(), store = new MemoryStorage(), input = { question: turn.question, max_text_calls: 8, visual_mode: 'frames', evidence_mode: 'structured', evidence_policy: 'screen_search_v1' }
  assert.deepEqual(agentSubmissionInput('TURN', input), input)
  const owner = { job_id: session.job_id, video_id: session.video_id, video_sha256: session.source.video_sha256 }
  const entry = createAgentSubmission(store, owner, 'TURN', JSON.stringify(input), () => 'screen-key', [session], session)
  assert.equal(parseAgentSubmissionResponse(turn, entry, session).kind, 'TURN')
  const draft = { session_id: session.id, question: turn.question, budget: 8, visual_mode: 'frames' as const, evidence_mode: 'structured' as const, evidence_policy: 'speech_lookup_v1' as const }
  assert.equal(agentDraftAfterSubmission(entry, draft), turn.question)
  assert.equal(agentDraftAfterSubmission(entry, { ...draft, evidence_policy: 'screen_search_v1' }), '')
  assert.deepEqual(agentSubmissionInput('TURN', { question: '旧问题', max_text_calls: 8 }), { question: '旧问题', max_text_calls: 8 })
})
test('screen turns cannot downgrade protocol or impersonate zero-budget replay, nor accept missing source', () => {
  for (const mutate of [(v: any) => { v.stage.required_worker_protocol = 3 }, (v: any) => { delete v.stage.required_worker_protocol }, (v: any) => { v.stage.required_worker_protocol = 2 }, (v: any) => { delete v.screen_source }, (v: any) => { v.budget.max_text_calls = 0; v.budget.text_calls_reserved = 0 }, (v: any) => { v.result.evidence_policy = 'speech_lookup_v1' }, (v: any) => { v.result.screen_source.index_hash = 'a'.repeat(64) }, (v: any) => { v.result.extra = true }]) {
    const { session, turn } = screenSample(); mutate(turn); assert.throws(() => parseAgentTurn(turn, session))
  }
})
test('full raw trace and public receipts remain strict even when an empty/reused visual step is removed from the temporary projection', () => {
  const mutations: Array<(v: any) => void> = [
    v => { v.result.tool_calls[0].extra = true }, v => { v.result.tool_calls[0].origin = 'EVIDENCE_POLICY' },
    v => { v.result.tool_calls[0].arguments.top_k = true }, v => { v.result.tool_calls[0].arguments.query = 'x '.repeat(9) },
    v => { v.result.tool_calls[0].result_utterance_ids = ['ocr_000001'] }, v => { v.result.screen_searches[0].extra = 1 },
    v => { v.result.screen_searches[0].tool_step = 2 }, v => { v.result.screen_searches[0].total_match_count = 121 },
    v => { v.result.screen_searches[0].hits[0].requested_ms++ }, v => { v.result.screen_searches[0].hits[0].source_pts++ },
    v => { v.result.screen_searches[0].hits[0].observed_text = 'different locator' }, v => { v.result.screen_searches[0].hits[0].observed_text = '\ud800' },
    v => { v.result.screen_searches[0].hits[0].ocr_frame_id = 'ocr_000121' }, v => { v.result.screen_searches[0].hits[0].frame_id = 'f0032' },
  ]
  for (const mutate of mutations) { const { session, turn } = screenSample(); mutate(turn); assert.throws(() => parseAgentTurn(turn, session)) }
  const { session, turn } = screenSample('native_failed_then_screen_observed')
  const modified = structuredClone(turn); modified.result!.tool_calls[0]!.arguments = { timestamps_ms: [session.source.duration_ms] }
  assert.throws(() => parseAgentTurn(modified, session))
  const native = structuredClone(turn); native.result!.tool_calls[0]!.result_utterance_ids = ['u001']; assert.throws(() => parseAgentTurn(native, session))
})
test('1.5 duplicate ASR tools must be empty even when raw argument keys have a different insertion order', () => {
  const { session, turn } = screenSample(), calls = turn.result!.tool_calls, id = screenFixture.utterances[0].utterance_id
  calls.push({ step: 2, tool_name: 'read_context', arguments: { utterance_id: id, before: 0, after: 0 }, result_utterance_ids: [id], result_frame_ids: [], origin: 'MODEL' })
  calls.push({ step: 3, tool_name: 'read_context', arguments: { after: 0, before: 0, utterance_id: id }, result_utterance_ids: [], result_frame_ids: [], origin: 'MODEL' })
  assert.deepEqual(parseAgentTurn(turn, session).result, turn.result)
  calls[2]!.result_utterance_ids = [id]; assert.throws(() => parseAgentTurn(turn, session))
  calls[2]!.arguments = { ...calls[1]!.arguments }; assert.throws(() => parseAgentTurn(turn, session))
})
test('screen accepted frames remain valid note owners and explicit followup uses time without inheriting policy', () => {
  const { session, turn } = screenSample(); session.turns = [parseAgentTurn(turn, session)]
  assert.ok(agentNoteOwner(session, session.turns[0]!))
  const id = turn.result!.visual_citations![0]!.frame_id
  const target = agentFrameFollowupTarget(session, turn.id, id, { owner: { job_id: session.job_id, video_id: session.video_id, source_hash: session.source.video_sha256, duration_ms: session.source.duration_ms }, session_id: session.id, transcript_hash: session.source.transcript_hash, active: true, visible: true, pending: false, visual_available: true, focus_supported: true })
  assert.equal(target.hint.timestamp_ms, turn.result!.visual_evidence!.find(frame => frame.frame_id === id)!.timestamp_ms)
  assert.equal(Object.hasOwn(target, 'evidence_policy'), false)
})
