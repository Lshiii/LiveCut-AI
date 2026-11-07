import test from 'node:test'
import assert from 'node:assert/strict'
import { reactive } from 'vue'
import { copyCaptionCues, type CaptionSnapshot } from '../captions.ts'
import { CaptionProofreadAvailability, CaptionProofreadReadScope, CaptionProofreadResponseError, applyCaptionProofreadSuggestions, captionProofreadAppliesTo,
  captionProofreadInstruction, captionProofreadReceipt, captionProofreadRejectedBeforeCreate, captionProofreadStoredReceiptMatches,
  newestCaptionProofreadSession, newestCaptionProofreadTurn, parseCaptionProofreadSession,
  parseCaptionProofreadTurn, parseCaptionProofreadTurnPage, type CaptionProofreadSession, type CaptionProofreadTurn } from '../captionProofread.ts'

const EXPORT = '11111111-1111-4111-8111-111111111111', CLIP = '22222222-2222-4222-8222-222222222222'
const SESSION = '33333333-3333-4333-8333-333333333333', JOB = '44444444-4444-4444-8444-444444444444', VIDEO = '55555555-5555-4555-8555-555555555555'
const id = (n: number) => `66666666-6666-4666-8666-${String(n).padStart(12, '0')}`
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
function snapshot(): CaptionSnapshot {
  return { export_id: EXPORT, clip_id: CLIP, clip_title: '技术课程成片', content_url: `/api/exports/${EXPORT}/clips/${CLIP}/content`,
    revision: 2, status: 'DRAFT', confirmed_revisions: [1], content_hash: 'a'.repeat(64),
    source: { snapshot_hash: 'b'.repeat(64), clip_sha256: 'c'.repeat(64), transcript_hash: 'd'.repeat(64), duration_ms: 30000,
      media_duration_ms: 30017, timing_basis: 'SOURCE_EDL_SENTENCE_MS', parts: [{ start_ms: 100000, end_ms: 130000 }] },
    cues: Array.from({ length: 30 }, (_, index) => ({ id: `c${String(index + 1).padStart(5, '0')}`, part_index: 0,
      source_utterance_id: `u${index + 1}`, source_start_ms: 100000 + index * 1000 - (index === 0 ? 10 : 0), source_end_ms: 101000 + index * 1000,
      retained_start_ms: 100000 + index * 1000, retained_end_ms: 101000 + index * 1000,
      start_ms: index * 1000, end_ms: (index + 1) * 1000, text: `cloud code 示例 ${index + 1}`, source_text: `原ASR cloud code 示例 ${index + 1}`,
      boundary_partial: index === 0, reviewed: false, requires_review: index === 0 })) }
}
function session(): CaptionProofreadSession {
  const saved = snapshot()
  return { id: SESSION, job_id: JOB, video_id: VIDEO, created_at: '2026-11-07T00:00:00Z',
    source: { video_sha256: 'e'.repeat(64), transcript_hash: saved.source.transcript_hash, duration_ms: 200000,
      export_id: EXPORT, clip_id: CLIP, caption_revision: saved.revision, caption_content_hash: saved.content_hash,
      caption_snapshot_hash: 'f'.repeat(64), caption_source: clone(saved.source), total_cues: saved.cues.length },
    turns_total: 0, turns_offset: 0, turns: [] }
}
function turn(n = 1, state: CaptionProofreadTurn['status'] = 'SUCCEEDED'): CaptionProofreadTurn {
  const s = session(), batch = { offset: 0, count: 20, total_cues: 30, next_offset: 20, cue_ids: snapshot().cues.slice(0, 20).map(cue => cue.id) }
  return { id: id(n), session_id: s.id, job_id: s.job_id, question: '核对术语，含糊之处提示回听。', sequence: n,
    status: state, state_version: state === 'SUCCEEDED' ? 3 : 1, created_at: '2026-11-07T00:01:00Z', updated_at: '2026-11-07T00:02:00Z',
    budget: { max_text_calls: 4, text_calls_reserved: state === 'SUCCEEDED' ? 1 : 0 },
    stage: { id: id(n + 100), stage_type: 'CAPTION_PROOFREAD', status: state === 'QUEUED' ? 'READY' : state, attempt: state === 'QUEUED' ? 0 : 1, error_code: null }, batch,
    evidence: state === 'SUCCEEDED' ? [{ utterance_id: 'u1', start_ms: 99990, end_ms: 101000, text: '原ASR cloud code 示例 1' },
      { utterance_id: 'u2', start_ms: 101000, end_ms: 102000, text: '原ASR cloud code 示例 2' }] : [],
    result: state === 'SUCCEEDED' ? { schema_version: '1.0', session_id: s.id, turn_id: id(n), job_id: s.job_id, video_id: s.video_id,
      source_hash: s.source.video_sha256, transcript_hash: s.source.transcript_hash, instruction: '核对术语，含糊之处提示回听。',
      caption_snapshot_hash: s.source.caption_snapshot_hash, export_id: EXPORT, clip_id: CLIP, caption_revision: s.source.caption_revision,
      caption_content_hash: s.source.caption_content_hash, coverage: clone(batch),
      suggestions: [{ cue_id: 'c00001', before: 'cloud code 示例 1', after: 'Claude Code 示例 1', decision: 'CORRECTION', reason: '根据上下文建议统一技术术语，仍需回听。', evidence_utterance_ids: ['u1'] },
        { cue_id: 'c00002', before: 'cloud code 示例 2', after: null, decision: 'UNCERTAIN', reason: '仅凭文字不能确定发音，请回听。', evidence_utterance_ids: ['u2'] }],
      tool_calls: [], provenance: { text_model_id: 'deepseek-chat', prompt_version: 'caption-proofread-v1', retrieval_mode: 'none' } } : null }
}
function complete(): CaptionProofreadSession { const s = session(); s.turns_total = 1; s.turns = [turn()]; return s }

test('a source-bound caption session preserves explicit batch coverage and uncertain suggestions', () => {
  const parsed = parseCaptionProofreadSession(complete(), EXPORT, CLIP)
  assert.equal(parsed.turns[0]!.batch.next_offset, 20)
  assert.equal(parsed.turns[0]!.result!.suggestions[1]!.after, null)
  assert.equal(captionProofreadAppliesTo(snapshot(), parsed), true)
  assert.notEqual(parsed.turns[0]!.result!.coverage.count, parsed.source.total_cues)
})
test('owner, frozen source, revision and result identities cannot be mixed', () => {
  for (const change of [
    (s: CaptionProofreadSession) => { s.source.clip_id = VIDEO },
    (s: CaptionProofreadSession) => { s.source.caption_source.transcript_hash = 'b'.repeat(64) },
    (s: CaptionProofreadSession) => { s.turns[0]!.result!.caption_revision++ },
    (s: CaptionProofreadSession) => { s.turns[0]!.result!.caption_snapshot_hash = 'b'.repeat(64) },
    (s: CaptionProofreadSession) => { s.turns[0]!.result!.source_hash = 'b'.repeat(64) },
    (s: CaptionProofreadSession) => { s.turns[0]!.result!.turn_id = SESSION },
    (s: CaptionProofreadSession) => { s.turns[0]!.result!.instruction = '其他要求' },
  ]) { const value = complete(); change(value); assert.throws(() => parseCaptionProofreadSession(value, EXPORT, CLIP), CaptionProofreadResponseError) }
  assert.throws(() => parseCaptionProofreadSession(complete(), EXPORT, CLIP, VIDEO), CaptionProofreadResponseError)
})
test('partial, duplicate, forged or reordered batch coverage is rejected', () => {
  for (const change of [
    (t: CaptionProofreadTurn) => { t.result!.coverage.cue_ids.pop() },
    (t: CaptionProofreadTurn) => { t.result!.coverage.cue_ids.reverse() },
    (t: CaptionProofreadTurn) => { t.batch.next_offset = null },
    (t: CaptionProofreadTurn) => { t.result!.suggestions[0]!.cue_id = 'c00030' },
    (t: CaptionProofreadTurn) => { t.result!.suggestions.push(clone(t.result!.suggestions[0]!)) },
    (t: CaptionProofreadTurn) => { t.batch.cue_ids[1] = t.batch.cue_ids[0]! },
  ]) { const value = turn(); change(value); assert.throws(() => parseCaptionProofreadTurn(value, session()), CaptionProofreadResponseError) }
})
test('unknown tools, bad provenance, missing evidence and unsafe suggested text are not accepted', () => {
  for (const change of [
    (t: CaptionProofreadTurn) => { t.result!.suggestions[0]!.evidence_utterance_ids = [] },
    (t: CaptionProofreadTurn) => { t.result!.suggestions[0]!.after = ' ' },
    (t: CaptionProofreadTurn) => { t.result!.suggestions[0]!.after = 'a'.repeat(2001) },
    (t: CaptionProofreadTurn) => { t.result!.suggestions[1]!.after = '擅自修正不确定内容' },
    (t: CaptionProofreadTurn) => { t.result!.tool_calls = [{ step: 1, tool_name: 'list_topics' as never, arguments: {}, result_utterance_ids: [] }] },
    (t: CaptionProofreadTurn) => { t.result!.provenance.retrieval_mode = 'bm25' },
  ]) { const value = turn(); change(value); assert.throws(() => parseCaptionProofreadTurn(value, session()), CaptionProofreadResponseError) }
})
test('only successful results can expose suggestions and stage identity stays dedicated', () => {
  const pending = turn(1, 'RUNNING'); pending.result = turn().result
  assert.throws(() => parseCaptionProofreadTurn(pending, session()), CaptionProofreadResponseError)
  const ordinary = turn(); ordinary.stage.stage_type = 'SEGMENT' as never
  assert.throws(() => parseCaptionProofreadTurn(ordinary, session()), CaptionProofreadResponseError)
})
test('complete source-ordered original evidence must exactly cover the suggestions', () => {
  for (const change of [
    (t: CaptionProofreadTurn) => { t.evidence.pop() },
    (t: CaptionProofreadTurn) => { t.evidence.reverse() },
    (t: CaptionProofreadTurn) => { t.evidence[0]!.utterance_id = 'foreign' },
    (t: CaptionProofreadTurn) => { t.evidence[0]!.end_ms = 300000 },
    (t: CaptionProofreadTurn) => { t.evidence[0]!.text = ' ' },
  ]) { const value = turn(); change(value); assert.throws(() => parseCaptionProofreadTurn(value, session()), CaptionProofreadResponseError) }
})
test('normal emoji and line breaks survive while forbidden controls and broken surrogates are refused', () => {
  const good = turn(); good.result!.suggestions[0]!.after = 'Claude Code 😀\n完整说明'
  assert.equal(parseCaptionProofreadTurn(good, session()).result!.suggestions[0]!.after, 'Claude Code 😀\n完整说明')
  for (const bad of ['错误\u0000字符', '错误\u000b字符', '错误\ud800字符', '错误\udc00字符']) {
    const value = turn(); value.result!.suggestions[0]!.after = bad
    assert.throws(() => parseCaptionProofreadTurn(value, session()), CaptionProofreadResponseError)
    assert.equal(applyCaptionProofreadSuggestions(snapshot(), snapshot().cues, complete(), value, ['c00001']).cues, null)
  }
})
test('local adoption changes only selected text and preserves raw cue metadata and later unrelated edits', () => {
  const saved = snapshot(), draft = reactive(copyCaptionCues(saved.cues))
  draft[0]!.start_ms = 12; draft[0]!.reviewed = true; draft[2]!.text = '后续无关手改 😀'
  const prior = clone(draft), adopted = applyCaptionProofreadSuggestions(saved, draft, complete(), turn(), ['c00001'])
  assert.equal(adopted.error, ''); assert.ok(adopted.cues)
  assert.equal(adopted.cues[0]!.text, 'Claude Code 示例 1')
  assert.deepEqual({ ...adopted.cues[0], text: prior[0]!.text }, prior[0])
  assert.deepEqual(adopted.cues.slice(1), prior.slice(1)); assert.deepEqual(draft, prior)
  adopted.cues[0]!.source_text = '独立副本'; assert.notEqual(draft[0]!.source_text, '独立副本')
})
test('any selected conflict refuses the whole adoption without overwriting later manual text', () => {
  const s = complete(), t = turn(), saved = snapshot(), draft = copyCaptionCues(saved.cues)
  t.result!.suggestions[1] = { ...t.result!.suggestions[1]!, after: 'Claude Code 示例 2', decision: 'CORRECTION' }
  draft[1]!.text = '已手改'; const before = clone(draft)
  assert.equal(applyCaptionProofreadSuggestions(saved, draft, s, t, ['c00001', 'c00002']).cues, null)
  assert.deepEqual(draft, before)
  assert.equal(applyCaptionProofreadSuggestions(saved, draft.filter(c => c.id !== 'c00001'), s, t, ['c00001']).cues, null)
})
test('uncertain, duplicate and foreign suggestions cannot be adopted', () => {
  for (const selected of [['c00002'], ['c00001', 'c00001'], ['c00030'], []])
    assert.equal(applyCaptionProofreadSuggestions(snapshot(), snapshot().cues, complete(), turn(), selected).cues, null)
})
test('saved revision, content hash, retained ranges and immutable source fields fence adoption', () => {
  for (const change of [
    (v: CaptionSnapshot) => { v.revision++ }, (v: CaptionSnapshot) => { v.content_hash = 'f'.repeat(64) },
    (v: CaptionSnapshot) => { v.source.parts[0]!.end_ms-- }, (v: CaptionSnapshot) => { v.source.clip_sha256 = 'e'.repeat(64) },
  ]) { const saved = snapshot(); change(saved); assert.equal(applyCaptionProofreadSuggestions(saved, saved.cues, complete(), turn(), ['c00001']).cues, null) }
  const draft = snapshot().cues; draft[0]!.source_utterance_id = 'forged'
  assert.equal(applyCaptionProofreadSuggestions(snapshot(), draft, complete(), turn(), ['c00001']).cues, null)
})
test('semantic source equality does not depend on JSON property order', () => {
  const value = snapshot(), s = value.source
  value.source = { parts: s.parts, timing_basis: s.timing_basis, media_duration_ms: s.media_duration_ms, duration_ms: s.duration_ms,
    transcript_hash: s.transcript_hash, clip_sha256: s.clip_sha256, snapshot_hash: s.snapshot_hash }
  assert.equal(captionProofreadAppliesTo(value, session()), true)
})
test('batch input and final UTF8 text limits reject the whole local adoption', () => {
  const tooLong = snapshot(); tooLong.cues.forEach(cue => { cue.source_text = '原'.repeat(2000) })
  assert.equal(applyCaptionProofreadSuggestions(tooLong, tooLong.cues, complete(), turn(), ['c00001']).cues, null)
  const saved = snapshot(), baseCue = saved.cues[0]!
  saved.cues = Array.from({ length: 10000 }, (_, index) => ({ ...baseCue, id: `c${String(index + 1).padStart(5, '0')}`,
    source_utterance_id: `u${index + 1}`, source_start_ms: index * 1000, source_end_ms: (index + 1) * 1000,
    retained_start_ms: index * 1000, retained_end_ms: (index + 1) * 1000, start_ms: index * 1000, end_ms: (index + 1) * 1000,
    source_text: '原句', text: 'a'.repeat(209), boundary_partial: false, requires_review: false }))
  saved.source.duration_ms = 10000000; saved.source.media_duration_ms = 10000000; saved.source.parts = [{ start_ms: 0, end_ms: 10000000 }]
  const s = complete(), t = turn(); s.source.caption_source = clone(saved.source); s.source.duration_ms = 10000000; s.source.total_cues = 10000
  t.batch.total_cues = 10000; t.result!.coverage.total_cues = 10000
  t.evidence = [{ utterance_id: 'u1', start_ms: 0, end_ms: 1000, text: '原句' }, { utterance_id: 'u2', start_ms: 1000, end_ms: 2000, text: '原句' }]
  t.result!.suggestions.forEach(suggestion => { suggestion.before = 'a'.repeat(209); suggestion.after = '中'.repeat(2000); suggestion.decision = 'CORRECTION' })
  s.turns = [clone(t)]
  const result = applyCaptionProofreadSuggestions(saved, saved.cues, s, t, ['c00001', 'c00002'])
  assert.equal(result.cues, null); assert.match(result.error, /2 MiB/)
})
test('caption histories support more than 100 turns and report their bounded recent window', () => {
  const s = session(); s.turns_total = 125; s.turns_offset = 105; s.turns = Array.from({ length: 20 }, (_, i) => turn(i + 106))
  const parsed = parseCaptionProofreadSession(s, EXPORT, CLIP)
  assert.equal(parsed.turns[0]!.sequence, 106); assert.equal(parsed.turns_total, 125)
  const page = parseCaptionProofreadTurnPage({ items: Array.from({ length: 20 }, (_, i) => turn(i + 1)), total: 125, limit: 20, offset: 0 }, s, 0)
  assert.equal(page.items.at(-1)!.sequence, 20)
  const bad = clone(s); bad.turns_offset = 0
  assert.throws(() => parseCaptionProofreadSession(bad, EXPORT, CLIP), CaptionProofreadResponseError)
  assert.throws(() => parseCaptionProofreadTurnPage({ ...page, offset: 20 }, s, 0), CaptionProofreadResponseError)
})
test('late polling cannot regress completed results, reserved calls or recent history totals', () => {
  const done = turn(), old = turn(1, 'RUNNING'); assert.equal(newestCaptionProofreadTurn(done, old), done)
  const lowerBudget = clone(done); lowerBudget.state_version++; lowerBudget.budget.text_calls_reserved = 0
  assert.throws(() => newestCaptionProofreadTurn(done, lowerBudget), CaptionProofreadResponseError)
  const changed = clone(done); changed.state_version++; changed.result!.suggestions[0]!.after = '其他建议'
  assert.throws(() => newestCaptionProofreadTurn(done, changed), CaptionProofreadResponseError)
  assert.equal(newestCaptionProofreadSession(complete(), session()).turns_total, 1)
})
test('source or turn identity changes cannot merge into a previously selected record', () => {
  const foreign = complete(); foreign.source.caption_snapshot_hash = 'b'.repeat(64)
  assert.throws(() => newestCaptionProofreadSession(complete(), foreign), CaptionProofreadResponseError)
  const changed = turn(); changed.batch.offset = 1
  assert.throws(() => newestCaptionProofreadTurn(turn(), changed), CaptionProofreadResponseError)
})
test('the same uncertain submission reuses its receipt while a genuinely different payload gets a new key', () => {
  let next = 0; const newKey = () => `receipt_${++next}`
  const payload = { offset: 0, max_text_calls: 4, instruction: '保留术语' }, first = captionProofreadReceipt(null, payload, newKey)
  assert.equal(captionProofreadReceipt(first, clone(payload), newKey), first)
  assert.equal(next, 1)
  assert.notEqual(captionProofreadReceipt(first, { ...payload, offset: 20 }, newKey).key, first.key)
  assert.notEqual(captionProofreadReceipt(first, { ...payload, max_text_calls: 8 }, newKey).key, first.key)
})
test('selection scope aborts prior reads and refuses late adoption after disposal', () => {
  const scope = new CaptionProofreadReadScope(), read = scope.begin()
  scope.invalidate(); assert.equal(read.signal.aborted, true); assert.equal(read.current(), false)
  const active = scope.begin(); assert.equal(active.current(), true)
  scope.dispose(); assert.equal(active.current(), false); assert.equal(scope.begin().signal.aborted, true)
})
test('instruction validation rejects invisible invalid input before a model request and keeps normal emoji', () => {
  assert.equal(captionProofreadInstruction(' \n\t '), '')
  assert.equal(captionProofreadInstruction(' \n保留术语 😀\t'), '保留术语 😀')
  assert.equal(captionProofreadInstruction('😀'.repeat(500)), '😀'.repeat(500))
  assert.equal(captionProofreadInstruction('😀'.repeat(501)), null)
  for (const value of ['末尾非法\u000b', '中间\u0000非法', '\ud800', '\udc00', null, 12]) assert.equal(captionProofreadInstruction(value), null)
})
test('only action-specific explicit pre-creation rejections release an uncertain receipt', () => {
  for (const code of ['INVALID_CAPTION_PROOFREAD_REQUEST', 'CAPTION_PROOFREAD_REVISION_MISMATCH', 'CAPTION_REVISION_NOT_FOUND'])
    assert.equal(captionProofreadRejectedBeforeCreate('session', code), true)
  for (const code of ['INVALID_CAPTION_PROOFREAD_REQUEST', 'INVALID_CAPTION_PROOFREAD_BATCH', 'AGENT_SESSION_BUSY', 'CAPTION_PROOFREAD_SESSION_LIMIT', 'CAPTION_PROOFREAD_SOURCE_CHANGED'])
    assert.equal(captionProofreadRejectedBeforeCreate('turn', code), true)
  assert.equal(captionProofreadRejectedBeforeCreate('session', 'AGENT_SESSION_BUSY'), false)
  assert.equal(captionProofreadRejectedBeforeCreate('turn', 'CAPTION_PROOFREAD_REVISION_MISMATCH'), false)
  for (const code of ['NETWORK_UNAVAILABLE', 'REQUEST_TIMEOUT', 'INVALID_CAPTION_PROOFREAD_RESPONSE', 'CAPTION_PROOFREAD_SOURCE_INVALID', 'INVALID_CAPTION_PROOFREAD_RESULT', 'HTTP_400', 'HTTP_500', 'IDEMPOTENCY_CONFLICT', null]) {
    assert.equal(captionProofreadRejectedBeforeCreate('session', code), false)
    assert.equal(captionProofreadRejectedBeforeCreate('turn', code), false)
  }
})
test('clearing one completed or rejected receipt never removes a newer page receipt already in the slot', () => {
  const expected = { key: 'request_a', input: JSON.stringify({ offset: 0, max_text_calls: 4 }) }
  assert.equal(captionProofreadStoredReceiptMatches(JSON.stringify({ receipt: expected, payload: {} }), expected), true)
  assert.equal(captionProofreadStoredReceiptMatches(JSON.stringify({ receipt: { ...expected, key: 'request_b' } }), expected), false)
  assert.equal(captionProofreadStoredReceiptMatches(JSON.stringify({ receipt: { ...expected, input: '{"offset":20}' } }), expected), false)
  for (const raw of [null, '', 'not-json', '{}', '{"receipt":null}']) assert.equal(captionProofreadStoredReceiptMatches(raw, expected), false)
})

test('initial availability blocks writes until the entire current-owner list is verified, including an empty list', () => {
  const availability = new CaptionProofreadAvailability()
  assert.equal(availability.status, 'unchecked'); assert.equal(availability.ready, false)
  availability.begin(); assert.equal(availability.status, 'checking'); assert.equal(availability.ready, false)
  const forged = complete(); forged.source.clip_id = VIDEO
  for (const raw of [null, '<html>404</html>', {}, { items: null }, { items: [] , unsupported: true }, { items: [complete(), forged] }, { items: [complete(), complete()] }]) {
    assert.throws(() => availability.acceptList(raw, EXPORT, CLIP), CaptionProofreadResponseError)
    assert.equal(availability.status, 'temporary'); assert.equal(availability.ready, false)
  }
  assert.throws(() => availability.acceptList({ items: [] }, 'invalid-owner', CLIP), CaptionProofreadResponseError)
  assert.deepEqual(availability.acceptList({ items: [] }, EXPORT, CLIP), [])
  assert.equal(availability.ready, true)
  availability.reset(); assert.equal(availability.ready, false)
  assert.equal(availability.acceptList({ items: [complete()] }, EXPORT, CLIP)[0]!.id, SESSION)
  assert.equal(availability.ready, true)
})

test('only the list endpoint 404 means unavailable; temporary failures recover and business 404 never change capability', () => {
  const availability = new CaptionProofreadAvailability()
  for (const code of ['RESOURCE_NOT_FOUND', 'HTTP_404']) {
    availability.begin(); availability.failure('list', code)
    assert.equal(availability.status, 'unavailable'); assert.equal(availability.ready, false)
    availability.begin(); availability.acceptList({ items: [] }, EXPORT, CLIP)
    for (const scope of ['session', 'turn'] as const) {
      availability.failure(scope, code); assert.equal(availability.ready, true)
      availability.failure(scope, 'CAPTION_PROOFREAD_NOT_FOUND'); assert.equal(availability.ready, true)
    }
  }
  for (const code of ['NETWORK_UNAVAILABLE', 'REQUEST_TIMEOUT', 'HTTP_500', 'DATABASE_UNAVAILABLE', 'INVALID_SERVER_RESPONSE', 'INVALID_CAPTION_PROOFREAD_RESPONSE', 'CAPTION_PROOFREAD_SOURCE_NOT_FOUND', null]) {
    availability.begin(); availability.failure('list', code)
    assert.equal(availability.status, 'temporary'); assert.equal(availability.ready, false)
    availability.begin(); availability.acceptList({ items: [] }, EXPORT, CLIP)
    assert.equal(availability.ready, true)
  }
})

test('availability failures leave unknown submissions intact and recovery still reuses their original keys', () => {
  let generated = 0
  const newKey = () => `unknown_${++generated}`
  const sessionPayload = { caption_revision: 2, caption_content_hash: 'a'.repeat(64) }, turnPayload = { offset: 0, max_text_calls: 4 }
  const sessionReceipt = captionProofreadReceipt(null, sessionPayload, newKey), turnReceipt = captionProofreadReceipt(null, turnPayload, newKey)
  const persisted = JSON.stringify({ sessionReceipt, turnReceipt }), availability = new CaptionProofreadAvailability()
  for (const code of ['RESOURCE_NOT_FOUND', 'HTTP_404', 'NETWORK_UNAVAILABLE', 'REQUEST_TIMEOUT', 'HTTP_500', 'INVALID_CAPTION_PROOFREAD_RESPONSE']) {
    availability.begin(); availability.failure('list', code)
    assert.equal(availability.ready, false)
    assert.equal(captionProofreadRejectedBeforeCreate('session', code), false)
    assert.equal(captionProofreadRejectedBeforeCreate('turn', code), false)
    assert.equal(captionProofreadReceipt(sessionReceipt, clone(sessionPayload), newKey), sessionReceipt)
    assert.equal(captionProofreadReceipt(turnReceipt, clone(turnPayload), newKey), turnReceipt)
  }
  availability.begin(); availability.acceptList({ items: [] }, EXPORT, CLIP)
  assert.equal(availability.ready, true)
  assert.equal(captionProofreadReceipt(turnReceipt, clone(turnPayload), newKey).key, turnReceipt.key)
  assert.equal(JSON.stringify({ sessionReceipt, turnReceipt }), persisted); assert.equal(generated, 2)
})
