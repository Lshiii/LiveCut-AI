import test from 'node:test'
import assert from 'node:assert/strict'
import { EXECUTOR_STAGES, ExecutorMonitor, ExecutorSnapshotError, executorQueueHint, parseExecutorSnapshot,
  type ExecutorClock, type ExecutorState } from '../executors.ts'

const JOB_A = 'aaaaaaaa-1111-2222-3333-444444444444', JOB_B = 'bbbbbbbb-1111-2222-3333-444444444444'
function snapshot() {
  return { generated_at: '2026-10-30T00:00:00Z', heartbeat_timeout_seconds: 35,
    online_workers: 1, total_slots: 1, busy_slots: 0, available_slots: 1,
    workers: [{ worker_id: 'worker-1', status: 'ONLINE', stage_types: [...EXECUTOR_STAGES],
      slots: 1, busy_slots: 0, last_seen_at: '2026-10-30T00:00:00Z', target_job_id: null as string | null }],
    stages: EXECUTOR_STAGES.map(stage_type => ({ stage_type, online_workers: 1, total_slots: 1,
      busy_slots: 0, available_slots: 1, reason: 'AVAILABLE' })),
    resources: [{ name: 'FFMPEG', limit: 1, in_use: 0 }, { name: 'ASR', limit: 2, in_use: 0 }, { name: 'TEXT', limit: 2, in_use: 0 }] }
}

test('offline leases may occupy resources even when no executor is currently online', () => {
  const value = snapshot()
  value.online_workers = value.total_slots = value.available_slots = 0
  value.busy_slots = 1
  value.workers[0]!.status = 'OFFLINE'; value.workers[0]!.busy_slots = 1
  value.stages.forEach(stage => {
    stage.online_workers = stage.total_slots = stage.available_slots = 0
    stage.busy_slots = 1; stage.reason = 'NO_EXECUTOR'
  })
  const result = parseExecutorSnapshot(value)
  assert.equal(result.total_slots, 0); assert.equal(result.busy_slots, 1)
  assert.equal(result.workers[0]!.status, 'OFFLINE')
})

test('malformed capacity, stage sets and worker identities are rejected rather than displayed as no executors', () => {
  const mutations = [
    (x: any) => { x.online_workers = false }, (x: any) => { x.busy_slots = -1 },
    (x: any) => { x.total_slots = 1.5 }, (x: any) => { x.available_slots = 2 },
    (x: any) => { x.heartbeat_timeout_seconds = 0 }, (x: any) => { x.generated_at = 'yesterday' },
    (x: any) => { x.workers[0].worker_id = '<script>' }, (x: any) => { x.workers.push(x.workers[0]) },
    (x: any) => { x.workers[0].status = 'RUNNING' }, (x: any) => { x.workers[0].last_seen_at = null },
    (x: any) => { x.workers[0].stage_types = ['MEDIA', 'MEDIA'] }, (x: any) => { x.workers[0].stage_types = [] },
    (x: any) => { x.workers[0].target_job_id = 'untrusted' }, (x: any) => { x.workers[0].slots = '1' },
    (x: any) => { x.stages.pop() }, (x: any) => { x.stages[1] = x.stages[0] },
    (x: any) => { x.stages[0].reason = 'FAILED' }, (x: any) => { x.resources.pop() },
    (x: any) => { x.resources[0].limit = 0 }, (x: any) => { x.resources[0].in_use = Infinity },
    (x: any) => { x.resources[1] = x.resources[0] },
  ]
  for (const mutate of mutations) { const value = snapshot(); mutate(value); assert.throws(() => parseExecutorSnapshot(value), ExecutorSnapshotError) }
  for (const value of [null, [], 'offline', 0]) assert.throws(() => parseExecutorSnapshot(value), ExecutorSnapshotError)
})

test('the public view keeps the target scope but drops undeclared private execution fields', () => {
  const value = snapshot()
  value.workers[0]!.target_job_id = JOB_A
  const raw = { ...value, session_id: 'private', run_token: 'private' }
  Object.assign(raw.workers[0]!, { session_id: 'private', run_token: 'private' })
  const result = parseExecutorSnapshot(raw)
  assert.equal(result.workers[0]!.target_job_id, JOB_A)
  assert.ok(!JSON.stringify(result).includes('private'))
})

test('queued work distinguishes no executor, busy slots, global resources and ready capacity', () => {
  const value = snapshot(), job = { status: 'QUEUED', stages: [{ stage_type: 'ASR', status: 'READY' }] }
  for (const [reason, message] of [['NO_EXECUTOR', /暂无可用执行器/], ['BUSY', /空闲槽位/],
    ['RESOURCE_BUSY', /处理资源繁忙/], ['AVAILABLE', /等待领取任务/]] as const) {
    value.stages.find(stage => stage.stage_type === 'ASR')!.reason = reason
    const hint = executorQueueHint(job, { status: 'fresh', snapshot: parseExecutorSnapshot(value) })!
    assert.match(hint.text, message)
    assert.equal(hint.warning, reason === 'NO_EXECUTOR')
  }
})

test('running work is never reported failed solely because executor registration is offline', () => {
  const value = snapshot()
  value.stages.find(stage => stage.stage_type === 'SEGMENT')!.online_workers = 0
  const job = { status: 'RUNNING', stages: [{ stage_type: 'SEGMENT', status: 'RUNNING' }] }
  const hint = executorQueueHint(job, { status: 'fresh', snapshot: parseExecutorSnapshot(value) })!
  assert.match(hint.text, /确认任务是否需要恢复/)
  assert.doesNotMatch(hint.text, /任务失败|执行失败/)
  assert.equal(job.status, 'RUNNING')
  value.stages.find(stage => stage.stage_type === 'SEGMENT')!.online_workers = 1
  assert.equal(executorQueueHint(job, { status: 'fresh', snapshot: parseExecutorSnapshot(value) }), null)
})

test('finished reviews are not disturbed by executor errors or offline warnings', () => {
  for (const status of ['REVIEW_READY', 'FAILED', 'CANCELLED']) {
    for (const availability of ['loading', 'stale', 'unavailable'] as const) {
      assert.equal(executorQueueHint({ status, stages: [] }, { status: availability, snapshot: null }), null)
    }
  }
  assert.equal(executorQueueHint(null, { status: 'fresh', snapshot: parseExecutorSnapshot(snapshot()) }), null)
  const hint = executorQueueHint({ status: 'QUEUED', stages: [{ stage_type: 'MEDIA', status: 'READY' }] },
    { status: 'unavailable', snapshot: null })!
  assert.equal(hint.warning, false); assert.match(hint.text, /暂不可用/); assert.doesNotMatch(hint.text, /暂无可用执行器/)
})

class Clock implements ExecutorClock {
  time = 1000
  sequence = 0
  timers = new Map<number, { at: number; callback: () => void }>()
  now = () => this.time
  setTimeout = (callback: () => void, milliseconds: number) => {
    const id = ++this.sequence; this.timers.set(id, { at: this.time + milliseconds, callback }); return id
  }
  clearTimeout = (handle: unknown) => { this.timers.delete(handle as number) }
  tick(milliseconds: number) {
    const target = this.time + milliseconds
    for (;;) {
      const next = [...this.timers.entries()].filter(([, timer]) => timer.at <= target).sort((a, b) => a[1].at - b[1].at)[0]
      if (!next) break
      this.time = next[1].at; this.timers.delete(next[0]); next[1].callback()
    }
    this.time = target
  }
}
function harness() {
  const clock = new Clock(), states: ExecutorState[] = []
  const requests: { jobId: string | null; signal: AbortSignal; resolve: (value: unknown) => void; reject: (error: Error) => void }[] = []
  const monitor = new ExecutorMonitor({ clock, onState: state => states.push(state),
    fetchSnapshot: (jobId, signal) => new Promise((resolve, reject) => { requests.push({ jobId, signal, resolve, reject }) }) })
  return { clock, states, requests, monitor }
}
async function flush() { await Promise.resolve(); await Promise.resolve() }

test('late responses from a different selection or a previously visited same job cannot overwrite the current scope', async () => {
  const h = harness()
  h.monitor.select(JOB_A); h.monitor.select(JOB_B); h.monitor.select(JOB_A)
  assert.equal(h.requests[0]!.signal.aborted, true); assert.equal(h.requests[1]!.signal.aborted, true)
  h.requests[2]!.resolve(snapshot()); await flush()
  assert.equal(h.states.at(-1)!.status, 'fresh')
  const count = h.states.length
  h.requests[0]!.resolve({}); h.requests[1]!.reject(new Error('late failure')); await flush()
  assert.equal(h.states.length, count)
  assert.deepEqual(h.requests.map(request => request.jobId), [JOB_A, JOB_B, JOB_A])
  h.monitor.dispose()
})

test('an unavailable registry clears stale capacity and recovers independently on a later poll', async () => {
  const h = harness()
  h.monitor.select(null); h.requests[0]!.resolve(snapshot()); await flush()
  h.clock.tick(5000); h.requests[1]!.reject(new Error('registry unavailable')); await flush()
  assert.deepEqual(h.states.at(-1), { status: 'unavailable', snapshot: null })
  h.clock.tick(5000); h.requests[2]!.resolve(snapshot()); await flush()
  assert.equal(h.states.at(-1)!.status, 'fresh')
  h.monitor.dispose()
})

test('old online capacity expires while a refresh is still pending', async () => {
  const h = harness()
  h.monitor.select(JOB_A); h.requests[0]!.resolve(snapshot()); await flush()
  h.clock.tick(35000)
  assert.equal(h.requests.length, 2)
  assert.deepEqual(h.states.at(-1), { status: 'stale', snapshot: null })
  h.monitor.dispose()
})

test('a response delayed beyond its full TTL is not presented as newly online', async () => {
  const h = harness()
  h.monitor.select(JOB_A); h.clock.tick(36000); h.requests[0]!.resolve(snapshot()); await flush()
  assert.deepEqual(h.states.at(-1), { status: 'stale', snapshot: null })
  h.monitor.dispose()
})

test('malformed registry responses cannot masquerade as a successfully checked empty registry', async () => {
  const h = harness()
  h.monitor.select(JOB_A); h.requests[0]!.resolve({ online_workers: 0 }); await flush()
  assert.deepEqual(h.states.at(-1), { status: 'unavailable', snapshot: null })
  h.monitor.dispose()
})

test('unmount aborts the request and clears refresh and expiry timers without publishing another status', async () => {
  const h = harness()
  h.monitor.select(JOB_A); h.requests[0]!.resolve(snapshot()); await flush()
  h.clock.tick(5000)
  const count = h.states.length
  h.monitor.dispose()
  assert.equal(h.requests[1]!.signal.aborted, true)
  assert.equal(h.clock.timers.size, 0)
  h.requests[1]!.reject(new Error('aborted')); await flush()
  assert.equal(h.states.length, count)
})
