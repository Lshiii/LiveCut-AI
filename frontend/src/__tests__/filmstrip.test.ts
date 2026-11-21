import assert from 'node:assert/strict'
import test from 'node:test'
import { filmstripTimes } from '../filmstrip.ts'

const parts = [{ start_ms: 125, end_ms: 1125 }, { start_ms: 4301, end_ms: 5301 }]

test('filmstrip cells sample retained media on the edit clock, including zoom across a cut', () => {
  assert.deepEqual(filmstripTimes(parts, 0, 2000, 4), [125, 500, 4500, 5000])
  assert.deepEqual(filmstripTimes(parts, 999, 1001, 2), [1000, 4301])
  assert.deepEqual(filmstripTimes([{ start_ms: 131, end_ms: 132 }], 0, 1, 3), [131, 131, 131])
})
test('a missing or invalid viewport never schedules out-of-range or unbounded image reads', () => {
  for (const [from, to, count] of [[0, 2001, 8], [-1, 2000, 4], [30, 30, 4], [0, 2000, 13], [0, 2000, 2.5]]) {
    assert.deepEqual(filmstripTimes(parts, from!, to!, count!), [])
  }
  assert.deepEqual(filmstripTimes([], 0, 2000, 6), [])
})
