import test from 'node:test'
import assert from 'node:assert/strict'
import { AgentReadScope, AgentResponseError, agentTurnActive, agentTurnReceipt, canPrefillClipInstruction,
  newestAgentSession, newestAgentTurn, parseAgentSession, parseAgentTurn, type AgentSession, type AgentTurn } from '../videoAgent.ts'

const JOB = '11111111-1111-4111-8111-111111111111', VIDEO = '22222222-2222-4222-8222-222222222222'
const SESSION = '33333333-3333-4333-8333-333333333333', TURN = '44444444-4444-4444-8444-444444444444'
const HASH = 'a'.repeat(64), TRANSCRIPT = 'b'.repeat(64)
function session(): AgentSession {
  return { id: SESSION, job_id: JOB, video_id: VIDEO, created_at: '2026-11-06T00:00:00Z',
    source: { video_sha256: HASH, transcript_hash: TRANSCRIPT, duration_ms: 5000 }, turns: [] }
}
function turn(): AgentTurn {
  return { id: TURN, session_id: SESSION, job_id: JOB, question: '缓存失效的原因是什么？', sequence: 1,
    status: 'SUCCEEDED', state_version: 5, created_at: '2026-11-06T00:00:01Z', updated_at: '2026-11-06T00:00:05Z',
    budget: { max_text_calls: 8, text_calls_reserved: 3 },
    stage: { id: '55555555-5555-4555-8555-555555555555', stage_type: 'SEGMENT', status: 'SUCCEEDED', attempt: 1, error_code: null },
    result: { schema_version: '1.0', session_id: SESSION, turn_id: TURN, job_id: JOB, video_id: VIDEO,
      source_hash: HASH, transcript_hash: TRANSCRIPT, question: '缓存失效的原因是什么？', answer: '修改前面的消息，会使后面的前缀缓存失效。', answer_scope: 'VIDEO',
      citations: [{ utterance_id: 'u10', start_ms: 0, end_ms: 1000, text: '前面的改了，后面的缓存就会失效。' },
        { utterance_id: 'u2', start_ms: 2000, end_ms: 3000, text: '只追加、不修改历史消息。' }],
      tool_calls: [{ step: 1, tool_name: 'search_transcript', arguments: { query: '缓存失效原因', top_k: 4 }, result_utterance_ids: ['u10', 'u2'] },
        { step: 2, tool_name: 'read_context', arguments: { utterance_id: 'u10', before: 1, after: 2 }, result_utterance_ids: ['u10', 'u2'] }],
      provenance: { text_model_id: 'deepseek-flash', prompt_version: 'video-agent-v1', retrieval_mode: 'bm25_dense_rrf' },
      suggested_clip_instruction: '把缓存失效原因单独剪出，保留上下文。' } }
}
function completeSession() { const value = session(); value.turns = [turn()]; return value }

test('video answers preserve source-ordered nonlexical citations and bounded tool evidence', () => {
  const parsed = parseAgentSession(completeSession(), JOB, VIDEO, HASH)
  assert.equal(parsed.turns[0]!.result?.answer_scope, 'VIDEO')
  assert.deepEqual(parsed.turns[0]!.result?.citations.map(c => c.utterance_id), ['u10', 'u2'])
  assert.equal(parsed.turns[0]!.result?.tool_calls[1]!.arguments.after, 2)
})
test('every session and result is bound to the selected job, video, session, turn and transcript', () => {
  for (const change of [
    (v: AgentSession) => { v.job_id = VIDEO }, (v: AgentSession) => { v.video_id = JOB },
    (v: AgentSession) => { v.source.video_sha256 = 'f'.repeat(64) },
    (v: AgentSession) => { v.turns[0]!.session_id = VIDEO },
    (v: AgentSession) => { v.turns[0]!.result!.turn_id = SESSION },
    (v: AgentSession) => { v.turns[0]!.result!.question = '其他问题' },
    (v: AgentSession) => { v.turns[0]!.result!.source_hash = 'f'.repeat(64) },
    (v: AgentSession) => { v.turns[0]!.result!.transcript_hash = 'f'.repeat(64) },
  ]) { const value = completeSession(); change(value); assert.throws(() => parseAgentSession(value, JOB, VIDEO, HASH), AgentResponseError) }
  assert.throws(() => parseAgentSession(session(), JOB, VIDEO, HASH, TURN), AgentResponseError)
  assert.throws(() => parseAgentTurn(turn(), session(), SESSION), AgentResponseError)
})
test('unknown, reversed, duplicated or out-of-bounds citations cannot become playback links', () => {
  for (const change of [
    (t: AgentTurn) => { t.result!.citations[0]!.utterance_id = '../../private' },
    (t: AgentTurn) => { t.result!.citations[1]!.utterance_id = 'u10' },
    (t: AgentTurn) => { t.result!.citations.reverse() },
    (t: AgentTurn) => { t.result!.citations[1]!.end_ms = 5001 },
    (t: AgentTurn) => { t.result!.citations[0]!.end_ms = 0 },
  ]) { const value = turn(); change(value); assert.throws(() => parseAgentTurn(value, session()), AgentResponseError) }
})
test('an exact long ASR sentence remains readable up to the shared source limit', () => {
  const value = turn(); value.result!.citations[0]!.text = '原'.repeat(100000)
  assert.equal(parseAgentTurn(value, session()).result!.citations[0]!.text.length, 100000)
  value.result!.citations[0]!.text += '超'
  assert.throws(() => parseAgentTurn(value, session()), AgentResponseError)
})
test('missing video evidence is explicit while general knowledge can have no citations', () => {
  const value = turn(); value.result!.citations = []
  assert.throws(() => parseAgentTurn(value, session()), AgentResponseError)
  value.result!.answer_scope = 'GENERAL'; value.result!.tool_calls = []; value.result!.provenance.retrieval_mode = 'none'
  assert.equal(parseAgentTurn(value, session()).result?.answer_scope, 'GENERAL')
  value.result!.answer_scope = 'INSUFFICIENT'
  assert.equal(parseAgentTurn(value, session()).result?.answer_scope, 'INSUFFICIENT')
})
test('context and topic tools use no retrieval claim unless transcript search actually ran', () => {
  const value = turn(); value.result!.tool_calls = [{ step: 1, tool_name: 'list_topics', arguments: { offset: 0, limit: 12 }, result_utterance_ids: ['u10', 'u2'] }]
  assert.throws(() => parseAgentTurn(value, session()), AgentResponseError)
  value.result!.provenance.retrieval_mode = 'none'
  assert.equal(parseAgentTurn(value, session()).result?.tool_calls[0]!.tool_name, 'list_topics')
  value.result!.tool_calls[0]!.tool_name = 'search_transcript'; value.result!.tool_calls[0]!.arguments = { query: '缓存', top_k: 3 }
  assert.throws(() => parseAgentTurn(value, session()), AgentResponseError)
})
test('tool names, steps, limits and exact argument shapes are restricted', () => {
  for (const replacement of [
    { tool_name: 'export_mp4' }, { step: 3 }, { arguments: { query: '缓存', top_k: 9 } },
    { arguments: { query: '缓存', top_k: 2, api_key: 'private' } }, { result_utterance_ids: ['u10', 'u10'] },
    { tool_name: 'read_context', arguments: { utterance_id: 'u10', before: 4, after: 0 } },
    { tool_name: 'list_topics', arguments: { offset: -1, limit: 2 } },
    { tool_name: 'list_topics', arguments: { offset: 0, limit: 13 } },
  ]) {
    const value = turn(); Object.assign(value.result!.tool_calls[0]!, replacement)
    assert.throws(() => parseAgentTurn(value, session()), AgentResponseError)
  }
  const value = turn(); value.result!.tool_calls[0]!.result_utterance_ids = Array.from({ length: 201 }, (_, i) => `u${i}`)
  assert.throws(() => parseAgentTurn(value, session()), AgentResponseError)
})
test('answers and suggestions remain plain text without interpreting markup', () => {
  const value = turn(); value.result!.answer = '<script>alert("private")</script> **不是 HTML**'; value.result!.suggested_clip_instruction = '<img src=x onerror=alert(1)>'
  const parsed = parseAgentTurn(value, session())
  assert.equal(parsed.result!.answer, value.result!.answer)
  assert.equal(parsed.result!.suggested_clip_instruction, value.result!.suggested_clip_instruction)
})
test('failed and pending turns never present a provider response as a completed answer', () => {
  for (const status of ['QUEUED', 'RUNNING', 'FAILED', 'CANCELLED'] as const) {
    const value = turn(); value.status = status
    assert.throws(() => parseAgentTurn(value, session()), AgentResponseError)
    value.result = null
    assert.equal(parseAgentTurn(value, session()).result, null)
    assert.equal(agentTurnActive(value), status === 'QUEUED' || status === 'RUNNING')
  }
})
test('unsupported optional progress and diagnostics do not discard a valid turn', () => {
  const value = { ...turn(), stage: { ...turn().stage, diagnostic: { api_key: 'private' }, text_progress: { state: 'UNKNOWN', provider: 'https://secret' } } }
  const parsed = parseAgentTurn(value, session())
  assert.equal(parsed.stage.diagnostic, undefined); assert.equal(parsed.stage.text_progress, undefined)
  assert.equal(parsed.result?.answer, turn().result?.answer)
})
test('duplicate, out-of-order or concurrently active history cannot replace the current session', () => {
  const value = completeSession(); value.turns.push(turn())
  assert.throws(() => parseAgentSession(value, JOB, VIDEO, HASH), AgentResponseError)
  value.turns[1] = { ...turn(), id: VIDEO, sequence: 2, status: 'QUEUED', result: null }
  value.turns[0] = { ...turn(), status: 'RUNNING', result: null }
  assert.throws(() => parseAgentSession(value, JOB, VIDEO, HASH), AgentResponseError)
})
test('late polling cannot regress completed answers or remove newer conversation history', () => {
  const known = completeSession(), older = session()
  assert.equal(newestAgentSession(known, older).turns.length, 1)
  const oldTurn: AgentTurn = { ...turn(), state_version: 2, status: 'RUNNING', result: null }
  older.turns = [oldTurn]
  assert.equal(newestAgentSession(known, older).turns[0]!.status, 'SUCCEEDED')
  oldTurn.state_version = 8
  assert.equal(newestAgentTurn(known.turns[0], oldTurn).status, 'SUCCEEDED')
  oldTurn.status = 'FAILED'
  assert.equal(newestAgentTurn(known.turns[0], oldTurn).status, 'SUCCEEDED')
  const switched = { ...turn(), question: '被替换的问题' }
  assert.throws(() => newestAgentTurn(turn(), switched), AgentResponseError)
})
test('session merging rejects changed source identities and conflicting sequence assignments', () => {
  const known = completeSession(), switched = completeSession()
  switched.source.transcript_hash = 'f'.repeat(64)
  assert.throws(() => newestAgentSession(known, switched), AgentResponseError)
  const incoming = completeSession(); incoming.turns[0]!.id = VIDEO
  assert.throws(() => newestAgentSession(known, incoming), AgentResponseError)
})
test('an unresolved receipt keeps its original identity and refuses changes to question, budget or key', () => {
  let calls = 0
  const next = () => `new-${++calls}`
  const receipt = agentTurnReceipt(null, '缓存原因？', 8, next)
  assert.equal(agentTurnReceipt({ ...receipt }, '缓存原因？', 8, next).key, receipt.key)
  assert.throws(() => agentTurnReceipt(receipt, '解决方法？', 8, next), AgentResponseError)
  assert.throws(() => agentTurnReceipt(receipt, '缓存原因？', 4, next), AgentResponseError)
  assert.throws(() => agentTurnReceipt({ ...receipt, key: 'https://secret' }, '缓存原因？', 8, next), AgentResponseError)
  assert.throws(() => agentTurnReceipt(receipt, ' 缓存原因？', 8, next), AgentResponseError)
  assert.equal(calls, 1, 'changed input never allocates a replacement key while the old outcome is unknown')
})
test('clip suggestions never overwrite an existing instruction or draft or submit automatically', () => {
  assert.equal(canPrefillClipInstruction('单独剪出原因', false, false), true)
  for (const [dirty, pending] of [[true, false], [false, true], [true, true]]) assert.equal(canPrefillClipInstruction('单独剪出原因', dirty!, pending!), false)
  for (const value of [null, '', '   ', 'x'.repeat(2001)]) assert.equal(canPrefillClipInstruction(value, false, false), false)
})
test('session selection aborts prior reads and never revives them after unmount', async () => {
  const scope = new AgentReadScope(), old = scope.begin()
  let resolve!: (v: AgentSession) => void
  const response = new Promise<AgentSession>(done => { resolve = done })
  let accepted = false
  const late = response.then(() => { if (old.current()) accepted = true })
  scope.invalidate(); const current = scope.begin()
  resolve(completeSession()); await late
  assert.equal(old.signal.aborted, true); assert.equal(accepted, false); assert.equal(current.current(), true)
  scope.dispose(); assert.equal(current.signal.aborted, true); assert.equal(scope.begin().current(), false)
})
