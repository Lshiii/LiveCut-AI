import test from 'node:test'
import assert from 'node:assert/strict'
import { parseTranscriptContext, parseTranscriptPage, TranscriptResponseError, TranscriptSearchWorkspace,
  sameTranscriptSource, transcriptContextUrl, transcriptHighlights, transcriptPageUrl, transcriptQueryError, transcriptQuote, transcriptTime,
  type TranscriptOwner, type TranscriptPageRequest, type TranscriptSource, type TranscriptUtterance } from '../transcriptSearch.ts'

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const owner: TranscriptOwner = { job_id: id(1), video_id: id(2), generation: 3, source_video_sha256: 'a'.repeat(64) }
const source: TranscriptSource = { ...owner, transcript_sha256: 'b'.repeat(64), analysis_sha256: 'c'.repeat(64), duration_ms: 36000000, source_fingerprint: 'd'.repeat(64) }
const utterance = (n: number, text = `原句 ${n} 缓存 过期`): TranscriptUtterance => ({ utterance_id: `u${n}`, start_ms: n * 1000, end_ms: (n + 1) * 1000, text })
const request = (q = '', offset = 0, fixed: TranscriptSource | null = source): TranscriptPageRequest => ({ q, offset, limit: 20, source: fixed })
const page = (q = '', offset = 0, count = 3, matched = 25, fixed = source) => ({ schema_version: '1.0', data_kind: 'transcript_page', source: { ...fixed }, q,
  offset, limit: 20, total_utterances: 30, matched_utterances: matched, next_offset: offset + count < matched ? offset + count : null,
  items: Array.from({ length: count }, (_, i) => utterance(offset + i)) })
const context = (center = utterance(1), fixed = source) => ({ schema_version: '1.0', data_kind: 'transcript_context', source: { ...fixed }, center,
  previous: center.start_ms ? utterance(Number(center.utterance_id.slice(1)) - 1) : null,
  next: utterance(Number(center.utterance_id.slice(1)) + 1) })
const clone = <T>(v: T): T => structuredClone(v)
const flush = async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve() }
function deferred<T>() { let resolve!: (v: T) => void, reject!: (v: unknown) => void; const promise = new Promise<T>((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function harness() {
  const calls: Array<{ path: string; signal: AbortSignal; response: ReturnType<typeof deferred<unknown>> }> = []
  const workspace = new TranscriptSearchWorkspace(owner, { changed: () => {}, get: (path, signal) => {
    const response = deferred<unknown>(); calls.push({ path, signal, response }); return response.promise
  } })
  return { workspace, calls }
}

test('transcript page protocol preserves literal query/ASR, pins every source field and accepts actual-count pagination', () => {
  const q = '  ＣＡＣＨＥ 缓存 02:30 ', raw = page(q), result = parseTranscriptPage(raw, owner, request(q))
  assert.equal(result.q, q); assert.equal(result.items[0]!.text, raw.items[0]!.text)
  assert.equal(result.next_offset, 3); assert.equal(result.limit, 20)
  assert.ok(Object.isFrozen(result) && Object.isFrozen(result.source) && Object.isFrozen(result.items) && Object.isFrozen(result.items[0]))
  const reordered = Object.fromEntries(Object.entries(source).reverse()) as unknown as TranscriptSource
  assert.equal(sameTranscriptSource(source, reordered), true)
  for (const field of ['job_id', 'video_id', 'generation', 'source_video_sha256', 'transcript_sha256', 'analysis_sha256', 'duration_ms', 'source_fingerprint'] as const) {
    const changed = clone(raw); (changed.source as any)[field] = typeof changed.source[field] === 'number' ? Number(changed.source[field]) + 1 : field.endsWith('_id') ? id(9) : 'e'.repeat(64)
    assert.throws(() => parseTranscriptPage(changed, owner, request(q)), TranscriptResponseError, field)
  }
})

test('transcript strict page protocol rejects wrong query/offset/limit, malformed counts, duplicate/order/range/text and unknown fields', () => {
  const changes: Array<(v: any) => void> = [v => { v.data_kind = 'transcript_context' }, v => { v.schema_version = '2.0' },
    v => { v.q = 'different' }, v => { v.offset = 1 }, v => { v.limit = 50 }, v => { v.total_utterances = 0 }, v => { v.total_utterances = 100001 },
    v => { v.matched_utterances = 31 }, v => { v.next_offset = 20 }, v => { v.next_offset = null }, v => { v.items = [] },
    v => { v.items[1] = clone(v.items[0]) }, v => { v.items[1].start_ms = 500 }, v => { v.items[0].end_ms = 0 },
    v => { v.items[0].start_ms = -1 }, v => { v.items[0].end_ms = source.duration_ms + 1 },
    v => { v.items[0].text = '' }, v => { v.items[0].text = 'x'.repeat(100001) }, v => { v.items[0].private_key = 'bad' },
    v => { v.source.source_fingerprint = 'NOT_HASH' }, v => { v.source.secret = 'bad' }, v => { v.extra = true }]
  for (const change of changes) { const raw = page(); change(raw); assert.throws(() => parseTranscriptPage(raw, owner, request()), TranscriptResponseError) }
  const tooMany = page('', 0, 21); assert.throws(() => parseTranscriptPage(tooMany, owner, request()), TranscriptResponseError)
  const beyond = page('', Number.MAX_SAFE_INTEGER, 0, 25); beyond.next_offset = null
  assert.equal(parseTranscriptPage(beyond, owner, request('', Number.MAX_SAFE_INTEGER)).items.length, 0)
  const empty = page('', 0, 0, 0); assert.equal(parseTranscriptPage(empty, owner, request()).next_offset, null)
})

test('transcript text budget is UTF16 units, shortened pages advance by actual items and no long sentence is skipped', () => {
  const raw = page('', 0, 2); raw.items[0]!.text = '😀'.repeat(50000); raw.items[1]!.text = '字'.repeat(100000)
  const result = parseTranscriptPage(raw, owner, request()); assert.equal(result.next_offset, 2); assert.equal(result.items[0]!.text.length, 100000)
  raw.items.push(utterance(2, '多')); assert.throws(() => parseTranscriptPage(raw, owner, request()), TranscriptResponseError)
})

test('transcript context validates exact selected content, complete source, first/last and ordered adjacent intervals', () => {
  const center = utterance(1), raw = context(center), checked = parseTranscriptContext(raw, owner, source, center)
  assert.equal(checked.previous!.utterance_id, 'u0'); assert.equal(checked.next!.utterance_id, 'u2')
  assert.ok(Object.isFrozen(checked.center))
  assert.equal(parseTranscriptContext({ ...context(utterance(0)), previous: null, next: null }, owner, source, utterance(0)).previous, null)
  for (const change of [(v: any) => { v.center.text = 'changed' }, (v: any) => { v.center.start_ms++ },
    (v: any) => { v.previous.end_ms = center.start_ms + 1 }, (v: any) => { v.next.start_ms = center.end_ms - 1 },
    (v: any) => { v.next.utterance_id = center.utterance_id }, (v: any) => { delete v.previous },
    (v: any) => { v.source.transcript_sha256 = 'e'.repeat(64) }]) {
    const value = context(utterance(1)); change(value); assert.throws(() => parseTranscriptContext(value, owner, source, center), TranscriptResponseError)
  }
})

test('transcript GET URLs preserve q, literal specials and pinned fingerprints while local limits reject unsafe requests', () => {
  const q = ' % _ [.*] ＣＡＣＨＥ ', url = new URL(transcriptPageUrl(owner, request(q)), 'http://local')
  assert.equal(url.pathname, `/api/jobs/${owner.job_id}/transcript`); assert.equal(url.searchParams.get('q'), q)
  assert.equal(url.searchParams.get('source_fingerprint'), source.source_fingerprint)
  assert.equal(new URL(transcriptPageUrl(owner, request('', 0, null)), 'http://local').searchParams.has('source_fingerprint'), false)
  assert.equal(new URL(transcriptContextUrl(owner, source, 'u:1'), 'http://local').searchParams.get('utterance_id'), 'u:1')
  assert.equal(transcriptQueryError('😀'.repeat(100)), ''); assert.ok(transcriptQueryError('😀'.repeat(101)))
  assert.ok(transcriptQueryError('1 2 3 4 5 6 7 8 9')); assert.equal(transcriptQueryError(' \t　 '), '')
  assert.equal(transcriptQueryError('1\u00852\u20033\uFEFF4　5\t6\n7 8'), '')
  assert.ok(transcriptQueryError('1\u00852\u20033\uFEFF4　5\t6\n7 8 9'))
  for (const offset of [-1, 0.5, Number.MAX_SAFE_INTEGER + 1]) assert.throws(() => transcriptPageUrl(owner, request('', offset)), TranscriptResponseError)
})

test('transcript highlighting returns only original text chunks including NFKC expansion, literal HTML and composed Unicode', () => {
  for (const [text, q] of [['ＨＴＭＬ <script>alert(1)</script> & 缓存', 'html 缓存'], ['ﬃabcdef', 'ffi abc'], ['ｅ́ 😀', 'é'], ['[.*] % _', '[.*]']]) {
    const chunks = transcriptHighlights(text, q)
    assert.equal(chunks.map(v => v.text).join(''), text); assert.ok(chunks.every(v => typeof v.hit === 'boolean'))
  }
  assert.deepEqual(transcriptHighlights('ＡＢＣ 缓存', 'abc'), [{ text: 'ＡＢＣ', hit: true }, { text: ' 缓存', hit: false }])
  assert.equal(transcriptTime(3723456), '01:02:03'); assert.equal(transcriptTime(1234, true), '00:01.234')
  const quote = transcriptQuote('真实视频.mp4', utterance(1, '原始\nASR <内容>'))
  assert.match(quote, /真实视频\.mp4/); assert.match(quote, /00:01\.000 → 00:02\.000/); assert.match(quote, /ASR 原句（未经人工校对）/); assert.ok(quote.endsWith('原始\nASR <内容>'))
})

test('transcript workspace does not GET before active visible, then initial browsing pins source without context prefetch', async () => {
  const { workspace: w, calls } = harness()
  w.setAvailability(false, true); w.setAvailability(true, false); assert.equal(calls.length, 0)
  w.setAvailability(true, true); assert.equal(calls.length, 1); assert.equal(w.state.pageLoading, true)
  calls[0]!.response.resolve(page()); await flush(); assert.equal(w.usable(), true); assert.equal(calls.length, 1)
  assert.equal(w.state.requested!.source!.source_fingerprint, source.source_fingerprint)
  w.setAvailability(false, true); assert.equal(w.usable(), false); assert.ok(w.state.page)
  w.setAvailability(true, true); assert.equal(calls.length, 2); assert.ok(calls[1]!.path.includes('source_fingerprint='))
  calls[1]!.response.resolve(page()); await flush(); assert.equal(w.usable(), true); w.dispose()
})

test('transcript search preserves last successful query on error, retries the explicit request and follows actual page sizes/back history', async () => {
  const { workspace: w, calls } = harness(); w.setAvailability(true, true); calls[0]!.response.resolve(page()); await flush()
  const searching = w.search(' 缓存 过期 '); assert.equal(w.state.page!.q, ''); assert.equal(w.usable(), false)
  calls[1]!.response.reject({ code: 'NETWORK_UNAVAILABLE' }); await searching
  assert.equal(w.state.page!.q, ''); assert.equal(w.state.requested!.q, ' 缓存 过期 '); assert.match(w.state.pageError, /上次成功结果/)
  const retry = w.retry(); calls[2]!.response.resolve(page(' 缓存 过期 ')); await retry; assert.equal(w.state.page!.q, ' 缓存 过期 ')
  const next = w.next(); assert.equal(new URL(calls[3]!.path, 'http://local').searchParams.get('offset'), '3')
  calls[3]!.response.reject({ code: 'REQUEST_TIMEOUT' }); await next
  const retryNext = w.retry(); calls[4]!.response.resolve(page(' 缓存 过期 ', 3, 2)); await retryNext; assert.equal(w.canGoBack(), true)
  const previous = w.previous(); calls[5]!.response.resolve(page(' 缓存 过期 ')); await previous; assert.equal(w.state.page!.offset, 0); assert.equal(w.canGoBack(), false)
  assert.ok(calls.slice(1).every(v => new URL(v.path, 'http://local').searchParams.get('source_fingerprint') === source.source_fingerprint)); w.dispose()
})

test('transcript old page response and finally cannot replace a newer query or clear its loading state', async () => {
  const { workspace: w, calls } = harness(); w.setAvailability(true, true)
  const newer = w.search('new'); assert.equal(calls[0]!.signal.aborted, true)
  calls[0]!.response.resolve(page()); await flush(); assert.equal(w.state.pageLoading, true); assert.equal(w.state.page, null)
  calls[1]!.response.resolve(page('new')); await newer; assert.equal(w.state.page!.q, 'new'); w.dispose()
})

test('transcript selected context has independent selection/query/hide lifetimes and old finally never clears a new read', async () => {
  const { workspace: w, calls } = harness(); w.setAvailability(true, true); calls[0]!.response.resolve(page()); await flush()
  const old = w.select('u0'), next = w.select('u1'); assert.equal(calls[1]!.signal.aborted, true)
  calls[1]!.response.resolve(context(utterance(0))); await old; assert.equal(w.state.contextLoading, true); assert.equal(w.state.context, null)
  calls[2]!.response.resolve(context()); await next; assert.equal(w.state.context!.center.utterance_id, 'u1')
  const pending = w.retryContext(); w.setAvailability(true, false); assert.equal(calls[3]!.signal.aborted, true)
  calls[3]!.response.resolve(context()); await pending; assert.equal(w.canUseSelection(), false); assert.equal(w.state.selected!.utterance_id, 'u1')
  w.setAvailability(true, true); calls[4]!.response.resolve(page()); await flush(); assert.equal(calls.length, 6)
  calls[5]!.response.resolve(context()); await flush(); assert.equal(w.state.context!.center.utterance_id, 'u1')
  const changedQuery = w.search('changed'); assert.equal(w.state.selected, null); assert.equal(w.state.context, null)
  calls[6]!.response.resolve(page('changed')); await changedQuery; w.dispose()
})

test('transcript source-changed responses keep old results read-only and require explicit unpinned source recheck', async () => {
  const { workspace: w, calls } = harness(); w.setAvailability(true, true); calls[0]!.response.resolve(page()); await flush()
  const changed = w.next(); calls[1]!.response.reject({ code: 'TRANSCRIPT_SOURCE_CHANGED' }); await changed
  assert.equal(w.state.sourceStale, true); assert.equal(w.usable(), false); assert.equal(w.state.page!.offset, 0)
  const reload = w.reloadSource(); assert.equal(new URL(calls[2]!.path, 'http://local').searchParams.has('source_fingerprint'), false)
  const newSource = { ...source, transcript_sha256: 'e'.repeat(64), source_fingerprint: 'f'.repeat(64) }
  calls[2]!.response.resolve(page('', 0, 3, 25, newSource)); await reload; assert.equal(w.usable(), true)
  assert.equal(w.state.page!.source.source_fingerprint, newSource.source_fingerprint); w.dispose()
})

test('transcript clipboard failure is retryable and hidden/selection/query/dispose late acknowledgements cannot claim another quote', async () => {
  for (const reason of ['hidden', 'selection', 'query', 'dispose']) {
    const { workspace: w, calls } = harness(); w.setAvailability(true, true); calls[0]!.response.resolve(page()); await flush()
    const selecting = w.select('u0'); calls[1]!.response.resolve(context(utterance(0))); await selecting
    await w.copySelected('视频.mp4', async () => { throw new Error('denied') }); assert.match(w.state.copyError, /重试/)
    const copied = deferred<void>(), pending = w.copySelected('视频.mp4', () => copied.promise)
    if (reason === 'hidden') w.setAvailability(true, false)
    if (reason === 'selection') { const selected = w.select('u1'); calls.at(-1)!.response.resolve(context()); await selected }
    if (reason === 'query') { const changed = w.search('new'); calls.at(-1)!.response.resolve(page('new')); await changed }
    if (reason === 'dispose') w.dispose()
    copied.resolve(); await pending; assert.equal(w.state.copyNotice, '', reason); assert.equal(w.state.copyBusy, false); w.dispose()
  }
})

test('transcript old clipboard finally cannot clear a new quote copy and only selected sentence is written', async () => {
  const { workspace: w, calls } = harness(); w.setAvailability(true, true); calls[0]!.response.resolve(page()); await flush()
  const first = w.select('u0'); calls[1]!.response.resolve(context(utterance(0))); await first
  const old = deferred<void>(), oldCopy = w.copySelected('视频.mp4', () => old.promise)
  const second = w.select('u1'); calls[2]!.response.resolve(context()); await second
  const fresh = deferred<void>(); let text = ''; const freshCopy = w.copySelected('视频.mp4', value => { text = value; return fresh.promise })
  old.resolve(); await oldCopy; assert.equal(w.state.copyBusy, true); assert.equal(w.state.copyNotice, '')
  fresh.resolve(); await freshCopy; assert.match(w.state.copyNotice, /已复制所选原句/); assert.ok(text.endsWith(utterance(1).text)); assert.ok(!text.includes(utterance(0).text)); w.dispose()
})
