import type { PreviewPlayer } from './topicPreview.ts'

export interface SequencePreviewPlayer extends PreviewPlayer {
  readonly paused: boolean
  readonly seeking: boolean
  readonly ended: boolean
  readonly playbackRate: number
}
export interface SequencePreviewPart { start_ms: number; end_ms: number }
export interface SequencePreviewState {
  id: string
  part_index: number
  part_count: number
  start_ms: number
  end_ms: number
  total_ms: number
  played_ms: number
}
export type SequencePreviewEnd = 'cancelled' | 'finished' | 'failed'
export interface SequencePreviewScheduler {
  setTimeout(callback: () => void, delayMs: number): unknown
  clearTimeout(handle: unknown): void
}
type StartResult = 'playing' | 'cancelled' | 'failed'
interface Playback {
  id: string
  player: SequencePreviewPlayer
  parts: readonly SequencePreviewPart[]
  index: number
  completedMs: number
  totalMs: number
  seekTarget: number | null
  timer: unknown | null
  resolveStart: ((result: StartResult) => void) | null
}
const nativeScheduler: SequencePreviewScheduler = {
  setTimeout: (callback, delayMs) => globalThis.setTimeout(callback, delayMs),
  clearTimeout: handle => globalThis.clearTimeout(handle as ReturnType<typeof globalThis.setTimeout>),
}
const finiteTime = (player: SequencePreviewPlayer) => player.currentTime * 1000
const validRate = (player: SequencePreviewPlayer) => Number.isFinite(player.playbackRate) && player.playbackRate > 0

/** A bounded review aid. Export timestamps remain independent of browser playback precision. */
export class SequencePreview {
  private playback: Playback | null = null
  private readonly changed: (state: SequencePreviewState | null, reason?: SequencePreviewEnd) => void
  private readonly scheduler: SequencePreviewScheduler

  constructor(
    changed: (state: SequencePreviewState | null, reason?: SequencePreviewEnd) => void = () => {},
    scheduler: SequencePreviewScheduler = nativeScheduler,
  ) { this.changed = changed; this.scheduler = scheduler }

  start(player: SequencePreviewPlayer, input: { id: string; parts: readonly SequencePreviewPart[] }): Promise<StartResult> {
    // Replacing a preview on the same element must not enqueue an unnecessary pause event.
    if (this.playback) this.stop(this.playback, 'cancelled', this.playback.player !== player)
    let totalMs = 0, previousEnd = 0
    const valid = typeof input.id === 'string' && !!input.id.trim() && Array.isArray(input.parts)
      && input.parts.length >= 1 && input.parts.length <= 12
      && input.parts.every(part => {
        if (!part || !Number.isSafeInteger(part.start_ms) || !Number.isSafeInteger(part.end_ms)
          || part.start_ms < previousEnd || part.end_ms <= part.start_ms) return false
        previousEnd = part.end_ms; totalMs += part.end_ms - part.start_ms
        return Number.isSafeInteger(totalMs)
      }) && validRate(player)
    if (!valid) { player.pause(); this.changed(null, 'failed'); return Promise.resolve('failed') }
    const parts = Object.freeze(input.parts.map(part => Object.freeze({ start_ms: part.start_ms, end_ms: part.end_ms })))
    let resolveStart!: (result: StartResult) => void
    const result = new Promise<StartResult>(resolve => { resolveStart = resolve })
    const playback: Playback = { id: input.id, player, parts, index: 0, completedMs: 0, totalMs,
      seekTarget: parts[0]!.start_ms, timer: null, resolveStart }
    this.playback = playback
    try {
      player.currentTime = playback.seekTarget! / 1000
      if (!player.seeking) playback.seekTarget = null
      this.publish(playback)
      if (!this.current(playback)) return result
      // Native play waits for an outstanding seek. Only this explicit user intent calls play().
      const playing = player.play()
      void Promise.resolve(playing).then(() => {
        if (!this.current(playback)) { this.pauseLate(playback); return }
        if (player.paused) { this.stop(playback, 'cancelled'); return }
        this.settleStart(playback, 'playing')
        this.observe(playback)
      }, () => {
        if (this.current(playback)) this.stop(playback, 'failed')
        else this.pauseLate(playback)
      })
    } catch { if (this.current(playback)) this.stop(playback, 'failed') }
    return result
  }

  cancel() { if (this.playback) this.stop(this.playback, 'cancelled') }

  /** Also forward the native `playing` event here to wake observation after buffering. */
  timeUpdate(player: SequencePreviewPlayer) {
    const playback = this.forPlayer(player)
    if (playback) this.observe(playback)
  }

  seeking(player: SequencePreviewPlayer) {
    const playback = this.forPlayer(player)
    if (!playback || !player.seeking) return
    this.clearTimer(playback)
    if (playback.seekTarget !== null && Math.abs(finiteTime(player) - playback.seekTarget) <= 1) return
    this.stop(playback, 'cancelled')
  }

  seeked(player: SequencePreviewPlayer) {
    const playback = this.forPlayer(player)
    // A queued seeked event from an older seek cannot clear an actual new in-flight seek.
    if (!playback || player.seeking || playback.seekTarget === null) return
    const milliseconds = finiteTime(player), part = playback.parts[playback.index]!
    if (!Number.isFinite(milliseconds) || milliseconds < part.start_ms - 1) { this.stop(playback, 'failed'); return }
    playback.seekTarget = null
    this.observe(playback)
  }

  pause(player: SequencePreviewPlayer) {
    const playback = this.forPlayer(player)
    if (!playback || !player.paused) return
    // Internal stops clear their owner before pause(); late pause events see current actual state.
    if (player.ended) this.ended(player)
    else this.stop(playback, 'cancelled')
  }

  ended(player: SequencePreviewPlayer) {
    const playback = this.forPlayer(player)
    if (!playback || !player.ended || player.seeking) return
    const milliseconds = finiteTime(player), part = playback.parts[playback.index]!
    if (playback.index === playback.parts.length - 1 && milliseconds >= part.end_ms) this.finish(playback)
    else this.stop(playback, 'failed')
  }

  rateChange(player: SequencePreviewPlayer) {
    const playback = this.forPlayer(player)
    if (playback) this.observe(playback)
  }

  private current(playback: Playback) { return this.playback === playback }
  private forPlayer(player: SequencePreviewPlayer) { return this.playback?.player === player ? this.playback : null }
  private settleStart(playback: Playback, result: StartResult) {
    const resolve = playback.resolveStart
    playback.resolveStart = null
    resolve?.(result)
  }
  private clearTimer(playback: Playback) {
    if (playback.timer !== null) this.scheduler.clearTimeout(playback.timer)
    playback.timer = null
  }
  private pauseLate(playback: Playback) {
    if (!this.playback || this.playback.player !== playback.player) playback.player.pause()
  }
  private stop(playback: Playback, reason: SequencePreviewEnd, pause = true) {
    if (!this.current(playback)) return
    this.playback = null
    this.clearTimer(playback)
    this.settleStart(playback, reason === 'failed' ? 'failed' : 'cancelled')
    if (pause) playback.player.pause()
    if (!this.playback) this.changed(null, reason)
  }
  private finish(playback: Playback) {
    if (!this.current(playback)) return
    const end = playback.parts[playback.index]!.end_ms / 1000
    this.stop(playback, 'finished')
    // A consumer may start a new preview from the terminal notification.
    if (!this.playback || this.playback.player !== playback.player) playback.player.currentTime = end
  }
  private publish(playback: Playback) {
    if (!this.current(playback)) return
    const part = playback.parts[playback.index]!, milliseconds = finiteTime(playback.player)
    const retained = Number.isFinite(milliseconds) ? Math.max(0, Math.min(part.end_ms - part.start_ms, milliseconds - part.start_ms)) : 0
    this.changed({ id: playback.id, part_index: playback.index, part_count: playback.parts.length,
      start_ms: part.start_ms, end_ms: part.end_ms, total_ms: playback.totalMs, played_ms: playback.completedMs + Math.floor(retained) })
  }
  private observe(playback: Playback, timerPosition?: number) {
    if (!this.current(playback)) return
    this.clearTimer(playback)
    const player = playback.player, milliseconds = finiteTime(player), part = playback.parts[playback.index]!
    if (!Number.isFinite(milliseconds) || !validRate(player)) { this.stop(playback, 'failed'); return }
    if (player.ended) { this.ended(player); return }
    if (player.paused) { this.stop(playback, 'cancelled'); return }
    if (player.seeking) {
      if (playback.seekTarget === null) this.stop(playback, 'cancelled')
      return
    }
    if (playback.seekTarget !== null) return
    if (milliseconds < part.start_ms - 1) { this.stop(playback, 'cancelled'); return }
    if (milliseconds >= part.end_ms) { this.advance(playback); return }
    this.publish(playback)
    if (!this.current(playback) || timerPosition !== undefined && milliseconds <= timerPosition + .01) return
    const delayMs = Math.max(4, Math.min(100, Math.ceil((part.end_ms - milliseconds) / player.playbackRate)))
    playback.timer = this.scheduler.setTimeout(() => {
      if (this.current(playback)) this.observe(playback, milliseconds)
    }, delayMs)
  }
  private advance(playback: Playback) {
    this.clearTimer(playback)
    const previous = playback.parts[playback.index]!
    if (playback.index === playback.parts.length - 1) { this.finish(playback); return }
    playback.completedMs += previous.end_ms - previous.start_ms
    playback.index++
    playback.seekTarget = playback.parts[playback.index]!.start_ms
    try {
      // Seek directly while playing; no internal pause or delayed play can mask a user's pause.
      playback.player.currentTime = playback.seekTarget / 1000
      if (!playback.player.seeking) playback.seekTarget = null
      this.publish(playback)
      if (this.current(playback)) this.observe(playback)
    } catch { if (this.current(playback)) this.stop(playback, 'failed') }
  }
}
