import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { parseAgentSession, parseAgentTurn, type AgentSession, type AgentTurn } from '../videoAgent.ts'
import { inspectClipRequestedTimes, parseAgentVisualCapability, parseAgentVisualFrames, visualEvidenceUrl, visualFrameInRange } from '../agentVisualEvidence.ts'
import { selectAnswerClip, verifyAnswerClipBinding } from '../answerClip.ts'
import { agentDraftAfterSubmission, createAgentSubmission, parseAgentSubmissionResponse, resolveAgentSubmission, restoreAgentSubmission } from '../agentSubmissionRecovery.ts'
import { MemoryStorage, clone, session as textSession, turn as textTurn } from './support/agentSubmissionFixture.ts'

const fixture = JSON.parse(readFileSync(new URL('../../../fixtures/agent-visual.synthetic.json', import.meta.url), 'utf8'))
function sample() {
  const result = clone(fixture.result)
  const session: AgentSession = { id: result.session_id, job_id: result.job_id, video_id: result.video_id,
    source: { video_sha256: result.source_hash, transcript_hash: result.transcript_hash, duration_ms: fixture.duration_ms }, created_at: '2026-11-09T00:00:00Z', turns: [] }
  const turn: AgentTurn = { id: result.turn_id, session_id: session.id, job_id: session.job_id, question: result.question, sequence: 1, status: 'SUCCEEDED', state_version: 4,
    visual_mode: 'frames', created_at: '2026-11-09T00:00:01Z', updated_at: '2026-11-09T00:00:02Z', budget: { max_text_calls: 8, text_calls_reserved: 3 },
    stage: { id: '55555555-5555-4555-8555-555555555555', stage_type: 'AGENT_VISUAL', status: 'SUCCEEDED', attempt: 1, error_code: null }, result }
  session.turns = [turn]
  return { session, turn }
}
function owner(session: AgentSession) { return { job_id: session.job_id, video_id: session.video_id, video_sha256: session.source.video_sha256 } }
const canonical = (value: any): any => Array.isArray(value) ? value.map(canonical) : value !== null && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value

test('shared synthetic 1.1 fixture retains mixed raw sentence and frame evidence without implying a provider run', () => {
  const { session } = sample(), parsed = parseAgentSession(session, session.job_id, session.video_id, session.source.video_sha256)
  assert.equal(fixture.data_kind, 'synthetic_fixture')
  assert.equal(parsed.turns[0]!.stage.stage_type, 'AGENT_VISUAL')
  assert.equal(parsed.turns[0]!.result!.schema_version, '1.1')
  assert.equal(parsed.turns[0]!.result!.citations.length, 1)
  assert.equal(parsed.turns[0]!.result!.visual_citations!.length, 1)
})

test('pure-visual VIDEO answers are readable, while no-evidence VIDEO and cross-mode schemas are rejected', () => {
  const { session, turn } = sample(); turn.result!.citations = []
  assert.equal(parseAgentTurn(turn, session).result!.visual_citations!.length, 1)
  const empty = clone(turn); empty.result!.visual_citations = []
  assert.throws(() => parseAgentTurn(empty, session))
  for (const mutate of [(v: any) => { v.visual_mode = 'none' }, (v: any) => { v.stage.stage_type = 'SEGMENT' }, (v: any) => { v.result.schema_version = '1.0' }, (v: any) => { v.result.provenance.prompt_version = 'video-agent-v1' }]) {
    const changed = clone(turn); mutate(changed); assert.throws(() => parseAgentTurn(changed, session))
  }
})

test('GENERAL and INSUFFICIENT remain separate from video facts even with available visual observations', () => {
  for (const scope of ['GENERAL', 'INSUFFICIENT'] as const) {
    const { session, turn } = sample(); turn.result!.answer_scope = scope; turn.result!.citations = []; turn.result!.visual_citations = []
    assert.equal(parseAgentTurn(turn, session).result!.answer_scope, scope)
  }
})

test('visual contracts reject extra fields, arbitrary paths, model URLs, wrong hashes and unbound frame references', () => {
  for (const mutate of [(v: any) => { v.result.extra = 1 }, (v: any) => { v.result.visual_evidence[0].path = '../private.jpg' },
    (v: any) => { v.result.visual_evidence[0].url = 'https://other/image' }, (v: any) => { v.result.visual_evidence[0].sha256 = 'wrong' },
    (v: any) => { v.result.visual_citations[0].frame_id = 'f0002' }, (v: any) => { v.result.visual_citations.push(v.result.visual_citations[0]) },
    (v: any) => { v.result.tool_calls[1].result_frame_ids = [] }, (v: any) => { v.result.tool_calls[0].result_frame_ids = ['f0001'] },
    (v: any) => { v.result.tool_calls[1].result_utterance_ids = ['u1'] }, (v: any) => { delete v.result.tool_calls[1].result_frame_ids },
    (v: any) => { v.result.tool_calls[1].arguments.timestamps_ms = [999] }, (v: any) => { v.result.visual_citations[0].explanation = ' ' }]) {
    const { session, turn } = sample(); mutate(turn); assert.throws(() => parseAgentTurn(turn, session))
  }
})

test('PTS mapping uses exact positive HALF_UP rounding and rejects seek labels masquerading as actual time', () => {
  const frame = sample().turn.result!.visual_evidence![0]!
  const half = { ...frame, requested_ms: 0, source_pts: 1, source_time_base: '1/2000', timestamp_ms: 1 }
  assert.equal(parseAgentVisualFrames([half], 4000)[0]!.timestamp_ms, 1)
  assert.throws(() => parseAgentVisualFrames([{ ...half, timestamp_ms: 0 }], 4000))
  const large = { ...frame, requested_ms: 2000, timestamp_ms: 2000, source_pts: 9007199254740000, source_time_base: '1/4503599627' }
  // BigInt-based comparison refuses overflow/precision guesses, even though PTS itself is a safe integer.
  assert.throws(() => parseAgentVisualFrames([large], 4000))
  for (const patch of [{ requested_ms: 1001 }, { timestamp_ms: 2001 }, { source_time_base: '0/1000' }, { source_time_base: '1/10000000000' }, { source_pts: Number.MAX_SAFE_INTEGER + 1 }, { bytes: 3 }, { width: 1281 }])
    assert.throws(() => parseAgentVisualFrames([{ ...frame, ...patch }], 4000))
})

test('sub-millisecond PTS cannot round across the requested lower or 1000ms upper tolerance boundary', () => {
  const frame = sample().turn.result!.visual_evidence![0]!
  assert.throws(() => parseAgentVisualFrames([{ ...frame, source_pts: 9999, source_time_base: '1/10000', timestamp_ms: 1000 }], 4000), '999.9ms is before requested 1000ms despite equal display labels')
  assert.throws(() => parseAgentVisualFrames([{ ...frame, source_pts: 20001, source_time_base: '1/10000', timestamp_ms: 2000 }], 4000), '2000.1ms exceeds requested+1000ms despite rounded timestamp 2000')
  assert.equal(parseAgentVisualFrames([{ ...frame, source_pts: 20000, source_time_base: '1/10000', timestamp_ms: 2000 }], 4000)[0]!.timestamp_ms, 2000)
})

test('frame identity follows first observation rather than time order, and repeat positions reuse the same ID', () => {
  const { session, turn } = sample(), first = turn.result!.visual_evidence![0]!
  turn.result!.visual_evidence!.push({ ...first, frame_id: 'f0002', requested_ms: 500, timestamp_ms: 500, source_pts: 500 })
  turn.result!.tool_calls.push({ step: 3, tool_name: 'inspect_frames', arguments: { timestamps_ms: [500, 1000] }, result_utterance_ids: [], result_frame_ids: ['f0002', 'f0001'] })
  assert.equal(parseAgentTurn(turn, session).result!.visual_evidence![1]!.timestamp_ms, 500)
  const duplicate = clone(turn); duplicate.result!.visual_evidence![1]!.requested_ms = 1000
  assert.throws(() => parseAgentTurn(duplicate, session))
  const actualDuplicate = clone(turn); actualDuplicate.result!.visual_evidence![1]!.timestamp_ms = 1000; actualDuplicate.result!.visual_evidence![1]!.source_pts = 1000
  assert.throws(() => parseAgentTurn(actualDuplicate, session))
})

test('inspect_clip is a bounded half-open 2fps sample, and actual frames must remain inside that interval', () => {
  assert.deepEqual(inspectClipRequestedTimes(0, 12000), Array.from({ length: 24 }, (_, i) => i * 500))
  assert.deepEqual(inspectClipRequestedTimes(100, 601), [100, 600])
  assert.throws(() => inspectClipRequestedTimes(0, 12001))
  const { session, turn } = sample()
  turn.result!.tool_calls[1] = { step: 2, tool_name: 'inspect_clip', arguments: { start_ms: 1000, end_ms: 1500 }, result_utterance_ids: [], result_frame_ids: ['f0001'] }
  assert.equal(parseAgentTurn(turn, session).result!.visual_evidence!.length, 1)
  turn.result!.visual_evidence![0]!.timestamp_ms = 1500; turn.result!.visual_evidence![0]!.source_pts = 1500
  assert.throws(() => parseAgentTurn(turn, session))
})

test('sampled clip membership uses exact half-open PTS bounds while preserving rounded display time', () => {
  const { session, turn } = sample(), frame = turn.result!.visual_evidence![0]!
  turn.result!.tool_calls[1] = { step: 2, tool_name: 'inspect_clip', arguments: { start_ms: 1000, end_ms: 1500 }, result_utterance_ids: [], result_frame_ids: ['f0001'] }
  Object.assign(frame, { source_pts: 14999, source_time_base: '1/10000', timestamp_ms: 1500 })
  assert.equal(visualFrameInRange(frame, 1000, 1500), true, '1499.9ms remains inside even when displayed as 1500ms')
  assert.equal(parseAgentTurn(turn, session).result!.visual_evidence![0]!.timestamp_ms, 1500)
  for (const pts of [15000, 15001]) {
    frame.source_pts = pts
    assert.equal(visualFrameInRange(frame, 1000, 1500), false)
    assert.throws(() => parseAgentTurn(turn, session))
  }
  frame.source_pts = 9999; frame.timestamp_ms = 1000
  assert.equal(visualFrameInRange(frame, 1000, 1500), false)
  assert.throws(() => parseAgentTurn(turn, session))
})

test('failed visual tools with no returned frames grant no fabricated observation', () => {
  const { session, turn } = sample(); turn.result!.visual_evidence = []; turn.result!.visual_citations = []; turn.result!.tool_calls[1]!.result_frame_ids = []
  assert.equal(parseAgentTurn(turn, session).result!.visual_evidence!.length, 0, 'mixed response can still use original sentence evidence')
  turn.result!.citations = []
  assert.throws(() => parseAgentTurn(turn, session))
})

test('a nonempty visual tool result must match every requested sample, rather than an unmarked partial subset', () => {
  const { session, turn } = sample()
  turn.result!.tool_calls[1]!.arguments = { timestamps_ms: [1000, 1500] }
  assert.throws(() => parseAgentTurn(turn, session))
  turn.result!.tool_calls[1] = { step: 2, tool_name: 'inspect_clip', arguments: { start_ms: 1000, end_ms: 2000 }, result_utterance_ids: [], result_frame_ids: ['f0001'] }
  assert.throws(() => parseAgentTurn(turn, session))
})

test('1.1 sentence anchors must be observed in this turn, and exact visual-tool replays cannot add frames', () => {
  const { session, turn } = sample()
  turn.result!.tool_calls[0]!.result_utterance_ids = []
  assert.throws(() => parseAgentTurn(turn, session))
  turn.result!.tool_calls[0]!.result_utterance_ids = ['u1']
  turn.result!.tool_calls.push({ ...clone(turn.result!.tool_calls[1]!), step: 3 })
  assert.throws(() => parseAgentTurn(turn, session))
  turn.result!.tool_calls[2]!.result_frame_ids = []
  assert.equal(parseAgentTurn(turn, session).result!.visual_evidence!.length, 1)
})

test('capability responses require explicit coherent mode/model and fixed limits; absence never means enablement', () => {
  const configured = { visual_mode: 'frames', visual_model_id: 'configured-vl', max_frames: 32, max_clip_duration_ms: 12000, clip_sample_interval_ms: 500 }
  assert.equal(parseAgentVisualCapability(configured).visual_mode, 'frames')
  assert.equal(parseAgentVisualCapability({ ...configured, visual_mode: null, visual_model_id: null }).visual_mode, null)
  for (const value of [null, {}, { ...configured, visual_model_id: null }, { ...configured, max_frames: 100 }, { ...configured, extra: true }]) assert.throws(() => parseAgentVisualCapability(value))
})

test('screenshot URLs are fixed same-origin owner/frame endpoints, without arbitrary paths or locations', () => {
  const { turn } = sample()
  assert.equal(visualEvidenceUrl(turn.id, 'f0001'), `/api/agent-turns/${turn.id}/visual-evidence/f0001/content`)
  for (const id of ['../private', 'https://evil/image', 'f0033']) assert.throws(() => visualEvidenceUrl(turn.id, id))
  assert.throws(() => visualEvidenceUrl('wrong-owner/path', 'f0001'))
})

test('old 1.0 answer shapes and hashes remain exact, and mixed 1.1 anchors only select real utterances', async () => {
  const oldSession = textSession(), oldTurn = textTurn(), rawBefore = JSON.stringify(oldTurn.result)
  const old = parseAgentTurn(oldTurn, oldSession)
  assert.equal(JSON.stringify(old.result), rawBefore)
  assert.equal(Object.hasOwn(old.result!, 'visual_evidence'), false)
  const oldSelection = await selectAnswerClip(oldSession, oldTurn, ['u1'])
  assert.equal(oldSelection.binding.answer_hash, createHash('sha256').update(JSON.stringify(canonical(oldTurn.result))).digest('hex'))
  const { session, turn } = sample(), selected = await selectAnswerClip(session, turn, ['u1'])
  assert.deepEqual(selected.binding.utterance_ids, ['u1'])
  assert.deepEqual((await verifyAnswerClipBinding(selected.binding, turn)).binding, selected.binding)
  await assert.rejects(selectAnswerClip(session, turn, ['f0001']))
  turn.result!.citations = []
  await assert.rejects(selectAnswerClip(session, turn, ['u1']))
})

test('visual immutable receipts freeze original mode and never accept a text downgrade on resolver success or retry', async () => {
  const { session, turn } = sample(), store = new MemoryStorage()
  const input = JSON.stringify({ question: turn.question, max_text_calls: 8, visual_mode: 'frames' })
  const entry = createAgentSubmission(store, owner(session), 'TURN', input, () => 'visual-original-key', [session], session)
  assert.equal(entry.input, input)
  let posts = 0
  const result = await resolveAgentSubmission(entry, { get: async () => turn, readSession: async () => session, beforePost: () => {}, current: () => true, post: async () => { posts++; return turn } })
  assert.equal(result?.kind === 'TURN' && result.turn.visual_mode, 'frames'); assert.equal(posts, 0)
  const downgraded = clone(turn); downgraded.visual_mode = 'none'; downgraded.stage.stage_type = 'SEGMENT'; downgraded.result = null; downgraded.status = 'FAILED'
  assert.throws(() => parseAgentSubmissionResponse(downgraded, entry, session))
  assert.equal(agentDraftAfterSubmission(entry, { session_id: session.id, question: turn.question, budget: 8, visual_mode: 'none' }), turn.question)
  assert.equal(agentDraftAfterSubmission(entry, { session_id: session.id, question: turn.question, budget: 8, visual_mode: 'frames' }), '')
})

test('legacy unknown text receipts retain original missing mode even when current UI may request visual later', async () => {
  const session = textSession(), turn = textTurn(), store = new MemoryStorage()
  const input = JSON.stringify({ question: turn.question, max_text_calls: 8 })
  const entry = createAgentSubmission(store, owner(session), 'TURN', input, () => 'old-text-key', [session], session)
  restoreAgentSubmission(store, entry)
  const calls: string[] = []
  await resolveAgentSubmission(entry, { current: () => true, get: async () => { throw Object.assign(Error('missing'), { status: 404, code: 'AGENT_TURN_SUBMISSION_NOT_FOUND' }) }, readSession: async () => session,
    beforePost: () => {}, post: async () => { calls.push(entry.input); return turn } })
  assert.deepEqual(calls, [input]); assert.equal(calls[0]!.includes('visual_mode'), false)
})
