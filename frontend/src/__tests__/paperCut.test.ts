import test from 'node:test'
import assert from 'node:assert/strict'
import { compilePaperCut, editPaperCut, newPaperCutHistory, preparePaperCut, stepPaperCut } from '../paperCut.ts'
import type { IntentOutput, IntentPart, IntentUtterance } from '../intentClips.ts'

function fixture(count = 8) {
  const utterances: IntentUtterance[] = Array.from({ length: count }, (_, index) => ({
    utterance_id: `u${index}`, start_ms: index * 1000, end_ms: index * 1000 + 700, text: `原句 ${index}`,
  }))
  function part(start: number, end: number): IntentPart {
    return { start_utterance_id: utterances[start]!.utterance_id, end_utterance_id: utterances[end]!.utterance_id,
      start_ms: utterances[start]!.start_ms, end_ms: utterances[end]!.end_ms }
  }
  const output: IntentOutput = { output_id: 'output', goal_id: 'goal', title: '候选', status: 'FOUND', enabled: false,
    reason: '原句见证据', parts: [part(0, 4), part(6, 7)], evidence_utterance_ids: ['u0', 'u2', 'u4', 'u7'],
    excluded: [{ utterance_id: 'u5', reason: '引导关注' }] }
  return { utterances, output, part, duration: count * 1000 }
}

test('middle ripple cut yields exact source intervals, including only internal pauses in output time', () => {
  const { utterances, output, duration, part } = fixture()
  const source = preparePaperCut(output, utterances, duration, ['u4'])
  const result = compilePaperCut(source, new Set(['u1', 'u2']))
  assert.deepEqual(result.parts, [part(0, 0), part(3, 4), part(6, 7)])
  assert.equal(result.durationMs, 4100)
  assert.equal(source.originalDurationMs, 6400)
  assert.deepEqual(result.evidenceIds, ['u0', 'u4', 'u7'])
  assert.deepEqual(output.evidence_utterance_ids, ['u0', 'u2', 'u4', 'u7'])
  assert.equal(output.enabled, false)
})
test('restore and undo do not bridge an original part boundary even for adjacent source sentences', () => {
  const { utterances, output, duration, part } = fixture()
  output.parts = [part(0, 2), part(3, 4), part(6, 7)]
  const source = preparePaperCut(output, utterances, duration)
  const start = newPaperCutHistory()
  const removed = editPaperCut(source, start, new Set(['u2', 'u3']), true)
  const undo = stepPaperCut(removed, 'undo')
  const redo = stepPaperCut(undo, 'redo')
  assert.deepEqual(compilePaperCut(source, undo.removed).parts, output.parts)
  assert.deepEqual(compilePaperCut(source, redo.removed).parts, [part(0, 1), part(4, 4), part(6, 7)])
  const restore = editPaperCut(source, redo, new Set(['u3']), false)
  assert.deepEqual(compilePaperCut(source, restore.removed).parts, [part(0, 1), part(3, 4), part(6, 7)])
  assert.deepEqual(stepPaperCut(restore, 'undo').removed, redo.removed)
  const next = editPaperCut(source, undo, new Set(['u0']), true)
  assert.equal(next.future.length, 0)
  assert.equal(start.removed.size, 0)
})
test('anchors and original evidence are protected across an atomic batch, excluded rows never enter restoration', () => {
  const { utterances, output, duration } = fixture()
  const source = preparePaperCut(output, utterances, duration, ['u6'])
  assert.ok(!source.rows.some(row => row.utterance.utterance_id === 'u5'))
  const start = newPaperCutHistory()
  assert.throws(() => editPaperCut(source, start, new Set(['u1', 'u6']), true), /锚|引用/)
  assert.throws(() => editPaperCut(source, start, new Set(output.evidence_utterance_ids), true), /依据/)
  assert.throws(() => editPaperCut(source, start, new Set(['u5']), true), /候选/)
  assert.equal(start.removed.size, 0)
  assert.equal(start.past.length, 0)
})
test('rejects unreviewed, contradictory and invalid existing drafts before opening', () => {
  const { utterances, output, duration } = fixture()
  assert.throws(() => preparePaperCut({ ...output, status: 'NEEDS_REVIEW' }, utterances, duration))
  assert.throws(() => preparePaperCut(output, utterances, duration, ['u5']))
  assert.throws(() => preparePaperCut({ ...output, excluded: [{ utterance_id: 'u0', reason: '冲突' }] }, utterances, duration))
  assert.throws(() => preparePaperCut({ ...output, parts: [{ ...output.parts[0]!, end_ms: 12 }] }, utterances, duration))
})
test('twelve parts allowed, thirteenth rejected without altering the current history', () => {
  const { utterances, output, duration, part } = fixture(25)
  const source = preparePaperCut({ ...output, parts: [part(0, 24)], excluded: [] }, utterances, duration)
  const start = newPaperCutHistory()
  const ids = Array.from({ length: 11 }, (_, i) => `u${i * 2 + 1}`)
  const twelve = editPaperCut(source, start, new Set(ids), true)
  assert.equal(compilePaperCut(source, twelve.removed).parts.length, 12)
  assert.throws(() => editPaperCut(source, twelve, new Set(['u23']), true), /12/)
  assert.equal(twelve.past.length, 1)
})
