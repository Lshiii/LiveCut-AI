import test from 'node:test'
import assert from 'node:assert/strict'
import { diagnosticView, parseDiagnostic, safeErrorCode } from '../diagnostics.ts'
import { parseAnalysisSnapshot, parseExportSnapshot } from '../snapshotStream.ts'

const id = (value: number) => `00000000-0000-4000-8000-${String(value).padStart(12, '0')}`
function diagnostic() {
  return { schema_version: 1, attempt: 2, error_code: 'INVALID_MODEL_OUTPUT', category: 'MODEL_OUTPUT',
    reason_code: 'ASSIGNMENT_KEYS_MISMATCH', suggestion_code: 'CHECK_FORMAT_THEN_RETRY',
    window_index: 8, window_count: 10, missing_core_id_count: 1, extra_context_id_count: 3,
    unknown_key_count: 2, non_core_key_count: 5, value_error_count: 0, format_repair: 'FAILED_VALIDATION' }
}
function job(rawDiagnostic?: unknown) {
  return { id: id(1), video_id: id(80), generation: 1, status: 'FAILED', state_version: 3, created_at: '2026-10-26T00:00:00Z',
    stages: ['MEDIA', 'ASR', 'SEGMENT', 'TIMELINE'].map((stage_type, index) => ({ id: id(100 + index), stage_type,
      status: index === 2 ? 'FAILED' : 'SUCCEEDED', attempt: 2, error_code: index === 2 ? 'INVALID_MODEL_OUTPUT' : null,
      ...(index === 2 && rawDiagnostic !== undefined ? { diagnostic: rawDiagnostic } : {}) })),
    budget: { max_text_calls: 12, text_calls_reserved: 3, max_asr_submissions: 1, asr_calls_reserved: 1 },
    actions: { can_cancel: true, can_retry: true, retry_block_reason: null, max_attempts: 3 } }
}
function batch(rawDiagnostic?: unknown) {
  return { id: id(10), job_id: id(1), video_id: id(80), approved_revision: 0, snapshot_hash: 'a'.repeat(64), profile: 'h264-aac-v1',
    status: 'FAILED', state_version: 3, created_at: '2026-10-26T00:00:00Z', updated_at: '2026-10-26T00:00:00Z',
    clips: [{ id: id(20), segment_id: 's:001', product_id: 'p.001', product_name: '合成商品', start_ms: 0, end_ms: 1000,
      stage_id: id(120), status: 'FAILED', attempt: 2, error_code: 'INVALID_MODEL_OUTPUT', size_bytes: null, content_url: null,
      download_url: null, ...(rawDiagnostic === undefined ? {} : { diagnostic: rawDiagnostic }) }] }
}

test('diagnostics show only fixed labels and bounded numbers, with human window numbering', () => {
  assert.deepEqual(parseDiagnostic(diagnostic(), 2, 'INVALID_MODEL_OUTPUT'), diagnostic())
  const view = diagnosticView(diagnostic(), 2, 'INVALID_MODEL_OUTPUT')!
  assert.equal(view.title, '分类结果存在缺项或多项')
  assert.equal(view.category, '模型结果')
  assert.deepEqual(view.details, ['第 9 / 10 个分析窗口', '缺少语句 1 项', '多余上下文 3 项', '未知条目 2 项', '分类内容错误 0 项', '格式修复后仍未通过校验'])
  assert.match(view.suggestion, /先检查/)
  const raw = diagnostic()
  const parsed = parseDiagnostic(raw, 2, 'INVALID_MODEL_OUTPUT')!
  parsed.window_index = 1
  assert.equal(raw.window_index, 8)
})

test('legacy, future, foreign-attempt and inconsistent diagnostics are safely omitted', () => {
  for (const raw of [undefined, null, [], {}, 'raw provider text', { ...diagnostic(), schema_version: 2 },
    { ...diagnostic(), attempt: 1 }, { ...diagnostic(), error_code: 'OTHER_FAILURE' }, { ...diagnostic(), category: 'BUDGET' },
    { ...diagnostic(), reason_code: 'FUTURE_REASON' }, { ...diagnostic(), suggestion_code: 'NEW_JOB_WITH_BUDGET' },
    { ...diagnostic(), non_core_key_count: 6 }, { ...diagnostic(), format_repair: 'RAW_TEXT' }]) {
    assert.equal(parseDiagnostic(raw, 2, 'INVALID_MODEL_OUTPUT'), undefined)
  }
  assert.equal(parseDiagnostic(diagnostic(), 2, null), undefined)
  assert.equal(parseDiagnostic(diagnostic(), 2, 'BUDGET_EXHAUSTED'), undefined)
  assert.equal(safeErrorCode('<script>raw</script>'), undefined)
})

test('private fields and arbitrary text never enter the parsed task or clip diagnostic', () => {
  for (const [field, value] of Object.entries({ message: 'private transcription', utterance_ids: ['secret-id'],
    prompt: 'private prompt', response: { content: 'provider output' }, url: 'https://signed.invalid/private',
    run_token: 'synthetic-private-token', path: 'C:\\private\\evidence', stack: 'private traceback' })) {
    const raw = { ...diagnostic(), [field]: value }
    const input = job(raw), original = JSON.stringify(input)
    const parsed = parseAnalysisSnapshot(input, id(1))
    assert.equal(parsed.status, 'FAILED')
    assert.equal(parsed.stages[2]!.error_code, 'INVALID_MODEL_OUTPUT')
    assert.ok(!Object.hasOwn(parsed.stages[2]!, 'diagnostic'))
    assert.ok(!Object.hasOwn(parseExportSnapshot(batch(raw), id(10)).clips[0]!, 'diagnostic'))
    assert.equal(JSON.stringify(input), original, 'Parsing must not mutate the received snapshot')
  }
})

test('diagnostic numeric fields require paired bounded safe integers', () => {
  const raw: Record<string, unknown> = diagnostic()
  for (const field of ['missing_core_id_count', 'extra_context_id_count', 'unknown_key_count', 'non_core_key_count', 'value_error_count']) {
    for (const bad of [true, null, -1, 0.5, 1000001, Number.NaN, Number.POSITIVE_INFINITY, '2']) {
      assert.equal(parseDiagnostic({ ...raw, [field]: bad }, 2, 'INVALID_MODEL_OUTPUT'), undefined, `${field}: ${bad}`)
    }
  }
  for (const replacement of [{ window_index: undefined }, { window_count: undefined }, { window_index: -1 },
    { window_index: 10 }, { window_count: 0 }, { window_count: 10001 }, { window_index: true }, { window_count: 2.5 }]) {
    assert.equal(parseDiagnostic({ ...raw, ...replacement }, 2, 'INVALID_MODEL_OUTPUT'), undefined)
  }
  const maximum = { ...raw, window_index: 9999, window_count: 10000, missing_core_id_count: 1000000 }
  assert.ok(parseDiagnostic(maximum, 2, 'INVALID_MODEL_OUTPUT'))
})

test('optional diagnostic parsing preserves snapshots, legacy omission and server action policy', () => {
  for (const raw of [undefined, null, diagnostic(), { ...diagnostic(), schema_version: 9 }]) {
    const parsedJob = parseAnalysisSnapshot(job(raw), id(1))
    const parsedBatch = parseExportSnapshot(batch(raw), id(10), id(1))
    assert.equal(parsedJob.actions.can_retry, true)
    assert.equal(parsedJob.state_version, 3)
    assert.equal(parsedBatch.clips[0]!.status, 'FAILED')
    assert.equal(Boolean(parsedJob.stages[2]!.diagnostic), raw?.schema_version === 1)
    assert.equal(Boolean(parsedBatch.clips[0]!.diagnostic), raw?.schema_version === 1)
  }
  assert.ok(!Object.hasOwn(parseAnalysisSnapshot(job(), id(1)).stages[2]!, 'diagnostic'))
  const valid = parseAnalysisSnapshot(job(diagnostic()), id(1)).stages[2]!.diagnostic!
  assert.equal(valid.attempt, 2)
})

test('budget and uncertain-outcome advice does not promise more calls or a safe resend', () => {
  const budget = diagnosticView({ schema_version: 1, attempt: 1, error_code: 'BUDGET_EXHAUSTED', category: 'BUDGET',
    reason_code: 'REQUEST_BUDGET_EXHAUSTED', suggestion_code: 'NEW_JOB_WITH_BUDGET' }, 1, 'BUDGET_EXHAUSTED')!
  assert.match(budget.suggestion, /新建预算足够/)
  assert.match(budget.suggestion, /不会增加可调用次数/)
  const unknown = diagnosticView({ schema_version: 1, attempt: 1, error_code: 'UNKNOWN_OUTCOME', category: 'PROVIDER',
    reason_code: 'PROVIDER_OUTCOME_UNKNOWN', suggestion_code: 'RECONCILE_MODEL_CALL' }, 1, 'UNKNOWN_OUTCOME')!
  assert.match(unknown.suggestion, /先恢复或核对/)
  assert.match(unknown.suggestion, /避免重复提交/)
  assert.equal(parseDiagnostic({ ...diagnostic(), error_code: 'BUDGET_EXHAUSTED' }, 2, 'BUDGET_EXHAUSTED'), undefined)
})

test('OCR capacity, integrity and contention each have a distinct prerequisite', () => {
  for (const [code, suggestion, expected] of [
    ['OCR_CACHE_BUSY', 'WAIT_FOR_CACHE_OWNER', /等待后刷新/],
    ['OCR_CACHE_CAPACITY_EXCEEDED', 'CHECK_CACHE_CAPACITY', /先检查.*空间/],
    ['OCR_CACHE_CORRUPT', 'INSPECT_CACHE_INTEGRITY', /先排查.*损坏或冲突/],
  ] as const) {
    const view = diagnosticView({ schema_version: 1, attempt: 1, error_code: code, category: 'OCR_CACHE',
      reason_code: code, suggestion_code: suggestion }, 1, code)!
    assert.match(view.suggestion, expected)
  }
})

test('existing transport and ASR failure codes use the final server recovery policies', () => {
  for (const [codes, reason, suggestion, expected] of [
    [['CALL_STATE_UNCERTAIN', 'MODEL_RESPONSE_ALREADY_EXISTS', 'MODEL_DISPATCH_NOT_GRANTED'], 'PROVIDER_OUTCOME_UNKNOWN', 'RECONCILE_MODEL_CALL', /先恢复或核对/],
    [['INVALID_CONFIGURATION'], 'PROVIDER_CONFIGURATION_INVALID', 'CHECK_PROVIDER_CONFIGURATION', /凭证、权限和模型配置/],
    [['TASK_FAILED', 'SUBTASK_FAILED', 'INVALID_RESPONSE', 'INVALID_RESULT'], 'PROVIDER_REQUEST_FAILED', 'CHECK_PROVIDER_AND_CALL_STATE', /核对已有调用状态/],
  ] as const) {
    for (const code of codes) {
      const view = diagnosticView({ schema_version: 1, attempt: 1, error_code: code, category: 'PROVIDER',
        reason_code: reason, suggestion_code: suggestion }, 1, code)!
      assert.ok(view, code)
      assert.match(view.suggestion, expected)
      const wrongPolicy = { schema_version: 1, attempt: 1, error_code: code, category: 'MODEL_OUTPUT',
        reason_code: 'MODEL_OUTPUT_INVALID', suggestion_code: 'CHECK_FORMAT_THEN_RETRY' }
      assert.equal(parseDiagnostic(wrongPolicy, 1, code), undefined)
    }
  }
})

test('diagnostic bindings follow the 64-character server limit while legacy error codes remain readable', () => {
  const code = 'E'.repeat(65)
  assert.equal(safeErrorCode(code), code)
  assert.equal(parseDiagnostic({ schema_version: 1, attempt: 1, error_code: code, category: 'EXECUTION',
    reason_code: 'UNCLASSIFIED_FAILURE', suggestion_code: 'CHECK_EXECUTION_ENVIRONMENT' }, 1, code), undefined)
  const maximum = 'E'.repeat(64)
  assert.ok(parseDiagnostic({ schema_version: 1, attempt: 1, error_code: maximum, category: 'EXECUTION',
    reason_code: 'UNCLASSIFIED_FAILURE', suggestion_code: 'CHECK_EXECUTION_ENVIRONMENT' }, 1, maximum))
})
