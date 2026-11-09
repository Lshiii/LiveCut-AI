import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { AgentFocusError, canCaptureAgentFocus, captureAgentFocus, focusHintMatches, parseAgentFocusCapability } from '../agentFocus.ts'
import { newestAgentTurn, parseAgentSession, parseAgentTurn, type AgentSession, type AgentTurn } from '../videoAgent.ts'
import { agentDraftAfterSubmission, agentDraftMatchesSubmission, createAgentSubmission, parseAgentSubmissionEntry, parseAgentSubmissionResponse, resolveAgentSubmission } from '../agentSubmissionRecovery.ts'
import { selectAnswerClip, verifyAnswerClipBinding } from '../answerClip.ts'
import { clone, MemoryStorage } from './support/agentSubmissionFixture.ts'

const fixture = JSON.parse(readFileSync(new URL('../../../fixtures/agent-visual-focus.synthetic.json', import.meta.url), 'utf8'))
function sample(focus = 1000) {
  const result = clone(fixture.result)
  result.focus_timestamp_ms = focus; result.tool_calls[0].arguments.timestamps_ms = [focus]
  Object.assign(result.visual_evidence[0], { requested_ms: focus, timestamp_ms: focus, source_pts: focus })
  const session: AgentSession = { id: result.session_id, job_id: result.job_id, video_id: result.video_id, created_at: '2026-11-09T00:00:00Z',
    source: { video_sha256: result.source_hash, transcript_hash: result.transcript_hash, duration_ms: fixture.duration_ms }, turns: [] }
  const turn: AgentTurn = { id: result.turn_id, session_id: session.id, job_id: session.job_id, question: result.question, sequence: 1, status: 'SUCCEEDED', state_version: 4,
    visual_mode: 'frames', focus_timestamp_ms: focus, created_at: '2026-11-09T00:00:01Z', updated_at: '2026-11-09T00:00:02Z', budget: { max_text_calls: 8, text_calls_reserved: 1 },
    stage: { id: '55555555-5555-4555-8555-555555555555', stage_type: 'AGENT_VISUAL_FOCUS', status: 'SUCCEEDED', attempt: 1, error_code: null }, result }
  session.turns = [turn]; return { session, turn }
}
function captureInput() {
  const { session } = sample()
  const player = { currentTime: 1.234999, duration: 4, readyState: 2, seeking: false, currentSrc: 'https://local.test/video/content', paused: false, pauses: 0,
    pause() { this.paused = true; this.pauses++ } }
  return { player, owner: { job_id: session.job_id, video_id: session.video_id, source_hash: session.source.video_sha256, duration_ms: 4000 },
    expectedSourceUrl: player.currentSrc, active: true, visible: true, frames: true, supported: true, pending: false, pendingPreview: false }
}
const owner = (session: AgentSession) => ({ job_id: session.job_id, video_id: session.video_id, video_sha256: session.source.video_sha256 })
const canonical = (value: any): any => Array.isArray(value) ? value.map(canonical) : value !== null && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value

test('explicit capture pauses and floors live player time into a frozen source-bound hint, not frame evidence', () => {
  const input = captureInput(); let cancelled = 0
  const hint = captureAgentFocus(input, () => { cancelled++ })
  assert.equal(cancelled, 1); assert.equal(input.player.paused, true); assert.equal(hint.timestamp_ms, 1234)
  assert.equal(Object.isFrozen(hint), true); assert.equal(Object.hasOwn(hint, 'frame_id'), false)
  input.player.currentTime = 2.5
  assert.equal(hint.timestamp_ms, 1234, 'seeking never changes an attached chip')
  assert.equal(captureAgentFocus(input, () => {}).timestamp_ms, 2500, 'only another explicit capture changes the hint')
})

test('zero is a valid capture and EOF, seeking, unloaded, hidden, wrong-source and unsupported states are rejected', () => {
  const zero = captureInput(); zero.player.currentTime = 0
  assert.equal(captureAgentFocus(zero, () => {}).timestamp_ms, 0)
  for (const change of [(v: any) => { v.player.currentTime = 4 }, (v: any) => { v.player.currentTime = NaN }, (v: any) => { v.player.seeking = true },
    (v: any) => { v.player.readyState = 1 }, (v: any) => { v.visible = false }, (v: any) => { v.active = false }, (v: any) => { v.supported = false },
    (v: any) => { v.frames = false }, (v: any) => { v.pending = true }, (v: any) => { v.pendingPreview = true }, (v: any) => { v.player.currentSrc += '?old-source' }]) {
    const input = captureInput(); change(input)
    assert.equal(canCaptureAgentFocus(input), false); assert.throws(() => captureAgentFocus(input, () => {}), AgentFocusError)
  }
})

test('capture rechecks after cancelling playback and cannot bind a changed source or late seek', () => {
  const changed = captureInput()
  assert.throws(() => captureAgentFocus(changed, () => { changed.player.currentSrc = 'https://local.test/new-source' }))
  const seeking = captureInput()
  assert.throws(() => captureAgentFocus(seeking, () => { seeking.player.seeking = true }))
  const input = captureInput(), hint = captureAgentFocus(input, () => {})
  assert.equal(focusHintMatches(hint, input.owner), true)
  assert.equal(focusHintMatches(hint, { ...input.owner, source_hash: 'f'.repeat(64) }), false)
  assert.equal(focusHintMatches(hint, { ...input.owner, job_id: input.owner.video_id }), false)
})

test('focus capability is separate and exact, with no inference from ordinary visual support', () => {
  assert.equal(parseAgentFocusCapability({ focus_timestamp_supported: true }), true)
  for (const raw of [null, {}, { focus_timestamp_supported: false }, { focus_timestamp_supported: 'true' }, { focus_timestamp_supported: true, visual_mode: 'frames' }])
    assert.throws(() => parseAgentFocusCapability(raw), AgentFocusError)
})

test('shared 1.2 focus wire retains zero and program/user origin while preserving later model origins', () => {
  assert.equal(fixture.data_kind, 'synthetic_fixture')
  for (const focus of [0, 1000]) {
    const { session, turn } = sample(focus), result = parseAgentTurn(turn, session).result!
    assert.equal(result.schema_version, '1.2'); assert.equal(result.focus_timestamp_ms, focus)
    assert.equal(result.tool_calls[0]!.origin, 'USER_FOCUS'); assert.equal(result.tool_calls[1]!.origin, 'MODEL')
    assert.equal(result.visual_evidence![0]!.requested_ms, focus)
  }
})

test('focus snapshots reject invalid timestamp types/EOF and dedicated stage/version/mode mismatches', () => {
  for (const value of [null, false, 1.5, -1, 4000, Number.MAX_SAFE_INTEGER + 1]) {
    const { session, turn } = sample(); (turn as any).focus_timestamp_ms = value
    assert.throws(() => parseAgentTurn(turn, session))
  }
  for (const mutate of [(v: any) => { v.stage.stage_type = 'AGENT_VISUAL' }, (v: any) => { v.visual_mode = 'none' },
    (v: any) => { v.result.schema_version = '1.1' }, (v: any) => { delete v.result.focus_timestamp_ms }, (v: any) => { v.result.focus_timestamp_ms++ },
    (v: any) => { v.result.provenance.prompt_version = 'video-agent-visual-v1' }]) {
    const { session, turn } = sample(); mutate(turn); assert.throws(() => parseAgentTurn(turn, session))
  }
  const { session, turn } = sample(), changed = clone(turn); changed.focus_timestamp_ms = 0
  assert.throws(() => newestAgentTurn(turn, changed))
  assert.equal(parseAgentSession(session, session.job_id, session.video_id, session.source.video_sha256).turns.length, 1)
})

test('1.2 requires real first-focus frame and exact USER_FOCUS/MODEL origins, not merely a timestamp', () => {
  for (const mutate of [(v: any) => { v.result.tool_calls[0].origin = 'MODEL' }, (v: any) => { v.result.tool_calls[1].origin = 'USER_FOCUS' },
    (v: any) => { delete v.result.tool_calls[1].origin }, (v: any) => { v.result.tool_calls[0].arguments.timestamps_ms = [999] },
    (v: any) => { v.result.tool_calls[0].result_frame_ids = [] }, (v: any) => { v.result.visual_evidence = []; v.result.visual_citations = []; v.result.tool_calls[0].result_frame_ids = [] }]) {
    const { session, turn } = sample(); mutate(turn); assert.throws(() => parseAgentTurn(turn, session))
  }
})

test('immutable focus receipts accept 0, reject illegal combinations and verify echoed mode/position before ACK', () => {
  const { session, turn } = sample(0), store = new MemoryStorage()
  const input = JSON.stringify({ question: turn.question, max_text_calls: 8, visual_mode: 'frames', focus_timestamp_ms: 0 })
  const entry = createAgentSubmission(store, owner(session), 'TURN', input, () => 'focus-zero', [session], session)
  assert.equal(entry.input, input); assert.equal(parseAgentSubmissionResponse(turn, entry, session).kind, 'TURN')
  for (const position of [null, false, 0.5, -1, 4000]) {
    const raw = { ...entry, input: JSON.stringify({ question: turn.question, max_text_calls: 8, visual_mode: 'frames', focus_timestamp_ms: position }) }
    assert.equal(parseAgentSubmissionEntry(raw), null)
  }
  assert.equal(parseAgentSubmissionEntry({ ...entry, input: JSON.stringify({ question: turn.question, max_text_calls: 8, visual_mode: 'none', focus_timestamp_ms: 0 }) }), null)
  assert.throws(() => parseAgentSubmissionResponse(sample(1000).turn, entry, session))
})

test('an old zero-position request cannot clear a later position chip even when question/mode/budget match', () => {
  const { session, turn } = sample(0), store = new MemoryStorage()
  const entry = createAgentSubmission(store, owner(session), 'TURN', JSON.stringify({ question: turn.question, max_text_calls: 8, visual_mode: 'frames', focus_timestamp_ms: 0 }), () => 'focus-old', [session], session)
  const draft = { session_id: session.id, question: turn.question, budget: 8, visual_mode: 'frames' as const, focus_timestamp_ms: 1000 }
  assert.equal(agentDraftMatchesSubmission(entry, draft), false)
  assert.equal(agentDraftAfterSubmission(entry, draft), turn.question)
  assert.equal(agentDraftMatchesSubmission(entry, { ...draft, focus_timestamp_ms: 0 }), true)
  assert.equal(agentDraftAfterSubmission(entry, { ...draft, focus_timestamp_ms: 0 }), '')
})

test('UNKNOWN replays the original focus body after business404, independent of a newer player position', async () => {
  const { session, turn } = sample(0), store = new MemoryStorage(), calls: string[] = []
  const input = JSON.stringify({ question: turn.question, max_text_calls: 8, visual_mode: 'frames', focus_timestamp_ms: 0 })
  const entry = createAgentSubmission(store, owner(session), 'TURN', input, () => 'focus-unknown', [session], session)
  const result = await resolveAgentSubmission(entry, { current: () => true, get: async () => { calls.push('GET original'); throw Object.assign(Error('missing'), { status: 404, code: 'AGENT_TURN_SUBMISSION_NOT_FOUND' }) },
    readSession: async () => session, beforePost: () => {}, post: async () => { calls.push(entry.input); return turn } })
  assert.equal(result?.kind === 'TURN' && result.turn.focus_timestamp_ms, 0)
  assert.deepEqual(calls, ['GET original', input])
})

test('focused answer clipping preserves the full 1.2 hash and never treats the focus itself as an ASR anchor', async () => {
  const { session, turn } = sample(0), selected = await selectAnswerClip(session, turn, ['u1'])
  assert.deepEqual(selected.binding.utterance_ids, ['u1'])
  assert.equal(selected.binding.answer_hash, createHash('sha256').update(JSON.stringify(canonical(turn.result))).digest('hex'))
  assert.deepEqual((await verifyAnswerClipBinding(selected.binding, turn)).binding, selected.binding)
  await assert.rejects(selectAnswerClip(session, turn, ['f0001']))
})
