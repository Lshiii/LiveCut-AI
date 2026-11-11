import test from 'node:test'
import assert from 'node:assert/strict'
import { h, ref } from 'vue'
import * as protocol from '../agentAnswerReplay.ts'
import * as storageProtocol from '../agentAnswerReplayStorage.ts'
import * as videoAgent from '../videoAgent.ts'
import { MemoryStorage } from './support/agentSubmissionFixture.ts'
import { answerReplaySample } from './support/agentAnswerReplayFixture.ts'
import { allHostNodes, compileHostComponent, flushHost, hostNode, hostRenderer, hostText } from './support/vueHost.ts'
class HttpError extends Error { code: string; status: number; constructor(code: string, status: number) { super(code); this.code = code; this.status = status } }
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }
async function settle(check: () => boolean) { const end = Date.now() + 2000; while (!check()) { if (Date.now() > end) throw Error('Replay SFC did not settle'); await new Promise(done => setTimeout(done, 2)); await flushHost() } }
async function mount() {
  const sample = answerReplaySample(), old = { document: globalThis.document, window: globalThis.window, localStorage: globalThis.localStorage }
  class Doc extends EventTarget {}
  const document = Object.assign(new Doc(), { hidden: false }), window = new EventTarget(), storage = new MemoryStorage()
  Object.assign(globalThis, { document, window, localStorage: storage })
  const calls: Array<{ path: string; init: any; stored?: string | null }> = [], adopted: videoAgent.AgentSession[] = []
  let serverSession = structuredClone(sample.session)
  const queued = { ...structuredClone(sample.child), status: 'QUEUED', state_version: 1, result: null, stage: { ...sample.child.stage, status: 'READY', attempt: 0 } }
  const defaultHandle = async (path: string, init: any = {}): Promise<unknown> => {
    if (path === '/api/agent-capabilities/cached-answer-replay') return { cached_answer_replay_supported: true, worker_protocol: 3 }
    if (path === `/api/agent-sessions/${sample.session.id}`) return structuredClone(serverSession)
    if (path.endsWith('/response-replay') && init.method === 'POST') { serverSession.turns = [sample.source, queued] as videoAgent.AgentTurn[]; return structuredClone(queued) }
    if (path.endsWith('/response-replay')) return structuredClone(sample.preview)
    throw Error(`Unexpected ${init.method ?? 'GET'} ${path}`)
  }
  let handle = defaultHandle
  const api = (path: string, init: any = {}) => { calls.push({ path, init, stored: init.headers?.['Idempotency-Key'] ? [...storage.values.values()].find(value => JSON.parse(value).key === init.headers['Idempotency-Key']) : undefined }); return handle(path, init) }
  const component = compileHostComponent(new URL('../AgentAnswerReplayPanel.vue', import.meta.url), { './api': { api }, './videoAgent': videoAgent, './agentAnswerReplay': protocol, './agentAnswerReplayStorage': storageProtocol })
  const root = hostNode('root'), exposed = ref<any>(null), props = { owner: sample.owner, session: sample.session as videoAgent.AgentSession | null, knownSessions: undefined as videoAgent.AgentSession[] | undefined, active: true, blocked: false, newBlocked: false }
  const wrapper = { setup(_props: unknown, { attrs }: any) { return () => h(component, { ...attrs, ref: exposed, onSession: (session: videoAgent.AgentSession) => adopted.push(session) }) } }
  const render = async () => { hostRenderer.render(h(wrapper, { ...props }), root); await flushHost() }
  await render()
  const nodes = () => allHostNodes(root), button = (name: string) => { const value = nodes().find(node => node.type === 'button' && hostText(node) === name); assert.ok(value, `${name} / ${hostText(root)}`); return value }
  return { ...sample, queued, root, props, exposed, storage, document, window, calls, adopted, render, nodes, button, defaultHandle,
    setHandle: (value: typeof handle) => { handle = value }, setServer: (value: videoAgent.AgentSession) => { serverSession = value },
    click: async (name: string) => { const value = button(name); assert.equal(!!value.props.disabled, false, name); value.props.onClick(); await flushHost() },
    close: () => { hostRenderer.render(null, root); Object.assign(globalThis, old) } }
}
const CHECK = '核对第 1 轮可复用回答', CREATE = '复用第 1 轮已收回答恢复', LOOKUP = '核对原恢复提交', RETRY = '按原标识与快照重试', RELEASE = '查看已有恢复并解除本机未创建标识'
test('production recovery SFC makes no automatic reads and only READY plus a second click persists a zero-call POST', async () => {
  const ui = await mount()
  try {
    assert.equal(ui.calls.length, 0); assert.match(hostText(ui.root), /新增模型、ASR 请求均为 0/)
    await ui.click(CHECK); await settle(() => !ui.exposed.value.pending)
    assert.equal(ui.calls.filter(call => call.init.method === 'POST').length, 0)
    await ui.click(CREATE); await settle(() => !ui.exposed.value.pending)
    const posted = ui.calls.find(call => call.init.method === 'POST')!
    assert.deepEqual(JSON.parse(posted.init.body), { snapshot_hash: ui.preview.snapshot_hash }); assert.equal(JSON.parse(posted.stored!).input, posted.init.body)
    assert.equal(ui.adopted[0]!.turns[0]!.status, 'FAILED'); assert.equal(ui.adopted[0]!.turns[1]!.budget.max_text_calls, 0)
    assert.equal(ui.exposed.value.unresolved, false)
  } finally { ui.close() }
})
test('unknown recovery receipt GET is independent of capability and ordinary 404/503 never trigger a POST', async () => {
  for (const failure of [new HttpError('HTTP_404', 404), new HttpError('AGENT_RESPONSE_REPLAY_SUBMISSION_NOT_FOUND', 503)]) {
    const ui = await mount()
    try {
      storageProtocol.prepareAnswerReplay(ui.storage, ui.session, ui.source.id, ui.preview.snapshot_hash, 'cccccccc-cccc-4ccc-8ccc-cccccccccccc')
      ui.window.dispatchEvent(new Event('focus')); await flushHost()
      ui.setHandle(async path => { if (path.includes('/response-replay-submissions/')) throw failure; throw Error('No capability or POST permitted') })
      await ui.click(LOOKUP); await settle(() => !ui.exposed.value.pending)
      assert.equal(ui.calls.length, 1); assert.equal(ui.calls[0]!.init.method, undefined); assert.equal(ui.exposed.value.unresolved, true)
      assert.equal(ui.nodes().some(node => node.type === 'button' && hostText(node) === RETRY), false)
    } finally { ui.close() }
  }
})
test('exact business 404 offers explicit same-key/body retry without automatically posting', async () => {
  const ui = await mount()
  try {
    const entry = storageProtocol.prepareAnswerReplay(ui.storage, ui.session, ui.source.id, ui.preview.snapshot_hash, 'cccccccc-cccc-4ccc-8ccc-cccccccccccc')
    ui.window.dispatchEvent(new Event('focus')); await flushHost()
    ui.setHandle(async (path, init) => { if (path.includes('/response-replay-submissions/')) throw new HttpError('AGENT_RESPONSE_REPLAY_SUBMISSION_NOT_FOUND', 404); return ui.defaultHandle(path, init) })
    await ui.click(LOOKUP); await settle(() => !ui.exposed.value.pending)
    assert.equal(ui.calls.some(call => call.init.method === 'POST'), false)
    await ui.click(RETRY); await settle(() => !ui.exposed.value.pending)
    const posted = ui.calls.find(call => call.init.method === 'POST')!; assert.equal(posted.init.headers['Idempotency-Key'], entry.key); assert.equal(posted.init.body, entry.input)
    assert.equal(ui.calls.some(call => call.path.includes('capabilities')), false)
  } finally { ui.close() }
})
test('hidden pending POST is not aborted or releasable; late success leaves the original local receipt without reads or adoption', async () => {
  const ui = await mount(), late = deferred<unknown>()
  try {
    await ui.click(CHECK); await settle(() => !ui.exposed.value.pending)
    ui.setHandle(async (path, init) => init.method === 'POST' ? late.promise : ui.defaultHandle(path, init))
    await ui.click(CREATE); assert.equal(ui.exposed.value.pending, true)
    ui.document.hidden = true; ui.document.dispatchEvent(new Event('visibilitychange')); await flushHost()
    assert.equal(ui.exposed.value.pending, true); const before = ui.calls.length
    late.resolve(ui.queued); await settle(() => !ui.exposed.value.pending)
    assert.equal(ui.calls.length, before); assert.equal(ui.adopted.length, 0); assert.equal(ui.exposed.value.unresolved, true)
    assert.equal(ui.calls.find(call => call.init.method === 'POST')!.init.signal, undefined)
    ui.document.hidden = false; ui.document.dispatchEvent(new Event('visibilitychange')); await flushHost()
    assert.equal(ui.calls.length, before); assert.equal(ui.nodes().some(node => node.type === 'button' && hostText(node) === CREATE), false)
  } finally { ui.close() }
})
test('late eligibility is revoked on inactive/source changes, and malformed cache metadata never enables create', async () => {
  for (const mode of ['hidden', 'inactive', 'source', 'malformed']) {
    const ui = await mount(), late = deferred<unknown>()
    try {
      ui.setHandle(async (path, init) => path.endsWith('/response-replay') ? late.promise : ui.defaultHandle(path, init))
      await ui.click(CHECK); await settle(() => ui.calls.some(call => call.path.endsWith('/response-replay')))
      if (mode === 'hidden') { ui.document.hidden = true; ui.document.dispatchEvent(new Event('visibilitychange')) }
      if (mode === 'inactive') { ui.props.active = false; await ui.render() }
      if (mode === 'source') { ui.props.owner = { ...ui.owner, video_sha256: 'c'.repeat(64) }; await ui.render() }
      late.resolve(mode === 'malformed' ? { ...ui.preview, source_turn_id: ui.child.id } : ui.preview); await flushHost(); await flushHost()
      assert.equal(ui.nodes().some(node => node.type === 'button' && hostText(node) === CREATE), false)
      assert.equal(ui.calls.some(call => call.init.method === 'POST'), false)
    } finally { ui.close() }
  }
})
test('another-window child requires explicit fresh absence plus ALREADY_EXISTS before clearing only this key', async () => {
  const ui = await mount()
  try {
    const b = storageProtocol.prepareAnswerReplay(ui.storage, ui.session, ui.source.id, ui.preview.snapshot_hash, 'cccccccc-cccc-4ccc-8ccc-cccccccccccc')
    const other = storageProtocol.prepareAnswerReplay(ui.storage, ui.session, ui.source.id, ui.preview.snapshot_hash, 'dddddddd-dddd-4ddd-8ddd-dddddddddddd')
    ui.window.dispatchEvent(new Event('focus')); await flushHost()
    ui.setServer({ ...ui.session, turns: [ui.source, ui.child] })
    ui.setHandle(async (path, init) => {
      if (path.includes('/response-replay-submissions/')) throw new HttpError('AGENT_RESPONSE_REPLAY_SUBMISSION_NOT_FOUND', 404)
      if (path.endsWith('/response-replay')) return { ...ui.preview, eligible: false, block_reason: 'ALREADY_EXISTS', snapshot_hash: null, existing_turn: ui.child }
      return ui.defaultHandle(path, init)
    })
    await ui.click(LOOKUP); await settle(() => !ui.exposed.value.pending)
    assert.notEqual(ui.storage.getItem(storageProtocol.answerReplayStorageKey(b)), null)
    const before = ui.calls.length; await ui.click(RELEASE); await settle(() => !ui.exposed.value.pending)
    assert.equal(ui.calls.slice(before).filter(call => call.path.includes('response-replay-submissions')).length, 1)
    assert.equal(ui.calls.slice(before).filter(call => call.path.endsWith('/response-replay')).length, 1)
    assert.equal(ui.storage.getItem(storageProtocol.answerReplayStorageKey(b)), null); storageProtocol.assertAnswerReplayStored(ui.storage, other)
    assert.equal(ui.calls.some(call => call.init.method === 'POST'), false)
  } finally { ui.close() }
})
test('a storage failure after preview prevents a POST and retains a visible unresolved issue', async () => {
  const ui = await mount()
  try {
    await ui.click(CHECK); await settle(() => !ui.exposed.value.pending)
    ui.storage.setItem = () => { throw Error('synthetic full storage') }
    await ui.click(CREATE); await settle(() => !ui.exposed.value.pending)
    assert.equal(ui.calls.some(call => call.init.method === 'POST'), false)
    assert.match(hostText(ui.root), /无法保存并复读本机恢复回执/)
  } finally { ui.close() }
})
test('late receipt success after hiding cannot clear an entry or initiate its session GET', async () => {
  const ui = await mount(), late = deferred<unknown>()
  try {
    const entry = storageProtocol.prepareAnswerReplay(ui.storage, ui.session, ui.source.id, ui.preview.snapshot_hash, 'cccccccc-cccc-4ccc-8ccc-cccccccccccc')
    ui.window.dispatchEvent(new Event('focus')); await flushHost()
    ui.setHandle(async () => late.promise)
    await ui.click(LOOKUP); ui.document.hidden = true; ui.document.dispatchEvent(new Event('visibilitychange'))
    late.resolve(ui.child); await flushHost(); await flushHost()
    assert.equal(ui.calls.length, 1); assert.equal(ui.adopted.length, 0); storageProtocol.assertAnswerReplayStored(ui.storage, entry)
  } finally { ui.close() }
})
test('release reconfirms freshness and cannot clear a replaced record or a late response from a hidden/source scope', async () => {
  for (const mode of ['replaced', 'hidden', 'source']) {
    const ui = await mount(), late = deferred<unknown>()
    try {
      const entry = storageProtocol.prepareAnswerReplay(ui.storage, ui.session, ui.source.id, ui.preview.snapshot_hash, 'cccccccc-cccc-4ccc-8ccc-cccccccccccc')
      ui.window.dispatchEvent(new Event('focus')); await flushHost(); ui.setServer({ ...ui.session, turns: [ui.source, ui.child] })
      let lookups = 0
      ui.setHandle(async (path, init) => {
        if (path.includes('/response-replay-submissions/')) {
          lookups++; if (lookups > 1 && mode !== 'replaced') await late.promise
          throw new HttpError('AGENT_RESPONSE_REPLAY_SUBMISSION_NOT_FOUND', 404)
        }
        if (path.endsWith('/response-replay')) {
          if (lookups > 1 && mode === 'replaced') ui.storage.setItem(storageProtocol.answerReplayStorageKey(entry), JSON.stringify({ ...entry, snapshot_hash: 'b'.repeat(64), input: JSON.stringify({ snapshot_hash: 'b'.repeat(64) }) }))
          return { ...ui.preview, eligible: false, block_reason: 'ALREADY_EXISTS', snapshot_hash: null, existing_turn: ui.child }
        }
        return ui.defaultHandle(path, init)
      })
      await ui.click(LOOKUP); await settle(() => !ui.exposed.value.pending)
      await ui.click(RELEASE)
      if (mode === 'hidden') { ui.document.hidden = true; ui.document.dispatchEvent(new Event('visibilitychange')) }
      if (mode === 'source') { ui.props.owner = { ...ui.owner, video_sha256: 'c'.repeat(64) }; await ui.render() }
      late.resolve(null); await settle(() => !ui.exposed.value.pending)
      assert.notEqual(ui.storage.getItem(storageProtocol.answerReplayStorageKey(entry)), null)
      if (mode === 'replaced') assert.equal(JSON.parse(ui.storage.getItem(storageProtocol.answerReplayStorageKey(entry))!).snapshot_hash, 'b'.repeat(64))
      assert.equal(ui.adopted.length, 0); assert.equal(ui.calls.some(call => call.init.method === 'POST'), false)
    } finally { ui.close() }
  }
})
test('only this mounts first definite business rejection plus fresh exact absence permits explicit release', async () => {
  for (const definite of [true, false]) {
    const ui = await mount()
    try {
      await ui.click(CHECK); await settle(() => !ui.exposed.value.pending)
      ui.setHandle(async (path, init) => {
        if (init.method === 'POST') throw new HttpError(definite ? 'AGENT_RESPONSE_REPLAY_UNAVAILABLE' : 'NETWORK_UNAVAILABLE', definite ? 409 : 503)
        if (path.includes('/response-replay-submissions/')) throw new HttpError('AGENT_RESPONSE_REPLAY_SUBMISSION_NOT_FOUND', 404)
        if (path.endsWith('/response-replay')) return { ...ui.preview, eligible: false, block_reason: 'CACHE_UNAVAILABLE', snapshot_hash: null }
        return ui.defaultHandle(path, init)
      })
      await ui.click(CREATE); await settle(() => !ui.exposed.value.pending)
      assert.equal(ui.exposed.value.unresolved, true)
      await ui.click(LOOKUP); await settle(() => !ui.exposed.value.pending)
      const label = '解除明确拒绝且未创建的恢复标识'
      assert.equal(ui.nodes().some(node => node.type === 'button' && hostText(node) === label), definite)
      if (definite) {
        const before = ui.calls.length; await ui.click(label); await settle(() => !ui.exposed.value.pending)
        assert.equal(ui.exposed.value.unresolved, false)
        assert.equal(ui.calls.slice(before).length, 1); assert.ok(ui.calls.at(-1)!.path.includes('/response-replay-submissions/'))
      }
    } finally { ui.close() }
  }
})
test('an uncertain retry revokes first-rejection cleanup proof instead of erasing a potentially written request', async () => {
  const ui = await mount()
  try {
    await ui.click(CHECK); await settle(() => !ui.exposed.value.pending)
    let posts = 0
    ui.setHandle(async (path, init) => {
      if (init.method === 'POST') { posts++; throw new HttpError(posts === 1 ? 'AGENT_RESPONSE_REPLAY_UNAVAILABLE' : 'NETWORK_UNAVAILABLE', posts === 1 ? 409 : 503) }
      if (path.includes('/response-replay-submissions/')) throw new HttpError('AGENT_RESPONSE_REPLAY_SUBMISSION_NOT_FOUND', 404)
      return ui.defaultHandle(path, init)
    })
    await ui.click(CREATE); await settle(() => !ui.exposed.value.pending)
    await ui.click(LOOKUP); await settle(() => !ui.exposed.value.pending)
    await ui.click(RETRY); await settle(() => !ui.exposed.value.pending)
    await ui.click(LOOKUP); await settle(() => !ui.exposed.value.pending)
    assert.equal(posts, 2); assert.equal(ui.exposed.value.unresolved, true)
    assert.equal(ui.nodes().some(node => node.type === 'button' && hostText(node) === '解除明确拒绝且未创建的恢复标识'), false)
  } finally { ui.close() }
})
test('a parent cached identity conflict is validated before clearing the durable original recovery entry', async () => {
  const ui = await mount()
  try {
    const entry = storageProtocol.prepareAnswerReplay(ui.storage, ui.session, ui.source.id, ui.preview.snapshot_hash, 'cccccccc-cccc-4ccc-8ccc-cccccccccccc')
    ui.window.dispatchEvent(new Event('focus')); await flushHost()
    ui.props.knownSessions = [{ ...ui.session, turns: [{ ...ui.source, question: 'different immutable question' }] }]; await ui.render()
    ui.setHandle(async (path, init) => path.includes('/response-replay-submissions/') ? ui.child : ui.defaultHandle(path, init))
    await ui.click(LOOKUP); await settle(() => !ui.exposed.value.pending)
    assert.equal(ui.adopted.length, 0); storageProtocol.assertAnswerReplayStored(ui.storage, entry)
  } finally { ui.close() }
})
test('late old eligibility and finally cannot clear the newer visible operation or revive an old ready snapshot', async () => {
  const ui = await mount(), first = deferred<unknown>(), second = deferred<unknown>()
  try {
    let reads = 0
    ui.setHandle(async (path, init) => path.endsWith('/response-replay') ? (++reads === 1 ? first.promise : second.promise) : ui.defaultHandle(path, init))
    await ui.click(CHECK); await settle(() => reads === 1)
    ui.document.hidden = true; ui.document.dispatchEvent(new Event('visibilitychange')); await flushHost()
    ui.document.hidden = false; ui.document.dispatchEvent(new Event('visibilitychange')); await flushHost()
    await ui.click(CHECK); await settle(() => reads === 2)
    first.resolve(ui.preview); await flushHost(); await flushHost()
    assert.equal(ui.exposed.value.pending, true); assert.equal(ui.nodes().some(node => node.type === 'button' && hostText(node) === CREATE), false)
    second.resolve({ ...ui.preview, eligible: false, block_reason: 'CACHE_UNAVAILABLE', snapshot_hash: null }); await settle(() => !ui.exposed.value.pending)
    assert.equal(ui.nodes().some(node => node.type === 'button' && hostText(node) === CREATE), false)
  } finally { ui.close() }
})
