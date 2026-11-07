import test from 'node:test'
import assert from 'node:assert/strict'
import { IntentRefreshScheduler, intentHasActiveWork, type IntentRefreshClock, type IntentRefreshLease } from '../intentRefreshScheduler.ts'
import { IntentReadScope } from '../intentClips.ts'

class Clock implements IntentRefreshClock {
  now = 0
  id = 0
  timers = new Map<number, { at: number; callback: () => void }>()
  setTimeout(callback: () => void, milliseconds: number) {
    const id = ++this.id; this.timers.set(id, { at: this.now + milliseconds, callback }); return id
  }
  clearTimeout(handle: unknown) { this.timers.delete(handle as number) }
  tick(milliseconds: number) {
    const end = this.now + milliseconds
    for (;;) {
      const next = [...this.timers.entries()].filter(([, timer]) => timer.at <= end).sort((a, b) => a[1].at - b[1].at)[0]
      if (!next) break
      this.now = next[1].at; this.timers.delete(next[0]); next[1].callback()
    }
    this.now = end
  }
}
function harness() {
  const clock = new Clock(), accepted: number[] = [], scope = new IntentReadScope()
  const work: { requests: { status: string }[]; batches: { status: string; clips: { status: string }[] }[] } = { requests: [], batches: [] }
  const reads: { lease: IntentRefreshLease; signal: AbortSignal; resolve(): void; reject(): void }[] = []
  const scheduler = new IntentRefreshScheduler({ clock, hasActiveWork: () => intentHasActiveWork(work.requests, work.batches),
    refresh: async lease => {
      const read = scope.begin(lease.signal), index = reads.length
      try {
        await new Promise<void>((resolve, reject) => reads.push({ lease, signal: read.signal, resolve, reject: () => reject(new Error('temporary GET failure')) }))
        if (lease.current() && read.current()) accepted.push(index)
      } finally { read.release() }
    } })
  return { clock, work, reads, accepted, scheduler }
}
const visible = { active: true, visible: true, blocked: false }
async function flush() { for (let index = 0; index < 6; index++) await Promise.resolve() }

test('inactive workspaces and hidden pages never start GETs; returning immediately verifies fresh state', async () => {
  const h = harness()
  h.scheduler.update({ ...visible, active: false }); await h.scheduler.refresh(); h.clock.tick(300000)
  assert.equal(h.reads.length, 0)
  h.scheduler.update({ ...visible, visible: false }); h.clock.tick(300000)
  assert.equal(h.reads.length, 0)
  h.scheduler.update(visible); assert.equal(h.reads.length, 1)
  h.scheduler.update({ ...visible, active: false })
  assert.equal(h.reads[0]!.signal.aborted, true); assert.equal(h.reads[0]!.lease.current(), false)
  h.clock.tick(300000); assert.equal(h.reads.length, 1)
  h.scheduler.update(visible); assert.equal(h.reads.length, 2)
  h.reads[0]!.resolve(); await flush(); assert.deepEqual(h.accepted, []); assert.equal(h.clock.timers.size, 0)
  h.reads[1]!.resolve(); await flush(); assert.deepEqual(h.accepted, [1]); assert.equal(h.clock.timers.size, 1)
  h.scheduler.dispose()
})

test('visibility restoration and focus refresh immediately without duplicate concurrent reads or duplicate timers', async () => {
  const h = harness(); h.scheduler.update(visible)
  const same = h.scheduler.refresh(); void h.scheduler.refresh()
  assert.equal(h.reads.length, 1)
  h.reads[0]!.resolve(); await same; await flush()
  h.clock.tick(30000); assert.equal(h.reads.length, 1)
  void h.scheduler.refresh(); assert.equal(h.reads.length, 2) // Window focus interrupts the idle wait.
  void h.scheduler.refresh(); assert.equal(h.reads.length, 2)
  h.scheduler.update({ ...visible, visible: false }); assert.equal(h.reads[1]!.signal.aborted, true)
  h.scheduler.update(visible); assert.equal(h.reads.length, 3)
  h.reads[1]!.resolve(); await flush(); assert.deepEqual(h.accepted, [0])
  h.reads[2]!.resolve(); await flush(); assert.equal(h.clock.timers.size, 1)
  h.scheduler.dispose(); assert.equal(h.clock.timers.size, 0)
})

test('a terminal request with queued or retrying export clips retains five-second progress checks', async () => {
  const h = harness(); h.work.requests = [{ status: 'PLAN_READY' }]
  h.work.batches = [{ status: 'QUEUED', clips: [{ status: 'READY' }] }]
  h.scheduler.update(visible); h.reads[0]!.resolve(); await flush()
  h.clock.tick(4999); assert.equal(h.reads.length, 1); h.clock.tick(1); assert.equal(h.reads.length, 2)
  h.work.batches = [{ status: 'PARTIAL_FAILED', clips: [{ status: 'FAILED' }, { status: 'RETRY_WAIT' }] }]
  h.reads[1]!.resolve(); await flush(); h.clock.tick(5000); assert.equal(h.reads.length, 3)
  h.work.batches = [{ status: 'SUCCEEDED', clips: [{ status: 'SUCCEEDED' }] }]
  h.reads[2]!.resolve(); await flush(); h.clock.tick(59999); assert.equal(h.reads.length, 3)
  h.clock.tick(1); assert.equal(h.reads.length, 4)
  h.scheduler.dispose()
})

test('all-terminal idle checks still discover another page’s new request and return to fast polling', async () => {
  const h = harness(); h.work.requests = [{ status: 'FAILED' }, { status: 'CANCELLED' }, { status: 'PLAN_READY' }]
  h.scheduler.update(visible); h.reads[0]!.resolve(); await flush()
  h.clock.tick(60000); assert.equal(h.reads.length, 2)
  h.work.requests.push({ status: 'QUEUED' }); h.reads[1]!.resolve(); await flush()
  h.clock.tick(5000); assert.equal(h.reads.length, 3)
  h.scheduler.dispose()
})

test('blocking background reads never aborts a write or its acknowledgement, and write completion refreshes immediately', async () => {
  const h = harness(), write = new AbortController(), acknowledgement: string[] = []
  let finishWrite!: () => void
  const mutation = new Promise<void>(resolve => { finishWrite = resolve }).then(() => { acknowledgement.push('saved'); h.scheduler.update(visible) })
  h.scheduler.update(visible); h.scheduler.update({ ...visible, blocked: true })
  assert.equal(h.reads[0]!.signal.aborted, true); assert.equal(write.signal.aborted, false)
  h.clock.tick(300000); await h.scheduler.refresh(); assert.equal(h.reads.length, 1)
  finishWrite(); await mutation
  assert.deepEqual(acknowledgement, ['saved']); assert.equal(h.reads.length, 2)
  h.reads[0]!.resolve(); await flush(); assert.equal(h.reads[1]!.signal.aborted, false)
  h.scheduler.dispose(); assert.equal(write.signal.aborted, false)
})

test('explicit refresh supersedes an old selection and late failures cannot alter its replacement lifecycle', async () => {
  const h = harness(); h.scheduler.update(visible)
  void h.scheduler.restart(); assert.equal(h.reads[0]!.signal.aborted, true); assert.equal(h.reads.length, 2)
  h.reads[0]!.reject(); await flush(); assert.equal(h.clock.timers.size, 0)
  h.reads[1]!.resolve(); await flush(); assert.deepEqual(h.accepted, [1]); assert.equal(h.clock.timers.size, 1)
  h.scheduler.dispose(); h.clock.tick(300000); assert.equal(h.reads.length, 2)
  h.scheduler.update(visible); await h.scheduler.restart(); assert.equal(h.reads.length, 2)
})

test('temporary refresh errors retry while foreground without spawning a hidden retry loop', async () => {
  const h = harness(); h.scheduler.update(visible); h.reads[0]!.reject(); await flush()
  h.clock.tick(5000); assert.equal(h.reads.length, 2)
  h.scheduler.update({ ...visible, visible: false }); h.reads[1]!.reject(); await flush()
  h.clock.tick(300000); assert.equal(h.reads.length, 2); assert.equal(h.clock.timers.size, 0)
  h.scheduler.update(visible); assert.equal(h.reads.length, 3)
  h.scheduler.dispose()
})
