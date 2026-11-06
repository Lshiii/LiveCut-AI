import test from 'node:test'
import assert from 'node:assert/strict'
import { reactive } from 'vue'
import type { TextProgress } from '../textProgress.ts'
import { editIntentBoundary, intentCanCancel, intentCanConfirm, intentCanExport, intentCanResume, intentOutputValidation, intentPartIds,
  intentPayload, IntentReadScope, IntentResponseError, newestIntentSnapshot, parseIntentSnapshot,
  parseResumedIntentSnapshot, intentResumeReceipt, refreshIntentEvidence, type IntentSnapshot } from '../intentClips.ts'

const JOB = '11111111-1111-4111-8111-111111111111'
const REQUEST = '22222222-2222-4222-8222-222222222222'
function snapshot(): IntentSnapshot {
  const utterances = ['u10', 'u2', 'u30', 'u4', 'u50', 'u6'].map((utterance_id, i) => ({ utterance_id,
    start_ms: i * 1000, end_ms: (i + 1) * 1000, text: i === 2 ? '关注一下再继续' : `缓存解释第${i}句` }))
  return { id: REQUEST, job_id: JOB, video_id: '33333333-3333-4333-8333-333333333333', instruction: '原因和方法分别剪出，跳过关注引导',
    status: 'PLAN_READY', state_version: 3, created_at: '2026-10-30T00:00:00Z', updated_at: '2026-10-30T00:00:30Z',
    budget: { max_text_calls: 6, text_calls_reserved: 2 }, stage: { id: '44444444-4444-4444-8444-444444444444', stage_type: 'SEGMENT', status: 'SUCCEEDED', attempt: 1, error_code: null },
    source: { video_sha256: 'a'.repeat(64), transcript_hash: 'b'.repeat(64), duration_ms: 6000 }, revision: 0,
    plan_hash: 'c'.repeat(64), confirmed_revisions: [], utterances,
    plan: { schema_version: '1.0', request_id: REQUEST, job_id: JOB, video_id: '33333333-3333-4333-8333-333333333333',
      source_hash: 'a'.repeat(64), transcript_hash: 'b'.repeat(64), duration_ms: 6000, instruction: '原因和方法分别剪出，跳过关注引导',
      goals: [{ goal_id: 'cause', description: '失效原因' }, { goal_id: 'solution', description: '解决方法' }],
      constraints: { keep_explanation_complete: true, exclude: ['关注引导'] },
      outputs: [{ output_id: 'output-cause', goal_id: 'cause', title: '缓存为何失效', status: 'FOUND', reason: '保留前提和结论', enabled: false,
        parts: [{ start_utterance_id: 'u10', end_utterance_id: 'u2', start_ms: 0, end_ms: 2000 }, { start_utterance_id: 'u4', end_utterance_id: 'u4', start_ms: 3000, end_ms: 4000 }],
        excluded: [{ utterance_id: 'u30', reason: '关注引导' }], evidence_utterance_ids: ['u10', 'u2', 'u4'] },
      { output_id: 'output-solution', goal_id: 'solution', title: '如何解决', status: 'FOUND', reason: '原句说明解决方法', enabled: false,
        parts: [{ start_utterance_id: 'u50', end_utterance_id: 'u6', start_ms: 4000, end_ms: 6000 }], excluded: [], evidence_utterance_ids: ['u50', 'u6'] }],
      provenance: { text_model_id: 'qwen-plus', prompt_version: 'intent-clips-v1', retrieval_mode: 'bm25', index_hash: 'd'.repeat(64) } } }
}

test('a two-goal plan retains disjoint parts and maps non-lexicographic source IDs in source order', () => {
  const value = parseIntentSnapshot(snapshot(), JOB, REQUEST)
  assert.equal(value.plan?.outputs.length, 2)
  assert.deepEqual(intentPartIds(value.plan!.outputs[0]!.parts[0]!, value.utterances), ['u10', 'u2'])
  assert.equal(intentOutputValidation(value.plan!.outputs, value.utterances, 6000, value.plan!.goals), '')
})
test('optional recovery metadata preserves clip plan and dirty-draft identity even when malformed', () => {
  const value = snapshot()
  const progress: TextProgress = { state: 'RETRY_WAIT', attempt_number: 2, max_attempts: 3, provider: 'primary',
    model_id: 'deepseek-flash', timeout_seconds: 90, elapsed_seconds: 95, retry_in_seconds: 2 }
  value.stage.text_progress = progress
  assert.deepEqual(parseIntentSnapshot(value, JOB).stage.text_progress, progress)
  const malformed = { ...value, stage: { ...value.stage, text_progress: { ...progress, timeout_seconds: -1 } } }
  const accepted = parseIntentSnapshot(malformed, JOB)
  assert.equal(accepted.stage.text_progress, undefined)
  assert.deepEqual(accepted.plan, value.plan)
  assert.equal(accepted.plan_hash, value.plan_hash)
  assert.deepEqual(value.stage.text_progress, progress)
})
test('request, video and immutable evidence identities must match the selected source', () => {
  assert.throws(() => parseIntentSnapshot(snapshot(), '55555555-5555-4555-8555-555555555555'), IntentResponseError)
  assert.throws(() => parseIntentSnapshot(snapshot(), JOB, '55555555-5555-4555-8555-555555555555'), IntentResponseError)
  for (const field of ['request_id', 'video_id', 'source_hash', 'transcript_hash'] as const) {
    const value = snapshot(); value.plan![field] = 'invalid'
    assert.throws(() => parseIntentSnapshot(value, JOB), IntentResponseError)
  }
})
test('unknown, reversed or overlapping source intervals cannot become exportable', () => {
  for (const change of [
    (v: IntentSnapshot) => { v.plan!.outputs[0]!.parts[0]!.start_utterance_id = 'missing' },
    (v: IntentSnapshot) => { v.plan!.outputs[0]!.parts[0]!.start_utterance_id = 'u4' },
    (v: IntentSnapshot) => { v.plan!.outputs[0]!.parts[1] = { start_utterance_id: 'u2', end_utterance_id: 'u4', start_ms: 1000, end_ms: 4000 } },
  ]) { const value = snapshot(); change(value); assert.throws(() => parseIntentSnapshot(value, JOB), IntentResponseError) }
})
test('valid source bounds alone are insufficient when times do not match referenced original sentences', () => {
  const value = snapshot(); value.plan!.outputs[0]!.parts[0]!.end_ms = 1999
  assert.throws(() => parseIntentSnapshot(value, JOB), IntentResponseError)
})
test('excluded advertising and supporting evidence must not leak across retained source parts', () => {
  const conflict = snapshot(); conflict.plan!.outputs[0]!.parts[0]!.end_utterance_id = 'u30'; conflict.plan!.outputs[0]!.parts[0]!.end_ms = 3000
  assert.throws(() => parseIntentSnapshot(conflict, JOB), IntentResponseError)
  const outside = snapshot(); outside.plan!.outputs[0]!.evidence_utterance_ids.push('u50')
  assert.throws(() => parseIntentSnapshot(outside, JOB), IntentResponseError)
})
test('a missing or unresolved goal is visible but cannot be enabled or silently omitted', () => {
  const value = snapshot(); const output = value.plan!.outputs[1]!
  output.status = 'NOT_FOUND'; output.parts = []; output.evidence_utterance_ids = []
  assert.equal(parseIntentSnapshot(value, JOB).plan!.outputs[1]!.status, 'NOT_FOUND')
  output.enabled = true
  assert.throws(() => parseIntentSnapshot(value, JOB), IntentResponseError)
  output.enabled = false; output.status = 'NEEDS_REVIEW'
  assert.equal(parseIntentSnapshot(value, JOB).plan!.outputs[1]!.status, 'NEEDS_REVIEW')
  value.plan!.outputs.pop()
  assert.throws(() => parseIntentSnapshot(value, JOB), IntentResponseError)
})
test('shared explanatory context between different outputs is allowed while each output remains non-overlapping', () => {
  const value = snapshot(); value.plan!.outputs[1]!.parts = [{ ...value.plan!.outputs[0]!.parts[0]! }]
  value.plan!.outputs[1]!.evidence_utterance_ids = ['u10', 'u2']
  assert.equal(parseIntentSnapshot(value, JOB).plan!.outputs.length, 2)
})
test('queued snapshots can have no plan but a ready or confirmed result cannot masquerade as empty', () => {
  const value = snapshot(); value.plan = null; value.plan_hash = null; value.status = 'QUEUED'; value.stage.status = 'READY'
  assert.equal(parseIntentSnapshot(value, JOB).plan, null)
  value.status = 'PLAN_READY'
  assert.throws(() => parseIntentSnapshot(value, JOB), IntentResponseError)
  value.status = 'QUEUED'; value.confirmed_revisions = [0]
  assert.throws(() => parseIntentSnapshot(value, JOB), IntentResponseError)
})
test('invalid accounting and duplicate original utterances are rejected before editing', () => {
  const accounting = snapshot(); accounting.budget.text_calls_reserved = 7
  assert.throws(() => parseIntentSnapshot(accounting, JOB), IntentResponseError)
  const duplicate = snapshot(); duplicate.utterances[1]!.utterance_id = 'u10'
  assert.throws(() => parseIntentSnapshot(duplicate, JOB), IntentResponseError)
})
test('editing a boundary derives source times and preserves an invalid ID draft for correction', () => {
  const value = snapshot(), output = value.plan!.outputs[0]!
  editIntentBoundary(output, 0, 'end_utterance_id', 'u30', value.utterances)
  assert.equal(output.parts[0]!.end_ms, 3000)
  assert.match(intentOutputValidation(value.plan!.outputs, value.utterances, 6000), /排除语句/)
  editIntentBoundary(output, 0, 'start_utterance_id', 'typing', value.utterances)
  assert.equal(output.parts[0]!.start_utterance_id, 'typing')
  assert.equal(output.parts[0]!.start_ms, -1)
  assert.notEqual(intentOutputValidation(value.plan!.outputs, value.utterances, 6000), '')
})
test('removing one source part also removes its old evidence but keeps the other part', () => {
  const value = snapshot(), output = value.plan!.outputs[0]!
  output.parts.splice(0, 1); refreshIntentEvidence(output, value.utterances)
  assert.deepEqual(output.evidence_utterance_ids, ['u4'])
  assert.equal(intentOutputValidation(value.plan!.outputs, value.utterances, 6000), '')
})
test('save payload works for reactive drafts and does not round-trip undeclared private fields', () => {
  const value = snapshot(); const draft = reactive(value.plan!.outputs)
  Object.assign(draft[0]!, { unexpected_private: 'never-submit' })
  const payload = intentPayload(draft)
  assert.equal('unexpected_private' in payload.outputs[0]!, false)
  payload.outputs[0]!.parts[0]!.end_ms = 9
  assert.equal(draft[0]!.parts[0]!.end_ms, 2000)
})
test('late snapshots cannot regress revisions, confirmed history or request state', () => {
  const known = snapshot(); known.state_version = 6; known.revision = 2; known.confirmed_revisions = [1, 2]
  assert.equal(newestIntentSnapshot(known, snapshot()), known)
  const wrongHistory = snapshot(); wrongHistory.state_version = 7; wrongHistory.revision = 3
  assert.equal(newestIntentSnapshot(known, wrongHistory), known)
  const wrongSource = snapshot(); wrongSource.source.video_sha256 = 'f'.repeat(64)
  assert.throws(() => newestIntentSnapshot(known, wrongSource), IntentResponseError)
})
test('confirmation requires saved selection, while export additionally requires that exact confirmed revision', () => {
  const value = snapshot(), outputs = value.plan!.outputs
  assert.equal(intentCanConfirm(value, outputs, false, false), false)
  outputs[0]!.enabled = true
  assert.equal(intentCanConfirm(value, outputs, false, false), true)
  assert.equal(intentCanConfirm(value, outputs, true, false), false)
  assert.equal(intentCanConfirm(value, outputs, false, true), false)
  assert.equal(intentCanExport(value, false, false), false)
  value.confirmed_revisions = [0]
  assert.equal(intentCanExport(value, false, false), true)
  assert.equal(intentCanExport(value, true, false), false)
  value.revision = 1
  assert.equal(intentCanExport(value, false, false), false)
})
test('selection changes invalidate late GET responses even when the same request is revisited', () => {
  const scope = new IntentReadScope(), oldA = scope.begin()
  scope.invalidate(); const requestB = scope.begin()
  scope.invalidate(); const newA = scope.begin()
  assert.equal(oldA.current(), false); assert.equal(oldA.signal.aborted, true)
  assert.equal(requestB.current(), false); assert.equal(newA.current(), true)
  newA.release(); scope.dispose(); assert.equal(newA.current(), false)
  const afterUnmount = scope.begin(); assert.equal(afterUnmount.current(), false); assert.equal(afterUnmount.signal.aborted, true)
})

function failedSnapshot(): IntentSnapshot {
  const value = snapshot()
  value.plan = null; value.plan_hash = null; value.status = 'FAILED'; value.confirmed_revisions = []
  value.stage.status = 'FAILED'; value.stage.error_code = 'TEXT_RETRIES_EXHAUSTED'
  return value
}
function continuedSnapshot(source: IntentSnapshot): IntentSnapshot {
  return { ...source, id: '55555555-5555-4555-8555-555555555555', resumed_from_request_id: source.id,
    status: 'QUEUED', state_version: 0, budget: { max_text_calls: 6, text_calls_reserved: 0 },
    stage: { id: '66666666-6666-4666-8666-666666666666', stage_type: 'SEGMENT', status: 'READY', attempt: 0, error_code: null } }
}

test('continued request ancestry is optional for old snapshots but must be a distinct valid request ID', () => {
  const source = failedSnapshot(), next = continuedSnapshot(source)
  assert.equal(parseIntentSnapshot(source, JOB).resumed_from_request_id, undefined)
  assert.equal(parseIntentSnapshot({ ...source, resumed_from_request_id: null }, JOB).resumed_from_request_id, undefined)
  assert.equal(parseIntentSnapshot(next, JOB).resumed_from_request_id, source.id)
  for (const value of [next.id, '', 12, 'https://unexpected.example/private']) {
    assert.throws(() => parseIntentSnapshot({ ...next, resumed_from_request_id: value }, JOB), IntentResponseError)
  }
})

test('cancel and continue actions protect terminal results, pending operations and unsaved input', () => {
  for (const status of ['QUEUED', 'RUNNING', 'PLAN_READY', 'FAILED', 'CANCELLED'] as const) {
    const value = failedSnapshot(); value.status = status
    assert.equal(intentCanCancel(value, false, false), status === 'QUEUED' || status === 'RUNNING')
    assert.equal(intentCanResume(value, false, false), status === 'FAILED' || status === 'CANCELLED')
    assert.equal(intentCanCancel(value, true, false), false)
    assert.equal(intentCanCancel(value, false, true), false)
    assert.equal(intentCanResume(value, true, false), false)
    assert.equal(intentCanResume(value, false, true), false)
  }
  assert.equal(intentCanCancel(null, false, false), false)
  assert.equal(intentCanResume(null, false, false), false)
})

test('continuing after a lost POST response reuses the same receipt while another request or budget cannot reuse it', () => {
  const firstKey = '77777777-7777-4777-8777-777777777777', nextKey = '88888888-8888-4888-8888-888888888888'
  const receipt = intentResumeReceipt(null, REQUEST, 6, () => firstKey)
  const stored = JSON.parse(JSON.stringify(receipt))
  assert.deepEqual(intentResumeReceipt(stored, REQUEST, 6, () => { throw new Error('must reuse the existing key') }), receipt)
  assert.equal(intentResumeReceipt(stored, REQUEST, 7, () => nextKey).key, nextKey)
  assert.equal(intentResumeReceipt(stored, '55555555-5555-4555-8555-555555555555', 6, () => nextKey).key, nextKey)
  for (const invalid of [{ ...stored, key: 'unsafe\nkey' }, { ...stored, key: '' }, [], 12]) {
    assert.equal(intentResumeReceipt(invalid, REQUEST, 6, () => nextKey).key, nextKey)
  }
  for (const budget of [0, 25, 2.5, Number.NaN]) assert.throws(() => intentResumeReceipt(null, REQUEST, budget, () => firstKey), IntentResponseError)
})

test('resume accepts the new request already running but never changes old accounting or evidence', () => {
  const source = failedSnapshot(), old = JSON.stringify(source), next = continuedSnapshot(source)
  next.status = 'RUNNING'; next.stage.status = 'RUNNING'; next.stage.attempt = 1; next.budget.text_calls_reserved = 1
  const accepted = parseResumedIntentSnapshot(next, source, 6)
  assert.equal(accepted.id, next.id); assert.equal(accepted.status, 'RUNNING')
  assert.equal(JSON.stringify(source), old)
  assert.equal(source.budget.text_calls_reserved, 2)
  assert.equal(accepted.budget.text_calls_reserved, 1)
})

test('an unrelated or source-changed resume response cannot replace the selected request', () => {
  const source = failedSnapshot()
  for (const change of [
    (v: IntentSnapshot) => { v.id = source.id },
    (v: IntentSnapshot) => { delete v.resumed_from_request_id },
    (v: IntentSnapshot) => { v.resumed_from_request_id = '99999999-9999-4999-8999-999999999999' },
    (v: IntentSnapshot) => { v.video_id = '99999999-9999-4999-8999-999999999999' },
    (v: IntentSnapshot) => { v.instruction = '其他剪辑要求' },
    (v: IntentSnapshot) => { v.source.video_sha256 = 'c'.repeat(64) },
    (v: IntentSnapshot) => { v.source.transcript_hash = 'd'.repeat(64) },
    (v: IntentSnapshot) => { v.source.duration_ms = 7000 },
    (v: IntentSnapshot) => { v.budget.max_text_calls = 7 },
  ]) {
    const value = structuredClone(continuedSnapshot(source)); change(value)
    assert.throws(() => parseResumedIntentSnapshot(value, source, 6), IntentResponseError)
  }
})

test('late polling cannot rewrite immutable continuation ancestry or instruction', () => {
  const source = failedSnapshot(), known = continuedSnapshot(source)
  for (const change of [
    (v: IntentSnapshot) => { delete v.resumed_from_request_id },
    (v: IntentSnapshot) => { v.instruction = '迟到的其他内容' },
    (v: IntentSnapshot) => { v.source.duration_ms = 7000 },
  ]) {
    const value = structuredClone(known); value.state_version++
    change(value)
    assert.throws(() => newestIntentSnapshot(known, value), IntentResponseError)
  }
})
