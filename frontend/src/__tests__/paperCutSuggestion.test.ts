import test from 'node:test'
import assert from 'node:assert/strict'
import { parseSuggestion, previewSuggestion, restoredSuggestion, suggestionInput, type SuggestionReceipt } from '../paperCutSuggestion.ts'
import type { PaperCutSource } from '../paperCut.ts'

const context = { revision: 2, planHash: 'a'.repeat(64), requestId: 'da47f007-e04e-4021-acdd-ef348294ecff', outputId: 'talk' }
const source: PaperCutSource = { originalDurationMs: 4000, rows: ['定义','例子1','例子2','结论'].map((text, i) => ({
  partIndex: 0, evidence: i === 0 || i === 3, anchor: i === 3,
  utterance: { utterance_id: 'u' + i, start_ms: i * 1000, end_ms: (i + 1) * 1000, text },
})) }
const key = 'ba1d44cc-97bb-49cf-aa3c-5006806d2ea2'

test('submit only retained ids; removing an internal sentence splits the frozen draft', () => {
  const removed = new Set(['u1'])
  const input = suggestionInput(context, source, removed, '  去掉举例  ', 2500)
  assert.equal(input.instruction, '去掉举例')
  assert.deepEqual(input.rows.map(r => [r.utterance_id, r.part_index]), [['u0',0],['u2',1],['u3',1]])
  assert.ok(input.rows[2]!.anchor)
  assert.ok(!JSON.stringify(input).includes('例子2'))
  const record = { requestId: context.requestId, key, input }
  assert.deepEqual(restoredSuggestion(record, context, source, removed), record)
  assert.equal(restoredSuggestion(record, context, source, new Set()), null)
  assert.equal(restoredSuggestion(record, { ...context, revision: 3 }, source, removed), null)
})

test('check server binding, target and result before a preview can be adopted', () => {
  const receipt: SuggestionReceipt = { requestId: context.requestId, key, input: suggestionInput(context, source, new Set(), '保留定义结论', 2000) }
  const raw = { id: key, status: 'SUCCEEDED', clip_request_id: context.requestId, output_id: 'talk', plan_hash: context.planHash,
    base_revision: 2, idempotency_key: key, result: { remove_ids: ['u1','u2'], reason: '保留两端', target_ms: 2000 }, error_code: null }
  assert.deepEqual(parseSuggestion(raw, receipt).result, raw.result)
  for (const change of [{ clip_request_id: key }, { idempotency_key: '' }, { base_revision: 1 },
    { result: { ...raw.result, target_ms: null } }, { result: { ...raw.result, remove_ids: ['u1','u1'] } }])
    assert.throws(() => parseSuggestion({ ...raw, ...change }, receipt))
  const draft = new Set<string>()
  assert.equal(previewSuggestion(source, draft, raw.result).cut.durationMs, 2000)
  assert.equal(draft.size, 0)
  for (const ids of [['u3'],['unknown'],['u1']]) assert.throws(() => previewSuggestion(source, draft, { ...raw.result, remove_ids: ids }))
  assert.throws(() => previewSuggestion(source, new Set(['u1']), raw.result))
})
