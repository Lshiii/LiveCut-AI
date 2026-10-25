import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { ResumableUploader, UploadError, newestUploadSnapshot, parseUploadSnapshot, uploadStorageKey, validateUploadFile } from '../upload.ts'
import { hashFileChunks } from '../hashFileStream.ts'

const id = (value: number) => `00000000-0000-4000-8000-${String(value).padStart(12, '0')}`
let nextKey = 500
const digest = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex')
const blobHash = async (blob: Blob) => digest(new Uint8Array(await blob.arrayBuffer()))
const file = (fill = 3, name = 'sample.mp4') => new File([new Uint8Array(129).fill(fill)], name, { lastModified: 5 })
const identity = async (input: File) => ({ original_filename: validateUploadFile(input), size_bytes: input.size, content_hash: await blobHash(input) })
const memoryStorage = () => {
  const data = new Map<string, string>()
  return { data, getItem: (key: string) => data.get(key) ?? null, setItem: (key: string, value: string) => { data.set(key, value) }, removeItem: (key: string) => { data.delete(key) } }
}
interface Session {
  id: string; identity: Awaited<ReturnType<typeof identity>>; status: string; version: number
  parts: Map<number, { index: number; size_bytes: number; sha256: string }>; reads: number
}
class Server {
  sessions = new Map<string, Session>(); keys = new Map<string, Session>(); calls: Array<{ path: string; method: string; key: string | null }> = []
  failInitialResponses = 0; failFirstChunkResponse = false; queryError = 0; active = 0; maxActive = 0
  delayReplies = false; committed: ((session: Session, index: number) => void) | null = null
  create(value: Session['identity'], key: string) {
    const session: Session = { id: id(this.sessions.size + 1), identity: value, status: 'UPLOADING', version: 1, parts: new Map(), reads: 0 }
    this.sessions.set(session.id, session); this.keys.set(key, session); return session
  }
  snapshot(session: Session) {
    const video = session.status === 'COMPLETED' ? { id: id(100 + Number(session.id.slice(-12))),
      ...session.identity, media_validation_status: 'HEADER_CHECKED', created_at: '2026-10-25T00:00:00Z', content_url: '' } : null
    if (video) video.content_url = `/api/videos/${video.id}/content`
    return { ...session.identity, id: session.id, status: session.status, chunk_size: 16, total_parts: Math.ceil(session.identity.size_bytes / 16),
      received_parts: [...session.parts.values()], received_bytes: [...session.parts.values()].reduce((sum, value) => sum + value.size_bytes, 0),
      state_version: session.version, expires_at: '2026-10-26T00:00:00Z', video_id: video?.id ?? null, video, error_code: null }
  }
  error(status: number, code: string) { return Response.json({ error_code: code, request_id: id(999), message: 'safe', retryable: false }, { status }) }
  fetch = async (input: string | URL | Request, init: RequestInit = {}) => {
    const path = String(input), method = init.method ?? 'GET', headers = new Headers(init.headers)
    this.calls.push({ path, method, key: headers.get('Idempotency-Key') })
    assert.ok(init.signal, 'Every request must support cancellation')
    if (path === '/api/uploads' && method === 'POST') {
      const key = headers.get('Idempotency-Key')!, value = JSON.parse(String(init.body))
      let session = this.keys.get(key)
      if (session) assert.deepEqual(value, session.identity)
      else session = this.create(value, key)
      if (this.failInitialResponses-- > 0) throw new TypeError('Acknowledgement lost after commit')
      if (session.status === 'EXPIRED') return this.error(410, 'UPLOAD_EXPIRED')
      return Response.json(this.snapshot(session))
    }
    const match = /^\/api\/uploads\/([^/]+)(?:\/parts\/(\d+)|\/(complete))?$/.exec(path)
    assert.ok(match, `Unexpected path: ${path}`)
    const session = this.sessions.get(match[1]!)
    if (!session) return this.error(404, 'UPLOAD_NOT_FOUND')
    if (method === 'DELETE') { session.status = 'EXPIRED'; session.version++; return Response.json(this.snapshot(session)) }
    if (method === 'GET') {
      if (this.queryError) return this.error(this.queryError, this.queryError === 404 ? 'UPLOAD_NOT_FOUND' : 'STORAGE_ERROR')
      if (session.status === 'MERGING' && ++session.reads >= 1) { session.status = 'COMPLETED'; session.version++ }
      return Response.json(this.snapshot(session))
    }
    if (method === 'POST' && match[3] === 'complete') {
      assert.equal(session.parts.size, Math.ceil(session.identity.size_bytes / 16))
      session.status = 'MERGING'; session.version++; return Response.json(this.snapshot(session), { status: 202 })
    }
    assert.equal(method, 'PUT'); assert.ok(init.body instanceof Blob)
    this.active++; this.maxActive = Math.max(this.maxActive, this.active)
    try {
      const index = Number(match[2]), data = new Uint8Array(await init.body.arrayBuffer()), hash = digest(data)
      assert.equal(headers.get('X-Chunk-SHA256'), hash)
      assert.equal(data.length, Math.min(16, session.identity.size_bytes - index * 16))
      if (!session.parts.has(index)) { session.parts.set(index, { index, size_bytes: data.length, sha256: hash }); session.version++ }
      const captured = this.snapshot(session)
      this.committed?.(session, index)
      if (this.failFirstChunkResponse && index === 0) { this.failFirstChunkResponse = false; throw new TypeError('Lost part response') }
      if (this.delayReplies) await new Promise(resolve => setTimeout(resolve, index % 3 === 0 ? 8 : 1))
      if (init.signal.aborted) throw new DOMException('Aborted', 'AbortError')
      return Response.json(captured)
    } finally { this.active-- }
  }
}
function client(server: Server, storage = memoryStorage(), changed: (value: ResumableUploader['state']) => void = () => {}) {
  return new ResumableUploader(changed, { storage, fetch: server.fetch as typeof fetch,
    hashFile: async (value, signal, progress) => { if (signal.aborted) throw new Error('aborted'); progress(value.size); return blobHash(value) },
    hashPart: blobHash, randomUUID: () => id(nextKey++), sleep: async () => {}, pollMs: 1 })
}

test('incremental SHA reads bounded slices and matches an independent full-file SHA', async () => {
  const data = new Uint8Array(2_097_152 * 2 + 37).fill(13), blob = new Blob([data]), reads: number[] = [], progress: number[] = []
  const source = { size: blob.size, slice(start: number, end: number) { reads.push(end - start); return blob.slice(start, end) }, arrayBuffer() { throw new Error('Whole-file read forbidden') } }
  assert.equal(await hashFileChunks(source as Blob, bytes => progress.push(bytes)), digest(data))
  assert.equal(reads.length, 3); assert.ok(Math.max(...reads) <= 2_097_152); assert.equal(progress.at(-1), data.length)
})
test('display filename normalization matches path stripping and Java whitespace rules', () => {
  assert.equal(validateUploadFile({ name: '  folder\\ video.mp4  ', size: 4 }), 'video.mp4')
  assert.equal(validateUploadFile({ name: '\u00a0video.mp4', size: 4 }), '\u00a0video.mp4')
  assert.throws(() => validateUploadFile({ name: 'bad\u0000.mp4', size: 4 }), UploadError)
  assert.throws(() => validateUploadFile({ name: 'video.mov', size: 4 }), UploadError)
})
test('bounded concurrent upload tolerates reversed replies and lost part acknowledgements', async () => {
  const server = new Server(); server.delayReplies = true; server.failFirstChunkResponse = true
  const progress: number[] = [], uploader = client(server, memoryStorage(), state => { if (state.snapshot) progress.push(state.snapshot.received_bytes) })
  const video = await uploader.start(file())
  assert.ok(video); assert.equal(uploader.state.phase, 'completed'); assert.equal(server.sessions.size, 1)
  assert.ok(server.maxActive <= 3); assert.ok(server.maxActive > 1)
  assert.ok(progress.every((bytes, index) => index === 0 || bytes >= progress[index - 1]!))
  assert.equal(server.calls.filter(call => call.method === 'PUT' && call.path.endsWith('/0')).length, 2)
  assert.equal([...server.sessions.values()][0]!.parts.size, 9)
})
test('lost initialization response survives a new client using the original idempotency key', async () => {
  const server = new Server(), storage = memoryStorage(), source = file(); server.failInitialResponses = 4
  const first = client(server, storage)
  assert.equal(await first.start(source), null); assert.equal(first.state.phase, 'error'); assert.equal(server.sessions.size, 1)
  const firstKeys = server.calls.filter(call => call.method === 'POST').map(call => call.key)
  assert.equal(new Set(firstKeys).size, 1); assert.equal(storage.data.size, 1)
  const second = client(server, storage); assert.ok(await second.start(source))
  assert.equal(server.sessions.size, 1); assert.equal(storage.data.size, 0)
  assert.equal(server.calls.filter(call => call.path === '/api/uploads').at(-1)!.key, firstKeys[0])
})
test('pause after server commits keeps its receipt; reselected file only sends missing parts', async () => {
  const server = new Server(), storage = memoryStorage(), source = file(); server.delayReplies = true
  const first = client(server, storage)
  server.committed = (_session, index) => { if (index === 1) first.pause() }
  assert.equal(await first.start(source), null); assert.equal(first.state.phase, 'paused')
  const accepted = new Set([...server.sessions.values()][0]!.parts.keys()); assert.ok(accepted.size > 0)
  server.committed = null; const boundary = server.calls.length
  const second = client(server, storage); assert.ok(await second.start(new File([await source.arrayBuffer()], source.name, { lastModified: source.lastModified })))
  assert.equal(second.state.resumedParts, accepted.size)
  for (const call of server.calls.slice(boundary).filter(call => call.method === 'PUT')) assert.ok(!accepted.has(Number(call.path.split('/').at(-1))))
})
test('status outage or not-found never silently recreates a stored upload session', async () => {
  for (const status of [500, 404]) {
    const source = file(), value = await identity(source), server = new Server(), storage = memoryStorage()
    const record = { key: id(nextKey++), uploadId: id(1) }; server.create(value, record.key); server.queryError = status
    storage.setItem(uploadStorageKey(value), JSON.stringify(record))
    const uploader = client(server, storage); assert.equal(await uploader.start(source), null)
    assert.equal(server.calls.filter(call => call.path === '/api/uploads').length, 0)
    assert.equal(storage.getItem(uploadStorageKey(value)), JSON.stringify(record))
  }
})
test('explicitly expired session creates a new session and does not mix confirmed parts', async () => {
  const source = file(), value = await identity(source), server = new Server(), storage = memoryStorage()
  const record = { key: id(nextKey++), uploadId: id(1) }, old = server.create(value, record.key); old.status = 'EXPIRED'
  storage.setItem(uploadStorageKey(value), JSON.stringify(record))
  assert.ok(await client(server, storage).start(source)); assert.equal(server.sessions.size, 2); assert.equal(old.parts.size, 0)
})
test('same name size and modification time with different bytes use independent sessions', async () => {
  const original = file(1), changed = file(2), oldIdentity = await identity(original), server = new Server(), storage = memoryStorage()
  const old = server.create(oldIdentity, id(nextKey++)); storage.setItem(uploadStorageKey(oldIdentity), JSON.stringify({ key: id(nextKey++), uploadId: old.id }))
  assert.ok(await client(server, storage).start(changed)); assert.equal(server.sessions.size, 2); assert.equal(old.parts.size, 0)
  assert.notEqual(uploadStorageKey(oldIdentity), uploadStorageKey(await identity(changed)))
})
test('an expired initialization with a lost response replaces its key only after explicit 410', async () => {
  const source = file(), value = await identity(source), server = new Server(), storage = memoryStorage()
  const key = id(nextKey++), old = server.create(value, key); old.status = 'EXPIRED'
  storage.setItem(uploadStorageKey(value), JSON.stringify({ key }))
  assert.ok(await client(server, storage).start(source))
  const initializations = server.calls.filter(call => call.path === '/api/uploads')
  assert.equal(initializations.length, 2); assert.equal(initializations[0]!.key, key)
  assert.notEqual(initializations[1]!.key, key); assert.equal(server.sessions.size, 2); assert.equal(old.parts.size, 0)
})
test('a stalled JSON body remains a retryable timeout and preserves its initialization key', async () => {
  let requests = 0
  const storage = memoryStorage(), uploader = new ResumableUploader(() => {}, {
    storage, hashFile: blobHash, hashPart: blobHash, randomUUID: () => id(nextKey++), sleep: async () => {}, requestTimeoutMs: 5,
    fetch: async (_input, init) => {
      requests++
      return new Response(new ReadableStream({ start(controller) {
        init!.signal!.addEventListener('abort', () => controller.error(new DOMException('Aborted', 'AbortError')), { once: true })
      } }))
    },
  })
  assert.equal(await uploader.start(file()), null); assert.equal(requests, 4)
  assert.equal(uploader.state.phase, 'error'); assert.match(uploader.state.error, /超时/); assert.equal(storage.data.size, 1)
})
test('receipt validation rejects duplicate indexes, wrong totals and immutable geometry drift', async () => {
  const server = new Server(), value = await identity(file()), session = server.create(value, id(99)), raw = server.snapshot(session)
  const initial = parseUploadSnapshot(raw, value)
  assert.throws(() => parseUploadSnapshot({ ...raw, content_hash: 'b'.repeat(64) }, value), UploadError)
  assert.throws(() => parseUploadSnapshot({ ...raw, received_bytes: 1 }, value), UploadError)
  const receipt = { index: 0, size_bytes: 16, sha256: 'a'.repeat(64) }
  assert.throws(() => parseUploadSnapshot({ ...raw, received_parts: [receipt, receipt], received_bytes: 32 }, value), UploadError)
  assert.throws(() => newestUploadSnapshot(initial, { ...initial, chunk_size: 32, total_parts: 5 }), UploadError)
  assert.throws(() => parseUploadSnapshot(raw, value, id(300)), UploadError)
})
test('completed receipt cannot regress when an older or equal-version response arrives', async () => {
  const server = new Server(), uploader = client(server), source = file(); assert.ok(await uploader.start(source))
  const done = uploader.state.snapshot!, older = { ...done, status: 'UPLOADING', video: null, video_id: null, state_version: done.state_version - 1 }
  assert.equal(newestUploadSnapshot(done, older), done)
  assert.equal(newestUploadSnapshot(done, { ...older, state_version: done.state_version }), done)
  assert.throws(() => newestUploadSnapshot(done, { ...older, state_version: done.state_version + 1 }), UploadError)
})
test('abandon after an initialization acknowledgement is lost recovers and deletes that session', async () => {
  const server = new Server(), storage = memoryStorage(), uploader = client(server, storage); server.failInitialResponses = 4
  assert.equal(await uploader.start(file()), null)
  await uploader.discard(); assert.equal(uploader.state.phase, 'idle'); assert.equal(server.sessions.size, 1)
  assert.equal([...server.sessions.values()][0]!.status, 'EXPIRED'); assert.equal(storage.data.size, 0)
})
test('forbidden localStorage still permits an in-page upload without claiming refresh recovery', async () => {
  const server = new Server(), uploader = new ResumableUploader(() => {}, { storage: { getItem() { throw new Error() }, setItem() { throw new Error() }, removeItem() { throw new Error() } },
    fetch: server.fetch as typeof fetch, hashFile: async source => blobHash(source), hashPart: blobHash, randomUUID: () => id(nextKey++), sleep: async () => {} })
  assert.ok(await uploader.start(file())); assert.equal(uploader.state.storageAvailable, false)
})
