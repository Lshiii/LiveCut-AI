import test from 'node:test'
import assert from 'node:assert/strict'
import { samplingLabel } from '../sampling.ts'

test('new and legacy strategies are described without changing the frozen config', () => {
  const legacy = { visual_provider: 'ocr', max_text_calls: 0 }
  assert.match(samplingLabel(legacy), /前段.*旧策略/)
  assert.ok(!Object.hasOwn(legacy, 'ocr_sampling'))
  assert.match(samplingLabel({ ...legacy, ocr_sampling: 'uniform' }), /整段视频.*16/)
  assert.match(samplingLabel({ ...legacy, ocr_sampling: 'prefix' }), /旧策略/)
  assert.equal(samplingLabel({ visual_provider: 'none', ocr_sampling: 'uniform' }), '仅使用语音转写')
})

test('unrecognized configuration is not echoed or described as an executed strategy', () => {
  for (const value of [null, undefined, [], 'untrusted raw data']) assert.equal(samplingLabel(value), '画面取证配置未提供')
  for (const value of [null, true, 1, '<script>private</script>', 'future-policy']) {
    assert.equal(samplingLabel({ visual_provider: 'ocr', ocr_sampling: value }), '画面取证策略未知')
  }
  assert.equal(samplingLabel({ visual_provider: 'future-provider' }), '画面取证策略未知')
})
