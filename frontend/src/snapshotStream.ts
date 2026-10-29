import type { AnalysisJob, ExportJob } from './api'
import { parseDiagnostic } from './diagnostics.ts'

const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const STAGE_STATUS = new Set(['BLOCKED', 'READY', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED'])
const JOB_STATUS = new Set(['QUEUED', 'RUNNING', 'REVIEW_READY', 'FAILED', 'CANCELLED'])
const EXPORT_STATUS = new Set(['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'PARTIAL_FAILED', 'CANCELLED'])
export interface VersionedSnapshot { id: string; status: string; state_version: number }
export type StreamMode = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'polling' | 'settled'
export const streamLabels: Record<StreamMode, string> = { idle: '', connecting: '连接中', live: '实时更新', reconnecting: '重连中', polling: '定时刷新', settled: '已更新' }
export class SnapshotError extends Error { constructor() { super('INVALID_SERVER_RESPONSE') } }
function invalid(): never { throw new SnapshotError() }
function object(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) }
function integer(value: unknown, min = 0): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= min }
function uuid(value: unknown): value is string { return typeof value === 'string' && UUID.test(value) }
function date(value: unknown) { return typeof value === 'string' && Number.isFinite(Date.parse(value)) }
function code(value: unknown) { return value === null || (typeof value === 'string' && /^[A-Z0-9_]{1,100}$/.test(value)) }
function identifier(value: unknown): value is string { return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/.test(value) }
function base(raw: unknown, id: string, statuses: Set<string>): asserts raw is Record<string, unknown> {
  if (!uuid(id) || !object(raw) || raw.id !== id || !integer(raw.state_version, 1)
      || typeof raw.status !== 'string' || !statuses.has(raw.status) || !date(raw.created_at)) invalid()
}
export function parseAnalysisSnapshot(raw: unknown, id: string): AnalysisJob {
  base(raw, id, JOB_STATUS)
  if (!uuid(raw.video_id) || !integer(raw.generation, 1) || !Array.isArray(raw.stages) || raw.stages.length !== 4
      || !object(raw.budget) || !object(raw.actions)) invalid()
  const ids = new Set<string>(), types = new Set<string>()
  for (const stage of raw.stages) {
    if (!object(stage) || !uuid(stage.id) || ids.has(stage.id) || typeof stage.stage_type !== 'string'
        || !['MEDIA', 'ASR', 'SEGMENT', 'TIMELINE'].includes(stage.stage_type) || types.has(stage.stage_type)
        || typeof stage.status !== 'string' || !STAGE_STATUS.has(stage.status) || !integer(stage.attempt) || !code(stage.error_code)) invalid()
    ids.add(stage.id); types.add(stage.stage_type)
  }
  const budget = raw.budget, actions = raw.actions
  if (!integer(budget.max_text_calls) || !integer(budget.text_calls_reserved) || budget.text_calls_reserved > budget.max_text_calls
      || !integer(budget.max_asr_submissions) || !integer(budget.asr_calls_reserved) || budget.asr_calls_reserved > budget.max_asr_submissions
      || typeof actions.can_cancel !== 'boolean' || typeof actions.can_retry !== 'boolean' || !integer(actions.max_attempts, 1)
      || ![null, 'JOB_NOT_RETRYABLE', 'ATTEMPTS_EXHAUSTED'].includes(actions.retry_block_reason as string | null)) invalid()
  const stages = raw.stages.map(stage => {
    const { diagnostic: rawDiagnostic, ...rest } = stage
    const diagnostic = parseDiagnostic(rawDiagnostic, stage.attempt, stage.error_code)
    return diagnostic ? { ...rest, diagnostic } : rest
  })
  return { ...raw, stages } as unknown as AnalysisJob
}
export function parseExportSnapshot(raw: unknown, id: string, jobId?: string): ExportJob {
  base(raw, id, EXPORT_STATUS)
  if (!uuid(raw.job_id) || (jobId !== undefined && raw.job_id !== jobId) || !uuid(raw.video_id)
      || !integer(raw.approved_revision) || typeof raw.snapshot_hash !== 'string' || !/^[a-f0-9]{64}$/.test(raw.snapshot_hash)
      || raw.profile !== 'h264-aac-v1' || !date(raw.updated_at) || !Array.isArray(raw.clips) || !raw.clips.length) invalid()
  const ids = new Set<string>(), segments = new Set<string>()
  let topicBatch: boolean | undefined
  for (const clip of raw.clips) {
    if (!object(clip)) invalid()
    const commerce = identifier(clip.product_id) && typeof clip.product_name === 'string' && !!clip.product_name.trim() && clip.title === undefined
    const topic = clip.product_id === null && clip.product_name === null && typeof clip.title === 'string' && !!clip.title.trim() && [...clip.title].length <= 120
    if ((!commerce && !topic) || !uuid(clip.id) || ids.has(clip.id) || !identifier(clip.segment_id) || segments.has(clip.segment_id)
        || !uuid(clip.stage_id) || !integer(clip.start_ms) || !integer(clip.end_ms, clip.start_ms + 1)
        || typeof clip.status !== 'string' || !STAGE_STATUS.has(clip.status) || !integer(clip.attempt) || !code(clip.error_code)
        || !(clip.size_bytes === null || integer(clip.size_bytes, 1))) invalid()
    if (topicBatch !== undefined && topicBatch !== topic) invalid()
    topicBatch = topic
    const content = `/api/exports/${id}/clips/${clip.id}/content`
    if (clip.status === 'SUCCEEDED') {
      if (clip.content_url !== content || clip.download_url !== `${content}?download=true` || !integer(clip.size_bytes, 1)) invalid()
    } else if (clip.content_url !== null || clip.download_url !== null) invalid()
    ids.add(clip.id); segments.add(clip.segment_id)
  }
  const clips = raw.clips.map(clip => {
    const { diagnostic: rawDiagnostic, ...rest } = clip
    const diagnostic = parseDiagnostic(rawDiagnostic, clip.attempt, clip.error_code)
    return diagnostic ? { ...rest, diagnostic } : rest
  })
  return { ...raw, clips } as unknown as ExportJob
}
export function isAnalysisTerminal(value: VersionedSnapshot) { return !['QUEUED', 'RUNNING'].includes(value.status) }
export function isExportTerminal(value: VersionedSnapshot) { return !['QUEUED', 'RUNNING'].includes(value.status) }
export function newestSnapshot<T extends VersionedSnapshot>(known: T | null | undefined, incoming: T): T {
  if (!known || known.id !== incoming.id) return incoming
  if (known.state_version > incoming.state_version || (known.state_version === incoming.state_version && known.status !== incoming.status)) return known
  // Configuration-derived actions may change without a persisted version bump.
  return incoming
}

export interface SnapshotEventSource {
  addEventListener(type: string, listener: EventListener): void
  removeEventListener(type: string, listener: EventListener): void
  close(): void
}
export interface StreamClock { setTimeout(callback: () => void, milliseconds: number): unknown; clearTimeout(handle: unknown): void }
interface StreamOptions<T extends VersionedSnapshot> {
  kind: 'job' | 'export'
  parse(raw: unknown, id: string): T
  terminal(snapshot: T): boolean
  fetchSnapshot(id: string, signal: AbortSignal): Promise<unknown>
  onSnapshot(snapshot: T): void
  onMode?(mode: StreamMode): void
  eventSource?: ((url: string) => SnapshotEventSource) | null
  clock?: StreamClock
  pollMs?: number
  initialSnapshotTimeoutMs?: number
}
const defaultClock: StreamClock = {
  setTimeout: (callback, milliseconds) => globalThis.setTimeout(callback, milliseconds),
  clearTimeout: handle => globalThis.clearTimeout(handle as ReturnType<typeof setTimeout>),
}

/** A latest-snapshot subscription. Event ids identify versions, never a replay log. */
export class SnapshotStream<T extends VersionedSnapshot> {
  current: T | null = null
  resourceId: string | null = null
  mode: StreamMode = 'idle'
  private options: StreamOptions<T>
  private clock: StreamClock
  private factory: ((url: string) => SnapshotEventSource) | null
  private source: SnapshotEventSource | null = null
  private sourceCleanup: (() => void) | null = null
  private retryTimer: unknown = null
  private pollTimer: unknown = null
  private initialTimer: unknown = null
  private pollController: AbortController | null = null
  private epoch = 0
  private connection = 0
  private attempt = 0
  private disposed = false
  constructor(options: StreamOptions<T>) {
    this.options = options; this.clock = options.clock ?? defaultClock
    this.factory = options.eventSource === undefined
      ? (typeof EventSource === 'undefined' ? null : url => new EventSource(url)) : options.eventSource
  }
  private setMode(mode: StreamMode) { if (this.mode !== mode) { this.mode = mode; this.options.onMode?.(mode) } }
  private valid(epoch: number) { return !this.disposed && epoch === this.epoch && this.resourceId !== null }
  private clearTimer(key: 'retryTimer' | 'pollTimer' | 'initialTimer') {
    if (this[key] !== null) { this.clock.clearTimeout(this[key]); this[key] = null }
  }
  private closeSource() {
    this.connection++; this.clearTimer('initialTimer'); this.sourceCleanup?.(); this.sourceCleanup = null
    this.source?.close(); this.source = null
  }
  private stopPolling() { this.clearTimer('pollTimer'); this.pollController?.abort(); this.pollController = null }
  private stopTransport() { this.closeSource(); this.stopPolling(); this.clearTimer('retryTimer') }
  select(id: string | null, initial?: T) {
    if (this.disposed) return
    if (id === this.resourceId) { if (initial) this.acceptHttp(initial); return }
    if (id !== null && !uuid(id)) invalid()
    const parsed = initial && id ? this.options.parse(initial, id) : null
    this.epoch++; this.stopTransport(); this.resourceId = id; this.current = parsed; this.attempt = 0
    if (!id) { this.setMode('idle'); return }
    if (parsed && this.options.terminal(parsed)) { this.setMode('settled'); return }
    this.connect(this.epoch)
  }
  acceptHttp(raw: unknown): T | null {
    if (this.disposed || !this.resourceId) return null
    const incoming = this.options.parse(raw, this.resourceId)
    this.accept(incoming)
    return this.current
  }
  private accept(incoming: T) {
    const previous = this.current, next = newestSnapshot(previous, incoming)
    this.current = next
    if (this.options.terminal(next)) { this.stopTransport(); this.setMode('settled') }
    else if (this.mode === 'settled') { this.attempt = 0; this.connect(this.epoch) }
    if (next !== previous) this.options.onSnapshot(next)
  }
  private connect(epoch: number) {
    if (!this.valid(epoch) || (this.current && this.options.terminal(this.current))) return
    if (!this.factory) { this.setMode('polling'); this.poll(epoch); return }
    this.closeSource()
    const connection = this.connection, id = this.resourceId!
    this.setMode(this.attempt ? 'reconnecting' : 'connecting')
    let source: SnapshotEventSource
    try { source = this.factory(`/api/${this.options.kind === 'job' ? 'jobs' : 'exports'}/${id}/events`) }
    catch { this.disconnected(epoch, connection); return }
    this.source = source
    const active = () => this.valid(epoch) && connection === this.connection && this.source === source
    const snapshot: EventListener = event => {
      if (!active()) return
      let value: T
      try {
        const message = event as MessageEvent<string>
        value = this.options.parse(JSON.parse(message.data), id)
        if (message.lastEventId !== `${this.options.kind}:${id}:${value.state_version}`) invalid()
      } catch { this.disconnected(epoch, connection); return }
      this.clearTimer('initialTimer'); this.clearTimer('retryTimer'); this.stopPolling(); this.attempt = 0
      this.setMode('live'); this.accept(value)
    }
    const error: EventListener = () => { if (active()) this.disconnected(epoch, connection) }
    source.addEventListener('snapshot', snapshot); source.addEventListener('error', error)
    this.sourceCleanup = () => { source.removeEventListener('snapshot', snapshot); source.removeEventListener('error', error) }
    // An open TCP connection alone does not prove that usable state was received.
    this.initialTimer = this.clock.setTimeout(() => { if (active()) this.disconnected(epoch, connection) }, this.options.initialSnapshotTimeoutMs ?? 10_000)
  }
  private disconnected(epoch: number, connection: number) {
    if (!this.valid(epoch) || connection !== this.connection) return
    this.closeSource(); this.setMode('reconnecting'); this.poll(epoch)
    if (!this.valid(epoch) || (this.current && this.options.terminal(this.current))) return
    const delay = Math.min(15_000, 1000 * 2 ** Math.min(this.attempt++, 4))
    this.clearTimer('retryTimer')
    this.retryTimer = this.clock.setTimeout(() => { this.retryTimer = null; this.connect(epoch) }, delay)
  }
  private poll(epoch: number) {
    if (!this.valid(epoch) || this.mode === 'live' || this.pollController || (this.current && this.options.terminal(this.current))) return
    this.clearTimer('pollTimer')
    const controller = new AbortController(), id = this.resourceId!
    this.pollController = controller
    void (async () => {
      try {
        const raw = await this.options.fetchSnapshot(id, controller.signal)
        if (this.valid(epoch) && !controller.signal.aborted && this.mode !== 'live') this.accept(this.options.parse(raw, id))
      } catch { /* Keep the last valid snapshot while either transport is unavailable. */ }
      finally {
        if (this.pollController === controller) this.pollController = null
        if (this.valid(epoch) && !controller.signal.aborted && this.mode !== 'live' && this.mode !== 'settled') {
          this.clearTimer('pollTimer')
          this.pollTimer = this.clock.setTimeout(() => { this.pollTimer = null; this.poll(epoch) }, this.options.pollMs ?? 3000)
        }
      }
    })()
  }
  dispose() {
    if (this.disposed) return
    this.disposed = true; this.epoch++; this.stopTransport(); this.resourceId = null; this.setMode('idle')
  }
}
