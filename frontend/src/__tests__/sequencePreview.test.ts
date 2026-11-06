import test from 'node:test'
import assert from 'node:assert/strict'
import { SequencePreview, type SequencePreviewEnd, type SequencePreviewPart, type SequencePreviewPlayer,
  type SequencePreviewScheduler, type SequencePreviewState } from '../sequencePreview.ts'

class Clock implements SequencePreviewScheduler {
  now = 0
  next = 1
  tasks = new Map<number, { at: number; callback: () => void }>()
  recorded: (() => void)[] = []
  elapsed: (milliseconds: number) => void = () => {}
  setTimeout(callback: () => void, delayMs: number) {
    assert.ok(delayMs >= 4 && delayMs <= 100, 'boundary wakeups must stay finite and bounded')
    const id = this.next++
    this.tasks.set(id, { at: this.now + delayMs, callback }); this.recorded.push(callback)
    return id
  }
  clearTimeout(handle: unknown) { this.tasks.delete(handle as number) }
  advance(milliseconds: number) {
    const until = this.now + milliseconds
    let count = 0
    while (true) {
      const next = [...this.tasks].sort((a, b) => a[1].at - b[1].at)[0]
      if (!next || next[1].at > until) break
      assert.ok(++count < 1000, 'a frozen media clock must not create an infinite timer loop')
      this.elapsed(next[1].at - this.now); this.now = next[1].at
      this.tasks.delete(next[0]); next[1].callback()
    }
    this.elapsed(until - this.now); this.now = until
  }
}
class Player implements SequencePreviewPlayer {
  position = 0
  paused = true
  seeking = false
  ended = false
  playbackRate = 1
  asynchronousSeek = false
  refuseSeek = false
  plays = 0
  pauses = 0
  seeks: number[] = []
  outcome: () => Promise<void> = () => Promise.resolve()
  get currentTime() { return this.position }
  set currentTime(value: number) {
    if (this.refuseSeek) throw new Error('Unsupported seek')
    this.position = value; this.seeking = this.asynchronousSeek; this.ended = false; this.seeks.push(value)
  }
  play() { this.plays++; this.paused = false; return this.outcome() }
  pause() { this.pauses++; this.paused = true }
  advance(milliseconds: number) { if (!this.paused && !this.seeking) this.position += milliseconds / 1000 * this.playbackRate }
}
function deferred() {
  let resolve!: () => void, reject!: () => void
  const promise = new Promise<void>((yes, no) => { resolve = yes; reject = () => no(new Error('Playback rejected')) })
  return { promise, resolve, reject }
}
function fixture() {
  const player = new Player(), clock = new Clock()
  const events: { state: SequencePreviewState | null; reason?: SequencePreviewEnd }[] = []
  const preview = new SequencePreview((state, reason) => events.push({ state, ...(reason ? { reason } : {}) }), clock)
  clock.elapsed = milliseconds => player.advance(milliseconds)
  return { player, clock, events, preview }
}
const plan = (parts: readonly SequencePreviewPart[] = [{ start_ms: 1000, end_ms: 1150 }, { start_ms: 2000, end_ms: 2100 }], id = 'output-one') => ({ id, parts })
const lastState = (events: ReturnType<typeof fixture>['events']) => events.findLast(event => event.state)?.state
const flush = async () => { await Promise.resolve(); await Promise.resolve() }

test('sparse media events still play only the ordered parts and clamp the final boundary', async () => {
  const { player, clock, preview, events } = fixture()
  assert.equal(await preview.start(player, plan()), 'playing')
  clock.advance(150)
  assert.equal(player.currentTime, 2)
  assert.equal(lastState(events)?.part_index, 1)
  assert.equal(lastState(events)?.played_ms, 150)
  assert.equal(lastState(events)?.total_ms, 250)
  assert.equal(player.plays, 1, 'transitions continue the native seek without another asynchronous play request')
  clock.advance(100)
  assert.equal(player.currentTime, 2.1); assert.equal(player.paused, true)
  assert.equal(events.at(-1)?.reason, 'finished'); assert.equal(clock.tasks.size, 0)
  assert.deepEqual(player.seeks, [1, 2, 2.1], 'the surrounding gap must never be played as a retained part')
})

test('input is copied and logical progress counts retained duration rather than the surrounding source span', async () => {
  const { player, preview, events } = fixture()
  const parts = [{ start_ms: 8572, end_ms: 31315 }, { start_ms: 178900, end_ms: 188442 }]
  await preview.start(player, plan(parts))
  parts[0]!.end_ms = 100000; parts[1]!.start_ms = 32000
  player.position = 31.315; preview.timeUpdate(player)
  assert.equal(player.currentTime, 178.9)
  assert.equal(lastState(events)?.start_ms, 178900); assert.equal(lastState(events)?.played_ms, 22743)
  assert.equal(lastState(events)?.total_ms, 32285)
  player.position = 183.9; preview.timeUpdate(player)
  assert.equal(lastState(events)?.played_ms, 27743)
  preview.cancel()
})

test('invalid, overlapping, reversed, oversized and unsafe plans never request playback', async () => {
  const { player, preview, events, clock } = fixture()
  const invalid: SequencePreviewPart[][] = [[], Array(13).fill({ start_ms: 0, end_ms: 1 }),
    [{ start_ms: -1, end_ms: 1 }], [{ start_ms: 0, end_ms: 0 }], [{ start_ms: .5, end_ms: 1 }],
    [{ start_ms: Number.NaN, end_ms: 1 }], [{ start_ms: 0, end_ms: Number.POSITIVE_INFINITY }],
    [{ start_ms: 0, end_ms: Number.MAX_SAFE_INTEGER + 1 }],
    [{ start_ms: 10, end_ms: 20 }, { start_ms: 19, end_ms: 30 }],
    [{ start_ms: 30, end_ms: 40 }, { start_ms: 10, end_ms: 20 }]]
  for (const parts of invalid) assert.equal(await preview.start(player, plan(parts)), 'failed')
  assert.equal(await preview.start(player, plan([{ start_ms: 0, end_ms: 1 }], '   ')), 'failed')
  assert.equal(player.plays, 0); assert.equal(clock.tasks.size, 0); assert.equal(events.at(-1)?.reason, 'failed')
  assert.equal(await preview.start(player, plan([{ start_ms: 0, end_ms: 1 }, { start_ms: 1, end_ms: 2 }])), 'playing', 'touching parts are valid')
  preview.cancel()
})

test('a stalled actual media clock never advances by wall time and stops its own timer until media resumes', async () => {
  const { player, clock, preview, events } = fixture()
  clock.elapsed = () => {}
  await preview.start(player, plan())
  clock.advance(100)
  assert.equal(player.currentTime, 1); assert.equal(clock.tasks.size, 0)
  const callbacks = clock.recorded.length
  clock.advance(5000)
  assert.equal(clock.recorded.length, callbacks); assert.equal(lastState(events)?.part_index, 0)
  clock.elapsed = milliseconds => player.advance(milliseconds)
  preview.timeUpdate(player) // The component also forwards native `playing` after buffering.
  clock.advance(250)
  assert.equal(events.at(-1)?.reason, 'finished'); assert.equal(player.currentTime, 2.1)
})

test('actual playback rate changes reschedule the end boundary without changing logical progress', async () => {
  const { player, clock, preview, events } = fixture()
  await preview.start(player, plan([{ start_ms: 0, end_ms: 1000 }]))
  clock.advance(200)
  player.playbackRate = 2; preview.rateChange(player)
  clock.advance(200)
  assert.equal(lastState(events)?.played_ms, 600)
  player.playbackRate = .5; preview.rateChange(player)
  clock.advance(800)
  assert.equal(events.at(-1)?.reason, 'finished'); assert.equal(player.currentTime, 1)
})

test('an automatic seek waits for actual seeking to end and ignores a stale seeked event', async () => {
  const { player, clock, preview, events } = fixture()
  await preview.start(player, plan())
  player.asynchronousSeek = true
  clock.advance(150)
  assert.equal(player.seeking, true); assert.equal(lastState(events)?.part_index, 1); assert.equal(clock.tasks.size, 0)
  preview.seeked(player) // Old seeked delivered while the actual new seek is still running.
  assert.equal(clock.tasks.size, 0)
  preview.seeking(player) // The engine's own target is allowed.
  player.seeking = false; preview.seeked(player)
  assert.equal(clock.tasks.size, 1); assert.equal(player.plays, 1)
  clock.advance(100)
  assert.equal(events.at(-1)?.reason, 'finished')
})

test('manual pause during the automatic transition cancels and cannot be revived by seeked or old events', async () => {
  const { player, clock, preview, events } = fixture()
  await preview.start(player, plan())
  player.asynchronousSeek = true
  clock.advance(150)
  player.pause(); preview.pause(player)
  assert.equal(events.at(-1)?.reason, 'cancelled'); assert.equal(clock.tasks.size, 0)
  player.seeking = false; preview.seeked(player); preview.timeUpdate(player); preview.rateChange(player)
  clock.advance(1000)
  assert.equal(player.paused, true); assert.equal(player.plays, 1); assert.equal(player.currentTime, 2)
})

test('a manual seek within the current part or away from an automatic target exits the sequence', async () => {
  for (const duringTransition of [false, true]) {
    const { player, clock, preview, events } = fixture()
    await preview.start(player, plan())
    if (duringTransition) { player.asynchronousSeek = true; clock.advance(150) }
    player.seeking = true; player.position = duringTransition ? 2.05 : 1.05
    preview.seeking(player)
    assert.equal(events.at(-1)?.reason, 'cancelled'); assert.equal(player.paused, true); assert.equal(clock.tasks.size, 0)
    player.seeking = false; preview.seeked(player)
    assert.equal(player.plays, 1)
  }
})

test('manual pause at the boundary wins over a delayed timeupdate and prevents an automatic next part', async () => {
  const { player, preview, events, clock } = fixture()
  await preview.start(player, plan())
  player.position = 1.15; player.pause()
  preview.timeUpdate(player) // pause event has not yet been dispatched.
  assert.equal(events.at(-1)?.reason, 'cancelled'); assert.equal(player.currentTime, 1.15)
  assert.equal(clock.tasks.size, 0); assert.equal(player.plays, 1)
})

test('cancelling a pending play settles the start and a late browser completion is paused', async () => {
  for (const reject of [false, true]) {
    const { player, preview, clock, events } = fixture(), delayed = deferred()
    player.outcome = () => delayed.promise
    const result = preview.start(player, plan())
    player.pause(); preview.pause(player)
    assert.equal(await result, 'cancelled'); assert.equal(clock.tasks.size, 0)
    player.paused = false
    if (reject) delayed.reject(); else delayed.resolve()
    await flush()
    assert.equal(player.paused, true); assert.equal(events.at(-1)?.reason, 'cancelled')
  }
})

test('late play results and media events cannot pause or clear a newer sequence on the same element', async () => {
  for (const reject of [false, true]) {
    const { player, preview, events } = fixture(), oldPlay = deferred(), newPlay = deferred()
    player.outcome = () => oldPlay.promise
    const old = preview.start(player, plan())
    player.outcome = () => newPlay.promise
    const next = preview.start(player, plan([{ start_ms: 5000, end_ms: 6000 }], 'new-output'))
    const pauses = player.pauses
    if (reject) oldPlay.reject(); else oldPlay.resolve()
    await flush()
    assert.equal(await old, 'cancelled'); assert.equal(player.pauses, pauses); assert.equal(player.paused, false)
    preview.pause(player); preview.ended(player); preview.seeking(player); preview.seeked(player); preview.timeUpdate(player)
    assert.equal(lastState(events)?.id, 'new-output'); assert.equal(player.currentTime, 5)
    newPlay.resolve(); assert.equal(await next, 'playing')
    preview.cancel()
  }
})

test('an old cleared timer callback cannot move the new sequence or rearm itself', async () => {
  const { player, clock, preview, events } = fixture()
  await preview.start(player, plan())
  const oldTimer = clock.recorded.at(-1)!
  await preview.start(player, plan([{ start_ms: 5000, end_ms: 6000 }], 'new-output'))
  const timers = clock.tasks.size, pauses = player.pauses
  oldTimer()
  assert.equal(clock.tasks.size, timers); assert.equal(player.pauses, pauses); assert.equal(player.currentTime, 5)
  assert.equal(lastState(events)?.id, 'new-output')
  preview.cancel(); oldTimer()
  assert.equal(clock.tasks.size, 0)
})

test('old seeked and pause events cannot settle or stop a new actual seek on the same element', async () => {
  const { player, clock, preview, events } = fixture()
  await preview.start(player, plan())
  player.asynchronousSeek = true
  assert.equal(await preview.start(player, plan([{ start_ms: 5000, end_ms: 6000 }], 'new-seek')), 'playing')
  preview.seeked(player); preview.pause(player); preview.seeking(player); preview.timeUpdate(player)
  assert.equal(player.seeking, true); assert.equal(player.paused, false); assert.equal(clock.tasks.size, 0)
  assert.equal(lastState(events)?.id, 'new-seek')
  player.seeking = false; preview.seeked(player)
  assert.equal(clock.tasks.size, 1)
  preview.cancel()
})

test('switching players isolates old play failures and events from the new media element', async () => {
  const { player, preview, clock, events } = fixture(), other = new Player(), delayed = deferred()
  player.outcome = () => delayed.promise
  const old = preview.start(player, plan())
  await preview.start(other, plan([{ start_ms: 5000, end_ms: 6000 }], 'other'))
  assert.equal(await old, 'cancelled')
  player.paused = false; delayed.resolve(); await flush()
  assert.equal(player.paused, true); assert.equal(other.paused, false)
  player.position = 100; player.ended = true; player.seeking = true
  preview.ended(player); preview.pause(player); preview.seeking(player); preview.seeked(player); preview.timeUpdate(player)
  assert.equal(lastState(events)?.id, 'other'); assert.equal(other.currentTime, 5)
  preview.cancel(); assert.equal(clock.tasks.size, 0)
})

test('current play rejection, refused seek and invalid playback observations fail without timers or revival', async () => {
  const { player, preview, clock, events } = fixture()
  player.outcome = () => Promise.reject(new Error('Autoplay blocked'))
  assert.equal(await preview.start(player, plan()), 'failed')
  assert.equal(events.at(-1)?.reason, 'failed'); assert.equal(player.paused, true)
  player.refuseSeek = true
  const plays = player.plays
  assert.equal(await preview.start(player, plan()), 'failed'); assert.equal(player.plays, plays)
  player.refuseSeek = false; player.outcome = () => Promise.resolve()
  await preview.start(player, plan())
  player.position = Number.NaN; preview.timeUpdate(player)
  assert.equal(events.at(-1)?.reason, 'failed'); assert.equal(clock.tasks.size, 0)
  for (const rate of [0, -1, Number.NaN, Number.POSITIVE_INFINITY]) {
    player.playbackRate = rate
    assert.equal(await preview.start(player, plan()), 'failed')
  }
})

test('natural media end finishes only a completed final part, and fails an incomplete source', async () => {
  for (const complete of [false, true]) {
    const { player, preview, events, clock } = fixture()
    await preview.start(player, plan([{ start_ms: 0, end_ms: 1000 }]))
    player.position = complete ? 1.1 : .9; player.ended = true; player.paused = true
    preview.ended(player)
    assert.equal(events.at(-1)?.reason, complete ? 'finished' : 'failed')
    assert.equal(player.currentTime, complete ? 1 : .9); assert.equal(clock.tasks.size, 0)
  }
})
