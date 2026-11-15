import test from 'node:test'
import assert from 'node:assert/strict'
import { h, ref } from 'vue'
import * as protocol from '../sourceImports.ts'
import { allHostNodes, compileHostComponent, flushHost, hostNode, hostRenderer, hostText } from './support/vueHost.ts'

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const url = 'https://www.youtube.com/watch?v=abcdefghijk'
const snapshot = (value: Record<string, unknown> = {}) => ({ id: id(1), url, status: 'QUEUED', phase: 'QUEUED', progress_percent: null,
  downloaded_bytes: 0, video_id: null, error_code: null, attempt: 0, created_at: '2026-11-15T00:00:00Z', updated_at: '2026-11-15T00:00:00Z', ...value })
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }
class HttpError extends Error { code: string; status: number; constructor(code: string, status = 0) { super(code); this.code = code; this.status = status } }
const settle = async (check: () => boolean) => { const deadline = Date.now() + 2000; while (!check()) { if (Date.now() > deadline) throw new Error('State did not settle'); await new Promise(done => setTimeout(done, 1)); await flushHost() } }
async function mount(initialReceipt = false) {
  const globals = { document: globalThis.document, Document: globalThis.Document, window: globalThis.window }
  class Doc extends EventTarget {}
  const doc = Object.assign(new Doc(), { hidden: false, activeElement: null }), store = new Map<string, string>(), timers = new Map<number, () => void>(); let nextTimer = 1
  const storage = { get length() { return store.size }, key: (n: number) => [...store.keys()][n] ?? null, getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => { store.set(key, value) }, removeItem: (key: string) => { store.delete(key) } }
  if (initialReceipt) protocol.createSourceReceipt(storage, url, () => id(9))
  const win = Object.assign(new EventTarget(), { localStorage: storage, setTimeout: (fn: () => void) => { const n = nextTimer++; timers.set(n, fn); return n }, clearTimeout: (n: number) => { timers.delete(n) } })
  Object.assign(globalThis, { document: doc, Document: Doc, window: win })
  const calls: { path: string; init: any; stored: string | null }[] = [], emitted: unknown[] = [], busy: boolean[] = []
  let items: ReturnType<typeof snapshot>[] = [], guard = true, videoReads = 0, starts = 0
  const video = { id: id(2), original_filename: '录播.mp4', content_hash: 'a'.repeat(64), size_bytes: 100, content_url: `/api/videos/${id(2)}/content`, media_validation_status: 'HEADER_CHECKED', created_at: '2026-11-15T00:00:00Z' }
  const defaultHandle = async (path: string, init: any) => {
    if (path === '/api/source-imports?limit=20') return { items }
    if (path === '/api/source-imports' && init.method === 'POST') { items = [snapshot({ url: JSON.parse(init.body).url })]; return items[0] }
    if (path.startsWith('/api/source-imports/by-key/')) { if (!items.length) throw new HttpError('SOURCE_IMPORT_NOT_FOUND', 404); return items[0] }
    if (path === `/api/source-imports/${id(1)}` && init.method === 'DELETE') { items = [snapshot({ status: 'CANCELLED', phase: 'CANCELLED', attempt: 1, updated_at: '2026-11-15T00:00:02Z' })]; return items[0] }
    if (path === `/api/source-imports/${id(1)}/retry`) { items = [snapshot({ attempt: 1 })]; return items[0] }
    if (path === `/api/videos/${id(2)}`) { videoReads++; return video }
    throw new Error(`Unexpected ${init.method ?? 'GET'} ${path}`)
  }
  let handle = defaultHandle
  const api = { api: (path: string, init: any = {}) => { const key = init.headers?.['Idempotency-Key']; calls.push({ path, init, stored: key ? storage.getItem(protocol.sourceReceiptKey(key)) : null }); return handle(path, init) } }
  const component = compileHostComponent(new URL('../SourceImportPanel.vue', import.meta.url), { './api': api, './sourceImports': protocol })
  const root = hostNode('root'), exposed = ref<any>(null), props = { active: true, canStart: () => guard }
  const wrapper = { setup(_props: unknown, { attrs }: any) { return () => h(component, { ...attrs, ref: exposed }) } }
  const render = async () => { hostRenderer.render(h(wrapper, { ...props, onComplete: (video: unknown) => { assert.equal(busy.at(-1), false, 'release own busy before selecting the imported video'); emitted.push(video) }, onBusy: (value: boolean) => busy.push(value), onStarted: () => { starts++ } }), root); await flushHost() }
  await render()
  const nodes = () => allHostNodes(root), button = (name: string) => { const node = nodes().find(node => node.type === 'button' && hostText(node) === name); assert.ok(node, `${name}: ${hostText(root)}`); return node }
  return { props, render, root, exposed, calls, emitted, busy, timers, storage, store, doc, win, defaultHandle, nodes, button,
    setHandle: (next: typeof handle) => { handle = next }, setItems: (values: typeof items) => { items = values }, setGuard: (value: boolean) => { guard = value },
    getVideoReads: () => videoReads, getStarts: () => starts,
    open: async () => { await exposed.value.open(); await settle(() => calls.some(call => call.path.endsWith('?limit=20'))); await flushHost() },
    click: async (name: string) => { const node = button(name); assert.equal(!!node.props.disabled, false, name); node.props.onClick({}); await flushHost() },
    input: async (value: string) => { nodes().find(node => node.type === 'input')!.props['onUpdate:modelValue'](value); await flushHost() },
    submit: async () => { nodes().find(node => node.type === 'form')!.props.onSubmit({ preventDefault() {} }); await flushHost() },
    close: () => { hostRenderer.render(null, root); Object.assign(globalThis, globals) } }
}

test('panel reads recent imports only while open, active and visible; showing it never creates a task', async () => {
  const ui = await mount()
  try {
    assert.equal(ui.calls.length, 0); await ui.open(); assert.equal(ui.calls.length, 1); assert.ok(ui.calls.every(call => !call.init.method))
    ui.props.active = false; await ui.render(); await ui.exposed.value.refresh(); assert.equal(ui.calls.length, 1)
    ui.props.active = true; await ui.render(); await settle(() => ui.calls.length === 2)
    ui.doc.hidden = true; ui.doc.dispatchEvent(new Event('visibilitychange')); await ui.exposed.value.refresh(); assert.equal(ui.calls.length, 2); assert.equal(ui.timers.size, 0)
  } finally { ui.close() }
})
test('create persists exact key/body before POST and background completion never automatically selects or analyzes', async () => {
  const ui = await mount()
  try {
    await ui.open(); await ui.input('https://youtu.be/abcdefghijk?si=tracking'); await ui.submit(); await settle(() => !ui.exposed.value.pending)
    const write = ui.calls.find(call => call.init.method === 'POST')!; assert.ok(write.stored); const receipt = JSON.parse(write.stored!)
    assert.equal(receipt.key, write.init.headers['Idempotency-Key']); assert.equal(receipt.input, write.init.body); assert.equal(receipt.url, url)
    assert.equal(ui.store.size, 0); assert.equal(ui.emitted.length, 0); assert.equal(ui.getStarts(), 0)
    ui.setItems([snapshot({ status: 'COMPLETED', phase: 'COMPLETED', attempt: 1, progress_percent: 100, video_id: id(2) })]); await ui.exposed.value.refresh(); await flushHost()
    assert.equal(ui.emitted.length, 0); assert.equal(ui.getVideoReads(), 0)
    await ui.click('使用此视频'); await settle(() => !ui.exposed.value.pending)
    assert.equal(ui.emitted.length, 1); assert.equal(ui.getVideoReads(), 1); assert.equal(ui.getStarts(), 1)
    assert.ok(!ui.calls.some(call => call.path === '/api/jobs')); assert.deepEqual(ui.busy, [true, false, true, false])
  } finally { ui.close() }
})
test('unknown create preserves original receipt across new input and only exact business 404 exposes same-key replay', async () => {
  const ui = await mount()
  try {
    await ui.open(); ui.setHandle(async (path, init) => { if (init.method === 'POST') throw new HttpError('NETWORK_UNAVAILABLE'); return ui.defaultHandle(path, init) })
    await ui.input(url); await ui.submit(); await settle(() => !ui.exposed.value.pending); const write = ui.calls.find(call => call.init.method === 'POST')!
    await ui.input('https://www.bilibili.com/video/BV1234567890')
    ui.setHandle(async (path, init) => { if (path.includes('/by-key/')) throw new HttpError('HTTP_404', 404); return ui.defaultHandle(path, init) })
    await ui.click('核对原提交'); await settle(() => !ui.exposed.value.pending); assert.equal(ui.nodes().some(node => node.type === 'button' && hostText(node) === '原样重发原提交'), false)
    ui.setHandle(ui.defaultHandle); await ui.click('核对原提交'); await settle(() => !ui.exposed.value.pending); await ui.click('原样重发原提交'); await settle(() => !ui.exposed.value.pending)
    const writes = ui.calls.filter(call => call.init.method === 'POST'); assert.equal(writes.length, 2); assert.equal(writes[1]!.init.body, write.init.body); assert.equal(writes[1]!.init.headers['Idempotency-Key'], write.init.headers['Idempotency-Key'])
    assert.equal(ui.nodes().find(node => node.type === 'input')!.value, 'https://www.bilibili.com/video/BV1234567890')
  } finally { ui.close() }
})
test('reload finds durable original submission by key without reposting or selecting its completed video', async () => {
  const ui = await mount(true)
  try {
    ui.setItems([snapshot({ status: 'COMPLETED', phase: 'COMPLETED', attempt: 1, progress_percent: 100, video_id: id(2) })])
    await ui.open(); await ui.click('核对原提交'); await settle(() => !ui.exposed.value.pending)
    assert.equal(ui.store.size, 0); assert.equal(ui.emitted.length, 0); assert.ok(ui.calls.every(call => !call.init.method))
  } finally { ui.close() }
})
test('abandonment requires a fresh exact absence and removes only the original entry without erasing new input', async () => {
  const ui = await mount(true)
  try {
    await ui.open(); await ui.input('https://www.bilibili.com/video/BV1234567890'); await ui.click('核对原提交'); await settle(() => !ui.exposed.value.pending)
    ui.setHandle(async (path, init) => { if (path.includes('/by-key/')) throw new HttpError('NETWORK_UNAVAILABLE'); return ui.defaultHandle(path, init) })
    await ui.click('放弃未提交链接'); await settle(() => !ui.exposed.value.pending); assert.equal(ui.store.size, 1)
    ui.setHandle(ui.defaultHandle); await ui.click('放弃未提交链接'); await settle(() => !ui.exposed.value.pending)
    assert.equal(ui.store.size, 0); assert.equal(ui.nodes().find(node => node.type === 'input')!.value, 'https://www.bilibili.com/video/BV1234567890')
    assert.ok(ui.calls.every(call => !call.init.method))
  } finally { ui.close() }
})
test('fresh abandonment lookup that discovers the original task adopts it instead of issuing a second POST', async () => {
  const ui = await mount(true)
  try {
    await ui.open(); await ui.click('核对原提交'); await settle(() => !ui.exposed.value.pending)
    ui.setItems([snapshot()]); await ui.click('放弃未提交链接'); await settle(() => !ui.exposed.value.pending)
    assert.equal(ui.store.size, 0); assert.match(hostText(ui.root), /等待导入/); assert.ok(ui.calls.every(call => !call.init.method)); assert.equal(ui.emitted.length, 0)
  } finally { ui.close() }
})
test('old list response cannot revive a cancelled import even when the transport ignores abort', async () => {
  const ui = await mount(), delayed = deferred<unknown>()
  try {
    ui.setItems([snapshot({ status: 'RUNNING', phase: 'DOWNLOADING', attempt: 1 })]); await ui.open()
    let delayedOnce = false
    ui.setHandle(async (path, init) => { if (path.endsWith('?limit=20') && !delayedOnce) { delayedOnce = true; return delayed.promise } return ui.defaultHandle(path, init) })
    const read = ui.exposed.value.refresh(); await flushHost(); await ui.click('取消导入'); await settle(() => !ui.exposed.value.pending)
    delayed.resolve({ items: [snapshot({ status: 'RUNNING', phase: 'DOWNLOADING', attempt: 1, updated_at: '2026-11-15T00:00:03Z' })] }); await read; await flushHost()
    assert.match(hostText(ui.root), /已取消/); assert.equal(ui.nodes().some(node => node.type === 'button' && hostText(node) === '取消导入'), false)
    assert.equal(ui.calls.find(call => call.init.method === 'DELETE')!.path, `/api/source-imports/${id(1)}`)
  } finally { delayed.resolve({ items: [] }); ui.close() }
})
test('editor guard blocks submission and rechecks after video lookup; retry freezes expected attempt', async () => {
  const ui = await mount(), videoReply = deferred<unknown>()
  try {
    await ui.open(); await ui.input(url); ui.setGuard(false); await ui.submit(); assert.equal(ui.calls.filter(call => call.init.method === 'POST').length, 0)
    ui.setGuard(true); ui.setItems([snapshot({ status: 'FAILED', phase: 'FAILED', attempt: 1, error_code: 'IMPORT_DOWNLOAD_FAILED' })]); await ui.exposed.value.refresh(); await ui.click('重试导入'); await settle(() => !ui.exposed.value.pending)
    assert.equal(ui.calls.find(call => call.path.endsWith('/retry'))!.init.body, JSON.stringify({ expected_attempt: 1 }))
    ui.setItems([snapshot({ status: 'COMPLETED', phase: 'COMPLETED', attempt: 2, progress_percent: 100, video_id: id(2) })]); await ui.exposed.value.refresh()
    ui.setHandle(async (path, init) => path.startsWith('/api/videos/') ? videoReply.promise : ui.defaultHandle(path, init))
    await ui.click('使用此视频'); ui.setGuard(false)
    videoReply.resolve({ id: id(2), original_filename: '录播.mp4', content_hash: 'a'.repeat(64), size_bytes: 100, content_url: `/api/videos/${id(2)}/content`, media_validation_status: 'HEADER_CHECKED', created_at: '2026-11-15T00:00:00Z' })
    await settle(() => !ui.exposed.value.pending); assert.equal(ui.emitted.length, 0)
  } finally { videoReply.resolve(null); ui.close() }
})
test('hiding while CREATE is in flight never aborts its write or consumes the unresolved receipt', async () => {
  const ui = await mount(), posted = deferred<unknown>()
  try {
    await ui.open(); ui.setHandle(async (path, init) => init.method === 'POST' ? posted.promise : ui.defaultHandle(path, init))
    await ui.input(url); await ui.submit(); assert.equal(ui.exposed.value.pending, true); assert.equal(ui.store.size, 1)
    ui.doc.hidden = true; ui.doc.dispatchEvent(new Event('visibilitychange')); posted.resolve(snapshot()); await settle(() => !ui.exposed.value.pending)
    assert.equal(ui.store.size, 1); assert.equal(ui.calls.find(call => call.init.method === 'POST')!.init.signal, undefined); assert.equal(ui.emitted.length, 0)
  } finally { posted.resolve(snapshot()); ui.close() }
})
