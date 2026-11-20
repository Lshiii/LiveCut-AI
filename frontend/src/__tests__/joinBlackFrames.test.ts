import assert from 'node:assert/strict'
import test from 'node:test'
import { editJoins } from '../editTimeline.ts'
import { blackFrame, findBlackRuns, joinSamples } from '../joinBlackFrames.ts'

const join = editJoins([{ start_ms: 1200, end_ms: 2000 }, { start_ms: 9000, end_ms: 11000 }])[0]!

test('black-frame scan samples only retained media, including both sides of a short flash', () => {
  const samples = joinSamples(join)
  const left = samples.filter(s => s.side === 'before'), right = samples.filter(s => s.side === 'after')
  assert.deepEqual(left[0], { side: 'before', sourceMs: 1600, editMs: 400 })
  assert.deepEqual(left.at(-1), { side: 'before', sourceMs: 1999, editMs: 799 })
  assert.deepEqual(right[0], { side: 'after', sourceMs: 9000, editMs: 800 })
  assert.deepEqual(right.at(-1), { side: 'after', sourceMs: 9399, editMs: 1199 })
  assert(samples.every((s, i) => i === 0 || (s.editMs - samples[i - 1]!.editMs > 0 && s.editMs - samples[i - 1]!.editMs <= 25)))
  assert(samples.length <= 34)
})

test('tiny source ranges keep half-open boundaries and a unique click target on each side', () => {
  const tiny = editJoins([{ start_ms: 0, end_ms: 1 }, { start_ms: 1, end_ms: 3 }])[0]!
  assert.deepEqual(joinSamples(tiny).map(s => [s.side, s.editMs, s.sourceMs]),
    [['before', 0, 0], ['after', 1, 1], ['after', 2, 2]])
})

test('only near-black decoded images match, including a few bright pixels but not letterboxing', () => {
  const pixels = new Uint8ClampedArray(400)
  for (let i = 0; i < pixels.length; i += 4) pixels[i + 3] = 255
  pixels[0] = 220
  assert.equal(blackFrame(pixels), true)
  for (let i = 100; i < 300; i += 4) pixels[i + 2] = 255
  assert.equal(blackFrame(pixels), false)
  assert.throws(() => blackFrame(new Uint8ClampedArray(4)), /完整/)
  assert.throws(() => blackFrame(new Uint8ClampedArray()), /可读取/)
})

test('adjacent dark samples group within each side and clicking maps to the corresponding source frame', async () => {
  const marks = await findBlackRuns(join, async ms =>
    ms >= 1950 && ms <= 9025 || ms === 9200, new AbortController().signal)
  assert.deepEqual(marks, [
    { side: 'before', editMs: 750, sourceMs: 1950 },
    { side: 'after', editMs: 800, sourceMs: 9000 },
    { side: 'after', editMs: 1000, sourceMs: 9200 },
  ])
})

test('aborted and incomplete scans cannot publish partial clean or dark results', async () => {
  const controller = new AbortController()
  let calls = 0
  await assert.rejects(findBlackRuns(join, async () => {
    calls++
    controller.abort()
    return true
  }, controller.signal), { name: 'AbortError' })
  assert.equal(calls, 1)
  await assert.rejects(findBlackRuns(join, async ms => {
    if (ms > 1600) throw new Error('read failure')
    return true
  }, new AbortController().signal), /read failure/)
})
