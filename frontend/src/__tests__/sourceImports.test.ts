import test from 'node:test'
import assert from 'node:assert/strict'
import { createSourceReceipt, finishSourceReceipt, loadSourceReceipts, newestSourceImport, parseSourceImport, parseSourceImports, sourceImportErrorText,
  sourceImportNotFound, sourceImportUrl, sourceImportVideo, sourceReceiptKey } from '../sourceImports.ts'

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const url = 'https://www.youtube.com/watch?v=abcdefghijk'
const snapshot = (value: Record<string, unknown> = {}) => ({ id: id(1), url, status: 'QUEUED', phase: 'QUEUED', progress_percent: null,
  downloaded_bytes: 0, video_id: null, error_code: null, attempt: 0, created_at: '2026-11-15T00:00:00Z', updated_at: '2026-11-15T00:00:00Z', ...value })
function storage() { const data = new Map<string, string>(); return { data, get length() { return data.size }, key: (n: number) => [...data.keys()][n] ?? null,
  getItem: (key: string) => data.get(key) ?? null, setItem: (key: string, value: string) => { data.set(key, value) }, removeItem: (key: string) => { data.delete(key) } } }

test('source links normalize a single public video and refuse credentials, playlists, redirects and multipart ambiguity', () => {
  assert.equal(sourceImportUrl(' https://youtu.be/abcdefghijk?si=track '), url)
  assert.equal(sourceImportUrl('https://www.bilibili.com/video/BV1234567890?p=1&share_source=foo'), 'https://www.bilibili.com/video/BV1234567890')
  for (const bad of ['http://youtu.be/abcdefghijk', 'https://user:pass@youtu.be/abcdefghijk', 'https://youtu.be:4433/abcdefghijk', 'https://localhost/video/BV1234567890',
    'https://www.youtube.com/watch?v=abcdefghijk&list=x', 'https://www.youtube.com/shorts/abcdefghijk', 'https://b23.tv/abc', 'https://bilibili.com/video/BV1234567890?p=2',
    'https://www.youtube.com/watch?v=abcdefghijk&v=other', 'https://bilibili.com/video/BV1234567890?p=1&p=2', 'https://bilibili.com/video/av123456789012345678901',
    'https://youtu.be/abcdefghijk?playlist=x', 'https://youtu.be/abcdefghijk?v=abcdefghijk', 'https://youtu.be/abcdefghijk?a=1&a=2', 'https://youtu.be/abcdefghijk?track=%0Asecret',
    'https://youtu.be/abc\tdefghijk']) assert.throws(() => sourceImportUrl(bad), undefined, bad)
})
test('source snapshots bind canonical identity, status/phase and completed video without trusting network types', () => {
  assert.equal(parseSourceImport(snapshot()).attempt, 0)
  const completed = snapshot({ status: 'COMPLETED', phase: 'COMPLETED', progress_percent: 100, video_id: id(2), attempt: 1 })
  assert.equal(parseSourceImport(completed, { id: id(1), url }).video_id, id(2))
  for (const change of [{ id: 'other' }, { url: 'https://youtu.be/abcdefghijk' }, { attempt: -1 }, { attempt: 4 }, { downloaded_bytes: -1 }, { progress_percent: 101 },
    { phase: 'DOWNLOADING' }, { status: 'RUNNING', phase: 'QUEUED' }, { video_id: id(2) }, { error_code: '/private/token' }, { updated_at: 'not a date' }]) assert.throws(() => parseSourceImport(snapshot(change)))
  assert.throws(() => parseSourceImport({ ...completed, video_id: null })); assert.throws(() => parseSourceImport(snapshot(), { id: id(5) }))
  assert.throws(() => parseSourceImports({ items: [snapshot(), snapshot()] })); assert.throws(() => parseSourceImports({ items: Array.from({ length: 21 }, (_, n) => snapshot({ id: id(n) })) }))
})
test('attempt 0 claims, unseen retries and explicit failed requeue progress without reviving cancellation or completion', () => {
  const queued = parseSourceImport(snapshot()), running = parseSourceImport(snapshot({ status: 'RUNNING', phase: 'DOWNLOADING', attempt: 1 }))
  assert.equal(newestSourceImport(queued, running).status, 'RUNNING')
  const failed = parseSourceImport(snapshot({ status: 'FAILED', phase: 'FAILED', attempt: 1, error_code: 'IMPORT_DOWNLOAD_FAILED' }))
  const retry = parseSourceImport(snapshot({ attempt: 1 }))
  assert.equal(newestSourceImport(failed, retry).status, 'FAILED')
  assert.equal(newestSourceImport(failed, retry, true).status, 'QUEUED')
  assert.equal(newestSourceImport(failed, parseSourceImport(snapshot({ ...running, attempt: 3 }))).attempt, 3)
  const cancelled = parseSourceImport(snapshot({ status: 'CANCELLED', phase: 'CANCELLED', attempt: 1 }))
  assert.equal(newestSourceImport(cancelled, running), cancelled)
  const completed = parseSourceImport(snapshot({ status: 'COMPLETED', phase: 'COMPLETED', progress_percent: 100, video_id: id(2), attempt: 1 }))
  assert.equal(newestSourceImport(completed, running), completed)
})
test('older reads cannot regress phases, attempts or later persisted state', () => {
  const verifying = parseSourceImport(snapshot({ status: 'RUNNING', phase: 'VERIFYING', attempt: 2, updated_at: '2026-11-15T00:00:02Z' }))
  assert.equal(newestSourceImport(verifying, parseSourceImport(snapshot({ status: 'RUNNING', phase: 'DOWNLOADING', attempt: 2, updated_at: '2026-11-15T00:00:03Z' }))), verifying)
  assert.equal(newestSourceImport(verifying, parseSourceImport(snapshot({ status: 'RUNNING', phase: 'VERIFYING', attempt: 1, updated_at: '2026-11-15T00:00:04Z' }))), verifying)
  assert.equal(newestSourceImport(verifying, parseSourceImport(snapshot({ status: 'FAILED', phase: 'FAILED', attempt: 2, updated_at: '2026-11-15T00:00:01Z' }))), verifying)
  const precise = parseSourceImport(snapshot({ status: 'RUNNING', phase: 'VERIFYING', attempt: 2, updated_at: '2026-11-15T00:00:02.000001Z' }))
  assert.equal(newestSourceImport(precise, verifying), precise)
})
test('receipt survives reload and new input cannot replace its immutable original key/body', () => {
  const store = storage(), entry = createSourceReceipt(store, 'https://youtu.be/abcdefghijk?si=x', () => id(9))
  assert.equal(entry.input, JSON.stringify({ url })); assert.deepEqual(loadSourceReceipts(store), [entry])
  assert.throws(() => createSourceReceipt(store, 'https://youtu.be/other123456', () => id(10)))
  assert.deepEqual(loadSourceReceipts(store), [entry]); finishSourceReceipt(store, entry); assert.equal(store.length, 0)
})
test('receipt write/readback failure prevents submission and cleanup never removes a changed or another tab record', () => {
  const store = storage(), entry = createSourceReceipt(store, url, () => id(9))
  store.setItem(sourceReceiptKey(id(10)), JSON.stringify({ ...entry, key: id(10) }))
  finishSourceReceipt(store, entry); assert.equal(store.length, 1)
  store.setItem(sourceReceiptKey(id(10)), 'changed'); assert.throws(() => finishSourceReceipt(store, { ...entry, key: id(10) })); assert.equal(store.length, 1)
  assert.throws(() => loadSourceReceipts(store))
  const failing = { ...storage(), setItem: () => { throw new Error('quota') } }
  assert.throws(() => createSourceReceipt(failing, url, () => id(11)))
})
test('only exact business 404 authorizes replay; public errors never echo arbitrary paths, tokens or messages', () => {
  assert.equal(sourceImportNotFound({ code: 'SOURCE_IMPORT_NOT_FOUND', status: 404 }), true)
  for (const error of [{ code: 'HTTP_404', status: 404 }, { code: 'SOURCE_IMPORT_NOT_FOUND', status: 500 }, { code: 'NETWORK_UNAVAILABLE', status: 0 }]) assert.equal(sourceImportNotFound(error), false)
  assert.ok(!sourceImportErrorText({ code: 'secret/token', message: 'C:/private/password' }).includes('password'))
})
test('using an imported video verifies exact id, safe filename, hash and playback source', () => {
  const video = { id: id(2), original_filename: '录播.mp4', content_hash: 'a'.repeat(64), size_bytes: 100, content_url: `/api/videos/${id(2)}/content`, media_validation_status: 'HEADER_CHECKED', created_at: '2026-11-15T00:00:00Z' }
  assert.equal(sourceImportVideo(video, id(2)).id, id(2))
  for (const change of [{ id: id(3) }, { original_filename: '../token.mp4' }, { content_url: 'https://private/' }, { content_hash: 'bad' }, { size_bytes: 0 }]) assert.throws(() => sourceImportVideo({ ...video, ...change }, id(2)))
})
