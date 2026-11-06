import test from 'node:test'
import assert from 'node:assert/strict'
import { intentOutputValidation, type IntentOutput, type IntentUtterance } from '../intentClips.ts'
import { intentPreviewParts } from '../intentPreviewParts.ts'

function fixture() {
  const ids = ['u10', 'u2', 'u1', 'u30', 'u3', 'u20']
  const times = [[0, 900], [1000, 1900], [2000, 2900], [3100, 3900], [4000, 4900], [5000, 6000]]
  const utterances: IntentUtterance[] = ids.map((utterance_id, index) => ({ utterance_id,
    start_ms: times[index]![0]!, end_ms: times[index]![1]!, text: `合成原句 ${index}` }))
  const output: IntentOutput = { output_id: 'preview-output', goal_id: 'goal', title: '合成片段',
    status: 'FOUND', reason: '仅供回看边界测试', enabled: false,
    parts: [
      { start_utterance_id: 'u10', end_utterance_id: 'u2', start_ms: 0, end_ms: 1900 },
      { start_utterance_id: 'u30', end_utterance_id: 'u20', start_ms: 3100, end_ms: 6000 },
    ], excluded: [{ utterance_id: 'u1', reason: '不在保留区间' }], evidence_utterance_ids: [] }
  return { output, utterances, duration: 6500 }
}

test('whole-target preview retains source order and exact disjoint intervals without sorting IDs or merging gaps', () => {
  const { output, utterances, duration } = fixture()
  assert.deepEqual(intentPreviewParts(output, utterances, duration), [
    { start_ms: 0, end_ms: 1900 }, { start_ms: 3100, end_ms: 6000 },
  ])
  assert.deepEqual(intentPreviewParts(output, utterances, duration, 1), [{ start_ms: 3100, end_ms: 6000 }])
})

test('missing semantic evidence does not prevent playback or get silently repaired for export', () => {
  const { output, utterances, duration } = fixture()
  assert.notEqual(intentOutputValidation([output], utterances, duration), '')
  assert.ok(intentPreviewParts(output, utterances, duration))
  assert.deepEqual(output.evidence_utterance_ids, [])
  assert.notEqual(intentOutputValidation([output], utterances, duration), '')
})

test('NEEDS_REVIEW playback does not promote status/enabled or grant business validation', () => {
  const { output, utterances, duration } = fixture()
  output.status = 'NEEDS_REVIEW'; output.enabled = true
  const before = structuredClone(output)
  assert.ok(intentPreviewParts(output, utterances, duration))
  assert.notEqual(intentOutputValidation([output], utterances, duration), '')
  assert.deepEqual(output, before)
  output.enabled = false
  assert.ok(intentPreviewParts(output, utterances, duration))
  assert.equal(output.enabled, false)
})

test('NOT_FOUND, unknown status and empty parts never produce playback ranges', () => {
  for (const status of ['NOT_FOUND', 'UNKNOWN', '', null]) {
    const { output, utterances, duration } = fixture()
    output.status = status as IntentOutput['status']
    assert.equal(intentPreviewParts(output, utterances, duration), null)
    assert.equal(intentPreviewParts(output, utterances, duration, 0), null)
  }
  const { output, utterances, duration } = fixture()
  output.parts = []
  assert.equal(intentPreviewParts(output, utterances, duration), null)
  assert.equal(intentPreviewParts(output, utterances, duration, 0), null)
})

test('single-part playback tolerates another unfinished boundary or unrelated part order', () => {
  const { output, utterances, duration } = fixture()
  output.parts[1]!.end_ms = NaN
  output.parts[1]!.end_utterance_id = 'draft-unknown'
  assert.deepEqual(intentPreviewParts(output, utterances, duration, 0), [{ start_ms: 0, end_ms: 1900 }])
  assert.equal(intentPreviewParts(output, utterances, duration), null)
  assert.equal(intentPreviewParts(output, utterances, duration, 1), null)
  const valid = fixture().output
  valid.parts.reverse()
  assert.equal(intentPreviewParts(valid, utterances, duration), null)
  assert.deepEqual(intentPreviewParts(valid, utterances, duration, 0), [{ start_ms: 3100, end_ms: 6000 }])
})

test('excluded interior sentences are never played, even when absent from semantic evidence', () => {
  const { output, utterances, duration } = fixture()
  output.excluded.push({ utterance_id: 'u3', reason: '' })
  assert.equal(intentPreviewParts(output, utterances, duration), null)
  assert.equal(intentPreviewParts(output, utterances, duration, 1), null)
  assert.deepEqual(intentPreviewParts(output, utterances, duration, 0), [{ start_ms: 0, end_ms: 1900 }])
  output.excluded = [{ utterance_id: 'u10', reason: '' }]
  assert.equal(intentPreviewParts(output, utterances, duration, 0), null)
  assert.deepEqual(intentPreviewParts(output, utterances, duration, 1), [{ start_ms: 3100, end_ms: 6000 }])
})

test('exclusion reason text is not a preview permission but unknown/malformed exclusion IDs fail closed', () => {
  const { output, utterances, duration } = fixture()
  output.excluded[0]!.reason = ''
  assert.ok(intentPreviewParts(output, utterances, duration))
  output.excluded[0]!.utterance_id = 'unknown'
  assert.equal(intentPreviewParts(output, utterances, duration), null)
  output.excluded = [null as unknown as IntentOutput['excluded'][number]]
  assert.equal(intentPreviewParts(output, utterances, duration), null)
})

test('reversed, overlapping and repeated full-target parts are rejected without reordering or mutation', () => {
  for (const change of [
    (output: IntentOutput) => output.parts.reverse(),
    (output: IntentOutput) => { output.parts[1] = structuredClone(output.parts[0]!) },
    (output: IntentOutput) => { output.parts[1] = { start_utterance_id: 'u2', end_utterance_id: 'u20', start_ms: 1000, end_ms: 6000 } },
    (output: IntentOutput) => { output.parts[0] = { start_utterance_id: 'u30', end_utterance_id: 'u2', start_ms: 3100, end_ms: 1900 } },
  ]) {
    const { output, utterances, duration } = fixture(); change(output)
    const before = structuredClone(output)
    assert.equal(intentPreviewParts(output, utterances, duration), null)
    assert.deepEqual(output, before)
  }
})

test('part times must exactly match known original sentence boundaries and remain safe integers', () => {
  for (const update of [
    { start_utterance_id: 'missing' }, { end_utterance_id: 'missing' },
    { start_ms: 1 }, { end_ms: 1899 }, { start_ms: -1 }, { end_ms: 0 },
    { start_ms: 0.5 }, { end_ms: NaN }, { start_ms: Infinity },
    { end_ms: Number.MAX_SAFE_INTEGER + 1 }, { end_ms: 6501 },
  ]) {
    const { output, utterances, duration } = fixture()
    Object.assign(output.parts[0]!, update)
    assert.equal(intentPreviewParts(output, utterances, duration, 0), null)
    assert.equal(intentPreviewParts(output, utterances, duration), null)
  }
})

test('ASR identities and original timeline cannot be ambiguous, overlapping, invalid or out of duration', () => {
  for (const change of [
    (rows: IntentUtterance[]) => { rows[1]!.utterance_id = rows[0]!.utterance_id },
    (rows: IntentUtterance[]) => { rows[1]!.start_ms = 899 },
    (rows: IntentUtterance[]) => { rows[0]!.end_ms = 0 },
    (rows: IntentUtterance[]) => { rows[0]!.start_ms = NaN },
    (rows: IntentUtterance[]) => { rows[5]!.end_ms = 6501 },
    (rows: IntentUtterance[]) => { rows.reverse() },
  ]) {
    const { output, utterances, duration } = fixture(); change(utterances)
    assert.equal(intentPreviewParts(output, utterances, duration), null)
    assert.equal(intentPreviewParts(output, utterances, duration, 0), null)
  }
})

test('invalid indexes and durations reject coercion, fractions, NaN and unsafe values', () => {
  const { output, utterances, duration } = fixture()
  for (const index of [-1, 2, 0.1, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1, null, '0', false]) {
    assert.equal(intentPreviewParts(output, utterances, duration, index as number), null)
  }
  for (const value of [0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1, '6500', null]) {
    assert.equal(intentPreviewParts(output, utterances, value as number), null)
  }
})

test('whole target allows up to twelve adjacent ranges, while a single valid range remains playable in a larger draft', () => {
  const utterances = Array.from({ length: 13 }, (_, i) => ({ utterance_id: `u${i}`, start_ms: i * 1000, end_ms: (i + 1) * 1000, text: '合成' }))
  const { output } = fixture()
  output.excluded = []
  output.parts = utterances.map(value => ({ start_utterance_id: value.utterance_id, end_utterance_id: value.utterance_id, start_ms: value.start_ms, end_ms: value.end_ms }))
  assert.equal(intentPreviewParts(output, utterances, 13000), null)
  assert.deepEqual(intentPreviewParts(output, utterances, 13000, 12), [{ start_ms: 12000, end_ms: 13000 }])
  output.parts.pop()
  assert.equal(intentPreviewParts(output, utterances, 13000)?.length, 12)
})

test('zero starts and the maximum safe integer endpoint are accepted without arithmetic overflow', () => {
  const { output, utterances, duration } = fixture()
  assert.equal(intentPreviewParts(output, utterances, duration)![0]!.start_ms, 0)
  const last = { utterance_id: 'last', start_ms: Number.MAX_SAFE_INTEGER - 1, end_ms: Number.MAX_SAFE_INTEGER, text: '合成' }
  output.excluded = []
  output.parts = [{ start_utterance_id: 'last', end_utterance_id: 'last', start_ms: last.start_ms, end_ms: last.end_ms }]
  assert.deepEqual(intentPreviewParts(output, [last], Number.MAX_SAFE_INTEGER), [{ start_ms: last.start_ms, end_ms: last.end_ms }])
})

function deepFreeze<T>(value: T): T {
  if (value && typeof value === 'object') { Object.freeze(value); for (const item of Object.values(value)) deepFreeze(item) }
  return value
}
test('playback returns independent objects and preserves deeply frozen draft metadata', () => {
  const { output, utterances, duration } = fixture()
  const expected = structuredClone({ output, utterances })
  deepFreeze(output); deepFreeze(utterances)
  const first = intentPreviewParts(output, utterances, duration)!
  const second = intentPreviewParts(output, utterances, duration)!
  assert.notEqual(first, second); assert.notEqual(first[0], second[0]); assert.notEqual(first[0], output.parts[0])
  first[0]!.start_ms = 123
  assert.equal(second[0]!.start_ms, 0)
  assert.deepEqual({ output, utterances }, expected)
})
