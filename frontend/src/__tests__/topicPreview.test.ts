import test from 'node:test'
import assert from 'node:assert/strict'
import { TopicPreview, type PreviewPlayer, type TopicPreviewRange } from '../topicPreview.ts'

const range = (id = 'first', start_ms = 5000, end_ms = 7000): TopicPreviewRange => ({ id, start_ms, end_ms })
function deferred() {
  let resolve!: () => void, reject!: () => void
  const promise = new Promise<void>((yes, no) => { resolve = yes; reject = () => no(new Error('Browser rejected playback')) })
  return { promise, resolve, reject }
}
class Player implements PreviewPlayer {
  currentTime = 0
  paused = true
  pauseCalls = 0
  plays = 0
  outcome: () => Promise<void> = () => Promise.resolve()
  play() { this.plays++; this.paused = false; return this.outcome() }
  pause() { this.pauseCalls++; this.paused = true }
}

test('segment preview starts at its edited boundary and pauses at its end without changing the input', async () => {
  const player = new Player(), changes: (TopicPreviewRange | null)[] = [], preview = new TopicPreview(value => changes.push(value))
  const input = range()
  assert.equal(await preview.start(player, input), 'playing')
  assert.equal(player.currentTime, 5)
  preview.seeking(player); preview.seeked(player)
  player.currentTime = 6.9; preview.timeUpdate(player)
  assert.equal(player.paused, false)
  player.currentTime = 7.13; preview.timeUpdate(player)
  assert.equal(player.paused, true); assert.equal(player.currentTime, 7)
  assert.deepEqual(changes, [null, input, null])
  assert.deepEqual(input, range())
})

test('manual seeking ends bounded playback, including seeks that stay inside the segment', async () => {
  const player = new Player(), changes: (TopicPreviewRange | null)[] = [], preview = new TopicPreview(value => changes.push(value))
  await preview.start(player, range())
  preview.seeking(player); preview.seeked(player)
  player.currentTime = 6; preview.seeking(player)
  assert.equal(player.paused, true); assert.equal(changes.at(-1), null)
  player.currentTime = 8; preview.timeUpdate(player)
  assert.equal(player.currentTime, 8, 'a cancelled preview must not keep moving the native playhead')
})

test('cancelling during a pending browser play prevents a late completion from reviving that preview', async () => {
  const player = new Player(), delayed = deferred(), preview = new TopicPreview()
  player.outcome = () => delayed.promise
  const result = preview.start(player, range())
  preview.cancel()
  player.paused = false; delayed.resolve()
  assert.equal(await result, 'cancelled'); assert.equal(player.paused, true)
})

test('a stale play completion or rejection cannot stop or clear a newer preview on the same element', async () => {
  for (const failure of [false, true]) {
    const player = new Player(), first = deferred(), second = deferred(), changes: (TopicPreviewRange | null)[] = []
    const preview = new TopicPreview(value => changes.push(value))
    player.outcome = () => first.promise
    const old = preview.start(player, range())
    player.outcome = () => second.promise
    const current = preview.start(player, range('second', 10000, 12000))
    const pauses = player.pauseCalls
    if (failure) first.reject(); else first.resolve()
    assert.equal(await old, 'cancelled')
    assert.equal(player.pauseCalls, pauses); assert.equal(changes.at(-1)?.id, 'second')
    assert.equal(player.currentTime, 10)
    preview.timeUpdate(player)
    assert.equal(changes.at(-1)?.id, 'second', 'a queued old media event uses the current range and playhead')
    second.resolve(); assert.equal(await current, 'playing')
    player.currentTime = 12.1; preview.timeUpdate(player)
    assert.equal(player.paused, true); assert.equal(changes.at(-1), null)
  }
})

test('a failed current play clears its range and can be retried; invalid ranges never call play', async () => {
  const player = new Player(), changes: (TopicPreviewRange | null)[] = [], preview = new TopicPreview(value => changes.push(value))
  player.outcome = () => Promise.reject(new Error('Not allowed'))
  assert.equal(await preview.start(player, range()), 'failed')
  assert.equal(player.paused, true); assert.equal(changes.at(-1), null)
  const plays = player.plays
  for (const invalid of [range('bad', Number.NaN), range('bad', -1), range('bad', 7000, 7000), range('bad', 1.5)]) {
    assert.equal(await preview.start(player, invalid), 'failed')
  }
  assert.equal(player.plays, plays)
  player.outcome = () => Promise.resolve()
  assert.equal(await preview.start(player, range()), 'playing')
})

test('switching media or cancelling for boundary edits keeps late playback isolated from the new player', async () => {
  const oldPlayer = new Player(), nextPlayer = new Player(), delayed = deferred(), preview = new TopicPreview()
  oldPlayer.outcome = () => delayed.promise
  const old = preview.start(oldPlayer, range())
  preview.cancel()
  assert.equal(await preview.start(nextPlayer, range('next', 9000, 11000)), 'playing')
  oldPlayer.paused = false; delayed.resolve()
  assert.equal(await old, 'cancelled')
  assert.equal(oldPlayer.paused, true); assert.equal(nextPlayer.paused, false)
  oldPlayer.currentTime = 20; preview.timeUpdate(oldPlayer)
  assert.equal(nextPlayer.paused, false, 'events from an old media element cannot stop the new one')
  preview.cancel()
  nextPlayer.currentTime = 13; preview.timeUpdate(nextPlayer)
  assert.equal(nextPlayer.currentTime, 13)
})
