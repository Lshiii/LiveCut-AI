import { IntentRefreshScheduler, type IntentRefreshClock, type IntentRefreshLease } from './intentRefreshScheduler.ts'
export type AgentRefreshLease = IntentRefreshLease
export type AgentRefreshClock = IntentRefreshClock
export function agentHasActiveWork(sessions: readonly { turns: readonly { status: string }[] }[]): boolean {
  return sessions.some(session => session.turns.some(turn => turn.status === 'QUEUED' || turn.status === 'RUNNING'))
}
interface ForegroundState { active: boolean; visible: boolean; blocked: boolean }
interface Options {
  refresh(lease: AgentRefreshLease): Promise<unknown>
  capabilities(lease: AgentRefreshLease): Promise<unknown>
  hasActiveWork(): boolean
  clock?: AgentRefreshClock
}
/** Background GETs only. Explicit submit preflights and POSTs stay outside this lifetime. */
export class AgentForegroundRefresh {
  private state: ForegroundState = { active: false, visible: false, blocked: false }
  private disposed = false
  private capabilitiesNeeded = true
  private capabilityRun: { controller: AbortController; promise: Promise<void> } | null = null
  private scheduler: IntentRefreshScheduler
  private options: Options
  constructor(options: Options) {
    this.options = options
    this.scheduler = new IntentRefreshScheduler({ refresh: options.refresh, hasActiveWork: options.hasActiveWork, clock: options.clock })
  }
  private get foreground() { return !this.disposed && this.state.active && this.state.visible }
  private get enabled() { return this.foreground && !this.state.blocked }
  update(state: ForegroundState) {
    if (this.disposed) return
    const wasForeground = this.foreground
    this.state = state
    if (!wasForeground && this.foreground) this.capabilitiesNeeded = true
    if (!this.enabled) this.cancelCapabilities()
    this.scheduler.update(state)
    if (this.enabled && this.capabilitiesNeeded) void this.checkCapabilities()
  }
  refresh() { return this.scheduler.refresh() }
  restart(sourceChanged = false) {
    if (sourceChanged) { this.cancelCapabilities(); this.capabilitiesNeeded = true }
    const result = this.scheduler.restart()
    if (sourceChanged && this.enabled) void this.checkCapabilities()
    return result
  }
  reschedule() { this.scheduler.reschedule() }
  focus() {
    if (!this.foreground) return
    void this.scheduler.refresh()
    if (this.state.blocked) this.capabilitiesNeeded = true
    else void this.checkCapabilities()
  }
  /** A foreground entry/check may probe capability once; the 5/60s timer never does. */
  checkCapabilities(): Promise<void> {
    if (!this.enabled) return Promise.resolve()
    if (this.capabilityRun) return this.capabilityRun.promise
    this.capabilitiesNeeded = false
    const run = { controller: new AbortController(), promise: Promise.resolve() }
    this.capabilityRun = run
    const lease = { signal: run.controller.signal, current: () => this.enabled && this.capabilityRun === run && !run.controller.signal.aborted }
    run.promise = (async () => {
      try { await this.options.capabilities(lease) } catch { /* The capability UI owns fixed error messages. */ }
      finally { if (this.capabilityRun === run) this.capabilityRun = null }
    })()
    return run.promise
  }
  private cancelCapabilities() {
    const run = this.capabilityRun; this.capabilityRun = null
    if (run) { this.capabilitiesNeeded = true; run.controller.abort() }
  }
  dispose() { this.disposed = true; this.scheduler.dispose(); this.cancelCapabilities() }
}
