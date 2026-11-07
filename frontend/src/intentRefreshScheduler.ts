export interface IntentRefreshLease { signal: AbortSignal; current(): boolean }
export interface IntentRefreshClock {
  setTimeout(callback: () => void, milliseconds: number): unknown
  clearTimeout(handle: unknown): void
}
interface RefreshOptions {
  refresh(lease: IntentRefreshLease): Promise<unknown>
  hasActiveWork(): boolean
  clock?: IntentRefreshClock
}
const browserClock: IntentRefreshClock = {
  setTimeout: (callback, milliseconds) => globalThis.setTimeout(callback, milliseconds),
  clearTimeout: handle => globalThis.clearTimeout(handle as ReturnType<typeof setTimeout>),
}
/** A terminal clipping request can still own an unfinished export. Check both. */
export function intentHasActiveWork(requests: readonly { status: string }[], batches: readonly { status: string; clips: readonly { status: string }[] }[], globalActiveCount?: number | null): boolean {
  const activeStage = new Set(['BLOCKED', 'READY', 'RUNNING', 'RETRY_WAIT'])
  return typeof globalActiveCount === 'number' && globalActiveCount > 0 || requests.some(request => request.status === 'QUEUED' || request.status === 'RUNNING')
    || batches.some(batch => batch.status === 'QUEUED' || batch.status === 'RUNNING' || batch.clips.some(clip => activeStage.has(clip.status)))
}
/** Route probes cannot erase a validated activity sample; selected detail always participates. */
export function intentHistoryHasActiveWork(history: { protocol: string; activeCount: number | null; legacyKnownActiveCount: number },
  selected: { status: string } | null, batches: readonly { status: string; clips: readonly { status: string }[] }[]): boolean {
  const knownCount = history.activeCount ?? history.legacyKnownActiveCount
  return intentHasActiveWork(selected ? [selected] : [], batches, knownCount)
}

/** Owns only background GET lifetimes; mutation and draft callbacks stay outside this epoch. */
export class IntentRefreshScheduler {
  private active = false
  private visible = false
  private blocked = false
  private disposed = false
  private timer: unknown
  private run: { controller: AbortController; promise: Promise<void> } | null = null
  private options: RefreshOptions
  constructor(options: RefreshOptions) { this.options = options }
  private get clock() { return this.options.clock ?? browserClock }
  private get enabled() { return !this.disposed && this.active && this.visible && !this.blocked }
  update(state: { active: boolean; visible: boolean; blocked: boolean }) {
    if (this.disposed) return
    const wasEnabled = this.enabled
    this.active = state.active; this.visible = state.visible; this.blocked = state.blocked
    if (!this.enabled) this.cancelRead()
    else if (!wasEnabled) void this.refresh()
  }
  /** Focus and repeated foreground events share the already current GET. */
  refresh(): Promise<void> {
    if (!this.enabled) return Promise.resolve()
    this.clearTimer()
    if (this.run) return this.run.promise
    const run = { controller: new AbortController(), promise: Promise.resolve() }
    this.run = run
    run.promise = this.execute(run)
    return run.promise
  }
  /** Explicit refresh or a changed selected request supersedes its previous GET. */
  restart(): Promise<void> { this.cancelRead(); return this.refresh() }
  reschedule() {
    if (!this.enabled || this.run) return
    this.schedule(this.options.hasActiveWork() ? 5000 : 60000)
  }
  private async execute(run: NonNullable<IntentRefreshScheduler['run']>) {
    const lease = { signal: run.controller.signal, current: () => this.enabled && this.run === run && !run.controller.signal.aborted }
    let failed = false
    try { await this.options.refresh(lease) } catch { failed = true }
    finally {
      if (this.run === run) {
        this.run = null
        if (this.enabled) this.schedule(failed || this.options.hasActiveWork() ? 5000 : 60000)
      }
    }
  }
  private schedule(milliseconds: number) {
    this.clearTimer()
    this.timer = this.clock.setTimeout(() => { this.timer = undefined; void this.refresh() }, milliseconds)
  }
  private clearTimer() { if (this.timer !== undefined) this.clock.clearTimeout(this.timer); this.timer = undefined }
  private cancelRead() {
    this.clearTimer()
    const run = this.run; this.run = null
    run?.controller.abort()
  }
  dispose() { this.disposed = true; this.cancelRead() }
}
