import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { AgentFocusError, type AgentFocusHint } from '../agentFocus.ts'
import { agentFrameFollowupTarget, confirmAgentFrameFollowup, prepareAgentFrameFollowup, sameAgentFocusHint, type AgentFrameFollowupContext } from '../agentFrameFollowup.ts'
import { type AgentSession, type AgentTurn } from '../videoAgent.ts'
import { agentDraftAfterSubmission, parseAgentSubmissionEntry } from '../agentSubmissionRecovery.ts'
import { clone } from './support/agentSubmissionFixture.ts'

const fixture = JSON.parse(readFileSync(new URL('../../../fixtures/agent-visual-focus.synthetic.json', import.meta.url), 'utf8'))
function sample(version: '1.1' | '1.2' = '1.2', requested = 1000, actual = 1050) {
  const result = clone(fixture.result)
  result.focus_timestamp_ms = requested; result.tool_calls[0].arguments.timestamps_ms = [requested]
  Object.assign(result.visual_evidence[0], { requested_ms: requested, timestamp_ms: actual, source_pts: actual })
  const session: AgentSession = { id: result.session_id, job_id: result.job_id, video_id: result.video_id, created_at: '2026-11-09T00:00:00Z',
    source: { video_sha256: result.source_hash, transcript_hash: result.transcript_hash, duration_ms: fixture.duration_ms }, turns: [] }
  const turn: AgentTurn = { id: result.turn_id, session_id: session.id, job_id: session.job_id, question: result.question, sequence: 1,
    status: 'SUCCEEDED', state_version: 4, created_at: '2026-11-09T00:00:01Z', updated_at: '2026-11-09T00:00:02Z',
    visual_mode: 'frames', focus_timestamp_ms: requested, budget: { max_text_calls: 8, text_calls_reserved: 1 },
    stage: { id: '55555555-5555-4555-8555-555555555555', stage_type: 'AGENT_VISUAL_FOCUS', status: 'SUCCEEDED', attempt: 1, error_code: null }, result }
  if (version === '1.1') {
    result.schema_version = version; result.provenance.prompt_version = 'video-agent-visual-v1'; delete result.focus_timestamp_ms
    for (const call of result.tool_calls) delete call.origin
    delete turn.focus_timestamp_ms; turn.stage.stage_type = 'AGENT_VISUAL'
  }
  session.turns = [turn]
  const context: AgentFrameFollowupContext = { owner: { job_id: session.job_id, video_id: session.video_id,
    source_hash: session.source.video_sha256, duration_ms: session.source.duration_ms }, session_id: session.id,
    transcript_hash: session.source.transcript_hash, active: true, visible: true, pending: false, visual_available: true, focus_supported: true }
  return { session, turn, context }
}
const read = (v: ReturnType<typeof sample>, frameId = 'f0001') => agentFrameFollowupTarget(v.session, v.turn.id, frameId, v.context)
const code = (expected: string) => (error: unknown) => error instanceof AgentFocusError && error.code === expected

test('1.1 and 1.2 followup use cited actual timestamp instead of requested time, previous turn focus or a player', () => {
  for (const version of ['1.1', '1.2'] as const) {
    const value = sample(version, 162525, 162533)
    value.session.source.duration_ms = 300000; value.context.owner.duration_ms = 300000
    const target = read(value)
    assert.equal(target.hint.timestamp_ms, 162533); assert.notEqual(target.hint.timestamp_ms, 162525)
    assert.equal(Object.isFrozen(target), true); assert.equal(Object.isFrozen(target.hint), true)
    assert.equal(Object.hasOwn(target.hint, 'frame_id'), false, 'only a hint goes into the existing focus protocol')
    assert.equal(Object.hasOwn(value.context, 'player'), false, 'collapsed/unloaded players are not required')
  }
})

test('0 is a valid actual time and scope GENERAL/INSUFFICIENT with cited visual evidence remains usable', () => {
  for (const scope of ['VIDEO', 'GENERAL', 'INSUFFICIENT'] as const) {
    const value = sample('1.2', 0, 0); value.turn.result!.answer_scope = scope
    assert.equal(read(value).hint.timestamp_ms, 0)
    const zero = read(value).hint
    assert.equal(prepareAgentFrameFollowup(value.session, value.turn.id, 'f0001', value.context, zero).kind, 'apply')
    assert.equal(prepareAgentFrameFollowup(value.session, value.turn.id, 'f0001', value.context, null).kind, 'apply')
  }
})

test('a rounded clip frame time may equal old clip end because the new hint is bounded by the whole source video', () => {
  const value = sample('1.1', 1000, 1500), result = value.turn.result!
  result.citations = []
  Object.assign(result.visual_evidence![0]!, { source_pts: 14999, source_time_base: '1/10000' })
  result.tool_calls = [{ step: 1, tool_name: 'inspect_clip', arguments: { start_ms: 1000, end_ms: 1500 }, result_utterance_ids: [], result_frame_ids: ['f0001'] }]
  assert.equal(read(value).hint.timestamp_ms, 1500)
})

test('current selected source owner, transcript, duration and turn identity are all required', () => {
  for (const change of [
    (v: ReturnType<typeof sample>) => { v.context.session_id = '99999999-9999-4999-8999-999999999999' },
    (v: ReturnType<typeof sample>) => { v.context.owner.job_id = v.session.video_id },
    (v: ReturnType<typeof sample>) => { v.context.owner.video_id = v.session.job_id },
    (v: ReturnType<typeof sample>) => { v.context.owner.source_hash = 'd'.repeat(64) },
    (v: ReturnType<typeof sample>) => { v.context.transcript_hash = 'd'.repeat(64) },
    (v: ReturnType<typeof sample>) => { v.context.owner.duration_ms-- },
    (v: ReturnType<typeof sample>) => { v.turn.session_id = '99999999-9999-4999-8999-999999999999' },
    (v: ReturnType<typeof sample>) => { v.turn.result!.transcript_hash = 'd'.repeat(64) },
  ]) { const value = sample(); change(value); assert.throws(() => read(value), code('AGENT_FRAME_FOLLOWUP_INVALID')) }
  const value = sample()
  assert.throws(() => agentFrameFollowupTarget(null, value.turn.id, 'f0001', value.context), code('AGENT_FRAME_FOLLOWUP_INVALID'))
  assert.throws(() => agentFrameFollowupTarget(value.session, value.session.id, 'f0001', value.context), code('AGENT_FRAME_FOLLOWUP_INVALID'))
})

test('unquoted observed frames, failed turns, invalid frame ids and unsafe/EOF times are rejected without guessing', () => {
  const unquoted = sample(); unquoted.turn.result!.visual_citations = []
  assert.throws(() => read(unquoted), code('AGENT_FRAME_FOLLOWUP_INVALID'))
  for (const status of ['QUEUED', 'RUNNING', 'FAILED', 'CANCELLED'] as const) {
    const value = sample(); value.turn.status = status; value.turn.result = null
    assert.throws(() => read(value), code('AGENT_FRAME_FOLLOWUP_INVALID'))
  }
  for (const frameId of ['f0002', '../f0001', '', 'f0033']) assert.throws(() => read(sample(), frameId), code('AGENT_FRAME_FOLLOWUP_INVALID'))
  for (const time of [null, false, '1000', -1, .5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1, fixture.duration_ms]) {
    const value = sample(); value.turn.result!.visual_evidence![0]!.timestamp_ms = time as number
    assert.throws(() => read(value), code('AGENT_FRAME_FOLLOWUP_INVALID'))
  }
})

test('only active visible nonpending contexts with confirmed visual and focus support can prepare a time', () => {
  for (const field of ['active', 'visible'] as const) {
    const value = sample(); value.context[field] = false
    assert.throws(() => read(value), code('AGENT_FRAME_FOLLOWUP_NOT_READY'))
  }
  const busy = sample(); busy.context.pending = true
  assert.throws(() => read(busy), code('AGENT_FRAME_FOLLOWUP_NOT_READY'))
  for (const field of ['visual_available', 'focus_supported'] as const) {
    const value = sample(); value.context[field] = false
    assert.throws(() => read(value), code('AGENT_FRAME_FOLLOWUP_UNAVAILABLE'))
  }
  const value = sample(); value.session.turns.push({ ...clone(value.turn), id: '99999999-9999-4999-8999-999999999999', sequence: 2, status: 'RUNNING',
    stage: { ...value.turn.stage, id: '99999999-9999-4999-8999-999999999998', status: 'RUNNING' }, result: null })
  assert.equal(read(value).hint.timestamp_ms, 1050, 'another active turn does not prevent preparing a draft')
})

test('different existing hints require a frozen replacement proposal and cancellation leaves original values untouched', () => {
  const value = sample(), previous: AgentFocusHint = { ...read(value).hint, timestamp_ms: 0 }
  const before = JSON.stringify({ previous, session: value.session, context: value.context })
  const result = prepareAgentFrameFollowup(value.session, value.turn.id, 'f0001', value.context, previous)
  assert.equal(result.kind, 'replace')
  if (result.kind !== 'replace') assert.fail()
  assert.equal(result.proposal.previous_hint.timestamp_ms, 0); assert.equal(Object.isFrozen(result.proposal.previous_hint), true)
  assert.equal(JSON.stringify({ previous, session: value.session, context: value.context }), before, 'preparing or dropping the proposal cannot change draft/source objects')
  const target = confirmAgentFrameFollowup(result.proposal, value.session, value.context, previous)
  assert.equal(target.hint.timestamp_ms, 1050); assert.equal(previous.timestamp_ms, 0)
  previous.timestamp_ms = 2000
  assert.equal(result.proposal.previous_hint.timestamp_ms, 0, 'proposal does not share a mutable baseline')
  assert.throws(() => confirmAgentFrameFollowup(result.proposal, value.session, value.context, previous), code('AGENT_FRAME_FOLLOWUP_CHANGED'))
})

test('confirmation rechecks baseline, current cited frame and lifecycle after the proposal was prepared', () => {
  for (const change of [
    (v: ReturnType<typeof sample>) => { v.context.active = false }, (v: ReturnType<typeof sample>) => { v.context.visible = false },
    (v: ReturnType<typeof sample>) => { v.context.pending = true }, (v: ReturnType<typeof sample>) => { v.context.focus_supported = false },
    (v: ReturnType<typeof sample>) => { v.context.visual_available = false }, (v: ReturnType<typeof sample>) => { v.context.owner.source_hash = 'd'.repeat(64) },
    (v: ReturnType<typeof sample>) => { v.session.turns = [] }, (v: ReturnType<typeof sample>) => { v.turn.result!.visual_citations = [] },
    (v: ReturnType<typeof sample>) => { v.turn.result!.visual_evidence![0]!.sha256 = 'd'.repeat(64) },
  ]) {
    const value = sample(), previous = { ...read(value).hint, timestamp_ms: 0 }
    const prepared = prepareAgentFrameFollowup(value.session, value.turn.id, 'f0001', value.context, previous)
    if (prepared.kind !== 'replace') assert.fail()
    change(value); assert.throws(() => confirmAgentFrameFollowup(prepared.proposal, value.session, value.context, previous))
  }
  const value = sample(), previous = { ...read(value).hint, timestamp_ms: 0 }
  const prepared = prepareAgentFrameFollowup(value.session, value.turn.id, 'f0001', value.context, previous)
  if (prepared.kind !== 'replace') assert.fail()
  assert.throws(() => confirmAgentFrameFollowup(prepared.proposal, value.session, value.context, null), code('AGENT_FRAME_FOLLOWUP_CHANGED'))
  assert.throws(() => confirmAgentFrameFollowup(prepared.proposal, value.session, value.context, { ...previous, source_hash: 'd'.repeat(64) }), code('AGENT_FRAME_FOLLOWUP_CHANGED'))
})

test('new draft hint cannot alter an immutable original receipt or clear a later identical question at the new position', () => {
  const value = sample(), original = parseAgentSubmissionEntry({ schema_version: 'agent-submission-v1', kind: 'TURN', key: 'original-followup-key',
    job_id: value.session.job_id, video_id: value.session.video_id, video_sha256: value.session.source.video_sha256,
    session_id: value.session.id, source: clone(value.session.source), input: JSON.stringify({ question: '保留原问题', max_text_calls: 4, visual_mode: 'frames', focus_timestamp_ms: 0 }) })
  const before = JSON.stringify(original), question = '保留原问题', budget = 4
  const prepared = prepareAgentFrameFollowup(value.session, value.turn.id, 'f0001', value.context, null)
  if (prepared.kind !== 'apply') assert.fail()
  assert.equal(JSON.stringify(original), before)
  assert.equal(agentDraftAfterSubmission(original, { session_id: value.session.id, question, budget, visual_mode: 'frames', focus_timestamp_ms: prepared.target.hint.timestamp_ms }), question)
  assert.equal(original.key, 'original-followup-key'); assert.equal(JSON.parse(original.input).focus_timestamp_ms, 0)
  assert.equal(question, '保留原问题'); assert.equal(budget, 4)
})

test('same time only skips replacement when the full source identity also matches', () => {
  const value = sample(), target = read(value)
  assert.equal(sameAgentFocusHint(target.hint, { ...target.hint }), true)
  assert.equal(sameAgentFocusHint(null, null), true)
  const otherSource = { ...target.hint, source_hash: 'd'.repeat(64) }
  assert.equal(sameAgentFocusHint(target.hint, otherSource), false)
  assert.equal(prepareAgentFrameFollowup(value.session, value.turn.id, 'f0001', value.context, otherSource).kind, 'replace')
})
