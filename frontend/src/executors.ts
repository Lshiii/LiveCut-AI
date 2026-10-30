export const EXECUTOR_STAGES = ['MEDIA', 'ASR', 'SEGMENT', 'TIMELINE', 'EXPORT_CLIP'] as const
export type ExecutorStageType = typeof EXECUTOR_STAGES[number]
export type ExecutorReason = 'AVAILABLE' | 'NO_EXECUTOR' | 'BUSY' | 'RESOURCE_BUSY'
export interface ExecutorCapacity {
  online_workers: number
  total_slots: number
  busy_slots: number
  available_slots: number
}
export interface ExecutorWorker {
  worker_id: string
  status: 'ONLINE' | 'OFFLINE'
  stage_types: ExecutorStageType[]
  slots: number
  busy_slots: number
  last_seen_at: string
  target_job_id: string | null
}
export interface ExecutorSnapshot extends ExecutorCapacity {
  generated_at: string
  heartbeat_timeout_seconds: number
  workers: ExecutorWorker[]
  stages: (ExecutorCapacity & { stage_type: ExecutorStageType; reason: ExecutorReason })[]
  resources: { name: 'FFMPEG' | 'ASR' | 'TEXT'; limit: number; in_use: number }[]
}
export class ExecutorSnapshotError extends Error {
  constructor() { super('INVALID_EXECUTOR_SNAPSHOT') }
}
type Row = Record<string, unknown>
function row(value: unknown): value is Row { return value !== null && typeof value === 'object' && !Array.isArray(value) }
function integer(value: unknown, minimum = 0): value is number { return Number.isSafeInteger(value) && (value as number) >= minimum }
function date(value: unknown): value is string { return typeof value === 'string' && Number.isFinite(Date.parse(value)) }
function stage(value: unknown): value is ExecutorStageType { return typeof value === 'string' && (EXECUTOR_STAGES as readonly string[]).includes(value) }
function invalid(): never { throw new ExecutorSnapshotError() }
function capacity(value: Row): ExecutorCapacity {
  if (!integer(value.online_workers) || !integer(value.total_slots) || !integer(value.busy_slots)
      || !integer(value.available_slots) || value.available_slots > value.total_slots) invalid()
  // An offline executor can still own an unexpired stage lease, so busy may
  // exceed the capacity of currently online executors. Do not subtract it here.
  return { online_workers: value.online_workers, total_slots: value.total_slots,
    busy_slots: value.busy_slots, available_slots: value.available_slots }
}
export function parseExecutorSnapshot(raw: unknown): ExecutorSnapshot {
  if (!row(raw) || !date(raw.generated_at) || !integer(raw.heartbeat_timeout_seconds, 1)
      || !Array.isArray(raw.workers) || !Array.isArray(raw.stages) || !Array.isArray(raw.resources)) invalid()
  const top = capacity(raw), ids = new Set<string>(), stageIds = new Set<string>(), resourceIds = new Set<string>()
  const workers = raw.workers.map(value => {
    if (!row(value) || typeof value.worker_id !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(value.worker_id)
        || ids.has(value.worker_id) || !['ONLINE', 'OFFLINE'].includes(value.status as string)
        || !Array.isArray(value.stage_types) || !value.stage_types.length || !value.stage_types.every(stage)
        || new Set(value.stage_types).size !== value.stage_types.length || !integer(value.slots, 1)
        || !integer(value.busy_slots) || !date(value.last_seen_at)
        || !(value.target_job_id === undefined || value.target_job_id === null
          || (typeof value.target_job_id === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(value.target_job_id)))) invalid()
    ids.add(value.worker_id)
    return { worker_id: value.worker_id, status: value.status as ExecutorWorker['status'],
      stage_types: value.stage_types as ExecutorStageType[], slots: value.slots, busy_slots: value.busy_slots,
      last_seen_at: value.last_seen_at, target_job_id: (value.target_job_id ?? null) as string | null }
  })
  const stages = raw.stages.map(value => {
    if (!row(value) || !stage(value.stage_type) || stageIds.has(value.stage_type)
        || !['AVAILABLE', 'NO_EXECUTOR', 'BUSY', 'RESOURCE_BUSY'].includes(value.reason as string)) invalid()
    stageIds.add(value.stage_type)
    return { ...capacity(value), stage_type: value.stage_type, reason: value.reason as ExecutorReason }
  })
  const resources = raw.resources.map(value => {
    if (!row(value) || !['FFMPEG', 'ASR', 'TEXT'].includes(value.name as string)
        || resourceIds.has(value.name as string) || !integer(value.limit, 1) || !integer(value.in_use)) invalid()
    resourceIds.add(value.name as string)
    return { name: value.name as ExecutorSnapshot['resources'][number]['name'], limit: value.limit, in_use: value.in_use }
  })
  if (stageIds.size !== EXECUTOR_STAGES.length || resourceIds.size !== 3) invalid()
  return { ...top, generated_at: raw.generated_at, heartbeat_timeout_seconds: raw.heartbeat_timeout_seconds,
    workers, stages, resources }
}

export interface ExecutorState {
  status: 'loading' | 'fresh' | 'unavailable' | 'stale'
  snapshot: ExecutorSnapshot | null
}
export interface ExecutorClock {
  now(): number
  setTimeout(callback: () => void, milliseconds: number): unknown
  clearTimeout(handle: unknown): void
}
interface ExecutorMonitorOptions {
  fetchSnapshot(jobId: string | null, signal: AbortSignal): Promise<unknown>
  onState(state: ExecutorState): void
  clock?: ExecutorClock
  pollMilliseconds?: number
}
const browserClock: ExecutorClock = {
  now: () => Date.now(), setTimeout: (callback, milliseconds) => globalThis.setTimeout(callback, milliseconds),
  clearTimeout: handle => globalThis.clearTimeout(handle as ReturnType<typeof setTimeout>),
}

/** Separate from task snapshots: registry failures never imply a failed job. */
export class ExecutorMonitor {
  private generation = 0
  private controller: AbortController | null = null
  private poll: unknown
  private expiry: unknown
  private options: ExecutorMonitorOptions
  constructor(options: ExecutorMonitorOptions) { this.options = options }
  private get clock() { return this.options.clock ?? browserClock }
  select(jobId: string | null) {
    this.dispose()
    this.publish({ status: 'loading', snapshot: null })
    void this.load(jobId, this.generation)
  }
  private publish(state: ExecutorState) { this.options.onState(state) }
  private async load(jobId: string | null, generation: number) {
    const controller = new AbortController(), started = this.clock.now()
    this.controller = controller
    try {
      const snapshot = parseExecutorSnapshot(await this.options.fetchSnapshot(jobId, controller.signal))
      if (generation !== this.generation || controller.signal.aborted) return
      this.clock.clearTimeout(this.expiry)
      // Start the freshness interval when the request began. A delayed response
      // must not give an already old availability report another complete TTL.
      const remaining = started + snapshot.heartbeat_timeout_seconds * 1000 - this.clock.now()
      if (remaining <= 0) this.publish({ status: 'stale', snapshot: null })
      else {
        this.publish({ status: 'fresh', snapshot })
        this.expiry = this.clock.setTimeout(() => {
          if (generation === this.generation) this.publish({ status: 'stale', snapshot: null })
        }, remaining)
      }
    } catch {
      if (generation !== this.generation || controller.signal.aborted) return
      this.clock.clearTimeout(this.expiry)
      this.publish({ status: 'unavailable', snapshot: null })
    } finally {
      if (generation === this.generation && !controller.signal.aborted) {
        this.controller = null
        this.poll = this.clock.setTimeout(() => { void this.load(jobId, generation) }, this.options.pollMilliseconds ?? 5000)
      }
    }
  }
  dispose() {
    this.generation++
    this.controller?.abort(); this.controller = null
    this.clock.clearTimeout(this.poll); this.clock.clearTimeout(this.expiry)
  }
}

export function executorQueueHint(job: { status: string; stages: { stage_type: string; status: string }[] } | null,
  state: ExecutorState): { text: string; warning: boolean } | null {
  if (!job || !['QUEUED', 'RUNNING'].includes(job.status)) return null
  if (state.status !== 'fresh' || !state.snapshot) {
    return { text: '执行器状态暂不可用，正在刷新；任务状态保持不变。', warning: false }
  }
  const running = job.stages.find(value => value.status === 'RUNNING')
  if (running) {
    const availability = state.snapshot.stages.find(value => value.stage_type === running.stage_type)
    return availability?.online_workers === 0
      ? { text: '执行器暂未报到，后台正在确认任务是否需要恢复。', warning: true } : null
  }
  const ready = job.stages.find(value => value.status === 'READY')
  const availability = ready && state.snapshot.stages.find(value => value.stage_type === ready.stage_type)
  if (!availability) return null
  switch (availability.reason) {
    case 'NO_EXECUTOR': return { text: '暂无可用执行器。任务已保存，等待执行服务上线。', warning: true }
    case 'BUSY': return { text: '执行器正在处理已有任务，当前任务排队等待空闲槽位。', warning: false }
    case 'RESOURCE_BUSY': return { text: '所需处理资源繁忙，当前任务正在排队等待。', warning: false }
    case 'AVAILABLE': return { text: '执行服务可用，等待领取任务。', warning: false }
  }
}
