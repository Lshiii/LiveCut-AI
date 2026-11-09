import test from 'node:test'
import assert from 'node:assert/strict'
import { ref, watch } from 'vue'
import { advanceTextProgress, parseTextProgress, textProgressCopy, textProgressSignature, textRecoveryFailureCopy, type TextProgress } from '../textProgress.ts'
import { parseAnalysisSnapshot } from '../snapshotStream.ts'
import { parseAgentTurn, type AgentSession } from '../videoAgent.ts'

const progress = (): TextProgress => ({ state: 'REQUESTING', attempt_number: 1, max_attempts: 3,
  provider: 'primary', model_id: 'deepseek-flash', timeout_seconds: 90, elapsed_seconds: 8, retry_in_seconds: 0 })
test('active requests count real local time and retry waits count down without changing source events', () => {
  const current = progress()
  assert.equal(advanceTextProgress(current, 1000, 6899).elapsed_seconds, 13)
  assert.equal(current.elapsed_seconds, 8)
  assert.equal(advanceTextProgress(current, 1000, 500).elapsed_seconds, 8)
  const waiting: TextProgress = { ...current, state: 'RETRY_WAIT', retry_in_seconds: 4 }
  assert.equal(advanceTextProgress(waiting, 1000, 3500).retry_in_seconds, 2)
  assert.equal(advanceTextProgress(waiting, 1000, 11000).retry_in_seconds, 0)
  assert.equal(advanceTextProgress(waiting, 1000, 3500).elapsed_seconds, 8)
  for (const state of ['SUCCEEDED', 'EXHAUSTED', 'SWITCHING_PROVIDER'] as const) {
    const stopped = { ...current, state }
    assert.deepEqual(advanceTextProgress(stopped, 1000, 61000), stopped)
  }
})
test('identical polling clones keep their clock anchor while a new state or count resets it', () => {
  const current = ref<TextProgress | undefined>(progress())
  let resets = 0
  const stop = watch(() => textProgressSignature(current.value), () => { resets++ }, { flush: 'sync', immediate: true })
  current.value = { ...progress() }
  current.value = { ...progress() }
  assert.equal(resets, 1)
  current.value = { ...progress(), elapsed_seconds: 14 }
  assert.equal(resets, 2)
  current.value = { ...progress(), state: 'RETRY_WAIT', attempt_number: 2, retry_in_seconds: 2 }
  assert.equal(resets, 3)
  current.value = undefined
  assert.equal(resets, 4)
  stop()
  current.value = progress()
  assert.equal(resets, 4)
})
test('recovery is optional for old snapshots and only bounded progress becomes visible', () => {
  for (const value of [undefined, null, [], 'REQUESTING', {}]) assert.equal(parseTextProgress(value), undefined)
  assert.deepEqual(parseTextProgress(progress()), progress())
  for (const patch of [{ attempt_number: 4 }, { attempt_number: 2, max_attempts: 1 }, { timeout_seconds: 0 },
    { timeout_seconds: 301 }, { elapsed_seconds: 1801 }, { retry_in_seconds: 61 }, { state: 'ARBITRARY' },
    { provider: 'https://private.example/?api_key=secret' }, { model_id: 'deepseek\nAuthorization: secret' }, { error_code: '<script>secret</script>' }]) {
    assert.equal(parseTextProgress({ ...progress(), ...patch }), undefined)
  }
})
test('unknown metadata is discarded instead of reaching the UI', () => {
  const sanitized = parseTextProgress({ ...progress(), api_key: 'private-key', endpoint: 'https://private.example', error_code: null })
  assert.deepEqual(sanitized, progress())
  assert.equal(JSON.stringify(sanitized).includes('private'), false)
})
test('bounded recovery failures stay readable when the optional diagnostic is absent', () => {
  for (const code of ['TEXT_RETRIES_EXHAUSTED', 'TEXT_RETRY_DEADLINE_EXCEEDED', 'INVALID_TEXT_RETRY_CONFIGURATION', 'TEXT_RETRY_POLICY_CONFLICT', 'TEXT_FALLBACK_NOT_ALLOWED']) {
    assert.match(textRecoveryFailureCopy(code)!.detail, /语音转写会保留/)
  }
  assert.equal(textRecoveryFailureCopy('https://secret'), undefined)
  assert.equal(textRecoveryFailureCopy('UNKNOWN_FUTURE_FAILURE'), undefined)
})

test('failed visual turns show exhausted provider quota from progress even when the stage code is generic', () => {
  const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
  const session: AgentSession = { id: id(1), job_id: id(2), video_id: id(3), created_at: '2026-11-09T00:00:00Z',
    source: { video_sha256: 'a'.repeat(64), transcript_hash: 'b'.repeat(64), duration_ms: 5000 }, turns: [] }
  const raw = { id: id(4), session_id: session.id, job_id: session.job_id, sequence: 1,
    question: '合成额度失败回归，不发真实请求', visual_mode: 'frames',
    status: 'FAILED', state_version: 3, created_at: '2026-11-09T00:00:01Z', updated_at: '2026-11-09T00:00:02Z',
    budget: { max_text_calls: 1, text_calls_reserved: 1 }, result: null,
    stage: { id: id(5), stage_type: 'AGENT_VISUAL', status: 'FAILED', attempt: 1, error_code: 'PROVIDER_ERROR',
      text_progress: { ...progress(), state: 'EXHAUSTED', max_attempts: 1, elapsed_seconds: 2,
        error_code: 'PROVIDER_QUOTA_EXHAUSTED', message: 'private raw provider error', endpoint: 'https://private.example' } } }
  const turn = parseAgentTurn(raw, session)
  assert.equal(turn.stage.error_code, 'PROVIDER_ERROR')
  assert.equal(turn.stage.diagnostic, undefined)
  const copy = textProgressCopy(turn.stage.text_progress!)
  assert.equal(copy.title, '模型服务额度不足')
  assert.match(copy.detail, /免费额度可能已用完/)
  assert.match(copy.detail, /核对额度与计费限制/)
  assert.match(copy.detail, /第 1 \/ 1 次尝试/)
  assert.doesNotMatch(JSON.stringify(copy), /private|https:|凭证错误|自动开启付费/)
  assert.equal(turn.status, 'FAILED'); assert.equal(turn.result, null)
})

test('quota copy requires the exact provider code and cannot mislabel local budgets, authentication or rate limiting', () => {
  assert.match(textRecoveryFailureCopy('PROVIDER_QUOTA_EXHAUSTED')!.detail, /模型平台/)
  for (const code of ['PROVIDER_UNAUTHORIZED', 'PROVIDER_RATE_LIMITED', 'BUDGET_EXHAUSTED', 'PROVIDER_ERROR',
    'AllocationQuota.FreeTierOnly', 'PROVIDER_QUOTA_EXHAUSTED<script>', 'UNKNOWN_FUTURE_FAILURE']) {
    assert.equal(textRecoveryFailureCopy(code), undefined)
    assert.notEqual(textProgressCopy({ ...progress(), state: 'EXHAUSTED', error_code: code }).title, '模型服务额度不足')
  }
  assert.notEqual(textProgressCopy({ ...progress(), error_code: 'PROVIDER_QUOTA_EXHAUSTED' }).title, '模型服务额度不足', 'an active event is not a terminal quota failure')
})
test('progress copy distinguishes retry, switching service and exhaustion using actual bounds', () => {
  assert.match(textProgressCopy(progress()).detail, /单次上限 90 秒/)
  const waiting = textProgressCopy({ ...progress(), state: 'RETRY_WAIT', attempt_number: 2, retry_in_seconds: 4 })
  assert.match(waiting.title, /自动恢复/); assert.match(waiting.detail, /4 秒后重试/)
  assert.match(waiting.detail, /第 2 \/ 3 次尝试/)
  assert.match(textProgressCopy({ ...progress(), state: 'SWITCHING_PROVIDER' }).title, /切换备用/)
  assert.match(textProgressCopy({ ...progress(), state: 'SUCCEEDED' }).title, /已完成/)
  const exhausted = textProgressCopy({ ...progress(), state: 'EXHAUSTED', attempt_number: 3, elapsed_seconds: 240 })
  assert.match(exhausted.detail, /240 秒/); assert.match(exhausted.title, /已结束/)
  assert.match(exhausted.detail, /已完成的语音转写会保留/)
})
test('malformed progress cannot destroy a valid analysis snapshot or contaminate other stages', () => {
  const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
  const value = { id: id(1), video_id: id(2), generation: 1, status: 'RUNNING', state_version: 4, created_at: '2026-11-05T00:00:00Z',
    stages: ['MEDIA', 'ASR', 'SEGMENT', 'TIMELINE'].map((stage_type, i) => ({ id: id(3 + i), stage_type, status: 'RUNNING', attempt: 1, error_code: null,
      text_progress: i === 2 ? { ...progress(), provider: 'https://secret' } : progress() })),
    budget: { max_text_calls: 12, text_calls_reserved: 2, max_asr_submissions: 1, asr_calls_reserved: 1 },
    actions: { can_cancel: true, can_retry: false, retry_block_reason: null, max_attempts: 3 } }
  const invalidProgress = parseAnalysisSnapshot(value, id(1))
  assert.equal(invalidProgress.status, 'RUNNING')
  assert.equal(invalidProgress.stages.every(stage => stage.text_progress === undefined), true)
  value.stages[2]!.text_progress = progress()
  assert.deepEqual(parseAnalysisSnapshot(value, id(1)).stages[2]!.text_progress, progress())
})
