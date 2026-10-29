import test from 'node:test'
import assert from 'node:assert/strict'
import { SnapshotError, SnapshotStream, isAnalysisTerminal, isExportTerminal, newestSnapshot, parseAnalysisSnapshot, parseExportSnapshot, type SnapshotEventSource, type StreamClock } from '../snapshotStream.ts'

const id = (value: number) => `00000000-0000-4000-8000-${String(value).padStart(12, '0')}`
function job(value = 1, version = 1, status = 'RUNNING') {
  return { id: id(value), video_id: id(80), generation: 1, status, state_version: version, created_at: '2026-10-25T00:00:00Z',
    stages: ['MEDIA', 'ASR', 'SEGMENT', 'TIMELINE'].map((stage_type, index) => ({ id: id(100 + index), stage_type, status: index === 0 ? 'RUNNING' : 'BLOCKED', attempt: 0, error_code: null })),
    budget: { max_text_calls: 12, text_calls_reserved: 0, max_asr_submissions: 1, asr_calls_reserved: 0 },
    actions: { can_cancel: true, can_retry: false, retry_block_reason: null, max_attempts: 3 } }
}
function batch(version = 1, status = 'RUNNING') {
  return { id: id(10), job_id: id(1), video_id: id(80), approved_revision: 0, snapshot_hash: 'a'.repeat(64), profile: 'h264-aac-v1',
    status, state_version: version, created_at: '2026-10-25T00:00:00Z', updated_at: '2026-10-25T00:00:00Z',
    clips: [{ id: id(20), segment_id: 's:001', product_id: 'p.001', product_name: '面膜', start_ms: 0, end_ms: 1000,
      stage_id: id(120), status: 'RUNNING', attempt: 1, error_code: null, size_bytes: null, content_url: null, download_url: null }] }
}
test('topic export snapshots require a title with both product fields null and preserve commerce validation', () => {
  const topic = { ...batch(), clips: [{ ...batch().clips[0]!, product_id: null, product_name: null, title: '缓存失效与随机 TTL' }] }
  assert.equal(parseExportSnapshot(topic, id(10), id(1)).clips[0]!.title, '缓存失效与随机 TTL')
  for (const update of [{ product_id: 'p.001' }, { product_name: '缓存' }, { product_id: undefined }, { product_name: undefined }, { title: '' }, { title: 'x'.repeat(121) }, { title: undefined }]) {
    assert.throws(() => parseExportSnapshot({ ...topic, clips: [{ ...topic.clips[0], ...update }] }, id(10), id(1)), SnapshotError)
  }
  assert.equal(parseExportSnapshot(batch(), id(10), id(1)).clips[0]!.product_name, '面膜')
  assert.throws(() => parseExportSnapshot({ ...batch(), clips: [{ ...batch().clips[0], title: '混合类型' }] }, id(10)), SnapshotError)
  assert.throws(() => parseExportSnapshot({ ...topic, clips: [...topic.clips, { ...batch().clips[0], id: id(21), segment_id: 's:002' }] }, id(10)), SnapshotError)
})
class Clock implements StreamClock {
  now = 0; next = 0; timers = new Map<number, { at: number; callback: () => void }>()
  setTimeout(callback: () => void, delay: number) { const key = ++this.next; this.timers.set(key, { at: this.now + delay, callback }); return key }
  clearTimeout(handle: unknown) { this.timers.delete(handle as number) }
  async advance(milliseconds: number) {
    const end = this.now + milliseconds
    for (;;) {
      const next = [...this.timers.entries()].filter(([, timer]) => timer.at <= end).sort((a, b) => a[1].at - b[1].at)[0]
      if (!next) break
      this.now = next[1].at; this.timers.delete(next[0]); next[1].callback(); await flush()
    }
    this.now = end; await flush()
  }
}
class Source implements SnapshotEventSource {
  closed = false; listeners = new Map<string, Set<EventListener>>()
  addEventListener(type: string, listener: EventListener) { if (!this.listeners.has(type)) this.listeners.set(type, new Set()); this.listeners.get(type)!.add(listener) }
  removeEventListener(type: string, listener: EventListener) { this.listeners.get(type)?.delete(listener) }
  close() { this.closed = true }
  event(type: string, data?: unknown, eventId?: string) {
    const event = type === 'snapshot' ? new MessageEvent(type, { data: typeof data === 'string' ? data : JSON.stringify(data), lastEventId: eventId ?? `job:${(data as ReturnType<typeof job>).id}:${(data as ReturnType<typeof job>).state_version}` }) : new Event(type)
    for (const listener of [...(this.listeners.get(type) ?? [])]) listener(event)
  }
}
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve() }
function setup(fetchSnapshot: (id: string, signal: AbortSignal) => Promise<unknown> = async resource => job(Number(resource.slice(-12)))) {
  const clock = new Clock(), sources: Source[] = [], urls: string[] = [], snapshots: ReturnType<typeof job>[] = [], polls: AbortSignal[] = []
  const stream = new SnapshotStream({ kind: 'job', clock, parse: parseAnalysisSnapshot, terminal: isAnalysisTerminal,
    eventSource: url => { const source = new Source(); sources.push(source); urls.push(url); return source },
    fetchSnapshot: (resource, signal) => { polls.push(signal); return fetchSnapshot(resource, signal) }, onSnapshot: snapshot => snapshots.push(snapshot) })
  return { clock, sources, urls, snapshots, polls, stream }
}

test('strict snapshots accept real revision zero and stable product identifiers, reject foreign or malformed state', () => {
  assert.equal(parseAnalysisSnapshot(job(), id(1)).id, id(1))
  const zeroBudget = job(); zeroBudget.budget.max_text_calls = 0; assert.ok(parseAnalysisSnapshot(zeroBudget, id(1)))
  assert.equal(parseExportSnapshot(batch(), id(10), id(1)).approved_revision, 0)
  for (const invalid of [{ ...job(), id: id(2) }, { ...job(), state_version: Number.MAX_SAFE_INTEGER + 1 }, { ...job(), stages: [] }, { ...job(), actions: null }, { ...job(), status: 'mystery' }]) {
    assert.throws(() => parseAnalysisSnapshot(invalid, id(1)), SnapshotError)
  }
  assert.throws(() => parseExportSnapshot(batch(), id(10), id(2)), SnapshotError)
  const foreignUrl = batch(); foreignUrl.clips[0]!.status = 'SUCCEEDED'; foreignUrl.clips[0]!.content_url = 'https://other.example/video' as never
  assert.throws(() => parseExportSnapshot(foreignUrl, id(10)), SnapshotError)
})

test('only a valid snapshot establishes live state; versions never regress and equal-version actions remain fresh', async () => {
  const { stream, sources, snapshots, polls, clock, urls } = setup()
  stream.select(id(1), job()); assert.equal(urls[0], `/api/jobs/${id(1)}/events`)
  sources[0]!.event('open'); assert.equal(stream.mode, 'connecting')
  sources[0]!.event('snapshot', job(1, 3)); assert.equal(stream.mode, 'live')
  sources[0]!.event('snapshot', job(1, 2)); assert.equal(stream.current!.state_version, 3)
  const updated = job(1, 3); updated.actions.max_attempts = 5; sources[0]!.event('snapshot', updated)
  assert.equal(stream.current!.actions.max_attempts, 5)
  stream.acceptHttp(job(1, 1)); assert.equal(stream.current!.state_version, 3)
  await clock.advance(60_000); assert.equal(polls.length, 0); assert.equal(sources.length, 1)
  assert.deepEqual(snapshots.map(value => value.state_version), [3, 3])
  stream.dispose(); assert.equal(clock.timers.size, 0)
})

test('disconnect closes the native reconnecting source, polls during backoff, and stops polling after recovery', async () => {
  const { stream, sources, polls, clock } = setup(async () => job(1, 2))
  stream.select(id(1), job()); sources[0]!.event('snapshot', job())
  sources[0]!.event('error'); await flush()
  assert.ok(sources[0]!.closed); assert.equal(stream.mode, 'reconnecting'); assert.equal(polls.length, 1)
  await clock.advance(999); assert.equal(sources.length, 1)
  await clock.advance(1); assert.equal(sources.length, 2)
  sources[1]!.event('open'); assert.equal(stream.mode, 'reconnecting')
  await clock.advance(2000); assert.equal(polls.length, 2)
  sources[1]!.event('snapshot', job(1, 4)); assert.equal(stream.mode, 'live')
  await clock.advance(20_000); assert.equal(polls.length, 2)
  sources[1]!.event('error'); await flush(); await clock.advance(1000)
  assert.equal(sources.length, 3, 'A valid recovery resets the reconnect delay')
  stream.dispose()
})

test('repeated failures back off to a 15 second ceiling without spawning parallel fallback requests', async () => {
  let active = 0, maxActive = 0
  const { stream, sources, clock } = setup(async () => { active++; maxActive = Math.max(maxActive, active); await flush(); active--; throw new Error('offline') })
  stream.select(id(1), job())
  for (const delay of [1000, 2000, 4000, 8000, 15_000, 15_000]) {
    const sourceCount = sources.length; sources.at(-1)!.event('error'); await flush()
    await clock.advance(delay - 1); assert.equal(sources.length, sourceCount)
    await clock.advance(1); assert.equal(sources.length, sourceCount + 1)
  }
  assert.equal(maxActive, 1); stream.dispose(); assert.equal(clock.timers.size, 0)
})

test('malformed JSON and mismatched event ids keep prior state, close that stream, and allow a clean recovery', async () => {
  const { stream, sources, clock } = setup(async () => { throw new Error('offline') })
  stream.select(id(1), job(1, 3)); sources[0]!.event('snapshot', '{invalid')
  assert.ok(sources[0]!.closed); assert.equal(stream.current!.state_version, 3)
  await flush(); await clock.advance(1000)
  sources[1]!.event('snapshot', job(1, 4), `job:${id(2)}:4`)
  assert.ok(sources[1]!.closed); assert.equal(stream.current!.state_version, 3)
  await flush(); await clock.advance(2000)
  sources[2]!.event('snapshot', job(1, 5)); assert.equal(stream.current!.state_version, 5); assert.equal(stream.mode, 'live')
  stream.dispose()
})

test('a connected source that never sends usable state falls back instead of claiming a live connection', async () => {
  const { stream, sources, clock, polls } = setup()
  stream.select(id(1), job()); sources[0]!.event('open')
  await clock.advance(10_000); assert.ok(sources[0]!.closed); assert.equal(stream.mode, 'reconnecting'); assert.equal(polls.length, 1)
  stream.dispose()
})

test('switching resources ignores both captured old stream callbacks and old HTTP responses', async () => {
  let resolveOld: (value: unknown) => void = () => {}
  const { stream, sources, snapshots, polls, clock } = setup(() => new Promise(resolve => { resolveOld = resolve }))
  stream.select(id(1), job()); const lateCallback = [...sources[0]!.listeners.get('snapshot')!][0]!
  sources[0]!.event('error'); assert.equal(polls.length, 1)
  stream.select(id(2), job(2)); assert.ok(polls[0]!.aborted)
  lateCallback(new MessageEvent('snapshot', { data: JSON.stringify(job(1, 100)), lastEventId: `job:${id(1)}:100` }))
  resolveOld(job(1, 99)); await flush()
  sources[1]!.event('snapshot', job(2, 3)); assert.equal(stream.current!.id, id(2)); assert.equal(stream.current!.state_version, 3)
  assert.ok(snapshots.every(value => value.id === id(2))); stream.dispose(); assert.equal(clock.timers.size, 0)
})

test('terminal state closes all transports; an explicit newer retry snapshot starts exactly one subscription', async () => {
  const { stream, sources, polls, clock } = setup()
  stream.select(id(1), job()); sources[0]!.event('snapshot', job(1, 5, 'FAILED'))
  assert.ok(sources[0]!.closed); assert.equal(stream.mode, 'settled'); assert.equal(clock.timers.size, 0)
  stream.acceptHttp(job(1, 4)); stream.acceptHttp(job(1, 5, 'FAILED'))
  await clock.advance(30_000); assert.equal(sources.length, 1); assert.equal(polls.length, 0)
  stream.acceptHttp(job(1, 6, 'QUEUED')); assert.equal(sources.length, 2); assert.equal(stream.mode, 'connecting')
  stream.acceptHttp(job(1, 6, 'QUEUED')); assert.equal(sources.length, 2)
  sources[1]!.event('snapshot', job(1, 7)); assert.equal(stream.mode, 'live'); stream.dispose()
})

test('without EventSource, serial polling keeps working and stops at completion', async () => {
  const clock = new Clock(); let calls = 0
  const stream = new SnapshotStream({ kind: 'export', clock, parse: parseExportSnapshot, terminal: isExportTerminal, eventSource: null,
    fetchSnapshot: async () => batch(++calls, calls >= 2 ? 'FAILED' : 'RUNNING'), onSnapshot() {} })
  stream.select(id(10), batch()); await flush(); assert.equal(stream.mode, 'polling'); assert.equal(calls, 1)
  await clock.advance(3000); assert.equal(calls, 2); assert.equal(stream.mode, 'settled')
  await clock.advance(20_000); assert.equal(calls, 2); assert.equal(clock.timers.size, 0); stream.dispose()
})

test('unmount aborts fallback IO and removes timers and listeners; late results cannot update display', async () => {
  let resolve: (value: unknown) => void = () => {}
  const { stream, sources, polls, clock, snapshots } = setup(() => new Promise(done => { resolve = done }))
  stream.select(id(1), job()); sources[0]!.event('error'); stream.dispose()
  assert.ok(polls[0]!.aborted); assert.ok(sources[0]!.closed); assert.equal(clock.timers.size, 0)
  assert.equal([...sources[0]!.listeners.values()].reduce((count, listeners) => count + listeners.size, 0), 0)
  resolve(job(1, 40)); await flush(); await clock.advance(60_000)
  assert.equal(snapshots.length, 0); assert.equal(sources.length, 1); assert.equal(stream.mode, 'idle')
})

test('HTTP and stream merge preserve terminal status at equal version while allowing same-version policy changes', () => {
  const terminal = job(1, 3, 'FAILED'), invalidEqual = job(1, 3, 'RUNNING')
  assert.equal(newestSnapshot(terminal, invalidEqual), terminal)
  const changedPolicy = { ...terminal, actions: { ...terminal.actions, max_attempts: 7 } }
  assert.equal(newestSnapshot(terminal, changedPolicy).actions.max_attempts, 7)
})
