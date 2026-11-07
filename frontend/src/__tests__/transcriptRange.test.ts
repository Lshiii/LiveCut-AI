import test from 'node:test'
import assert from 'node:assert/strict'
import type { IntentOutput, IntentUtterance } from '../intentClips.ts'
import { filterTranscriptIndexes, transcriptPage, transcriptPageForId, validateTranscriptRange } from '../transcriptRange.ts'

function fixture() {
  const ids = ['z9', 'u2', 'a1', 'u30', 'u3', 'b20']
  const utterances: IntentUtterance[] = ids.map((utterance_id, index) => ({ utterance_id,
    start_ms: index * 1000, end_ms: (index + 1) * 1000, text: `合成原句${index}` }))
  const output: IntentOutput = { output_id: 'output-1', goal_id: 'goal-1', title: '合成标题', status: 'FOUND', reason: '合成', enabled: false,
    parts: [{ start_utterance_id: 'z9', end_utterance_id: 'u2', start_ms: 0, end_ms: 2000 },
      { start_utterance_id: 'u30', end_utterance_id: 'b20', start_ms: 3000, end_ms: 6000 }],
    excluded: [], evidence_utterance_ids: [] }
  return { output, utterances, duration: 6000 }
}

test('selection maps inclusive original source order to new exact boundary values, including one sentence', () => {
  const { output, utterances, duration } = fixture()
  const range = validateTranscriptRange(output, 0, 'u2', 'a1', utterances, duration)
  assert.equal(range.error, '')
  assert.deepEqual(range.part, { start_utterance_id: 'u2', end_utterance_id: 'a1', start_ms: 1000, end_ms: 3000 })
  assert.deepEqual(validateTranscriptRange(output, 0, 'z9', 'z9', utterances, duration).part,
    { start_utterance_id: 'z9', end_utterance_id: 'z9', start_ms: 0, end_ms: 1000 })
})

test('NOT_FOUND, unknown statuses and invalid part indexes cannot be edited', () => {
  const { output, utterances, duration } = fixture()
  for (const status of ['NOT_FOUND', 'UNKNOWN', '', null]) {
    const value = { ...output, status } as IntentOutput
    assert.equal(validateTranscriptRange(value, 0, 'z9', 'u2', utterances, duration).part, null)
  }
  for (const index of [-1, 2, .5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1, '0', null]) {
    const value = validateTranscriptRange(output, index as number, 'z9', 'u2', utterances, duration)
    assert.equal(value.part, null); assert.ok(value.error)
  }
})

test('unknown or reversed selected IDs fail rather than sorting or silently changing a range', () => {
  const { output, utterances, duration } = fixture()
  for (const [start, end] of [['missing', 'u2'], ['z9', 'missing'], ['u2', 'z9']]) {
    const result = validateTranscriptRange(output, 0, start!, end!, utterances, duration)
    assert.equal(result.part, null); assert.ok(result.error)
  }
})

test('excluded middle sentences and excluded endpoints cannot be selected or silently unexcluded', () => {
  const { output, utterances, duration } = fixture()
  output.parts = [output.parts[0]!]
  output.excluded = [{ utterance_id: 'u2', reason: '' }]
  const before = structuredClone(output)
  for (const [start, end] of [['z9', 'a1'], ['u2', 'a1'], ['z9', 'u2'], ['u2', 'u2']]) {
    const result = validateTranscriptRange(output, 0, start!, end!, utterances, duration)
    assert.equal(result.part, null); assert.match(result.error, /排除/)
  }
  assert.ok(validateTranscriptRange(output, 0, 'a1', 'u30', utterances, duration).part)
  assert.deepEqual(output, before)
})

test('complete legal ranges before and after the edited part retain their order and do not overlap', () => {
  const { output, utterances, duration } = fixture()
  assert.equal(validateTranscriptRange(output, 0, 'a1', 'u30', utterances, duration).part, null)
  assert.equal(validateTranscriptRange(output, 0, 'u3', 'b20', utterances, duration).part, null)
  assert.equal(validateTranscriptRange(output, 1, 'u2', 'a1', utterances, duration).part, null)
  assert.equal(validateTranscriptRange(output, 1, 'z9', 'z9', utterances, duration).part, null)
  assert.ok(validateTranscriptRange(output, 0, 'a1', 'a1', utterances, duration).part, 'adjacent source boundaries may touch')
  assert.ok(validateTranscriptRange(output, 1, 'a1', 'a1', utterances, duration).part)
})

test('another incomplete/invalid draft does not block repairing the selected range', () => {
  for (const patch of [{ end_utterance_id: 'draft' }, { start_ms: NaN }, { start_ms: 3001 },
    { start_utterance_id: 'b20', end_utterance_id: 'u30', start_ms: 5000, end_ms: 4000 }]) {
    const { output, utterances, duration } = fixture()
    Object.assign(output.parts[1]!, patch)
    const before = structuredClone(output)
    assert.ok(validateTranscriptRange(output, 0, 'a1', 'u30', utterances, duration).part)
    assert.deepEqual(output, before)
  }
  const { output, utterances, duration } = fixture()
  output.parts[0] = null as unknown as IntentOutput['parts'][number]
  assert.ok(validateTranscriptRange(output, 0, 'z9', 'u2', utterances, duration).part)
})

test('an unrelated range that currently includes an excluded sentence stays invalid without blocking a local repair', () => {
  const { output, utterances, duration } = fixture()
  output.excluded = [{ utterance_id: 'u3', reason: '排除' }]
  const before = structuredClone(output)
  assert.ok(validateTranscriptRange(output, 0, 'a1', 'u30', utterances, duration).part)
  assert.deepEqual(output, before)
})

test('source IDs, duration and all original sentence timestamps must be unambiguous and safe', () => {
  for (const change of [
    (rows: IntentUtterance[]) => { rows[1]!.utterance_id = rows[0]!.utterance_id },
    (rows: IntentUtterance[]) => { rows[1]!.start_ms = 999 },
    (rows: IntentUtterance[]) => { rows[0]!.end_ms = 0 },
    (rows: IntentUtterance[]) => { rows[0]!.start_ms = -.1 },
    (rows: IntentUtterance[]) => { rows[0]!.end_ms = NaN },
    (rows: IntentUtterance[]) => { rows[0]!.end_ms = Number.MAX_SAFE_INTEGER + 1 },
    (rows: IntentUtterance[]) => { rows.reverse() },
  ]) {
    const { output, utterances, duration } = fixture(); change(utterances)
    assert.equal(validateTranscriptRange(output, 0, 'z9', 'u2', utterances, duration).part, null)
  }
  const { output, utterances } = fixture()
  for (const duration of [0, -1, 5999, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    assert.equal(validateTranscriptRange(output, 0, 'z9', 'u2', utterances, duration).part, null)
  }
  output.excluded = [{ utterance_id: 'missing', reason: '合成' }]
  assert.equal(validateTranscriptRange(output, 0, 'z9', 'u2', utterances, 6000).part, null)
})

function deepFreeze<T>(value: T): T {
  if (value && typeof value === 'object') { Object.freeze(value); for (const item of Object.values(value)) deepFreeze(item) }
  return value
}
test('NEEDS_REVIEW edits preserve deeply frozen status/enabled/evidence and return independent part copies', () => {
  const { output, utterances, duration } = fixture()
  output.status = 'NEEDS_REVIEW'; output.enabled = false
  const before = structuredClone({ output, utterances })
  deepFreeze(output); deepFreeze(utterances)
  const first = validateTranscriptRange(output, 0, 'z9', 'u2', utterances, duration)
  const second = validateTranscriptRange(output, 0, 'z9', 'u2', utterances, duration)
  assert.ok(first.part); assert.ok(second.part)
  assert.notEqual(first.part, second.part); assert.notEqual(first.part, output.parts[0])
  first.part.start_ms = 123
  assert.equal(second.part.start_ms, 0)
  assert.deepEqual({ output, utterances }, before)
})

function searchable(): IntentUtterance[] {
  return [
    { utterance_id: 'z9', start_ms: 0, end_ms: 1000, text: 'Ｃａｃｈｅ ＫＶ café ﬃ ①' },
    { utterance_id: 'u2', start_ms: 1000, end_ms: 2000, text: 'cache only' },
    { utterance_id: 'a1', start_ms: 2000, end_ms: 3000, text: '[a+b].* <b>字面</b> \\N 😀' },
    { utterance_id: 'u30', start_ms: 62000, end_ms: 63000, text: 'KV 请求缓存' },
  ]
}
test('search is NFKC/case normalized literal whitespace-AND matching against original source order', () => {
  const rows = searchable(), before = structuredClone(rows)
  assert.deepEqual(filterTranscriptIndexes(rows, '  cache　ＫＶ\tCAFE\u0301\nffi 1 '), [0])
  assert.deepEqual(filterTranscriptIndexes(rows, 'kv'), [0, 3])
  assert.deepEqual(filterTranscriptIndexes(rows, 'cache missing'), [])
  assert.deepEqual(filterTranscriptIndexes(rows, ''), [0, 1, 2, 3])
  assert.deepEqual(filterTranscriptIndexes(rows, '　 \n\t'), [0, 1, 2, 3])
  assert.deepEqual(rows, before)
})

test('regex metacharacters, HTML-looking text, backslashes and emoji stay literal', () => {
  const rows = searchable()
  assert.deepEqual(filterTranscriptIndexes(rows, '[a+b].* <B>字面</B> \\n 😀'), [2])
  assert.deepEqual(filterTranscriptIndexes(rows, '^cache$'), [])
  assert.deepEqual(filterTranscriptIndexes(rows, '['), [2])
  assert.deepEqual(filterTranscriptIndexes(rows, '.*'), [2])
})

test('displayed start and end time labels are searchable together with text', () => {
  const rows = searchable()
  assert.deepEqual(filterTranscriptIndexes(rows, '01:02.000 KV 01:03.000'), [3])
  assert.deepEqual(filterTranscriptIndexes(rows, '00:01.000 cache'), [0, 1])
  assert.deepEqual(filterTranscriptIndexes(rows, '00:01.000 00:02.000'), [1])
})

test('pagination clamps safely, keeps copies, and uses one-based empty pages', () => {
  const indexes = Array.from({ length: 45 }, (_, i) => i), before = [...indexes]
  assert.deepEqual(transcriptPage(indexes, 99), { indexes: [40, 41, 42, 43, 44], page: 3, totalPages: 3, total: 45 })
  assert.deepEqual(transcriptPage([], 99), { indexes: [], page: 1, totalPages: 1, total: 0 })
  for (const page of [NaN, Infinity, -Infinity, -1, 0]) assert.equal(transcriptPage(indexes, page).page, 1)
  assert.equal(transcriptPage(indexes, 2.8).page, 2)
  assert.equal(transcriptPage(indexes, Number.MAX_SAFE_INTEGER + 1).page, 3)
  for (const size of [0, -1, .5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) assert.equal(transcriptPage(indexes, 1, size).indexes.length, 20)
  const slice = transcriptPage(indexes, 1).indexes; slice[0] = 999
  assert.deepEqual(indexes, before)
})

test('large filtered lists retain their final sentence and locate IDs within filtered positions', () => {
  const rows = Array.from({ length: 10003 }, (_, i) => ({ utterance_id: `u${i}`, start_ms: i * 1000, end_ms: (i + 1) * 1000, text: i % 2 ? 'other' : 'needle' }))
  const indexes = filterTranscriptIndexes(rows, 'needle')
  assert.equal(indexes.length, 5002)
  const page = transcriptPageForId(rows, indexes, 'u10002')
  assert.equal(page, 251)
  assert.deepEqual(transcriptPage(indexes, page).indexes, [10000, 10002])
  assert.equal(transcriptPageForId(rows, indexes, 'u10001'), 1)
  assert.equal(transcriptPageForId(rows, indexes, 'unknown'), 1)
  assert.equal(transcriptPageForId(rows, [], 'u10002'), 1)
  assert.equal(transcriptPageForId(rows, indexes, 'u10002', 10), 501)
})
