import test from 'node:test'
import assert from 'node:assert/strict'
import { AgentForegroundRefresh, agentHasActiveWork, type AgentRefreshClock, type AgentRefreshLease } from '../agentRefresh.ts'
import { AgentReadScope } from '../videoAgent.ts'

class Clock implements AgentRefreshClock {
  now = 0; nextId = 0
  timers = new Map<number, { time: number; callback: () => void }>()
  setTimeout(callback: () => void, milliseconds: number) { const id = ++this.nextId; this.timers.set(id, { time: this.now + milliseconds, callback }); return id }
  clearTimeout(handle: unknown) { this.timers.delete(handle as number) }
  tick(milliseconds: number) {
    const end = this.now + milliseconds
    while (true) {
      const next = [...this.timers.entries()].filter(([, value]) => value.time <= end).sort((a, b) => a[1].time - b[1].time)[0]
      if (!next) break
      this.now = next[1].time; this.timers.delete(next[0]); next[1].callback()
    }
    this.now = end
  }
}
const visible = { active: true, visible: true, blocked: false }
async function flush() { for (let i = 0; i < 8; i++) await Promise.resolve() }
function harness() {
  const clock = new Clock(), scope = new AgentReadScope(), accepted: number[] = [], acceptedCaps: number[] = []
  const work: Array<{ turns: Array<{ status: string }> }> = []
  type Run = { lease: AgentRefreshLease; signal: AbortSignal; resolve(): void; reject(): void }
  const reads: Run[] = [], caps: Run[] = []
  const controller = new AgentForegroundRefresh({ clock, hasActiveWork: () => agentHasActiveWork(work),
    refresh: async lease => {
      const read = scope.begin(lease.signal), index = reads.length
      try {
        await new Promise<void>((resolve, reject) => reads.push({ lease, signal: read.signal, resolve, reject: () => reject(Error('GET failed')) }))
        if (lease.current() && read.current()) accepted.push(index)
      } finally { read.release() }
    },
    capabilities: async lease => {
      const index = caps.length
      await new Promise<void>((resolve, reject) => caps.push({ lease, signal: lease.signal, resolve, reject: () => reject(Error('capability unavailable')) }))
      if (lease.current()) acceptedCaps.push(index)
    },
  })
  return { clock, reads, caps, controller, work, accepted, acceptedCaps }
}

test('initial hidden or inactive conversation never reads history or capabilities; foreground entry starts both once', async () => {
  const h = harness()
  h.controller.update({ ...visible, active: false }); await h.controller.refresh(); await h.controller.checkCapabilities(); h.controller.focus(); h.clock.tick(180000)
  h.controller.update({ ...visible, visible: false }); h.clock.tick(180000)
  assert.equal(h.reads.length, 0); assert.equal(h.caps.length, 0)
  h.controller.update(visible); assert.equal(h.reads.length, 1); assert.equal(h.caps.length, 1)
  h.controller.update(visible); h.controller.focus(); h.controller.focus()
  assert.equal(h.reads.length, 1); assert.equal(h.caps.length, 1)
  h.reads[0]!.resolve(); h.caps[0]!.resolve(); await flush()
  assert.deepEqual(h.accepted, [0]); assert.deepEqual(h.acceptedCaps, [0]); assert.equal(h.clock.timers.size, 1)
  h.controller.dispose()
})

test('known QUEUED or RUNNING work in any session selects 5 seconds; all terminal turns use 60 without polling capabilities', async () => {
  const h = harness(); h.work.push({ turns: [{ status: 'SUCCEEDED' }] }, { turns: [{ status: 'QUEUED' }] })
  const before = JSON.stringify(h.work)
  h.controller.update(visible); h.reads[0]!.resolve(); h.caps[0]!.resolve(); await flush()
  h.clock.tick(4999); assert.equal(h.reads.length, 1)
  h.clock.tick(1); assert.equal(h.reads.length, 2); assert.equal(h.caps.length, 1)
  h.work[1]!.turns[0]!.status = 'RUNNING'; h.reads[1]!.resolve(); await flush(); h.clock.tick(5000)
  assert.equal(h.reads.length, 3)
  h.work[1]!.turns[0]!.status = 'CANCELLED'; h.reads[2]!.resolve(); await flush()
  h.clock.tick(59999); assert.equal(h.reads.length, 3)
  h.clock.tick(1); assert.equal(h.reads.length, 4); assert.equal(h.caps.length, 1)
  assert.notEqual(JSON.stringify(h.work), before, 'only this test changes the supplied statuses')
  for (const status of ['FAILED', 'CANCELLED', 'SUCCEEDED']) assert.equal(agentHasActiveWork([{ turns: [{ status }] }]), false)
  assert.equal(agentHasActiveWork([]), false); h.controller.dispose()
})

test('terminal GET failure retries at 5 seconds and a successful recovery returns to 60 seconds', async () => {
  const h = harness(); h.controller.update(visible); h.caps[0]!.reject(); h.reads[0]!.reject(); await flush()
  h.clock.tick(4999); assert.equal(h.reads.length, 1)
  h.clock.tick(1); assert.equal(h.reads.length, 2); assert.equal(h.caps.length, 1)
  h.reads[1]!.resolve(); await flush(); h.clock.tick(59999); assert.equal(h.reads.length, 2)
  h.clock.tick(1); assert.equal(h.reads.length, 3); h.controller.dispose()
})

test('hidden re-entry supersedes old GETs and their finally cannot clear a newer history or capability run', async () => {
  const h = harness(); h.controller.update(visible)
  h.controller.update({ ...visible, visible: false })
  assert.equal(h.reads[0]!.signal.aborted, true); assert.equal(h.caps[0]!.signal.aborted, true)
  h.clock.tick(180000); assert.equal(h.reads.length, 1); assert.equal(h.caps.length, 1)
  h.controller.update(visible); assert.equal(h.reads.length, 2); assert.equal(h.caps.length, 2)
  const activeRead = h.controller.refresh(), activeCaps = h.controller.checkCapabilities()
  h.reads[0]!.reject(); h.caps[0]!.resolve(); await flush()
  assert.deepEqual(h.accepted, []); assert.deepEqual(h.acceptedCaps, []); assert.equal(h.clock.timers.size, 0)
  assert.equal(h.controller.refresh(), activeRead); assert.equal(h.controller.checkCapabilities(), activeCaps)
  h.controller.focus(); assert.equal(h.reads.length, 2); assert.equal(h.caps.length, 2)
  h.reads[1]!.resolve(); h.caps[1]!.resolve(); await flush()
  assert.deepEqual(h.accepted, [1]); assert.deepEqual(h.acceptedCaps, [1]); assert.equal(h.clock.timers.size, 1)
  h.controller.dispose()
})

test('selecting another session restarts its history GET without racing an old result or reprobing capabilities', async () => {
  const h = harness(); h.controller.update(visible)
  void h.controller.restart()
  assert.equal(h.reads[0]!.signal.aborted, true); assert.equal(h.reads.length, 2)
  assert.equal(h.caps.length, 1); assert.equal(h.caps[0]!.signal.aborted, false)
  h.reads[0]!.resolve(); await flush(); assert.deepEqual(h.accepted, []); assert.equal(h.clock.timers.size, 0)
  h.reads[1]!.resolve(); h.caps[0]!.resolve(); await flush(); assert.deepEqual(h.accepted, [1])
  h.controller.dispose()
})

test('a source change immediately replaces both background history and capability reads', async () => {
  const h = harness(); h.controller.update(visible); void h.controller.restart(true)
  assert.equal(h.reads[0]!.signal.aborted, true); assert.equal(h.caps[0]!.signal.aborted, true)
  assert.equal(h.reads.length, 2); assert.equal(h.caps.length, 2)
  h.reads[0]!.resolve(); h.caps[0]!.resolve(); await flush()
  assert.deepEqual(h.accepted, []); assert.deepEqual(h.acceptedCaps, [])
  h.reads[1]!.resolve(); h.caps[1]!.resolve(); await flush()
  assert.deepEqual(h.accepted, [1]); assert.deepEqual(h.acceptedCaps, [1]); h.controller.dispose()
})

test('busy user actions pause background GETs while independent visual preflight and POST signals remain usable', async () => {
  const h = harness(), explicitPreflight = new AbortController(), submittedPost = new AbortController()
  const original = { key: 'immutable-original', body: '{"question":"A","visual_mode":"frames","focus_timestamp_ms":0}' }
  const originalSnapshot = JSON.stringify(original)
  h.controller.update(visible); h.controller.update({ ...visible, blocked: true })
  assert.equal(h.reads[0]!.signal.aborted, true); assert.equal(h.caps[0]!.signal.aborted, true)
  assert.equal(explicitPreflight.signal.aborted, false); assert.equal(submittedPost.signal.aborted, false)
  h.controller.focus(); h.clock.tick(180000); await h.controller.refresh(); await h.controller.checkCapabilities()
  assert.equal(h.reads.length, 1); assert.equal(h.caps.length, 1)
  h.controller.update(visible); assert.equal(h.reads.length, 2); assert.equal(h.caps.length, 2)
  h.reads[0]!.resolve(); h.caps[0]!.reject(); await flush()
  assert.equal(h.reads[1]!.signal.aborted, false); assert.equal(h.caps[1]!.signal.aborted, false)
  h.controller.dispose(); assert.equal(explicitPreflight.signal.aborted, false); assert.equal(submittedPost.signal.aborted, false)
  assert.equal(JSON.stringify(original), originalSnapshot)
})

test('a completed capability probe is not repeated after every write or history poll; explicit check and focus still work', async () => {
  const h = harness(); h.controller.update(visible); h.reads[0]!.resolve(); h.caps[0]!.resolve(); await flush()
  h.controller.update({ ...visible, blocked: true }); h.controller.update(visible)
  assert.equal(h.reads.length, 2); assert.equal(h.caps.length, 1)
  h.reads[1]!.resolve(); await flush()
  void h.controller.checkCapabilities(); void h.controller.checkCapabilities(); assert.equal(h.caps.length, 2)
  h.caps[1]!.resolve(); await flush(); h.controller.focus(); h.controller.focus()
  assert.equal(h.reads.length, 3); assert.equal(h.caps.length, 3)
  h.controller.dispose()
})

test('disposal cancels only owned reads and prevents future timers, focus or visibility re-entry', async () => {
  const h = harness(); h.controller.update(visible); h.controller.dispose()
  assert.equal(h.reads[0]!.signal.aborted, true); assert.equal(h.caps[0]!.signal.aborted, true)
  h.reads[0]!.resolve(); h.caps[0]!.resolve(); await flush(); h.clock.tick(180000)
  h.controller.update(visible); h.controller.focus(); await h.controller.restart(); await h.controller.checkCapabilities()
  assert.equal(h.reads.length, 1); assert.equal(h.caps.length, 1); assert.equal(h.clock.timers.size, 0)
  assert.deepEqual(h.accepted, []); assert.deepEqual(h.acceptedCaps, [])
})

test('AgentReadScope parent cancellation propagates only to its read and release removes the parent listener', () => {
  const parent = new AbortController(), scope = new AgentReadScope(), read = scope.begin(parent.signal), ordinary = scope.begin()
  parent.abort(); assert.equal(read.signal.aborted, true); assert.equal(read.current(), false); assert.equal(ordinary.signal.aborted, false)
  read.release(); ordinary.release()
  const anotherParent = new AbortController(), released = scope.begin(anotherParent.signal)
  released.release(); anotherParent.abort(); assert.equal(released.signal.aborted, false, 'released read no longer listens to its parent')
  const oldParent = new AbortController(); oldParent.abort(); assert.equal(scope.begin(oldParent.signal).signal.aborted, true)
  scope.dispose()
})
