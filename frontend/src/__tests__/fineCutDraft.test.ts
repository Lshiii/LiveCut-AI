import assert from 'node:assert/strict'
import test from 'node:test'
import { fineDraftStore, playWindow, validFineCuts } from '../fineCutDraft.ts'

const base = [{ start_ms: 4500, end_ms: 6000 }, { start_ms: 12000, end_ms: 16000 }]
const adjusted = [{ start_ms: 4511, end_ms: 5900 }, { start_ms: 12222, end_ms: 15666 }]
function storage() {
  const data = new Map<string, string>()
  return { getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => data.set(key, value) } as Storage
}

test('a join audition and a resume from its exact boundary play only the fine-cut source media', () => {
  assert.equal(validFineCuts(base, adjusted), true)
  assert.deepEqual(playWindow(adjusted, 1380, 1490),
    [{ start_ms: 5891, end_ms: 5900 }, { start_ms: 12222, end_ms: 12323 }])
  assert.deepEqual(playWindow(adjusted, 1389, 1390), [{ start_ms: 12222, end_ms: 12223 }])
  assert.deepEqual(playWindow(adjusted, 4833, 4833), [])
})

test('fine cuts reopen under their own confirmation and a stale tab preserves newer local cuts', () => {
  const db = storage(), first = fineDraftStore(db, 'r1', base), other = fineDraftStore(db, 'r1', base)
  assert.deepEqual(first.cuts, base)
  first.write(adjusted)
  assert.deepEqual(fineDraftStore(db, 'r1', base).cuts, adjusted)
  assert.deepEqual(fineDraftStore(db, 'r2', base).cuts, base)
  assert.throws(() => other.write(base), /另一页面/)
  assert.deepEqual(fineDraftStore(db, 'r1', base).cuts, adjusted)
})

test('the local draft rejects out-of-bounds, reordered, empty and corrupt cuts', () => {
  for (const cuts of [[], adjusted.slice().reverse(), [{ start_ms: 0, end_ms: 6000 }, base[1]],
    [base[0], { start_ms: 16000, end_ms: 16000 }], [base[0], { start_ms: 12111.5, end_ms: 12500 }]]) {
    assert.equal(validFineCuts(base, cuts), false)
  }
  const db = storage()
  db.setItem('livecut.fine-cut.v1.r1', JSON.stringify({ project: 'r2', version: 1, cuts: adjusted }))
  assert.throws(() => fineDraftStore(db, 'r1', base), /草稿/)
  assert.deepEqual(fineDraftStore(db, 'r2', base).cuts, base)
})
