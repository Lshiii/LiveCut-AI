import test from 'node:test'
import assert from 'node:assert/strict'
import { reactive } from 'vue'
import { intentPayload, type IntentOutput, type IntentSnapshot } from '../intentClips.ts'
import { intentSaveRecovered } from '../intentSaveRecovery.ts'

function snapshot(): IntentSnapshot {
  const id = '11111111-1111-4111-8111-111111111111', job = '22222222-2222-4222-8222-222222222222'
  const video = '33333333-3333-4333-8333-333333333333', instruction = '分别剪出原因和方法，跳过关注引导'
  const utterances = ['u10', 'u2', 'u30', 'u4', 'u50', 'u6'].map((utterance_id, index) => ({ utterance_id,
    start_ms: index * 1000, end_ms: (index + 1) * 1000, text: index === 2 ? '关注后再继续' : `缓存解释第${index + 1}句` }))
  return { id, job_id: job, video_id: video, instruction, status: 'PLAN_READY', state_version: 3,
    created_at: '2026-11-07T00:00:00Z', updated_at: '2026-11-07T00:00:10Z',
    budget: { max_text_calls: 6, text_calls_reserved: 2 }, stage: { id: '44444444-4444-4444-8444-444444444444',
      stage_type: 'SEGMENT', status: 'SUCCEEDED', attempt: 1, error_code: null },
    source: { video_sha256: 'a'.repeat(64), transcript_hash: 'b'.repeat(64), duration_ms: 6000 },
    revision: 0, plan_hash: 'c'.repeat(64), confirmed_revisions: [], utterances,
    plan: { schema_version: '1.0', request_id: id, job_id: job, video_id: video, source_hash: 'a'.repeat(64),
      transcript_hash: 'b'.repeat(64), duration_ms: 6000, instruction,
      goals: [{ goal_id: 'cause', description: '缓存失效原因' }, { goal_id: 'solution', description: '解决方法' }],
      constraints: { keep_explanation_complete: true, exclude: ['关注引导'] },
      outputs: [{ output_id: 'cause-output', goal_id: 'cause', title: '为什么失效', status: 'FOUND', reason: '保留前提和解释', enabled: false,
        parts: [{ start_utterance_id: 'u10', end_utterance_id: 'u2', start_ms: 0, end_ms: 2000 },
          { start_utterance_id: 'u4', end_utterance_id: 'u4', start_ms: 3000, end_ms: 4000 }],
        excluded: [{ utterance_id: 'u30', reason: '关注引导' }], evidence_utterance_ids: ['u10', 'u2', 'u4'] },
      { output_id: 'solution-output', goal_id: 'solution', title: '怎么解决', status: 'FOUND', reason: '保留解决方法', enabled: false,
        parts: [{ start_utterance_id: 'u50', end_utterance_id: 'u6', start_ms: 4000, end_ms: 6000 }],
        excluded: [], evidence_utterance_ids: ['u50', 'u6'] }],
      provenance: { text_model_id: 'text-model', prompt_version: 'intent-clips-v1', retrieval_mode: 'bm25', index_hash: 'd'.repeat(64) } } }
}
function lostSave() {
  const known = snapshot(), draft = intentPayload(known.plan!.outputs).outputs
  draft[0]!.title = '本地修改后的原因标题'; draft[0]!.enabled = true
  const incoming = structuredClone(known)
  incoming.revision = 1; incoming.state_version = 4; incoming.plan_hash = 'e'.repeat(64)
  incoming.updated_at = '2026-11-07T00:01:00Z'; incoming.plan!.outputs = intentPayload(draft).outputs
  return { known, incoming, draft }
}
function freeze<T>(value: T): T {
  if (value !== null && typeof value === 'object') {
    for (const child of Object.values(value)) freeze(child)
    Object.freeze(value)
  }
  return value
}

test('a later matching saved revision can acknowledge a lost PUT response without changing input', () => {
  const { known, incoming, draft } = lostSave(), before = JSON.stringify({ known, incoming, draft })
  assert.equal(intentSaveRecovered(known, incoming, draft, 0), true)
  assert.equal(JSON.stringify({ known, incoming, draft }), before)
  assert.deepEqual(incoming.confirmed_revisions, [], 'recognition does not confirm the saved plan')
})

test('polling may already have adopted the new snapshot while the local base still points to the old revision', () => {
  const { incoming, draft } = lostSave()
  assert.equal(intentSaveRecovered(incoming, structuredClone(incoming), draft, 0), true)
  assert.equal(intentSaveRecovered(incoming, incoming, draft, 1), false, 'the draft base itself is not a recovered newer save')
})

test('a saved then independently confirmed revision with exactly the same payload is recognizable without auto-confirming', () => {
  const { known, incoming, draft } = lostSave()
  incoming.confirmed_revisions = [1]
  const before = JSON.stringify({ known, incoming, draft })
  assert.equal(intentSaveRecovered(known, incoming, draft, 0), true)
  assert.equal(JSON.stringify({ known, incoming, draft }), before)
  assert.deepEqual(known.confirmed_revisions, [])
})

test('the current unsaved revision or a newer server plan with different content cannot be acknowledged', () => {
  const { known, incoming, draft } = lostSave()
  assert.equal(intentSaveRecovered(known, known, known.plan!.outputs, 0), false)
  const changed = structuredClone(incoming); changed.plan!.outputs[0]!.title = '另一个客户端保存的标题'
  assert.equal(intentSaveRecovered(known, changed, draft, 0), false)
  const editedAgain = intentPayload(draft).outputs; editedAgain[1]!.title = '用户在等待期间继续编辑'
  assert.equal(intentSaveRecovered(known, incoming, editedAgain, 0), false)
})

test('every normalized output field, sequence and evidence selection must match the saved plan', () => {
  const changes: ((draft: IntentOutput[]) => void)[] = [
    value => { value[0]!.title += '变化' }, value => { value[0]!.reason += '变化' }, value => { value[0]!.enabled = false },
    value => { value[0]!.status = 'NEEDS_REVIEW' }, value => { value[0]!.output_id = 'different-output' },
    value => { value[0]!.goal_id = 'different-goal' }, value => { value.reverse() },
    value => { value[0]!.parts.reverse() }, value => { value[0]!.parts[0]!.end_utterance_id = 'u10'; value[0]!.parts[0]!.end_ms = 1000 },
    value => { value[0]!.parts[0]!.start_utterance_id = 'u2'; value[0]!.parts[0]!.start_ms = 1000 },
    value => { value[0]!.evidence_utterance_ids = ['u10', 'u4'] }, value => { value[0]!.evidence_utterance_ids.reverse() },
    value => { value[0]!.excluded[0]!.reason = '其他排除原因' }, value => { value[0]!.excluded = [] },
  ]
  for (const change of changes) {
    const { known, incoming, draft } = lostSave(); change(draft)
    assert.equal(intentSaveRecovered(known, incoming, draft, 0), false)
  }
})

test('snapshot and nested plan request, job and video identities are checked independently', () => {
  const other = '55555555-5555-4555-8555-555555555555'
  const changes: ((value: IntentSnapshot) => void)[] = [
    value => { value.id = other; value.plan!.request_id = other },
    value => { value.job_id = other; value.plan!.job_id = other },
    value => { value.video_id = other; value.plan!.video_id = other },
    value => { value.plan!.request_id = other }, value => { value.plan!.job_id = other }, value => { value.plan!.video_id = other },
    value => { value.instruction = '不同要求'; value.plan!.instruction = value.instruction },
    value => { value.resumed_from_request_id = other },
  ]
  for (const change of changes) {
    const { known, incoming, draft } = lostSave(); change(incoming)
    assert.equal(intentSaveRecovered(known, incoming, draft, 0), false)
  }
})

test('source hash, duration, original text, source times and order cannot change beneath the draft', () => {
  const changes: ((value: IntentSnapshot) => void)[] = [
    value => { value.source.video_sha256 = 'f'.repeat(64); value.plan!.source_hash = value.source.video_sha256 },
    value => { value.source.transcript_hash = 'f'.repeat(64); value.plan!.transcript_hash = value.source.transcript_hash },
    value => { value.source.duration_ms = 7000; value.plan!.duration_ms = 7000 },
    value => { value.plan!.source_hash = 'f'.repeat(64) }, value => { value.plan!.transcript_hash = 'f'.repeat(64) },
    value => { value.plan!.duration_ms = 7000 }, value => { value.utterances[0]!.text = '同hash下的篡改原文' },
    value => { value.utterances[2]!.start_ms = 2100 }, value => { value.utterances[2]!.end_ms = 2900 },
    value => { value.utterances.reverse() }, value => { value.utterances.pop() },
  ]
  for (const change of changes) {
    const { known, incoming, draft } = lostSave(); change(incoming)
    assert.equal(intentSaveRecovered(known, incoming, draft, 0), false)
  }
})

test('a matching payload cannot elevate frozen goal status or change fixed plan identities', () => {
  const changes: ((value: IntentSnapshot) => void)[] = [
    value => { value.plan!.outputs[1]!.status = 'NEEDS_REVIEW' },
    value => { value.plan!.outputs.reverse() },
    value => { value.plan!.goals[0]!.description = '不同语义目标' },
    value => { value.plan!.constraints.keep_explanation_complete = false },
    value => { value.plan!.constraints.exclude = [] },
    value => { value.plan!.provenance.text_model_id = 'other-model' },
    value => { value.plan!.provenance.index_hash = 'f'.repeat(64) },
  ]
  for (const change of changes) {
    const { known, incoming } = lostSave(); change(incoming)
    const matchingDraft = intentPayload(incoming.plan!.outputs).outputs
    assert.equal(intentSaveRecovered(known, incoming, matchingDraft, 0), false)
  }
})

test('old snapshots, invalid versions and same-revision hash contradictions are rejected', () => {
  for (const base of [null, -1, .5, Number.NaN, Number.POSITIVE_INFINITY, Number.MAX_SAFE_INTEGER + 1]) {
    const { known, incoming, draft } = lostSave()
    assert.equal(intentSaveRecovered(known, incoming, draft, base), false)
  }
  const { known, incoming, draft } = lostSave()
  assert.equal(intentSaveRecovered(null, incoming, draft, 0), false)
  assert.equal(intentSaveRecovered(known, incoming, draft, 1), false, 'known must include the local base revision')
  const older = structuredClone(incoming); older.revision = 0
  assert.equal(intentSaveRecovered(incoming, older, draft, 0), false)
  for (const version of [-1, .5, Number.NaN, Number.POSITIVE_INFINITY, Number.MAX_SAFE_INTEGER + 1]) {
    const invalid = structuredClone(incoming); invalid.state_version = version
    assert.equal(intentSaveRecovered(known, invalid, draft, 0), false)
    invalid.state_version = 4; invalid.revision = version
    assert.equal(intentSaveRecovered(known, invalid, draft, 0), false)
  }
  const regressedState = structuredClone(incoming); regressedState.state_version = known.state_version - 1
  assert.equal(intentSaveRecovered(known, regressedState, draft, 0), false)
  regressedState.state_version = known.state_version
  assert.equal(intentSaveRecovered(known, regressedState, draft, 0), false, 'a new revision cannot reuse the older state version')
  const conflictingHash = structuredClone(incoming); conflictingHash.plan_hash = 'f'.repeat(64)
  assert.equal(intentSaveRecovered(incoming, conflictingHash, draft, 0), false)
})

test('both snapshots must remain ready and retain their plan and existing confirmation history', () => {
  for (const state of ['QUEUED', 'RUNNING', 'FAILED', 'CANCELLED'] as const) {
    const { known, incoming, draft } = lostSave()
    incoming.status = state; assert.equal(intentSaveRecovered(known, incoming, draft, 0), false)
    incoming.status = 'PLAN_READY'; known.status = state; assert.equal(intentSaveRecovered(known, incoming, draft, 0), false)
  }
  const { known, incoming, draft } = lostSave()
  const empty = structuredClone(known); empty.plan = null; empty.plan_hash = null; empty.status = 'QUEUED'
  assert.equal(intentSaveRecovered(empty, incoming, draft, 0), false)
  assert.equal(intentSaveRecovered(known, empty, draft, 0), false)
  known.confirmed_revisions = [0]
  assert.equal(intentSaveRecovered(known, incoming, draft, 0), false)
  incoming.confirmed_revisions = [0]
  assert.equal(intentSaveRecovered(known, incoming, draft, 0), true)
})

test('reactive and deeply frozen inputs are read without proxy cloning, mutation or leaking undeclared payload fields', () => {
  const { known, incoming, draft } = lostSave()
  const proxiedKnown = reactive(known), proxiedIncoming = reactive(incoming), proxiedDraft = reactive(draft)
  Object.assign(proxiedDraft[0]!, { local_only_note: '不进入保存正文' })
  const before = JSON.stringify({ known, incoming, draft })
  assert.equal(intentSaveRecovered(proxiedKnown, proxiedIncoming, proxiedDraft, 0), true)
  assert.equal(JSON.stringify({ known, incoming, draft }), before)
  const frozen = lostSave()
  freeze(frozen)
  assert.equal(intentSaveRecovered(frozen.known, frozen.incoming, frozen.draft, 0), true)
})
