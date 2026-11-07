import test from 'node:test'
import assert from 'node:assert/strict'
import { BrowserDraftStore, DRAFT_PREFIX, MAX_DRAFT_BYTES, MAX_DRAFT_DOWNLOAD_BYTES, browserDraftKey, copyDraftData, draftFile, importDraftFile, type DraftStorage } from '../browserDrafts.ts'

const documentId = '11111111-1111-4111-8111-111111111111', otherDocument = '22222222-2222-4222-8222-222222222222'
const writer = '33333333-3333-4333-8333-333333333333', otherWriter = '44444444-4444-4444-8444-444444444444'
class MemoryStorage implements DraftStorage {
  readonly values = new Map<string, string>()
  failWrite = false
  get length() { return this.values.size }
  key(index: number) { return [...this.values.keys()][index] ?? null }
  getItem(key: string) { return this.values.get(key) ?? null }
  setItem(key: string, value: string) {
    if (this.failWrite) throw Object.assign(new Error('never expose storage internals'), { name: 'QuotaExceededError' })
    this.values.set(key, value)
  }
  removeItem(key: string) { this.values.delete(key) }
}
function fixture() {
  const storage = new MemoryStorage()
  let sequence = 0, time = 100
  const newId = () => `55555555-5555-4555-8555-${String(++sequence).padStart(12, '0')}`
  return { storage, store: new BrowserDraftStore(() => storage, () => ++time, newId) }
}

test('a stored backup is an independent JSON snapshot and survives creating a new store', () => {
  const { storage, store } = fixture(), input = { text: '保留未完成输入', time: '00:', cues: [] as string[] }
  const saved = store.save('caption', `${documentId}:${otherDocument}`, writer, input)
  assert.equal(saved.error, ''); assert.ok(saved.entry)
  input.text = '继续编辑'; input.cues.push('new')
  assert.deepEqual(new BrowserDraftStore(() => storage).list('caption', `${documentId}:${otherDocument}`).entries[0]!.data,
    { text: '保留未完成输入', time: '00:', cues: [] })
  assert.equal(saved.entry.data && (saved.entry.data as typeof input).text, '保留未完成输入')
})

test('document, editor kind and mounted writer identities do not share a last-writer-wins slot', () => {
  const { store } = fixture()
  store.save('intent', documentId, writer, { title: 'A' })
  store.save('intent', documentId, otherWriter, { title: 'B' })
  store.save('caption', documentId, writer, { title: 'C' })
  store.save('intent', otherDocument, writer, { title: 'D' })
  assert.deepEqual(store.list('intent', documentId).entries.map(entry => entry.data), [{ title: 'B' }, { title: 'A' }])
  assert.deepEqual(store.list('caption', documentId).entries.map(entry => entry.data), [{ title: 'C' }])
  assert.deepEqual(store.list('intent', otherDocument).entries.map(entry => entry.data), [{ title: 'D' }])
})

test('every write gets an immutable version key, so removing an old entry keeps concurrent newer data', () => {
  const { storage, store } = fixture()
  const before = store.save('intent', documentId, writer, { title: 'old' }).entry!
  const after = store.save('intent', documentId, writer, { title: 'new' }).entry!
  assert.notEqual(before.entry_id, after.entry_id); assert.equal(storage.length, 2)
  assert.equal(store.remove(before).removed, true)
  assert.deepEqual(store.list('intent', documentId).entries.map(entry => entry.data), [{ title: 'new' }])
  assert.equal(store.remove(before).removed, true)
})

test('removing one mounted editor backup never removes other editors or the source history', () => {
  const { store } = fixture()
  const source = store.save('caption', documentId, writer, { text: '历史' }).entry!
  const copy = store.save('caption', documentId, otherWriter, copyDraftData(source.data)).entry!
  assert.equal(store.remove(copy).removed, true)
  assert.deepEqual(store.list('caption', documentId).entries, [source])
})

test('unexpected modification of the immutable old key refuses removal instead of deleting changed data', () => {
  const { storage, store } = fixture()
  const entry = store.save('intent', documentId, writer, { title: 'old' }).entry!
  storage.setItem(browserDraftKey(entry.kind, entry.document_id, entry.writer_id, entry.entry_id), JSON.stringify({ ...entry, data: { title: 'changed elsewhere' } }))
  assert.equal(store.remove(entry).removed, false)
  assert.equal(storage.length, 1)
  assert.deepEqual(store.list('intent', documentId).entries[0]!.data, { title: 'changed elsewhere' })
})

test('malformed, foreign and key-forged records are not restored or silently removed', () => {
  const { storage, store } = fixture()
  const valid = store.save('intent', documentId, writer, { title: 'valid' }).entry!
  const key = browserDraftKey('intent', documentId, writer, valid.entry_id)
  storage.setItem(`${DRAFT_PREFIX}intent:${documentId}:broken`, '{')
  storage.setItem(key + '-forged', JSON.stringify(valid))
  const unrelated = 'another-application:key'; storage.setItem(unrelated, 'private')
  const list = store.list('intent', documentId)
  assert.deepEqual(list.entries, [valid]); assert.match(list.error, /原记录仍保留/)
  assert.equal(storage.length, 4); assert.equal(storage.getItem(unrelated), 'private')
})

test('quota failure keeps the previous successful backup and never returns a false saved entry', () => {
  const { storage, store } = fixture()
  const old = store.save('intent', documentId, writer, { title: 'old' }).entry!
  storage.failWrite = true
  const failure = store.save('intent', documentId, writer, { title: 'new' })
  assert.equal(failure.entry, null); assert.match(failure.error, /空间不足/)
  assert.deepEqual(store.list('intent', documentId).entries, [old])
  assert.doesNotMatch(failure.error, /internals/)
})

test('unavailable browser storage reports failure without throwing or exposing raw exception text', () => {
  const store = new BrowserDraftStore(() => { throw new Error('sensitive implementation detail') })
  assert.equal(store.save('caption', documentId, writer, { text: 'draft' }).entry, null)
  const result = store.list('caption', documentId)
  assert.deepEqual(result.entries, []); assert.match(result.error, /未完成/); assert.doesNotMatch(result.error, /sensitive/)
})

test('data beyond local storage limit can still be copied and downloaded within the separate export limit', () => {
  const { store } = fixture(), data = { text: 'a'.repeat(MAX_DRAFT_BYTES) }
  assert.doesNotThrow(() => copyDraftData(data))
  const failure = store.save('caption', documentId, writer, data)
  assert.equal(failure.entry, null); assert.match(failure.error, /上限/)
  const entry = store.save('caption', documentId, writer, { text: 'small' }).entry!
  const file = draftFile({ ...entry, data })
  assert.ok(file.text.length > MAX_DRAFT_BYTES); assert.ok(file.text.length < MAX_DRAFT_DOWNLOAD_BYTES)
  assert.deepEqual(JSON.parse(file.text).data, data)
  assert.match(file.filename, /^livecut-caption-draft-[a-f0-9]{8}\.json$/)
  assert.throws(() => copyDraftData({ text: 'a'.repeat(MAX_DRAFT_DOWNLOAD_BYTES) }))
})

test('non JSON numbers, cycles and undefined are refused instead of being silently changed', () => {
  const { storage, store } = fixture(), cycle: { self?: unknown } = {}; cycle.self = cycle
  for (const data of [undefined, { value: undefined }, { value: NaN }, { value: Infinity }, { value: 1n }, cycle, { value() {} }]) {
    assert.equal(store.save('intent', documentId, writer, data).entry, null)
  }
  assert.equal(storage.length, 0)
})

test('invalid document, writer and collision identities cannot overwrite an existing entry', () => {
  const { storage, store } = fixture()
  assert.equal(store.save('intent', '../another-key', writer, {}).entry, null)
  assert.equal(store.save('caption', documentId, 'invalid-writer', {}).entry, null)
  const id = '55555555-5555-4555-8555-555555555555'
  const collisions = new BrowserDraftStore(() => storage, () => 1, () => id)
  const first = collisions.save('intent', documentId, writer, { title: 'first' }).entry!
  assert.equal(collisions.save('intent', documentId, writer, { title: 'second' }).entry, null)
  assert.deepEqual(collisions.list('intent', documentId).entries, [first])
})

test('a local recovery file round-trips without saving it and is tied to its editor and document', () => {
  const { storage, store } = fixture()
  const entry = store.save('caption', `${documentId}:${otherDocument}`, writer, { text: '', time: '00:' }).entry!
  const before = [...storage.values], file = draftFile(entry)
  assert.deepEqual(importDraftFile(file.text, 'caption', `${documentId}:${otherDocument}`), entry)
  assert.equal(importDraftFile(file.text, 'intent', `${documentId}:${otherDocument}`), null)
  assert.equal(importDraftFile(file.text, 'caption', otherDocument), null)
  assert.equal(importDraftFile('{', 'caption', `${documentId}:${otherDocument}`), null)
  assert.equal(importDraftFile(JSON.stringify({ ...entry, unexpected: true }), 'caption', `${documentId}:${otherDocument}`), null)
  assert.deepEqual([...storage.values], before)
})

test('an over-storage-limit download can be imported as an in-memory candidate, with a strict download ceiling', () => {
  const { store } = fixture(), entry = store.save('intent', documentId, writer, {}).entry!
  const file = draftFile({ ...entry, data: { text: 'a'.repeat(MAX_DRAFT_BYTES) } })
  assert.ok(importDraftFile(file.text, 'intent', documentId))
  const tooLarge = JSON.stringify({ ...entry, data: { text: 'a'.repeat(MAX_DRAFT_DOWNLOAD_BYTES) } })
  assert.equal(importDraftFile(tooLarge, 'intent', documentId), null)
  assert.equal(importDraftFile(JSON.stringify({ ...entry, entry_id: '../bad' }), 'intent', documentId), null)
})
