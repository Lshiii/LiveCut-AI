import test from 'node:test'
import assert from 'node:assert/strict'
import { AnswerClipError, answerClipBinding, parseAnswerClipSource, selectAnswerClip, supportsAnswerClipProtocol, verifyAnswerClipBinding, type AnswerClipSelection, type AnswerClipSource } from '../answerClip.ts'
import { IntentCreateError, intentCreatePayload, intentCreateReceipt, intentCreateRejectedBeforeInsert, intentCreateResponseMatches, parseIntentCreatePayload, parseIntentCreateReceipt, prepareIntentCreateReceipt } from '../intentCreate.ts'
import { intentOutputValidation, intentPayload, IntentResponseError, newestIntentSnapshot, parseIntentSnapshot, parseResumedIntentSnapshot, type IntentSnapshot } from '../intentClips.ts'
import { captureIntentDraft, parseIntentDraft, restoreIntentDraft } from '../intentDraft.ts'
import { EXECUTOR_STAGES, ExecutorSnapshotError } from '../executors.ts'
import type { AgentSession, AgentTurn } from '../videoAgent.ts'

const JOB = '11111111-1111-4111-8111-111111111111', VIDEO = '22222222-2222-4222-8222-222222222222'
const SESSION = '33333333-3333-4333-8333-333333333333', TURN = '44444444-4444-4444-8444-444444444444'
const REQUEST = '55555555-5555-4555-8555-555555555555', STAGE = '66666666-6666-4666-8666-666666666666'
const KEY = '77777777-7777-4777-8777-777777777777', NEXT = '88888888-8888-4888-8888-888888888888'
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
function videoAnswer() {
  const utterances = Array.from({ length: 4 }, (_, index) => ({ utterance_id: `u${index + 1}`, start_ms: index * 1000, end_ms: (index + 1) * 1000,
    text: ['同词但不同语义的缓存例子', '另一处干扰缓存', '真正要切出的缓存失效解释', '本段结尾与解决方法'][index]! }))
  const source = { video_sha256: 'a'.repeat(64), transcript_hash: 'b'.repeat(64), duration_ms: 4000 }
  const turn: AgentTurn = { id: TURN, session_id: SESSION, job_id: JOB, question: '缓存为什么失效？', sequence: 1, status: 'SUCCEEDED', state_version: 3,
    created_at: '2026-11-07T00:00:00Z', updated_at: '2026-11-07T00:01:00Z', budget: { max_text_calls: 8, text_calls_reserved: 1 },
    stage: { id: STAGE, stage_type: 'SEGMENT', status: 'SUCCEEDED', attempt: 1, error_code: null },
    result: { schema_version: '1.0', session_id: SESSION, turn_id: TURN, job_id: JOB, video_id: VIDEO, source_hash: source.video_sha256,
      transcript_hash: source.transcript_hash, question: '缓存为什么失效？', answer: '第三处解释了当前讨论的原因。', answer_scope: 'VIDEO', citations: clone(utterances), tool_calls: [],
      provenance: { text_model_id: 'synthetic', prompt_version: 'video-agent-v1', retrieval_mode: 'none' }, suggested_clip_instruction: null } }
  const session: AgentSession = { id: SESSION, job_id: JOB, video_id: VIDEO, source, created_at: turn.created_at, turns: [turn] }
  return { utterances, source, turn, session }
}
async function selection() { const fixture = videoAnswer(); return selectAnswerClip(fixture.session, fixture.turn, ['u3']) }
function sourceAnswer(selected: AnswerClipSelection): AnswerClipSource {
  return { schema_version: '1.0', ...answerClipBinding(selected.binding), question: selected.question, answer: videoAnswer().turn.result!.answer,
    citations: clone(selected.citations), context_utterance_ids: ['u2', 'u3', 'u4'] }
}
function clip(selected: AnswerClipSelection): IntentSnapshot {
  const fixture = videoAnswer(), instruction = selected.instruction
  return { id: REQUEST, job_id: JOB, video_id: VIDEO, instruction, status: 'PLAN_READY', state_version: 3, revision: 0,
    created_at: '2026-11-07T00:02:00Z', updated_at: '2026-11-07T00:03:00Z', budget: { max_text_calls: 6, text_calls_reserved: 1 },
    stage: { id: STAGE, stage_type: 'ANCHOR_CLIP', status: 'SUCCEEDED', attempt: 1, error_code: null }, source: fixture.source, source_answer: sourceAnswer(selected),
    plan_hash: 'c'.repeat(64), confirmed_revisions: [], utterances: fixture.utterances,
    plan: { schema_version: '1.0', request_id: REQUEST, job_id: JOB, video_id: VIDEO, source_hash: fixture.source.video_sha256, transcript_hash: fixture.source.transcript_hash,
      duration_ms: 4000, instruction, goals: [{ goal_id: 'answer', description: '对应回答的片段' }], constraints: { keep_explanation_complete: true, exclude: [] },
      outputs: [{ output_id: 'answer-clip', goal_id: 'answer', title: '对应回答', status: 'FOUND', reason: '使用冻结上下文生成，仍需人工核对', enabled: false,
        parts: [{ start_utterance_id: 'u2', end_utterance_id: 'u4', start_ms: 1000, end_ms: 4000 }], excluded: [], evidence_utterance_ids: ['u3'] }],
      provenance: { text_model_id: 'synthetic', prompt_version: 'anchor-clips-v1', retrieval_mode: 'anchor_context', index_hash: 'd'.repeat(64) } } }
}

test('selected answer citations bind the third occurrence instead of sending an ambiguous keyword search', async () => {
  const fixture = videoAnswer(), original = clone(fixture)
  const chosen = await selectAnswerClip(fixture.session, fixture.turn, ['u3'])
  assert.deepEqual(chosen.binding.utterance_ids, ['u3']); assert.equal(chosen.citations[0]!.start_ms, 2000)
  assert.equal(chosen.citations[0]!.text, '真正要切出的缓存失效解释')
  assert.deepEqual(fixture, original)
  const reordered = await selectAnswerClip(fixture.session, fixture.turn, ['u4', 'u2'])
  assert.deepEqual(reordered.binding.utterance_ids, ['u2', 'u4'])
  assert.deepEqual((await verifyAnswerClipBinding(chosen.binding, fixture.turn)).binding, chosen.binding)
  for (const ids of [[], ['u3', 'u3'], ['foreign']]) await assert.rejects(selectAnswerClip(fixture.session, fixture.turn, ids), AnswerClipError)
  for (const scope of ['GENERAL', 'INSUFFICIENT'] as const) { const turn = clone(fixture.turn); turn.result!.answer_scope = scope; await assert.rejects(selectAnswerClip(fixture.session, turn, ['u3']), AnswerClipError) }
})

test('answer hashes use canonical object keys and fresh recovery rejects changed answers, source, or selected IDs', async () => {
  const fixture = videoAnswer(), chosen = await selection(), reordered = clone(fixture.turn)
  reordered.result = Object.fromEntries(Object.entries(reordered.result!).reverse()) as unknown as NonNullable<AgentTurn['result']>
  assert.equal((await selectAnswerClip(fixture.session, reordered, ['u3'])).binding.answer_hash, chosen.binding.answer_hash)
  const changed = clone(fixture.turn); changed.result!.answer = '回答后来不同'
  await assert.rejects(verifyAnswerClipBinding(chosen.binding, changed), AnswerClipError)
  for (const field of ['source_hash', 'transcript_hash', 'answer_hash'] as const) { const forged = clone(chosen.binding); forged[field] = 'f'.repeat(64); await assert.rejects(verifyAnswerClipBinding(forged, fixture.turn)) }
  const oversized = clone(fixture.turn); oversized.result!.citations[2]!.text = '字'.repeat(12001)
  await assert.rejects(selectAnswerClip(fixture.session, oversized, ['u3']), AnswerClipError)
})

test('frozen source envelopes require exact identities, ordered citations, bounded text, and a context containing every anchor', async () => {
  const chosen = await selection(), fixture = videoAnswer(), raw = sourceAnswer(chosen)
  assert.deepEqual(parseAnswerClipSource(raw, JOB, VIDEO, fixture.source), raw)
  for (const mutate of [(v: any) => { v.turn_id = 'foreign' }, (v: any) => { v.video_id = JOB }, (v: any) => { v.source_hash = 'e'.repeat(64) },
    (v: any) => { v.context_utterance_ids = ['u2'] }, (v: any) => { v.citations[0].utterance_id = 'u1' }, (v: any) => { v.citations[0].text = '字'.repeat(12001) },
    (v: any) => { v.context_utterance_ids.push('u3') }, (v: any) => { v.extra = true }]) { const value = clone(raw); mutate(value); assert.throws(() => parseAnswerClipSource(value, JOB, VIDEO, fixture.source), AnswerClipError) }
})

test('anchor snapshots pair their dedicated stage and provenance and keep every selected sentence', async () => {
  const chosen = await selection(), saved = clip(chosen)
  assert.equal(parseIntentSnapshot(saved, JOB).stage.stage_type, 'ANCHOR_CLIP')
  for (const mutate of [(v: any) => { v.source_answer = null }, (v: any) => { v.stage.stage_type = 'SEGMENT' },
    (v: any) => { v.plan.provenance.retrieval_mode = 'bm25' }, (v: any) => { v.plan.provenance.prompt_version = 'intent-clips-v1' },
    (v: any) => { v.source_answer.citations[0].text = '伪造原句' }, (v: any) => { v.source_answer.context_utterance_ids = ['u3', 'u2'] },
    (v: any) => { v.plan.outputs[0].parts = [{ start_utterance_id: 'u1', end_utterance_id: 'u1', start_ms: 0, end_ms: 1000 }]; v.plan.outputs[0].evidence_utterance_ids = ['u1'] }]) {
    const value = clone(saved); mutate(value); assert.throws(() => parseIntentSnapshot(value, JOB), IntentResponseError)
  }
  const empty = clone(saved); empty.plan!.outputs[0]!.status = 'NEEDS_REVIEW'; empty.plan!.outputs[0]!.parts = []; empty.plan!.outputs[0]!.evidence_utterance_ids = []
  assert.equal(parseIntentSnapshot(empty, JOB).plan!.outputs[0]!.status, 'NEEDS_REVIEW')
  const multiple = [...saved.plan!.outputs, { ...clone(saved.plan!.outputs[0]!), output_id: 'other', goal_id: 'other' }]
  assert.match(intentOutputValidation(multiple, saved.utterances, 4000, undefined, saved.source_answer), /一个剪辑目标/)
})

test('merging, continuation, and acknowledged POST responses cannot strip or swap their frozen answer', async () => {
  const chosen = await selection(), saved = clip(chosen), payload = intentCreatePayload(chosen.instruction, 6, chosen.binding)
  assert.equal(intentCreateResponseMatches(saved, payload, chosen.binding), true)
  const unanchored = clone(saved); unanchored.source_answer = null; assert.equal(intentCreateResponseMatches(unanchored, payload, chosen.binding), false)
  const changed = clone(saved); changed.source_answer!.answer = '换回答但沿用hash'
  assert.throws(() => newestIntentSnapshot(saved, changed), IntentResponseError)
  const resumed = clone(saved); resumed.id = NEXT; resumed.resumed_from_request_id = saved.id; resumed.plan = null; resumed.plan_hash = null; resumed.status = 'QUEUED'; resumed.revision = 0; resumed.utterances = []
  assert.equal(parseResumedIntentSnapshot(resumed, saved, 6).source_answer!.turn_id, TURN)
  resumed.source_answer!.turn_id = NEXT; assert.throws(() => parseResumedIntentSnapshot(resumed, saved, 6), IntentResponseError)
})

test('draft capture keeps source bindings without answer text, and imported anchors require a fresh verified VIDEO turn', async () => {
  const chosen = await selection(), saved = clip(chosen), payload = intentCreatePayload(chosen.instruction, 6, chosen.binding)
  const receipt = intentCreateReceipt(null, payload, () => KEY, chosen.binding)
  const raw = captureIntentDraft(JOB, chosen.instruction, 6, { snapshot: saved, outputs: intentPayload(saved.plan!.outputs).outputs, baseRevision: 0, basePlanHash: saved.plan_hash, needsCheck: false }, chosen.binding, receipt)
  const json = JSON.stringify(raw)
  assert.equal(json.includes('真正要切出的缓存失效解释'), false); assert.equal(json.includes('第三处解释了当前讨论的原因'), false)
  const draft = parseIntentDraft(raw, JOB)!
  assert.equal(draft.create_receipt!.key, KEY); assert.deepEqual(draft.source_answer, chosen.binding)
  assert.equal(restoreIntentDraft(raw, JOB, saved).ok, false)
  const restored = restoreIntentDraft(raw, JOB, saved, [await verifyAnswerClipBinding(chosen.binding, videoAnswer().turn)])
  assert.equal(restored.ok, true)
  const stripped = clone(raw) as any; delete stripped.source_answer
  assert.equal(parseIntentDraft(stripped, JOB), null)
  const foreign = clone(saved); foreign.source_answer!.turn_id = NEXT
  assert.equal(restoreIntentDraft(raw, JOB, foreign, [chosen]).ok, false)
})

test('UNKNOWN create receipts preserve original anchor IDs and forbid a silent ordinary fallback or new key', async () => {
  const chosen = await selection(), payload = intentCreatePayload(chosen.instruction, 6, chosen.binding)
  let keys = 0; const key = () => { keys++; return KEY }
  const first = intentCreateReceipt(null, payload, key, chosen.binding)
  assert.deepEqual(intentCreateReceipt(first, clone(payload), key, chosen.binding), first); assert.equal(keys, 1)
  assert.equal(parseIntentCreateReceipt({ input: first.input, key: first.key }), null)
  for (const changed of [intentCreatePayload(chosen.instruction, 6), { ...payload, instruction: '新的要求' }, { ...payload, max_text_calls: 8 }])
    assert.throws(() => intentCreateReceipt(first, changed, key, chosen.binding), (error: unknown) => error instanceof IntentCreateError && error.code === 'CLIP_SUBMISSION_UNKNOWN')
  const other = clone(chosen.binding); other.answer_hash = 'e'.repeat(64)
  assert.throws(() => intentCreateReceipt(first, payload, key, other), IntentCreateError); assert.equal(keys, 1)
  assert.ok(parseIntentCreateReceipt({ input: JSON.stringify({ instruction: '普通旧请求', max_text_calls: 6 }), key: KEY }))
  assert.ok(parseIntentCreatePayload({ instruction: '😀'.repeat(1000), max_text_calls: 6 }))
  assert.equal(parseIntentCreatePayload({ instruction: '😀'.repeat(1001), max_text_calls: 6 }), null)
})

test('old five and caption six queue protocols cannot submit anchors; a seven queue protocol needs no online worker', () => {
  const report = (count: number) => ({ generated_at: '2026-11-07T00:00:00Z', heartbeat_timeout_seconds: 35,
    online_workers: 0, total_slots: 0, busy_slots: 0, available_slots: 0, workers: [],
    stages: EXECUTOR_STAGES.slice(0, count).map(stage_type => ({ stage_type, online_workers: 0, total_slots: 0, busy_slots: 0, available_slots: 0, reason: 'NO_EXECUTOR' })),
    resources: ['FFMPEG', 'ASR', 'TEXT'].map(name => ({ name, limit: 1, in_use: 0 })) })
  assert.equal(supportsAnswerClipProtocol(report(5)), false); assert.equal(supportsAnswerClipProtocol(report(6)), false)
  assert.equal(supportsAnswerClipProtocol(report(7)), true)
  const malformed = report(7); malformed.stages[6]!.stage_type = 'SEGMENT'; assert.throws(() => supportsAnswerClipProtocol(malformed), ExecutorSnapshotError)
  assert.throws(() => supportsAnswerClipProtocol({ stages: ['ANCHOR_CLIP'] }), ExecutorSnapshotError)
})

test('an existing committed request whose frozen answer becomes invalid must keep its UNKNOWN receipt', async () => {
  const chosen = await selection(), payload = intentCreatePayload(chosen.instruction, 6, chosen.binding)
  const receipt = intentCreateReceipt(null, payload, () => KEY, chosen.binding)
  assert.equal(intentCreateRejectedBeforeInsert('ANSWER_SOURCE_INVALID'), false)
  for (const code of ['NETWORK_UNAVAILABLE', 'REQUEST_TIMEOUT', 'HTTP_500', 'IDEMPOTENCY_CONFLICT', 'INVALID_CLIP_RESPONSE']) assert.equal(intentCreateRejectedBeforeInsert(code), false)
  for (const code of ['INVALID_CLIP_REQUEST', 'INVALID_ANSWER_SOURCE', 'ANSWER_SOURCE_UNAVAILABLE']) assert.equal(intentCreateRejectedBeforeInsert(code), true)
  assert.equal(intentCreateReceipt(receipt, payload, () => { throw Error('must not allocate a second key') }, chosen.binding).key, KEY)
})

test('another tab writing during capability or answer awaits blocks key allocation, storage replacement, and POST', async () => {
  const chosen = await selection(), payload = intentCreatePayload(chosen.instruction, 6, chosen.binding)
  for (const original of [null, intentCreateReceipt(null, payload, () => KEY, chosen.binding)]) {
    let slot: string | null = original ? JSON.stringify(original) : null, generated = 0, writes = 0, posts = 0
    const localDraft = { instruction: chosen.instruction, binding: clone(chosen.binding) }, before = clone(localDraft)
    let resume!: () => void
    const preparation = new Promise<void>(resolve => { resume = resolve })
    const submitAfterReads = async () => {
      await preparation // The real component awaits /executors and /agent-turns here.
      const receipt = prepareIntentCreateReceipt(() => slot, original, payload, () => { generated++; return NEXT }, chosen.binding)
      slot = JSON.stringify(receipt); writes++; posts++
    }
    const waiting = submitAfterReads()
    const foreign = intentCreateReceipt(null, intentCreatePayload('另一页的合法待核对请求', 4), () => NEXT)
    slot = JSON.stringify(foreign); resume()
    await assert.rejects(waiting, (error: unknown) => error instanceof IntentCreateError && error.code === 'CLIP_SUBMISSION_OTHER_PAGE')
    assert.equal(generated, 0); assert.equal(writes, 0); assert.equal(posts, 0)
    assert.deepEqual(JSON.parse(slot), foreign); assert.deepEqual(localDraft, before)
  }
  const own = intentCreateReceipt(null, payload, () => KEY, chosen.binding)
  assert.deepEqual(prepareIntentCreateReceipt(() => JSON.stringify(own), own, payload, () => { throw Error('No replacement key') }, chosen.binding), own)
  assert.throws(() => prepareIntentCreateReceipt(() => { throw new Error('storage blocked') }, own, payload, () => NEXT, chosen.binding),
    (error: unknown) => error instanceof IntentCreateError && error.code === 'CLIP_RECEIPT_STORAGE_UNAVAILABLE')
})
