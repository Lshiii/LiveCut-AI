import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { AgentReadScope, type AgentSession, type AgentTurn } from '../videoAgent.ts'
import { AgentNoteError, MAX_AGENT_NOTE_JSON_BYTES, MAX_AGENT_NOTE_TEXT_BYTES, MAX_AGENT_NOTE_ZIP_BYTES,
  agentNoteErrorCopy, agentNoteOwner, copyAgentNoteMarkdown, downloadAgentNoteZip, parseAgentNotePreview, readAgentNote,
  readAgentNoteZip, type AgentNoteOwner, type AgentNotePreview } from '../agentNote.ts'
import { clone, session, turn } from './support/agentSubmissionFixture.ts'

const shared = JSON.parse(readFileSync(new URL('../../../fixtures/agent-note.synthetic.json', import.meta.url), 'utf8'))
const visualFixture = JSON.parse(readFileSync(new URL('../../../fixtures/agent-visual-focus.synthetic.json', import.meta.url), 'utf8'))
function owner() { return agentNoteOwner(session(), turn()) }
function preview(identity = owner()): AgentNotePreview {
  return { ...shared, ...identity, source_filename: '合成测试素材.mp4', download_filename: `video-note-${identity.turn_id}.zip` }
}
const json = (value: unknown, headers?: HeadersInit) => Response.json(value, { headers })
const fetcher = (value: Response): typeof fetch => async () => value
const code = (expected: string) => (error: unknown) => error instanceof AgentNoteError && error.code === expected
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(yes => { resolve = yes }); return { promise, resolve } }
function zip(note = preview(), headers: Record<string, string> = {}, body = new Uint8Array([0x50, 0x4b, 3, 4, ...new Array(28).fill(0)])) {
  return new Response(body, { headers: { 'Content-Type': 'application/zip',
    'Content-Disposition': `attachment; filename="${note.download_filename}"`,
    'X-LiveCut-Note-Turn-Id': note.turn_id, 'X-LiveCut-Note-Result-Hash': note.result_hash, ...headers } })
}

test('text note identity comes from one successful verified turn and retains knowledge/insufficient scope', () => {
  for (const scope of ['VIDEO', 'GENERAL', 'INSUFFICIENT'] as const) {
    const value = turn(); value.result!.answer_scope = scope
    if (scope !== 'VIDEO') { value.result!.citations = []; value.result!.tool_calls = [] }
    const identity = agentNoteOwner(session(), value)
    assert.equal(identity.answer_scope, scope); assert.equal(identity.citation_count, scope === 'VIDEO' ? 1 : 0)
    assert.equal(identity.image_count, 0); assert.equal(Object.isFrozen(identity), true)
    assert.equal(parseAgentNotePreview(preview(identity), identity).answer_scope, scope)
  }
  for (const status of ['QUEUED', 'RUNNING', 'FAILED', 'CANCELLED'] as const) {
    assert.throws(() => agentNoteOwner(session(), turn(status)), code('AGENT_NOTE_NOT_READY'))
  }
  const foreign = turn(); foreign.session_id = '99999999-9999-4999-8999-999999999999'
  assert.throws(() => agentNoteOwner(session(), foreign))
})

test('visual 1.1 and focus 1.2 identities count only cited images, not all observations or the user hint', () => {
  for (const version of ['1.1', '1.2', '1.2-pure-visual'] as const) {
    const result = clone(visualFixture.result)
    result.visual_evidence.push({ ...result.visual_evidence[0], frame_id: 'f0002', requested_ms: 1500, timestamp_ms: 1500, source_pts: 1500 })
    result.tool_calls.push({ step: 3, tool_name: 'inspect_frames', arguments: { timestamps_ms: [1500] }, result_utterance_ids: [], result_frame_ids: ['f0002'], origin: 'MODEL' })
    const identity: AgentSession = { id: result.session_id, job_id: result.job_id, video_id: result.video_id,
      created_at: '2026-11-09T00:00:00Z', source: { video_sha256: result.source_hash, transcript_hash: result.transcript_hash,
        duration_ms: visualFixture.duration_ms }, turns: [] }
    const value: AgentTurn = { ...turn(), id: result.turn_id, session_id: identity.id, job_id: identity.job_id, question: result.question,
      visual_mode: 'frames', focus_timestamp_ms: result.focus_timestamp_ms,
      stage: { ...turn().stage, stage_type: 'AGENT_VISUAL_FOCUS' }, result }
    if (version === '1.1') {
      result.schema_version = version; result.provenance.prompt_version = 'video-agent-visual-v1'; delete result.focus_timestamp_ms
      for (const call of result.tool_calls) delete call.origin
      delete value.focus_timestamp_ms; value.stage.stage_type = 'AGENT_VISUAL'
    }
    if (version === '1.2-pure-visual') {
      result.citations = []; result.tool_calls.splice(1, 1); result.tool_calls[1].step = 2
      result.focus_timestamp_ms = 0; value.focus_timestamp_ms = 0; result.tool_calls[0].arguments.timestamps_ms = [0]
      Object.assign(result.visual_evidence[0], { requested_ms: 0, timestamp_ms: 0, source_pts: 0 })
    }
    const noteOwner = agentNoteOwner(identity, value)
    assert.equal(noteOwner.image_count, result.visual_citations.length)
    assert(noteOwner.image_count < result.visual_evidence.length, 'unquoted observed frames must not be exported')
    assert.equal(parseAgentNotePreview(preview(noteOwner), noteOwner).turn_id, value.id)
  }
})

test('shared preview shape is strict and owner/source/scope/count/filename cannot authorize another note', () => {
  const identity: AgentNoteOwner = { turn_id: shared.turn_id, session_id: shared.session_id, job_id: shared.job_id, video_id: shared.video_id,
    source_hash: shared.source_hash, transcript_hash: shared.transcript_hash, answer_scope: 'VIDEO', citation_count: 1, image_count: 1 }
  assert.equal(parseAgentNotePreview(shared, identity).markdown, shared.markdown)
  for (const patch of [{ schema_version: '1' }, { future: true }, { turn_id: '99999999-9999-4999-8999-999999999999' },
    { session_id: shared.job_id }, { job_id: shared.video_id }, { video_id: shared.job_id }, { source_hash: 'd'.repeat(64) },
    { transcript_hash: 'd'.repeat(64) }, { result_hash: 'C'.repeat(64) }, { answer_scope: 'GENERAL' }, { citation_count: 0 },
    { image_count: true }, { image_count: 2 }, { source_filename: ' ' }, { source_filename: 'a'.repeat(1001) },
    { markdown: '' }, { download_filename: '../note.zip' }, { download_filename: `video-note-${shared.session_id}.zip` }]) {
    assert.throws(() => parseAgentNotePreview({ ...shared, ...patch }, identity), code('INVALID_AGENT_NOTE_RESPONSE'))
  }
  const missing = { ...shared }; delete missing.result_hash
  assert.throws(() => parseAgentNotePreview(missing, identity), code('INVALID_AGENT_NOTE_RESPONSE'))
})

test('text limits are UTF8 bytes, while JSON allows escaping overhead up to its separate 5MiB cap', async () => {
  const valid = { ...preview(), markdown: '\\'.repeat(MAX_AGENT_NOTE_TEXT_BYTES) }
  assert.equal(parseAgentNotePreview(valid, owner()).markdown.length, MAX_AGENT_NOTE_TEXT_BYTES)
  assert(new TextEncoder().encode(JSON.stringify(valid)).byteLength > MAX_AGENT_NOTE_TEXT_BYTES)
  assert.equal((await readAgentNote(owner(), { fetcher: fetcher(json(valid)) })).markdown, valid.markdown)
  const multibyte = { ...preview(), markdown: '中'.repeat(Math.floor(MAX_AGENT_NOTE_TEXT_BYTES / 3) + 1) }
  assert.throws(() => parseAgentNotePreview(multibyte, owner()), code('AGENT_NOTE_TOO_LARGE'))
})

test('preview reads use only the fixed same-origin turn GET and do not fetch content URLs from markdown or filename', async () => {
  const raw = { ...preview(), source_filename: 'https://private.invalid/a.mp4', markdown: '<img src="https://private.invalid/key">\n![remote](https://private.invalid/pic)' }
  const calls: Array<{ url: string; init: RequestInit }> = []
  const value = await readAgentNote(owner(), { fetcher: async (url, init) => { calls.push({ url: String(url), init: init! }); return json(raw) } })
  assert.equal(calls.length, 1); assert.equal(calls[0]!.url, `/api/agent-turns/${owner().turn_id}/note`)
  assert.equal(calls[0]!.init.method, 'GET'); assert.equal(calls[0]!.init.redirect, 'error'); assert.equal(calls[0]!.init.cache, 'no-store')
  assert.equal(value.markdown, raw.markdown); assert.equal(value.source_filename, raw.source_filename)
})

test('invalid JSON MIME, UTF8, response shape and advertised or streamed oversized bodies are rejected', async () => {
  for (const response of [new Response(JSON.stringify(preview()), { headers: { 'Content-Type': 'text/html' } }),
    new Response(new Uint8Array([0xff]), { headers: { 'Content-Type': 'application/json' } }), json({ ...preview(), run_token: 'private' }),
    json(preview(), { 'Content-Length': '1, 1' })]) {
    await assert.rejects(readAgentNote(owner(), { fetcher: fetcher(response) }), code('INVALID_AGENT_NOTE_RESPONSE'))
  }
  await assert.rejects(readAgentNote(owner(), { fetcher: fetcher(json(preview(), { 'Content-Length': String(MAX_AGENT_NOTE_JSON_BYTES + 1) })) }), code('AGENT_NOTE_TOO_LARGE'))
  let cancelled = false
  const stream = new ReadableStream({ start(controller) { controller.enqueue(new Uint8Array(MAX_AGENT_NOTE_JSON_BYTES + 1)) }, cancel() { cancelled = true } })
  await assert.rejects(readAgentNote(owner(), { fetcher: fetcher(new Response(stream, { headers: { 'Content-Type': 'application/json' } })) }), code('AGENT_NOTE_TOO_LARGE'))
  assert.equal(cancelled, true)
})

test('ZIP type, fixed filename, server turn id and result hash must all bind to the verified preview', async () => {
  const note = preview(), result = await readAgentNoteZip(owner(), note, { fetcher: fetcher(zip(note)) })
  assert.equal(result.filename, note.download_filename); assert.equal(result.blob.type, 'application/zip'); assert.equal(result.blob.size, 32)
  for (const headers of [{ 'Content-Type': 'text/html' }, { 'Content-Disposition': 'attachment; filename="../../note.zip"' },
    { 'Content-Disposition': `attachment; filename="video-note-${note.session_id}.zip"` },
    { 'X-LiveCut-Note-Turn-Id': note.session_id }, { 'X-LiveCut-Note-Result-Hash': 'd'.repeat(64) }, { 'X-LiveCut-Note-Result-Hash': '' }]) {
    let saved = 0
    await assert.rejects(downloadAgentNoteZip(owner(), note, { fetcher: fetcher(zip(note, headers)) }, () => { saved++ }), code('INVALID_AGENT_NOTE_RESPONSE'))
    assert.equal(saved, 0)
  }
  await assert.rejects(readAgentNoteZip(owner(), note, { fetcher: fetcher(zip(note, {}, new Uint8Array(32))) }), code('INVALID_AGENT_NOTE_RESPONSE'))
  await assert.rejects(readAgentNoteZip(owner(), note, { fetcher: fetcher(zip(note, { 'Content-Length': String(MAX_AGENT_NOTE_ZIP_BYTES + 1) })) }), code('AGENT_NOTE_TOO_LARGE'))
})

test('ZIP streaming enforces the 24MiB cap even without Content-Length and never triggers a partial download', async () => {
  const note = preview(), response = zip(note)
  const stream = new ReadableStream({ start(controller) { controller.enqueue(new Uint8Array(MAX_AGENT_NOTE_ZIP_BYTES + 1)); controller.close() } })
  let saved = 0
  await assert.rejects(downloadAgentNoteZip(owner(), note, { fetcher: fetcher(new Response(stream, { headers: response.headers })) }, () => { saved++ }), code('AGENT_NOTE_TOO_LARGE'))
  assert.equal(saved, 0)
})

test('switching task/session/turn or deactivating a read discards late ZIP completion before its download side effect', async () => {
  for (const change of ['task', 'session', 'turn', 'hidden', 'unmount']) {
    const scope = new AgentReadScope(), read = scope.begin(), pending = deferred<Response>(); let saved = 0
    const promise = downloadAgentNoteZip(owner(), preview(), { ...read, fetcher: () => pending.promise }, () => { saved++ })
    if (change === 'unmount') scope.dispose(); else scope.invalidate()
    pending.resolve(zip())
    await assert.rejects(promise, code('AGENT_NOTE_READ_CANCELLED')); assert.equal(saved, 0, change); read.release()
  }
  let current = true, saved = 0
  const pending = deferred<Response>()
  const promise = downloadAgentNoteZip(owner(), preview(), { current: () => current, fetcher: () => pending.promise }, () => { saved++ })
  current = false; pending.resolve(zip())
  await assert.rejects(promise, code('AGENT_NOTE_READ_CANCELLED')); assert.equal(saved, 0)
})

test('a current explicit ZIP download publishes once and only after complete bounded bytes arrive', async () => {
  const note = preview(), pending = deferred<Response>(), saved: string[] = []
  const promise = downloadAgentNoteZip(owner(), note, { current: () => true, fetcher: () => pending.promise }, (blob, filename) => { saved.push(`${filename}:${blob.size}`) })
  assert.deepEqual(saved, []); pending.resolve(zip(note)); await promise
  assert.deepEqual(saved, [`${note.download_filename}:32`])
})

test('copy uses server markdown verbatim, keeps preview on denied clipboard, and never acknowledges a late new context', async () => {
  const note = preview(), before = JSON.stringify(note), copied: string[] = []
  await copyAgentNoteMarkdown(note, async value => { copied.push(value) }, () => true)
  assert.deepEqual(copied, [note.markdown]); assert.equal(JSON.stringify(note), before)
  await assert.rejects(copyAgentNoteMarkdown(note, undefined, () => true), code('AGENT_NOTE_CLIPBOARD_UNAVAILABLE'))
  await assert.rejects(copyAgentNoteMarkdown(note, async () => { throw Error('private browser detail') }, () => true), code('AGENT_NOTE_CLIPBOARD_UNAVAILABLE'))
  assert.equal(JSON.stringify(note), before); assert.match(agentNoteErrorCopy(new AgentNoteError('AGENT_NOTE_CLIPBOARD_UNAVAILABLE')), /保留文字笔记.*下载含图 ZIP/)
  let active = false, writes = 0
  await assert.rejects(copyAgentNoteMarkdown(note, async () => { writes++ }, () => active), code('AGENT_NOTE_READ_CANCELLED')); assert.equal(writes, 0)
  active = true; const pending = deferred<void>()
  const promise = copyAgentNoteMarkdown(note, () => pending.promise, () => active)
  active = false; pending.resolve(); await assert.rejects(promise, code('AGENT_NOTE_READ_CANCELLED'))
})

test('read cancellation and timeout do not become an unbounded request or leak arbitrary server errors', async () => {
  const controller = new AbortController(); controller.abort(); let calls = 0
  await assert.rejects(readAgentNote(owner(), { signal: controller.signal, fetcher: async () => { calls++; return json(preview()) } }), code('AGENT_NOTE_READ_CANCELLED'))
  assert.equal(calls, 0)
  await assert.rejects(readAgentNote(owner(), { timeoutMs: 1, fetcher: (_url, init) => new Promise((_resolve, reject) => { init!.signal!.addEventListener('abort', () => reject(Error('aborted')), { once: true }) }) }), code('REQUEST_TIMEOUT'))
  for (const [status, error_code] of [[429, 'AGENT_NOTE_BUSY'], [413, 'AGENT_NOTE_TOO_LARGE'], [409, 'AGENT_NOTE_NOT_READY']] as const) {
    await assert.rejects(readAgentNote(owner(), { fetcher: fetcher(new Response(JSON.stringify({ error_code, message: 'secret provider text' }), { status })) }), code(error_code))
  }
  let failure: unknown
  try { await readAgentNote(owner(), { fetcher: fetcher(new Response(JSON.stringify({ error_code: 'PRIVATE_ERROR_secret', message: 'private response https://signed.invalid' }), { status: 500 })) }) }
  catch (error) { failure = error }
  assert.doesNotMatch(agentNoteErrorCopy(failure), /private|secret|https:|PRIVATE_ERROR/)
})

test('published source/result/image/stage failures retain their exact server codes and distinct fixed Chinese guidance', async () => {
  for (const [serverCode, guidance] of [
    ['AGENT_RESULT_INVALID', /回答.*一致性校验/], ['AGENT_VISUAL_EVIDENCE_INVALID', /截图依据.*完整性校验/],
    ['AGENT_NOTE_SOURCE_INVALID', /来源或引用图片.*核对/], ['AGENT_STAGE_UNAVAILABLE', /执行记录.*核对/],
    ['AGENT_SOURCE_CHANGED', /视频来源已变化/],
  ] as const) {
    let failure: unknown
    try { await readAgentNote(owner(), { fetcher: fetcher(new Response(JSON.stringify({ error_code: serverCode,
      message: 'private internal path and provider response' }), { status: 409 })) }) } catch (error) { failure = error }
    assert(code(serverCode)(failure)); assert.match(agentNoteErrorCopy(failure), guidance)
    assert.doesNotMatch(agentNoteErrorCopy(failure), /private|internal|provider/)
  }
})
