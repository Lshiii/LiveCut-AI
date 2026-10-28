import test from 'node:test'
import assert from 'node:assert/strict'
import { DEFAULT_OCR_MAX_FRAMES, newAnalysisConfig, samplingLabel } from '../sampling.ts'

test('new and legacy strategies are described without changing the frozen config', () => {
  const legacy = { visual_provider: 'ocr', max_text_calls: 0 }
  assert.match(samplingLabel(legacy), /前段.*旧策略/)
  assert.ok(!Object.hasOwn(legacy, 'ocr_sampling'))
  assert.match(samplingLabel({ ...legacy, ocr_sampling: 'uniform' }), /整段视频.*16/)
  assert.match(samplingLabel({ ...legacy, ocr_sampling: 'prefix' }), /旧策略/)
  assert.ok(!Object.hasOwn(legacy, 'ocr_max_frames'))
  assert.equal(samplingLabel({ visual_provider: 'none', ocr_sampling: 'uniform' }), '仅使用语音转写')
})

test('persisted budgets are displayed independently of current new-task defaults', () => {
  for (const budget of [1, 16, 48, 120]) {
    const frozen = Object.freeze({ visual_provider: 'ocr', ocr_sampling: 'uniform', ocr_max_frames: budget })
    assert.equal(samplingLabel(frozen), `整段视频均匀取样 · 最多 ${budget} 帧`)
    assert.equal(frozen.ocr_max_frames, budget)
    assert.match(samplingLabel({ ...frozen, ocr_sampling: 'prefix' }), new RegExp(`最多 ${budget} 帧.*旧策略`))
  }
  for (const budget of [null, false, true, 0, -1, 1.5, 121, NaN, Infinity, '120', '<script>private</script>', [], {}]) {
    assert.equal(samplingLabel({ visual_provider: 'ocr', ocr_sampling: 'uniform', ocr_max_frames: budget }), '画面取样上限未知')
  }
  assert.equal(samplingLabel({ visual_provider: 'none', ocr_max_frames: 'untrusted' }), '仅使用语音转写')
})

test('new analysis payload freezes an integer frame cap only for local OCR', () => {
  assert.equal(DEFAULT_OCR_MAX_FRAMES, 120)
  assert.deepEqual(newAnalysisConfig('ocr', 100), {
    visual_provider: 'ocr', max_text_calls: 100, ocr_sampling: 'uniform', ocr_max_frames: 120,
  })
  for (const budget of [1, 16, 48, 120]) {
    assert.deepEqual(newAnalysisConfig('ocr', 30, budget), {
      visual_provider: 'ocr', max_text_calls: 30, ocr_sampling: 'uniform', ocr_max_frames: budget,
    })
  }
  for (const budget of [undefined, null, '', 0, -1, 1.25, 121, true, false, NaN, Infinity, '120', [], {}]) {
    if (budget === undefined) continue // omitted input intentionally selects the new-task default
    assert.throws(() => newAnalysisConfig('ocr', 30, budget), { name: 'RangeError', message: '画面取样上限须为 1～120 的整数。' })
  }
  for (const unusedBudget of [0, 'invalid', 120]) {
    const speechOnly = newAnalysisConfig('none', 12, unusedBudget)
    assert.deepEqual(speechOnly, { visual_provider: 'none', max_text_calls: 12, ocr_sampling: 'uniform' })
    assert.ok(!Object.hasOwn(speechOnly, 'ocr_max_frames'))
  }
})

test('unrecognized configuration is not echoed or described as an executed strategy', () => {
  for (const value of [null, undefined, [], 'untrusted raw data']) assert.equal(samplingLabel(value), '画面取证配置未提供')
  for (const value of [null, true, 1, '<script>private</script>', 'future-policy']) {
    assert.equal(samplingLabel({ visual_provider: 'ocr', ocr_sampling: value }), '画面取证策略未知')
  }
  assert.equal(samplingLabel({ visual_provider: 'future-provider' }), '画面取证策略未知')
})
