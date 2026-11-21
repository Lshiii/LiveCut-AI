import assert from 'node:assert/strict'
import test from 'node:test'
import { filmstripCellCount, filmstripTimes, thumbnailSize } from '../filmstrip.ts'

const parts = [{ start_ms: 125, end_ms: 1125 }, { start_ms: 4301, end_ms: 5301 }]

test('portrait thumbnails keep their original aspect and tile the actual track height', () => {
  const aspect = 864 / 1920
  assert.deepEqual(thumbnailSize(864, 1920), { width: 108, height: 240 })
  assert.deepEqual(thumbnailSize(1920, 1080), { width: 240, height: 135 })
  assert.deepEqual(thumbnailSize(16, 9), { width: 16, height: 9 })
  assert.equal(filmstripCellCount(1840, 90, aspect), 45)
  assert.equal(filmstripCellCount(1840, 90, 16 / 9), 12)
  assert.equal(filmstripCellCount(340, 64, aspect), 11)
  assert.equal(filmstripCellCount(3840, 48, aspect), 48)
  assert.equal(filmstripCellCount(0, 90, aspect), 0)
  assert.equal(filmstripCellCount(1200, 90, NaN), 0)
})

test('filmstrip cells sample retained media on the edit clock, including zoom across a cut', () => {
  assert.deepEqual(filmstripTimes(parts, 0, 2000, 4), [125, 500, 4500, 5000])
  assert.deepEqual(filmstripTimes(parts, 999, 1001, 2), [1000, 4301])
  assert.deepEqual(filmstripTimes([{ start_ms: 131, end_ms: 132 }], 0, 1, 3), [131, 131, 131])
})
test('a missing or invalid viewport never schedules out-of-range or unbounded image reads', () => {
  for (const [from, to, count] of [[0, 2001, 8], [-1, 2000, 4], [30, 30, 4], [0, 2000, 49], [0, 2000, 2.5]]) {
    assert.deepEqual(filmstripTimes(parts, from!, to!, count!), [])
  }
  assert.deepEqual(filmstripTimes([], 0, 2000, 6), [])
})
