import assert from 'node:assert/strict'
import test from 'node:test'
import { editJoins, editSpans, editToSource, sourceToEdit } from '../editTimeline.ts'

test('cut review maps each edit position to both source edges without auditioning the deleted passage', () => {
  const parts = [{ start_ms: 8572, end_ms: 31315 }, { start_ms: 178900, end_ms: 188442 }, { start_ms: 200000, end_ms: 200420 }]
  assert.deepEqual(editJoins(parts), [
    { at_ms: 22743, from_ms: 31315, to_ms: 178900, audition: [{ start_ms: 29815, end_ms: 31315 }, { start_ms: 178900, end_ms: 180400 }] },
    { at_ms: 32285, from_ms: 188442, to_ms: 200000, audition: [{ start_ms: 186942, end_ms: 188442 }, { start_ms: 200000, end_ms: 200420 }] },
  ])
})

test('a join between tiny touching ranges clips both handles to retained media', () => {
  const parts = [{ start_ms: 10, end_ms: 12 }, { start_ms: 12, end_ms: 13 }]
  assert.deepEqual(editJoins(parts)[0], { at_ms: 2, from_ms: 12, to_ms: 12, audition: parts })
  assert.deepEqual(editJoins(parts.slice(0, 1)), [])
  assert.deepEqual(editJoins([]), [])
})

test('the stitched cursor skips source gaps, enters the later part at a join, and seeks playable media at the endpoint', () => {
  const parts = [{ start_ms: 10, end_ms: 12 }, { start_ms: 19, end_ms: 20 }, { start_ms: 20, end_ms: 24 }]
  for (const [edit, expectedSource, expectedPart] of [[0,10,0],[1,11,0],[2,19,1],[3,20,2],[6,23,2]] as const) {
    assert.deepEqual(editToSource(parts, edit), { sourceMs: expectedSource, partIndex: expectedPart })
    assert.equal(sourceToEdit(parts, expectedSource), edit)
  }
  assert.deepEqual(editToSource(parts, 7), { sourceMs: 23, partIndex: 2 })
  assert.equal(sourceToEdit(parts, 24), 7)
  for (const ms of [9,12,18,25,NaN,11.5]) assert.equal(sourceToEdit(parts, ms), null)
  for (const ms of [-1,8,.1,Infinity]) assert.equal(editToSource(parts, ms), null)
})

test('bounds must be safe ordered millisecond ranges, including a one-millisecond single part', () => {
  const last = Number.MAX_SAFE_INTEGER
  const one = [{ start_ms: last - 1, end_ms: last }]
  assert.deepEqual(editToSource(one, 0), { sourceMs: last - 1, partIndex: 0 })
  assert.deepEqual(editToSource(one, 1), editToSource(one, 0))
  for (const parts of [[], [{start_ms:-1,end_ms:1}], [{start_ms:1,end_ms:1}], [{start_ms:0,end_ms:1.5}],
    [{start_ms:0,end_ms:last+1}], [{start_ms:0,end_ms:3},{start_ms:2,end_ms:5}]]) {
    assert.deepEqual(editSpans(parts), [])
    assert.equal(sourceToEdit(parts, 0), null)
    assert.equal(editToSource(parts, 0), null)
  }
})
