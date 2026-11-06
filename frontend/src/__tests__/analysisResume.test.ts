import test from 'node:test'
import assert from 'node:assert/strict'
import type { AnalysisJob } from '../api.ts'
import { analysisCanResume, analysisResumeReceipt, analysisRetryExplanation, newestAnalysisSnapshot, parseResumedAnalysisSnapshot } from '../analysisResume.ts'
import { isAnalysisTerminal, parseAnalysisSnapshot, SnapshotError, SnapshotStream } from '../snapshotStream.ts'

const id = (value: number) => `00000000-0000-4000-8000-${String(value).padStart(12, '0')}`
function source(): AnalysisJob {
  return { id: id(1), video_id: id(80), generation: 4, status: 'FAILED', state_version: 15,
    config: { analysis_mode: 'topics', text_model_id: 'old-model' }, created_at: '2026-11-06T00:00:00Z',
    stages: ['MEDIA', 'ASR', 'SEGMENT', 'TIMELINE'].map((stage_type, index) => ({ id: id(100 + index), stage_type,
      status: index < 2 ? 'SUCCEEDED' : index === 2 ? 'FAILED' : 'BLOCKED', attempt: index < 3 ? 1 : 0,
      error_code: index === 2 ? 'TEXT_RECOVERY_EXHAUSTED' : null })),
    budget: { max_text_calls: 24, text_calls_reserved: 3, max_asr_submissions: 1, asr_calls_reserved: 1 },
    actions: { can_cancel: false, can_retry: false, retry_block_reason: 'TEXT_RECOVERY_EXHAUSTED', max_attempts: 3,
      can_resume_from_asr: true, resume_from_asr_block_reason: null } }
}
function resumed(): AnalysisJob {
  const old = source()
  return { ...old, id: id(2), resumed_from_job_id: old.id, generation: 6, state_version: 1, status: 'QUEUED',
    config: { analysis_mode: 'topics', text_model_id: 'current-model' },
    stages: old.stages.map((stage, index) => ({ ...stage, id: id(200 + index), attempt: 0,
      error_code: null, status: index === 0 ? 'READY' : 'BLOCKED' })),
    budget: { max_text_calls: 12, text_calls_reserved: 0, max_asr_submissions: 0, asr_calls_reserved: 0 },
    actions: { can_cancel: true, can_retry: false, retry_block_reason: 'JOB_NOT_RETRYABLE', max_attempts: 3,
      can_resume_from_asr: false, resume_from_asr_block_reason: 'JOB_NOT_RESUMABLE' } }
}

test('old analysis snapshots default resume eligibility to false and absent/null lineage remains compatible', () => {
  const value = source()
  delete value.actions.can_resume_from_asr; delete value.actions.resume_from_asr_block_reason
  const parsed = parseAnalysisSnapshot(value, value.id)
  assert.equal(parsed.actions.can_resume_from_asr, false)
  assert.equal(parsed.actions.resume_from_asr_block_reason, null)
  assert.equal(parsed.resumed_from_job_id, undefined)
  assert.equal(analysisCanResume(parsed, false, false), false)
  assert.equal(parseAnalysisSnapshot({ ...value, resumed_from_job_id: null }, value.id).resumed_from_job_id, undefined)
})

test('lineage, resume policy fields and the new retry exhaustion reason have strict parsing', () => {
  const value = resumed()
  assert.equal(parseAnalysisSnapshot(value, value.id).resumed_from_job_id, source().id)
  assert.equal(parseAnalysisSnapshot(source(), source().id).actions.retry_block_reason, 'TEXT_RECOVERY_EXHAUSTED')
  for (const reason of ['RESUME_REQUIRES_TOPICS', 'JOB_NOT_RESUMABLE', 'PREPARATION_NOT_READY', 'PREPARATION_SOURCE_INVALID']) {
    assert.equal(parseAnalysisSnapshot({ ...value, actions: { ...value.actions, resume_from_asr_block_reason: reason } }, value.id).actions.resume_from_asr_block_reason, reason)
  }
  for (const lineage of ['', 'bad-id', value.id, 42, {}]) {
    assert.throws(() => parseAnalysisSnapshot({ ...value, resumed_from_job_id: lineage }, value.id), SnapshotError)
  }
  for (const update of [{ can_resume_from_asr: 'true' }, { can_resume_from_asr: null }, { resume_from_asr_block_reason: 'UNKNOWN' }]) {
    assert.throws(() => parseAnalysisSnapshot({ ...value, actions: { ...value.actions, ...update } }, value.id), SnapshotError)
  }
})

test('resume needs backend eligibility and preserves unsaved or pending topic and intent editors', () => {
  for (const status of ['FAILED', 'CANCELLED', 'REVIEW_READY']) {
    const value = { ...source(), status }
    assert.equal(analysisCanResume(value, false, false), true)
    assert.equal(analysisCanResume(value, true, false), false)
    assert.equal(analysisCanResume(value, false, true), false)
  }
  const blocked = source(); blocked.actions.can_resume_from_asr = false
  assert.equal(analysisCanResume(blocked, false, false), false)
  assert.equal(analysisCanResume(null, false, false), false)
})

test('text recovery exhaustion explicitly recommends the independent text analysis entry', () => {
  const explanation = analysisRetryExplanation(source())
  assert.match(explanation, /复用转写重新分析/)
  assert.match(explanation, /新的文本请求预算/)
  assert.match(explanation, /原任务与调用记录保留/)
  const attempts = source(); attempts.actions.retry_block_reason = 'ATTEMPTS_EXHAUSTED'
  assert.match(analysisRetryExplanation(attempts), /复用转写重新分析/)
  attempts.actions.can_resume_from_asr = false
  assert.match(analysisRetryExplanation(attempts), /重新提交 ASR/)
})

test('unknown POST results reuse a receipt only for exactly the same source and text budget', () => {
  const receipt = analysisResumeReceipt(null, id(1), 12, () => id(900))
  assert.deepEqual(analysisResumeReceipt(JSON.parse(JSON.stringify(receipt)), id(1), 12,
    () => { throw new Error('must reuse the existing key') }), receipt)
  assert.equal(analysisResumeReceipt(receipt, id(1), 13, () => id(901)).key, id(901))
  assert.equal(analysisResumeReceipt(receipt, id(2), 12, () => id(902)).key, id(902))
  for (const invalid of [null, [], { input: receipt.input, key: 'bad' }, { input: 'foreign', key: receipt.key }]) {
    assert.equal(analysisResumeReceipt(invalid, id(1), 12, () => id(903)).key, id(903))
  }
  for (const budget of [1, 100]) assert.ok(analysisResumeReceipt(null, id(1), budget, () => id(900)))
  for (const budget of [0, 101, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY]) {
    assert.throws(() => analysisResumeReceipt(null, id(1), budget, () => id(900)), SnapshotError)
  }
  assert.throws(() => analysisResumeReceipt(null, 'invalid', 12, () => id(900)), SnapshotError)
  assert.throws(() => analysisResumeReceipt(null, id(1), 12, () => 'invalid'), SnapshotError)
})

test('new analysis accepts queued preparation or already advanced work without changing source accounting', () => {
  const old = source(), unchanged = JSON.stringify(old)
  const queued = parseResumedAnalysisSnapshot(resumed(), old, 12)
  assert.equal(queued.status, 'QUEUED')
  assert.equal(queued.stages[0]!.status, 'READY')
  assert.equal(queued.config?.text_model_id, 'current-model')
  const running = resumed(); running.status = 'RUNNING'; running.state_version = 4
  running.stages[0]!.status = 'SUCCEEDED'; running.stages[1]!.status = 'SUCCEEDED'; running.stages[2]!.status = 'RUNNING'
  running.budget.text_calls_reserved = 2
  assert.equal(parseResumedAnalysisSnapshot(running, old, 12).budget.text_calls_reserved, 2)
  assert.equal(JSON.stringify(old), unchanged)
})

test('foreign job, video, lineage, generation, mode or ASR accounting cannot replace the old selection', () => {
  for (const change of [
    (value: AnalysisJob) => { value.id = source().id },
    (value: AnalysisJob) => { value.video_id = id(81) },
    (value: AnalysisJob) => { delete value.resumed_from_job_id },
    (value: AnalysisJob) => { value.resumed_from_job_id = id(3) },
    (value: AnalysisJob) => { value.generation = source().generation },
    (value: AnalysisJob) => { value.config = { analysis_mode: 'commerce' } },
    (value: AnalysisJob) => { value.budget.max_text_calls = 24 },
    (value: AnalysisJob) => { value.budget.max_asr_submissions = 1 },
    (value: AnalysisJob) => { value.budget.asr_calls_reserved = 1 },
  ]) {
    const old = source(), unchanged = JSON.stringify(old), invalid = resumed(); change(invalid)
    assert.throws(() => parseResumedAnalysisSnapshot(invalid, old, 12), SnapshotError)
    assert.equal(JSON.stringify(old), unchanged)
  }
  for (const invalid of [null, [], {}, { id: 3 }]) assert.throws(() => parseResumedAnalysisSnapshot(invalid, source(), 12), SnapshotError)
})

test('same-job updates preserve lineage and budgets while equal-version policy changes remain readable', () => {
  const known = resumed()
  const policy = structuredClone(known); policy.actions.max_attempts = 5
  assert.equal(newestAnalysisSnapshot(known, policy).actions.max_attempts, 5)
  const older = structuredClone(known); older.state_version = 0
  assert.equal(newestAnalysisSnapshot(known, older), known)
  for (const change of [
    (value: AnalysisJob) => { value.video_id = id(81) },
    (value: AnalysisJob) => { value.generation++ },
    (value: AnalysisJob) => { value.resumed_from_job_id = id(3) },
    (value: AnalysisJob) => { value.budget.max_text_calls++ },
    (value: AnalysisJob) => { value.budget.max_asr_submissions++ },
  ]) {
    const invalid = structuredClone(known); change(invalid)
    assert.throws(() => newestAnalysisSnapshot(known, invalid), SnapshotError)
  }
})

test('selecting a resumed job aborts the old source poll and ignores a late high-version source response', async () => {
  let resolveSource: (value: unknown) => void = () => {}, sourceSignal: AbortSignal | undefined
  const delivered: string[] = []
  const stream = new SnapshotStream<AnalysisJob>({ kind: 'job', eventSource: null, parse: parseAnalysisSnapshot,
    terminal: isAnalysisTerminal, fetchSnapshot: async (resource, signal) => {
      if (resource === id(1)) { sourceSignal = signal; return await new Promise(resolve => { resolveSource = resolve }) }
      return { ...resumed(), status: 'REVIEW_READY', state_version: 5 }
    }, onSnapshot: value => { delivered.push(value.id) } })
  stream.select(id(1), { ...source(), status: 'RUNNING' })
  stream.select(id(2), parseResumedAnalysisSnapshot(resumed(), source(), 12))
  assert.equal(sourceSignal?.aborted, true)
  resolveSource({ ...source(), state_version: 999 })
  for (let index = 0; index < 8; index++) await Promise.resolve()
  assert.equal(stream.current?.id, id(2))
  assert.equal(stream.current?.status, 'REVIEW_READY')
  assert.deepEqual(delivered, [id(2)])
  stream.dispose()
})
