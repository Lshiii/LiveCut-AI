import test from 'node:test'
import assert from 'node:assert/strict'
import { h, ref } from 'vue'
import { readFileSync } from 'node:fs'
import * as protocol from '../manualClips.ts'
import * as receipts from '../manualClipReceipts.ts'
import * as transcript from '../transcriptSearch.ts'
import * as snapshots from '../snapshotStream.ts'
import * as preview from '../sequencePreview.ts'
import * as delivery from '../exportVideoPackage.ts'
import { AgentReadScope } from '../videoAgent.ts'
import { allHostNodes, compileHostComponent, flushHost, hostNode, hostRenderer, hostText } from './support/vueHost.ts'
import { manualExport, manualId, manualOwner, manualPlan, manualSource, manualSubmission, manualUtterance } from './support/manualClipFixture.ts'

class HttpError extends Error { code: string; status: number; constructor(code: string, status = 409) { super(code); this.code = code; this.status = status } }
const clone = <T>(value: T): T => structuredClone(value)
function deferred<T>() { let resolve!: (value: T) => void, reject!: (error: unknown) => void; const promise = new Promise<T>((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const settle = async (check: () => boolean) => { const deadline = Date.now() + 2000; while (!check()) { if (Date.now() > deadline) throw new Error('Component state did not settle'); await new Promise(done => setTimeout(done, 2)); await flushHost() } }
async function mount(active = true, initialStore?: string, searchWorkspace = false) {
  const globals = { document: globalThis.document, Document: globalThis.Document, window: globalThis.window, localStorage: globalThis.localStorage }
  class Doc extends EventTarget {}
  const doc = Object.assign(new Doc(), { hidden: false, baseURI: 'http://test/', activeElement: null }), win = Object.assign(new EventTarget(), { setTimeout, clearTimeout })
  const data = new Map<string, string>(); if (initialStore) data.set(receipts.manualClipReceiptStorageKey(JSON.parse(initialStore)), initialStore)
  let failWrite = false
  const storage = { get length() { return data.size }, key: (index: number) => [...data.keys()][index] ?? null, getItem: (key: string) => data.get(key) ?? null, setItem: (key: string, value: string) => { if (failWrite) throw new Error('quota'); data.set(key, value) }, removeItem: (key: string) => { data.delete(key) } }
  const firstPending = () => [...data.entries()].find(([key]) => key.startsWith(receipts.manualClipReceiptSlot(manualOwner.job_id)))?.[1] ?? null
  Object.assign(globalThis, { document: doc, Document: Doc, window: win, localStorage: storage })
  const calls: Array<{ path: string; method: string; init: any; stored: string | null }> = [], versions = new Map<number, protocol.ManualClipPlan>(), submitted = new Map<string, unknown>()
  let currentExport: any = null
  const defaultHandle = async (path: string, init: any) => {
    const url = new URL(path, 'http://test/'), method = init.method ?? 'GET'
    if (method === 'GET' && url.pathname.endsWith('/transcript')) {
      const offset = Number(url.searchParams.get('offset')); return { schema_version: '1.0', data_kind: 'transcript_page', source: manualSource, q: url.searchParams.get('q'), offset, limit: 20,
        total_utterances: 6, matched_utterances: 6, next_offset: offset < 4 ? offset + 2 : null, items: [manualUtterance(offset), manualUtterance(offset + 1)] }
    }
    if (method === 'GET' && url.pathname.endsWith('/transcript/range')) {
      const first = manualUtterance(Number(url.searchParams.get('start_utterance_id')!.slice(1))), last = manualUtterance(Number(url.searchParams.get('end_utterance_id')!.slice(1)))
      return { schema_version: '1.0', data_kind: 'transcript_range', source: manualSource, start_utterance_id: first.utterance_id, end_utterance_id: last.utterance_id,
        range: { start_ms: first.start_ms, end_ms: last.end_ms, utterance_count: Number(last.utterance_id.slice(1)) - Number(first.utterance_id.slice(1)) + 1, start_text: first.text, end_text: last.text } }
    }
    if (method === 'GET' && url.pathname.endsWith('/transcript/context')) {
      const center = manualUtterance(Number(url.searchParams.get('utterance_id')!.slice(1)))
      return { schema_version: '1.0', data_kind: 'transcript_context', source: manualSource, center, previous: null, next: null }
    }
    if (method === 'GET' && /\/(manual-clip-plan-submissions|revision-submissions|export-submissions)\//.test(path)) {
      const key = url.pathname.split('/').at(-1)!; if (!submitted.has(key)) throw new HttpError(path.includes('/export-submissions/') ? 'MANUAL_CLIP_EXPORT_SUBMISSION_NOT_FOUND' : 'MANUAL_CLIP_SUBMISSION_NOT_FOUND', 404)
      return clone(submitted.get(key))
    }
    if (method === 'GET' && url.pathname.endsWith('/manual-clip-plans')) return { schema_version: '1.0', data_kind: 'manual_clip_plan_list', job_id: manualOwner.job_id, items: [], total: 0, limit: 20, offset: Number(url.searchParams.get('offset')) }
    if (method === 'GET' && url.pathname.endsWith('/exports')) return { items: currentExport ? [currentExport] : [], limit: 20, offset: 0 }
    if (method === 'GET' && url.pathname === `/api/exports/${manualId(20)}`) return currentExport
    if (method === 'GET' && url.pathname === `/api/manual-clip-plans/${manualId(10)}`) {
      const revision = url.searchParams.has('revision') ? Number(url.searchParams.get('revision')) : Math.max(...versions.keys())
      const value = versions.get(revision); if (!value) throw new HttpError('MANUAL_CLIP_NOT_FOUND', 404); return clone(value)
    }
    if (method === 'POST' && (url.pathname.endsWith('/manual-clip-plans') || url.pathname.endsWith('/revisions'))) {
      const input = JSON.parse(init.body), revision = url.pathname.endsWith('/revisions') ? input.base_revision + 1 : 1
      const value = manualPlan(input, revision); versions.set(revision, value)
      const receipt = manualSubmission(revision === 1 ? 'CREATE' : 'REVISION', input, init.headers['Idempotency-Key'], value); submitted.set(receipt.idempotency_key, receipt); return clone(receipt)
    }
    if (method === 'POST' && url.pathname.endsWith('/confirm')) {
      const input = JSON.parse(init.body), value = versions.get(input.revision)!; const confirmed = { ...value, status: 'CONFIRMED' as const, confirmed_revisions: [value.revision] }; versions.set(value.revision, confirmed); return clone(confirmed)
    }
    if (method === 'POST' && url.pathname.endsWith('/exports')) { const input = JSON.parse(init.body); currentExport = manualExport(versions.get(input.revision)!); submitted.set(init.headers['Idempotency-Key'], currentExport); return clone(currentExport) }
    throw new Error(`Unexpected fixture request ${method} ${path}`)
  }
  let handle = defaultHandle
  const api = { ApiError: HttpError, api: (path: string, init: any = {}) => { calls.push({ path, method: init.method ?? 'GET', init, stored: init.headers?.['Idempotency-Key'] ? storage.getItem(receipts.manualClipReceiptSlot(manualOwner.job_id) + init.headers['Idempotency-Key']) : firstPending() }); return handle(path, init) } }
  const previewComponent = compileHostComponent(new URL('../ManualClipPreview.vue', import.meta.url), { './sequencePreview': preview, './transcriptSearch': transcript })
  const packageComponent = compileHostComponent(new URL('../VideoPackagePanel.vue', import.meta.url), { './api': api, './videoAgent': { AgentReadScope }, './exportVideoPackage': delivery })
  const manualComponent = compileHostComponent(new URL('../ManualClipPanel.vue', import.meta.url), { './api': api, './videoAgent': { AgentReadScope }, './snapshotStream': snapshots,
    './transcriptSearch': transcript, './manualClips': protocol, './manualClipReceipts': receipts, './ManualClipPreview.vue': previewComponent,
    './VideoPackagePanel.vue': packageComponent, './FailureDiagnostic.vue': { render: () => null }, './CaptionPanel.vue': { setup(_props: unknown, { expose }: any) { expose({ dirty: false, pending: false }); return () => null } } })
  const component = searchWorkspace ? compileHostComponent(new URL('../TranscriptSearchPanel.vue', import.meta.url), { './api': api, './transcriptSearch': transcript, './sequencePreview': preview, './ManualClipPanel.vue': manualComponent }) : manualComponent
  const root = hostNode('root'), exposed = ref<any>(null), props = { jobId: manualOwner.job_id, videoId: manualOwner.video_id, generation: manualOwner.generation,
    videoHash: manualOwner.source_video_sha256, videoUrl: '/original.mp4', videoName: '原视频.mp4', active }
  const wrapper = { setup(_props: unknown, { attrs }: any) { return () => h(component, { ...attrs, ref: exposed }) } }
  const render = async () => { hostRenderer.render(h(wrapper, { ...props }), root); await flushHost() }
  await render(); const nodes = () => allHostNodes(root)
  const button = (label: string) => { const value = nodes().find(node => node.type === 'button' && hostText(node) === label); assert.ok(value, `${label}: ${hostText(root)}`); return value }
  const ui = { root, exposed, props, doc, win, calls, versions, submitted, storage, firstPending, render, nodes, button, defaultHandle,
    setHandle: (next: typeof handle) => { handle = next }, failStorageWrite: () => { failWrite = true },
    click: async (label: string) => { const value = button(label); assert.equal(!!value.props.disabled, false, label); value.props.onClick({}); await flushHost() },
    setTitle: async (value: string) => { nodes().find(node => node.type === 'input' && node.props['aria-label'] === '人工成片标题')!.props['onUpdate:modelValue'](value); await flushHost() },
    choose: async (field: 'start' | 'end', n: number, source = manualSource) => { const result = exposed.value.setBoundary(field, source, manualUtterance(n)); await flushHost(); return result },
    close: () => { hostRenderer.render(null, root); Object.assign(globalThis, globals) } }
  return ui
}
async function readyRange(ui: Awaited<ReturnType<typeof mount>>) { await ui.choose('start', 1); await ui.choose('end', 4); await ui.click('核对完整范围'); await settle(() => !ui.button('创建人工剪辑方案').props.disabled) }
async function created(ui: Awaited<ReturnType<typeof mount>>) { await readyRange(ui); await ui.click('创建人工剪辑方案'); await settle(() => hostText(ui.root).includes('人工方案 r1 已保存')); }

test('manual production panel preserves explicit cross-page endpoints while inactive and only counts/permits preview after full range GET', async () => {
  const ui = await mount(false)
  try {
    assert.equal(ui.calls.length, 0); assert.equal(await ui.choose('start', 1), true); assert.equal(await ui.choose('end', 4), true)
    assert.equal(ui.exposed.value.dirty, true); assert.equal(ui.calls.length, 0)
    ui.props.active = true; await ui.render(); assert.ok(ui.calls.every(call => call.method === 'GET'))
    assert.equal(ui.button('创建人工剪辑方案').props.disabled, true); assert.equal(ui.button('预览完整人工区间').props.disabled, true)
    await ui.click('核对完整范围'); await settle(() => !ui.button('创建人工剪辑方案').props.disabled)
    assert.match(hostText(ui.root), /4 句/); assert.equal(ui.calls.filter(call => call.path.includes('/transcript/range')).length, 1)
    ui.props.active = false; await ui.render(); assert.equal(ui.exposed.value.summary.start.utterance_id, 'u1'); assert.equal(ui.exposed.value.summary.end.utterance_id, 'u4')
    ui.props.active = true; await ui.render(); await ui.setTitle('手动保留全部中间内容'); assert.equal(ui.button('创建人工剪辑方案').props.disabled, false)
    await ui.choose('end', 0); assert.match(hostText(ui.root), /止句不能早于起句/); assert.equal(ui.button('创建人工剪辑方案').props.disabled, true)
    assert.equal(await ui.choose('end', 4, { ...manualSource, transcript_sha256: 'f'.repeat(64) }), false); assert.equal(ui.exposed.value.summary.end.utterance_id, 'u0')
  } finally { ui.close() }
})

test('manual production CREATE writes original local receipt before POST, verifies exact r1/ASR and never auto-confirms or exports', async () => {
  const ui = await mount()
  try {
    await created(ui)
    const writes = ui.calls.filter(call => call.method === 'POST'); assert.equal(writes.length, 1)
    const entry = JSON.parse(writes[0]!.stored!); assert.equal(entry.key, writes[0]!.init.headers['Idempotency-Key']); assert.equal(entry.input, writes[0]!.init.body)
    assert.deepEqual(Object.keys(JSON.parse(entry.input)).sort(), ['end_utterance_id', 'source_fingerprint', 'start_utterance_id', 'title'])
    assert.ok(ui.calls.some(call => call.path === `/api/manual-clip-plans/${manualId(10)}?revision=1`)); assert.equal(ui.exposed.value.dirty, false)
    assert.equal(ui.firstPending(), null)
    assert.equal(ui.button('生成该版本 MP4').props.disabled, true); assert.equal(ui.button('确认人工 r1').props.disabled, true)
    assert.ok(!ui.calls.some(call => /clip-requests|topic-review|agent-sessions|model/.test(call.path)))
  } finally { ui.close() }
})

test('manual storage failure sends no POST and retains source/title/range draft', async () => {
  const ui = await mount()
  try { await readyRange(ui); ui.failStorageWrite(); await ui.click('创建人工剪辑方案'); await flushHost(); assert.equal(ui.calls.filter(call => call.method === 'POST').length, 0); assert.equal(ui.exposed.value.dirty, true); assert.match(hostText(ui.root), /本机回执无法可靠保存/); assert.equal(ui.exposed.value.summary.start.utterance_id, 'u1') }
  finally { ui.close() }
})

test('manual unknown create recovers its immutable receipt and exact revision without reposting or adopting a new head', async () => {
  const ui = await mount()
  try {
    await readyRange(ui)
    ui.setHandle(async (path, init) => {
      if (init.method === 'POST') { const result = await ui.defaultHandle(path, init); const first = ui.versions.get(1)!; ui.versions.set(1, { ...first, latest_revision: 2 }); ui.versions.set(2, manualPlan({ source_fingerprint: manualSource.source_fingerprint, title: '另一页面新标题', start_utterance_id: 'u2', end_utterance_id: 'u5' }, 2)); assert.ok(result); throw new HttpError('NETWORK_UNAVAILABLE', 0) }
      return ui.defaultHandle(path, init)
    })
    await ui.click('创建人工剪辑方案'); await settle(() => !!ui.firstPending() && !ui.exposed.value.pending)
    ui.setHandle(ui.defaultHandle); await ui.click('核对原提交'); await settle(() => hostText(ui.root).includes('最新已到 r2'))
    assert.equal(ui.calls.filter(call => call.method === 'POST').length, 1); assert.equal(ui.exposed.value.summary.title, '人工片段')
    assert.ok(!ui.calls.some(call => call.path === `/api/manual-clip-plans/${manualId(10)}`)); assert.equal(ui.firstPending(), null)
    await ui.click('读取并采用最新版本'); await settle(() => ui.exposed.value.summary.title === '另一页面新标题'); assert.equal(ui.exposed.value.dirty, false)
  } finally { ui.close() }
})

test('manual unknown receipt requires precise business 404 before explicit replay of the identical key/body', async () => {
  const ui = await mount()
  try {
    await readyRange(ui); ui.setHandle(async (path, init) => { if (init.method === 'POST') throw new HttpError('REQUEST_TIMEOUT', 0); throw new HttpError('HTTP_404', 404) })
    await ui.click('创建人工剪辑方案'); await settle(() => !ui.exposed.value.pending); const original = ui.calls.find(call => call.method === 'POST')!
    await ui.click('核对原提交'); await settle(() => !ui.exposed.value.pending); assert.equal(ui.nodes().filter(node => node.type === 'button' && hostText(node) === '按原内容重试').length, 0)
    ui.setHandle(ui.defaultHandle); await ui.click('核对原提交'); await settle(() => ui.nodes().some(node => node.type === 'button' && hostText(node) === '按原内容重试'))
    assert.equal(ui.calls.filter(call => call.method === 'POST').length, 1)
    await ui.click('按原内容重试'); await settle(() => hostText(ui.root).includes('人工方案 r1 已保存'))
    const writes = ui.calls.filter(call => call.method === 'POST'); assert.equal(writes.length, 2); assert.equal(writes[1]!.init.body, original.init.body); assert.equal(writes[1]!.init.headers['Idempotency-Key'], original.init.headers['Idempotency-Key'])
  } finally { ui.close() }
})

test('manual first explicit rollback may clear its own entry but UNKNOWN replay rejection retains the original entry', async () => {
  for (const unknown of [false, true]) {
    const ui = await mount()
    try {
      await readyRange(ui); ui.setHandle(async (_path, init) => { if (init.method === 'POST') throw new HttpError(unknown ? 'NETWORK_UNAVAILABLE' : 'MANUAL_CLIP_REVISION_CONFLICT', unknown ? 0 : 409); throw new HttpError('MANUAL_CLIP_SUBMISSION_NOT_FOUND', 404) })
      await ui.click('创建人工剪辑方案'); await settle(() => !ui.exposed.value.pending)
      if (!unknown) { assert.equal(ui.firstPending(), null); assert.equal(ui.exposed.value.dirty, true) }
      else { const original = ui.firstPending(); await ui.click('核对原提交'); await settle(() => !ui.exposed.value.pending); ui.setHandle(async () => { throw new HttpError('MANUAL_CLIP_SOURCE_CHANGED', 409) }); await ui.click('按原内容重试'); await settle(() => !ui.exposed.value.pending); assert.equal(ui.firstPending(), original) }
    } finally { ui.close() }
  }
})

test('manual hidden POST completion retains original recovery entry and does not fetch details or overwrite its draft until active', async () => {
  const ui = await mount()
  try {
    await readyRange(ui); const response = deferred<unknown>()
    ui.setHandle((path, init) => init.method === 'POST' ? response.promise : ui.defaultHandle(path, init)); await ui.click('创建人工剪辑方案')
    const write = ui.calls.find(call => call.method === 'POST')!, input = JSON.parse(write.init.body), value = manualPlan(input), receipt = manualSubmission('CREATE', input, write.init.headers['Idempotency-Key'], value)
    ui.versions.set(1, value); ui.submitted.set(receipt.idempotency_key, receipt); ui.props.active = false; await ui.render(); const count = ui.calls.length
    response.resolve(receipt); await settle(() => !ui.exposed.value.pending); assert.equal(ui.calls.length, count); assert.ok(ui.firstPending()); assert.equal(ui.exposed.value.dirty, true)
    ui.setHandle(ui.defaultHandle); ui.props.active = true; await ui.render(); await settle(() => !ui.exposed.value.unresolved)
    assert.equal(ui.calls.filter(call => call.method === 'POST').length, 1); assert.equal(ui.exposed.value.dirty, false)
  } finally { ui.close() }
})

test('manual saved revision, explicit confirmation and key-bound export reach the existing delivery and fresh MP4 ZIP selector', async () => {
  const ui = await mount()
  try {
    await created(ui); await ui.setTitle('人工保存的新标题'); await ui.click('保存为新修订'); await settle(() => hostText(ui.root).includes('人工方案 r2 已保存'))
    const revision = ui.calls.filter(call => call.method === 'POST').at(-1)!; const input = JSON.parse(revision.init.body); assert.equal(input.base_revision, 1); assert.ok(input.base_plan_hash); assert.ok(revision.path.endsWith('/revisions'))
    ui.nodes().find(node => node.type === 'input' && node.props.type === 'checkbox')!.props['onUpdate:modelValue'](true); await flushHost()
    await ui.click('确认人工 r2'); await settle(() => hostText(ui.root).includes('人工 r2 已确认')); assert.equal(ui.calls.filter(call => call.path.endsWith('/exports') && call.method === 'POST').length, 0)
    await ui.click('生成该版本 MP4'); await settle(() => hostText(ui.root).includes('已核对原导出'))
    const exported = ui.calls.find(call => call.path.endsWith('/exports') && call.method === 'POST')!; const local = JSON.parse(exported.stored!); assert.equal(local.operation, 'EXPORT'); assert.equal(local.key, exported.init.headers['Idempotency-Key']); assert.equal(local.input, exported.init.body)
    assert.ok(hostText(ui.root).includes('下载人工 MP4')); assert.ok(ui.button('校对人工成片字幕'))
    await ui.click('全选已完成'); await ui.click('核对所选成片'); await settle(() => ui.nodes().some(node => node.type === 'a' && hostText(node) === '下载所选MP4 ZIP'))
    assert.ok(ui.calls.some(call => call.path === `/api/exports/${manualId(20)}`)); assert.ok(ui.calls.every(call => !/clip-requests|topic-review|agent-sessions/.test(call.path)))
  } finally { ui.close() }
})

test('manual Workbench integration aggregates dirty/pending through the existing source tab without adding a sixth primary tab', () => {
  const workbench = readFileSync(new URL('../Workbench.vue', import.meta.url), 'utf8'), search = readFileSync(new URL('../TranscriptSearchPanel.vue', import.meta.url), 'utf8')
  assert.match(workbench, /const editorsDirty[^\n]+transcriptEditor\.value\?\.dirty/); assert.match(workbench, /const editorsPending[^\n]+transcriptEditor\.value\?\.pending/)
  assert.match(workbench, /<TranscriptSearchPanel[^>]+ref="transcriptEditor"/); assert.doesNotMatch(workbench, /workbench-tab-manual/)
  assert.match(search, /v-show="view === 'search'"/); assert.match(search, /v-show="view === 'manual'"/); assert.match(search, /ManualClipPanel ref="manualEditor"/)
})

test('manual source-invalid export reads stop pending or playing preview and require a new explicit full-range check', async () => {
  for (const pendingMetadata of [true, false]) {
    const ui = await mount()
    try {
      await created(ui)
      const video = ui.nodes().find(node => node.type === 'video')!; if (pendingMetadata) video.readyState = 0
      await ui.click('预览完整人工区间'); assert.equal(hostText(ui.root).includes('正在准备人工区间定位'), pendingMetadata)
      if (!pendingMetadata) assert.equal(video.paused, false)
      ui.setHandle(async (path, init) => { if (path.endsWith('/exports')) throw new HttpError('MANUAL_CLIP_SOURCE_INVALID'); return ui.defaultHandle(path, init) })
      await ui.click('刷新人工成片'); await settle(() => hostText(ui.root).includes('原句来源暂未通过校验'))
      assert.equal(video.paused, true); assert.doesNotMatch(hostText(ui.root), /正在准备人工区间定位/); assert.equal(ui.button('预览完整人工区间').props.disabled, true)
      video.readyState = 2; video.props.onLoadedmetadata({ currentTarget: video }); await flushHost(); assert.equal(video.paused, true)
      ui.setHandle(ui.defaultHandle); await ui.click('刷新人工成片'); assert.equal(ui.button('预览完整人工区间').props.disabled, true)
      await ui.click('核对完整范围'); await settle(() => !ui.button('预览完整人工区间').props.disabled); assert.equal(video.paused, true)
    } finally { ui.close() }
  }
})

test('manual current write or confirm source rejection revokes proof and acceptance without discarding the range', async () => {
  for (const confirm of [false, true]) {
    const ui = await mount()
    try {
      if (confirm) { await created(ui); ui.nodes().find(node => node.type === 'input' && node.props.type === 'checkbox')!.props['onUpdate:modelValue'](true); await flushHost() }
      else await readyRange(ui)
      const video = ui.nodes().find(node => node.type === 'video')!; video.readyState = 0; await ui.click('预览完整人工区间')
      ui.setHandle(async (path, init) => { if (init.method === 'POST') throw new HttpError('MANUAL_CLIP_SOURCE_CHANGED'); return ui.defaultHandle(path, init) })
      await ui.click(confirm ? '确认人工 r1' : '创建人工剪辑方案'); await settle(() => !ui.exposed.value.pending)
      assert.equal(ui.button('预览完整人工区间').props.disabled, true); assert.doesNotMatch(hostText(ui.root), /正在准备人工区间定位/)
      assert.equal(ui.exposed.value.summary.start.utterance_id, 'u1'); assert.equal(ui.firstPending(), null)
      if (confirm) assert.equal(ui.button('确认人工 r1').props.disabled, true)
      ui.setHandle(ui.defaultHandle); await ui.click('核对完整范围'); await settle(() => !ui.button('预览完整人工区间').props.disabled)
      if (confirm) assert.equal(ui.button('确认人工 r1').props.disabled, true)
      video.readyState = 2; video.props.onLoadedmetadata({ currentTarget: video }); await flushHost(); assert.equal(video.playCalls, 0)
    } finally { ui.close() }
  }
})

test('manual late old export failure cannot revoke a new explicitly checked draft', async () => {
  const ui = await mount()
  try {
    await created(ui); const old = deferred<unknown>()
    ui.setHandle((path, init) => path.endsWith('/exports') ? old.promise : ui.defaultHandle(path, init)); await ui.click('刷新人工成片')
    const signal = ui.calls.at(-1)!.init.signal; await ui.click('清空本页人工草稿'); assert.equal(signal.aborted, true)
    await readyRange(ui); old.reject(new HttpError('MANUAL_CLIP_SOURCE_CHANGED')); await flushHost()
    assert.equal(ui.button('创建人工剪辑方案').props.disabled, false); assert.equal(ui.button('预览完整人工区间').props.disabled, false)
  } finally { ui.close() }
})

test('manual recovering another window original never replaces a different dirty draft', async () => {
  const ui = await mount()
  try {
    await readyRange(ui); await ui.setTitle('当前页面另一个草稿')
    const input = protocol.manualClipInput(manualSource, '旧页面原提交', manualUtterance(2), manualUtterance(5)), value = manualPlan(input)
    const entry = receipts.parseManualClipPending({ version: 1, operation: 'CREATE', job_id: manualOwner.job_id, video_id: manualOwner.video_id, plan_id: null, key: manualId(77), input: JSON.stringify(input) })
    ui.versions.set(1, value); ui.submitted.set(entry.key, manualSubmission('CREATE', input, entry.key, value)); ui.storage.setItem(receipts.manualClipReceiptStorageKey(entry), JSON.stringify(entry))
    const storageEvent = Object.assign(new Event('storage'), { key: receipts.manualClipReceiptStorageKey(entry) }); ui.win.dispatchEvent(storageEvent); await flushHost()
    await ui.click('核对原提交'); await settle(() => !ui.exposed.value.unresolved)
    assert.equal(ui.exposed.value.summary.title, '当前页面另一个草稿'); assert.equal(ui.exposed.value.summary.start.utterance_id, 'u1'); assert.equal(ui.exposed.value.dirty, true)
    assert.equal(ui.button('打开已核对原提交版本').props.disabled, true); assert.equal(ui.firstPending(), null); assert.equal(ui.calls.filter(call => call.method === 'POST').length, 0)
    await ui.click('清空本页人工草稿'); await ui.click('打开已核对原提交版本'); await settle(() => ui.exposed.value.summary.title === '旧页面原提交')
  } finally { ui.close() }
})

test('real search and manual SFCs hand off explicit endpoints, preserve across paging/query/subview tabs and aggregate task guards', async () => {
  const ui = await mount(true, undefined, true)
  try {
    assert.equal(ui.calls.length, 1); assert.ok(ui.calls[0]!.path.includes('/transcript?')); assert.equal(ui.exposed.value.dirty, false)
    const choose = async (index: number) => { ui.nodes().filter(node => node.type === 'button' && node.props['aria-label']?.startsWith('选择原句 '))[index]!.props.onClick(); await flushHost() }
    await choose(0); assert.equal(ui.exposed.value.dirty, false); await ui.click('设为人工起句'); assert.equal(ui.exposed.value.dirty, true)
    assert.equal(ui.button('搜索与回看').props['aria-selected'], true); assert.equal(ui.calls.filter(call => call.path.includes('/manual-clip-plans')).length, 0)
    await ui.click('下一页'); await choose(1); await ui.click('设为人工止句')
    const query = ui.nodes().find(node => node.type === 'input' && node.props['aria-label'] === '搜索原句') ?? ui.nodes().find(node => node.type === 'input')!
    query.props['onUpdate:modelValue']('缓存'); ui.nodes().find(node => node.type === 'form')!.props.onSubmit({ preventDefault() {} }); await flushHost()
    await ui.click('查看人工片段'); await settle(() => ui.calls.some(call => call.path.includes('/manual-clip-plans?')))
    assert.match(hostText(ui.root), /原始 ASR 0 缓存/); assert.match(hostText(ui.root), /原始 ASR 3 缓存/)
    await ui.click('核对完整范围'); await settle(() => !ui.button('创建人工剪辑方案').props.disabled); assert.match(hostText(ui.root), /4 句/)
    const video = ui.nodes().filter(node => node.type === 'video').at(-1)!; await ui.click('预览完整人工区间'); assert.equal(video.paused, false)
    await ui.click('搜索与回看'); assert.equal(video.paused, true); assert.equal(ui.exposed.value.dirty, true)
    await ui.click('人工剪辑'); assert.equal(ui.button('创建人工剪辑方案').props.disabled, false)
    const result = deferred<unknown>(); ui.setHandle((path, init) => init.method === 'POST' ? result.promise : ui.defaultHandle(path, init)); await ui.click('创建人工剪辑方案')
    assert.equal(ui.exposed.value.pending, true); ui.props.active = false; await ui.render(); assert.equal(ui.exposed.value.pending, true)
    result.reject(new HttpError('NETWORK_UNAVAILABLE', 0)); await settle(() => !ui.exposed.value.pending); assert.equal(ui.exposed.value.dirty, true); assert.ok(ui.firstPending())
    assert.ok(ui.calls.every(call => !/agent-sessions|clip-requests|topic-review|model/.test(call.path)))
  } finally { ui.close() }
})

test('manual UNKNOWN export recovers the same batch by original key without creating a second encoding', async () => {
  const ui = await mount()
  try {
    await created(ui); ui.nodes().find(node => node.type === 'input' && node.props.type === 'checkbox')!.props['onUpdate:modelValue'](true); await flushHost()
    await ui.click('确认人工 r1'); await settle(() => hostText(ui.root).includes('人工 r1 已确认'))
    ui.setHandle(async (path, init) => { if (init.method === 'POST' && path.endsWith('/exports')) { await ui.defaultHandle(path, init); throw new HttpError('NETWORK_UNAVAILABLE', 0) } return ui.defaultHandle(path, init) })
    await ui.click('生成该版本 MP4'); await settle(() => !ui.exposed.value.pending); const entry = JSON.parse(ui.firstPending()!)
    assert.equal(entry.operation, 'EXPORT'); assert.equal(ui.button('生成该版本 MP4').props.disabled, true)
    ui.setHandle(ui.defaultHandle); await ui.click('核对原提交'); await settle(() => !ui.exposed.value.unresolved)
    assert.ok(ui.calls.some(call => call.path === `/api/manual-clip-plans/${manualId(10)}/export-submissions/${entry.key}`))
    assert.equal(ui.calls.filter(call => call.method === 'POST' && call.path.endsWith('/exports')).length, 1); assert.equal(ui.firstPending(), null)
    assert.match(hostText(ui.root), /下载人工 MP4/)
  } finally { ui.close() }
})

test('manual failed submission hash never adopts response or discards original key/body', async () => {
  const ui = await mount()
  try {
    await readyRange(ui)
    ui.setHandle(async (path, init) => { const value: any = await ui.defaultHandle(path, init); return init.method === 'POST' ? { ...value, request_hash: 'a'.repeat(64) } : value })
    await ui.click('创建人工剪辑方案'); await settle(() => !ui.exposed.value.pending)
    const entry = JSON.parse(ui.firstPending()!); assert.equal(entry.operation, 'CREATE'); assert.equal(ui.exposed.value.dirty, true); assert.equal(ui.exposed.value.unresolved, true)
    assert.equal(ui.calls.filter(call => /\/manual-clip-plans\/[^/]+\?revision=/.test(call.path)).length, 0)
    ui.setHandle(ui.defaultHandle); await ui.click('核对原提交'); await settle(() => !ui.exposed.value.unresolved); assert.equal(ui.exposed.value.dirty, false)
  } finally { ui.close() }
})

test('manual export version action fresh-reads its exact original revision and cannot discard a dirty draft', async () => {
  const ui = await mount()
  try {
    await created(ui); ui.nodes().find(node => node.type === 'input' && node.props.type === 'checkbox')!.props['onUpdate:modelValue'](true); await flushHost()
    await ui.click('确认人工 r1'); await settle(() => hostText(ui.root).includes('人工 r1 已确认')); await ui.click('生成该版本 MP4'); await settle(() => hostText(ui.root).includes('已核对原导出'))
    await ui.setTitle('r2 未确认新稿'); assert.equal(ui.button('查看此版本方案 r1').props.disabled, true)
    await ui.click('保存为新修订'); await settle(() => hostText(ui.root).includes('人工方案 r2 已保存')); assert.equal(ui.exposed.value.summary.title, 'r2 未确认新稿')
    const first = ui.versions.get(1)!; ui.versions.set(1, { ...first, latest_revision: 2 }); const before = ui.calls.length
    await ui.click('查看此版本方案 r1'); await settle(() => ui.exposed.value.summary.title === '人工片段')
    assert.ok(ui.calls.slice(before).some(call => call.path === `/api/manual-clip-plans/${manualId(10)}?revision=1`)); assert.equal(ui.exposed.value.dirty, false)
    assert.match(hostText(ui.root), /当前为已核对 r1；最新为 r2/); assert.equal(ui.button('生成该版本 MP4').props.disabled, false)
    assert.ok(!ui.calls.slice(before).some(call => call.method === 'POST'))
  } finally { ui.close() }
})
