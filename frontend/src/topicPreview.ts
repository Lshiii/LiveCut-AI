/** Playback is a review aid; exported clip boundaries remain independent. */
export interface PreviewPlayer {
  currentTime: number
  play(): Promise<void>
  pause(): void
}

export interface TopicPreviewRange { id: string; start_ms: number; end_ms: number }
export type PreviewStartResult = 'playing' | 'cancelled' | 'failed'
interface Playback extends TopicPreviewRange { player: PreviewPlayer; token: number; initialSeek: boolean }

/** One bounded playback intent, including an outstanding browser play() promise. */
export class TopicPreview {
  private generation = 0
  private playback: Playback | null = null
  private changed: (range: TopicPreviewRange | null) => void

  constructor(changed: (range: TopicPreviewRange | null) => void = () => {}) { this.changed = changed }

  cancel() {
    const previous = this.playback
    this.playback = null; this.generation++
    previous?.player.pause()
    this.changed(null)
  }

  async start(player: PreviewPlayer, range: TopicPreviewRange): Promise<PreviewStartResult> {
    this.cancel()
    if (!Number.isSafeInteger(range.start_ms) || !Number.isSafeInteger(range.end_ms)
        || range.start_ms < 0 || range.end_ms <= range.start_ms) return 'failed'
    const playback: Playback = { ...range, player, token: this.generation, initialSeek: true }
    this.playback = playback; this.changed({ ...range })
    try {
      player.currentTime = range.start_ms / 1000
      await player.play()
      if (this.playback?.token === playback.token) return 'playing'
      // An older completion must not pause a newer preview on the same element.
      if (!this.playback || this.playback.player !== player) player.pause()
      return 'cancelled'
    } catch {
      if (this.playback?.token !== playback.token) return 'cancelled'
      this.cancel()
      return 'failed'
    }
  }

  timeUpdate(player: PreviewPlayer) {
    const current = this.playback
    if (!current || current.player !== player) return
    const milliseconds = player.currentTime * 1000
    if (!Number.isFinite(milliseconds) || milliseconds < current.start_ms - 1) {
      this.cancel()
    } else if (milliseconds >= current.end_ms) {
      this.cancel()
      player.currentTime = current.end_ms / 1000
    }
  }

  seeking(player: PreviewPlayer) {
    const current = this.playback
    if (!current || current.player !== player) return
    // The initial programmatic seek emits the same events as native controls.
    if (current.initialSeek && Math.abs(player.currentTime * 1000 - current.start_ms) <= 1) return
    this.cancel()
  }

  seeked(player: PreviewPlayer) {
    if (this.playback?.player === player) this.playback.initialSeek = false
  }
}
