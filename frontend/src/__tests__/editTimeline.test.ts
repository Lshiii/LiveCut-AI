import assert from 'node:assert/strict'
import test from 'node:test'
import { editJoins } from '../editTimeline.ts'

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
