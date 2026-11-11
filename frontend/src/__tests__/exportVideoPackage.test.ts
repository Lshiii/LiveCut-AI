import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { compileScript, parse } from '@vue/compiler-sfc'
import { transpileModule, ModuleKind, ScriptTarget } from 'typescript'
import { createRenderer, h, nextTick } from 'vue'
import type { ExportClip, ExportJob } from '../api.ts'
import { AgentReadScope } from '../videoAgent.ts'
import * as delivery from '../exportVideoPackage.ts'
import { VideoPackageError, prepareVideoPackage, videoPackageClipSelectable, videoPackageErrorCopy, videoPackageSelection } from '../exportVideoPackage.ts'

const id = (value: number) => `00000000-0000-4000-8000-${String(value).padStart(12, '0')}`
type Clip = ExportClip & { parts?: unknown; duration_ms?: unknown }
type Batch = ExportJob & { clip_request_id?: string | null; clips: Clip[] }
function clip(value: number, status = 'SUCCEEDED'): Clip {
  const content = `/api/exports/${id(10)}/clips/${id(value)}/content`
  return { id: id(value), segment_id: `s:${value}`, product_id: null, product_name: null, title: `成片 ${value}`,
    start_ms: 0, end_ms: 1000, stage_id: id(value + 100), status, attempt: 1, error_code: status === 'FAILED' ? 'EXPORT_FAILED' : null,
    size_bytes: status === 'SUCCEEDED' ? 1000 + value : null, content_url: status === 'SUCCEEDED' ? content : null,
    download_url: status === 'SUCCEEDED' ? `${content}?download=true` : null }
}
function batch(): Batch {
  return { id: id(10), job_id: id(1), video_id: id(2), approved_revision: 0, snapshot_hash: 'a'.repeat(64), profile: 'h264-aac-v1',
    status: 'RUNNING', state_version: 1, created_at: '2026-11-09T00:00:00Z', updated_at: '2026-11-09T00:00:00Z',
    clips: [clip(20), clip(21), clip(22, 'RUNNING'), clip(23, 'FAILED')] }
}
const errorCode = (code: string) => (value: unknown) => value instanceof VideoPackageError && value.code === code
const clone = <T>(value: T): T => structuredClone(value)

test('MP4 package selection is explicit, ordered by the batch, native, subtitle independent and permits revision zero', async () => {
  const value = batch(), untouched = clone(value), calls: string[] = []
  assert.equal(videoPackageSelection(value, []).url, null)
  const selected = videoPackageSelection(value, [id(21), id(20)], true)
  assert.deepEqual(selected.clip_ids, [id(20), id(21)]); assert.equal(selected.total_bytes, 2041)
  assert.equal(new URL(selected.url!, 'http://local').searchParams.get('clips'), `${id(20)},${id(21)}`)
  assert.equal(Object.isFrozen(selected), true); assert.equal(Object.isFrozen(selected.clip_ids), true)
  const prepared = await prepareVideoPackage(value, [id(20)], { signal: new AbortController().signal, current: () => true,
    get: async path => { calls.push(path); return clone(value) } })
  assert.deepEqual(calls, [`/api/exports/${value.id}`]); assert.ok(prepared.url.startsWith(`/api/exports/${value.id}/video-package?`))
  assert.equal(value.approved_revision, 0); assert.equal(value.profile, 'h264-aac-v1'); assert.deepEqual(value, untouched)
  assert.throws(() => videoPackageSelection({ ...value, profile: 'CLEAN' }, [id(20)], true), VideoPackageError)
})

test('manual MP4 package pins manual plan provenance independently of AI request identity', async () => {
  const value = batch(); value.approved_revision = 1; value.manual_clip_plan_id = id(50); value.clips = [value.clips[0]!]
  const expected = videoPackageSelection(value, [id(20)], true)
  const prepared = await prepareVideoPackage(value, [id(20)], { signal: new AbortController().signal, current: () => true, get: async () => clone(value) })
  assert.equal(prepared.signature, expected.signature)
  for (const update of [{ manual_clip_plan_id: id(51) }, { manual_clip_plan_id: undefined }, { clip_request_id: id(51) }]) {
    await assert.rejects(prepareVideoPackage(value, [id(20)], { signal: new AbortController().signal, current: () => true, get: async () => ({ ...clone(value), ...update }) }), VideoPackageError)
  }
  for (const update of [{ approved_revision: 0 }, { clips: batch().clips }, { clip_request_id: id(51) }]) assert.throws(() => videoPackageSelection({ ...value, ...update }, [id(20)], true), VideoPackageError)
})

test('MP4 package rejects empty, duplicate, unknown, cross-batch, failed and over-limit selected clips without truncation', () => {
  for (const ids of [[], [id(20), id(20)], [id(99)], [id(22)], [id(23)], ['../clip'], Array.from({ length: 101 }, (_, i) => id(i + 200))]) {
    assert.throws(() => videoPackageSelection(batch(), ids, true), VideoPackageError)
  }
  for (const update of [{ content_url: 'https://other.example/video.mp4' }, { download_url: '/api/exports/other/content?download=true' },
    { size_bytes: 0 }, { size_bytes: Number.MAX_SAFE_INTEGER + 1 }]) {
    const value = batch(); Object.assign(value.clips[0]!, update)
    assert.equal(videoPackageClipSelectable(value.id, value.clips[0]!), false)
    assert.throws(() => videoPackageSelection(value, [id(20)], true), VideoPackageError)
  }
  const maximum = batch(); maximum.clips = Array.from({ length: 100 }, (_, i) => clip(200 + i))
  assert.equal(videoPackageSelection(maximum, maximum.clips.map(v => v.id), true).clip_ids.length, 100)
  maximum.clips.push(clip(300))
  assert.throws(() => videoPackageSelection(maximum, maximum.clips.map(v => v.id), true), errorCode('INVALID_VIDEO_PACKAGE'))
})

test('MP4 package only freezes selected identities and ignores unselected progress, error diagnostics and whole-batch versions', async () => {
  const value = batch(), fresh = clone(value), original = videoPackageSelection(value, [id(20)], true)
  fresh.state_version = 99; fresh.status = 'PARTIAL_FAILED'; fresh.updated_at = '2026-11-10T01:00:00Z'
  fresh.clips[2] = clip(22); fresh.clips[3] = clip(23, 'RETRY_WAIT'); fresh.clips[1]!.attempt = 8
  fresh.clips[0]!.error_code = 'OLD_DIAGNOSTIC'
  assert.equal(videoPackageSelection(fresh, [id(20)], true).signature, original.signature)
  const result = await prepareVideoPackage(value, [id(20)], { signal: new AbortController().signal, current: () => true, get: async () => fresh })
  assert.equal(result.signature, original.signature)
})

test('MP4 package fresh metadata rejects changed immutable source and every selected stage, attempt, size and source interval', async () => {
  const changes: Array<(value: Batch) => void> = [
    v => { v.job_id = id(8) }, v => { v.video_id = id(8) }, v => { v.approved_revision = 1 },
    v => { v.snapshot_hash = 'b'.repeat(64) }, v => { v.clip_request_id = id(8) },
    v => { v.clips[0]!.stage_id = id(8) }, v => { v.clips[0]!.attempt++ }, v => { v.clips[0]!.size_bytes!++ },
    v => { v.clips[0]!.start_ms = 1 }, v => { v.clips[0]!.end_ms = 1001 }, v => { v.clips[0]!.title = '另一成片' },
    v => { v.clips[0] = clip(20, 'FAILED') }, v => { v.clips.shift() },
  ]
  for (const change of changes) {
    const value = batch(), fresh = clone(value); change(fresh)
    await assert.rejects(prepareVideoPackage(value, [id(20)], { signal: new AbortController().signal, current: () => true, get: async () => fresh }), VideoPackageError)
  }
})

test('MP4 package validates and canonically freezes selected parts with logical duration, including single-part legacy metadata', () => {
  const single = batch(), expanded = clone(single)
  expanded.clips[0]!.parts = [{ end_ms: 1000, start_ms: 0 }]; expanded.clips[0]!.duration_ms = 1000
  assert.equal(videoPackageSelection(single, [id(20)]).signature, videoPackageSelection(expanded, [id(20)]).signature)
  const multi = batch(); Object.assign(multi.clips[0]!, { end_ms: 5000, duration_ms: 2000, parts: [{ start_ms: 0, end_ms: 1000 }, { start_ms: 4000, end_ms: 5000 }] })
  const reorderedKeys = clone(multi); reorderedKeys.clips[0]!.parts = [{ end_ms: 1000, start_ms: 0 }, { end_ms: 5000, start_ms: 4000 }]
  assert.equal(videoPackageSelection(multi, [id(20)]).signature, videoPackageSelection(reorderedKeys, [id(20)]).signature)
  const changed = clone(multi); changed.clips[0]!.parts = [{ start_ms: 0, end_ms: 500 }, { start_ms: 3500, end_ms: 5000 }]
  assert.notEqual(videoPackageSelection(multi, [id(20)]).signature, videoPackageSelection(changed, [id(20)]).signature)
  for (const fields of [{ parts: [] }, { parts: 'invalid' }, { parts: [{ start_ms: 1, end_ms: 1000 }] }, { duration_ms: 2000 },
    { parts: [{ start_ms: 0, end_ms: 0 }] }, { parts: [{ start_ms: 0, end_ms: 600 }, { start_ms: 500, end_ms: 1000 }] },
    { parts: Array.from({ length: 13 }, (_, i) => ({ start_ms: i, end_ms: i + 1 })) }, { parts: [{ start_ms: 0, end_ms: 1.5 }] }]) {
    const value = batch(); Object.assign(value.clips[0]!, fields)
    assert.throws(() => videoPackageSelection(value, [id(20)]), VideoPackageError)
  }
  const unselected = batch(); unselected.clips[1]!.parts = []
  assert.doesNotThrow(() => videoPackageSelection(unselected, [id(20)]))
})

test('MP4 package rejects stale or aborted reads and retries only on explicit new checks', async () => {
  for (const cancel of ['current', 'abort'] as const) {
    const value = batch(), controller = new AbortController(); let current = true, resolve!: (value: unknown) => void, calls = 0
    const pending = prepareVideoPackage(value, [id(20)], { signal: controller.signal, current: () => current,
      get: () => { calls++; return new Promise(done => { resolve = done }) } })
    if (cancel === 'current') current = false; else controller.abort()
    resolve(value); await assert.rejects(pending, errorCode('VIDEO_PACKAGE_READ_CANCELLED')); assert.equal(calls, 1)
  }
  let calls = 0
  const value = batch(), io = { signal: new AbortController().signal, current: () => true,
    get: async () => { if (++calls === 1) throw { code: 'NETWORK_UNAVAILABLE' }; return clone(value) } }
  await assert.rejects(prepareVideoPackage(value, [id(20)], io)); assert.equal(calls, 1)
  assert.ok((await prepareVideoPackage(value, [id(20)], io)).url); assert.equal(calls, 2)
  assert.match(videoPackageErrorCopy({ code: 'NETWORK_UNAVAILABLE' }), /当前选择与编辑内容保留/)
})

// Compile the actual SFC and mount it in Vue's host renderer: watchers and event guards run without a browser or a network.
interface HostNode { type: string; props: Record<string, any>; children: HostNode[]; parent?: HostNode; text: string }
const node = (type: string, text = ''): HostNode => ({ type, text, props: {}, children: [] })
const renderer = createRenderer<HostNode, HostNode>({
  createElement: type => node(type), createText: text => node('#text', text), createComment: text => node('#comment', text),
  setText: (target, text) => { target.text = text }, setElementText: (target, text) => { target.text = text; for (const child of target.children) child.parent = undefined; target.children = [] },
  parentNode: target => target.parent ?? null, nextSibling: target => { const index = target.parent?.children.indexOf(target) ?? -1; return index < 0 ? null : target.parent?.children[index + 1] ?? null },
  patchProp: (target, key, _previous, value) => { target.props[key] = value },
  insert: (target, parent, anchor) => { const oldIndex = target.parent?.children.indexOf(target) ?? -1; if (oldIndex >= 0) target.parent!.children.splice(oldIndex, 1); target.parent = parent;
    const index = anchor ? parent.children.indexOf(anchor) : -1; if (index < 0) parent.children.push(target); else parent.children.splice(index, 0, target) },
  remove: target => { const index = target.parent?.children.indexOf(target) ?? -1; if (index >= 0) target.parent!.children.splice(index, 1); target.parent = undefined },
})
const textOf = (target: HostNode): string => target.text + target.children.map(textOf).join('')
const all = (target: HostNode): HostNode[] => [target, ...target.children.flatMap(all)]
const flush = async () => { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick() }
const require = createRequire(import.meta.url)
const descriptor = parse(readFileSync(new URL('../VideoPackagePanel.vue', import.meta.url), 'utf8')).descriptor
const componentCode = transpileModule(compileScript(descriptor, { id: 'video-package-test', inlineTemplate: true }).content,
  { compilerOptions: { module: ModuleKind.CommonJS, target: ScriptTarget.ES2022 } }).outputText
async function mountPanel() {
  const previousDocument = globalThis.document
  const doc = Object.assign(new EventTarget(), { hidden: false }); Object.assign(globalThis, { document: doc })
  let read: (path: string, signal: AbortSignal) => Promise<unknown> = async () => clone(value)
  const calls: Array<{ path: string; signal: AbortSignal }> = [], exports = { default: undefined as any }, value = batch()
  new Function('require', 'exports', componentCode)((name: string) => {
    if (name === './api') return { api: (path: string, init: { signal: AbortSignal }) => { calls.push({ path, signal: init.signal }); return read(path, init.signal) } }
    if (name === './videoAgent') return { AgentReadScope }
    if (name === './exportVideoPackage') return delivery
    return require(name)
  }, exports)
  const root = node('root'), props = { batch: value, sourceKey: id(1), active: true, busy: false }
  const render = async () => { renderer.render(h(exports.default, { ...props }), root); await flush() }
  await render()
  return { root, props, calls, doc, render, setRead: (next: typeof read) => { read = next },
    buttons: () => all(root).filter(v => v.type === 'button'), checkboxes: () => all(root).filter(v => v.type === 'input'),
    link: () => all(root).find(v => v.type === 'a'),
    click: async (label: string) => { const button = all(root).find(v => v.type === 'button' && textOf(v) === label); assert.ok(button, label); assert.equal(!!button.props.disabled, false); button.props.onClick(); await flush() },
    close: () => { renderer.render(null, root); Object.assign(globalThis, { document: previousDocument }) } }
}

test('MP4 package panel starts empty, selects only completed clips, prepares metadata and waits for native download', async () => {
  const ui = await mountPanel()
  try {
    assert.equal(ui.calls.length, 0); assert.equal(ui.link(), undefined); assert.ok(ui.checkboxes().every(v => !v.props.checked))
    assert.deepEqual(ui.checkboxes().map(v => !!v.props.disabled), [false, false, true, true])
    await ui.click('全选已完成'); assert.deepEqual(ui.checkboxes().map(v => !!v.props.checked), [true, true, false, false])
    await ui.click('核对所选成片'); assert.deepEqual(ui.calls.map(v => v.path), [`/api/exports/${id(10)}`])
    const link = ui.link()!; assert.ok(link); assert.equal(link.props.download, ''); assert.equal(link.props.target, '_blank')
    assert.equal(new URL(link.props.href, 'http://local').searchParams.get('clips'), `${id(20)},${id(21)}`)
    assert.match(textOf(ui.root), /点击下载后.*构建 ZIP/); assert.equal(ui.calls.length, 1)
    let prevented = false; link.props.onClick({ preventDefault: () => { prevented = true } }); assert.equal(prevented, false)
    await ui.click('清空'); assert.equal(ui.link(), undefined); assert.ok(ui.checkboxes().every(v => !v.props.checked))
  } finally { ui.close() }
})

test('MP4 package panel preserves a valid link through unselected progress but revokes selected changes and explicit reselection', async () => {
  const ui = await mountPanel()
  try {
    ui.checkboxes()[0]!.props.onChange({ target: { checked: true } }); await flush(); await ui.click('核对所选成片')
    const original = ui.link()!.props.href
    const progress = clone(ui.props.batch); progress.state_version++; progress.clips[2] = clip(22)
    ui.props.batch = progress; await ui.render(); assert.equal(ui.link()!.props.href, original)
    const selected = clone(progress); selected.clips[0]!.attempt++; ui.props.batch = selected; await ui.render(); assert.equal(ui.link(), undefined)
    ui.setRead(async () => clone(ui.props.batch)); await ui.click('核对所选成片'); assert.ok(ui.link())
    ui.checkboxes()[1]!.props.onChange({ target: { checked: true } }); await flush(); assert.equal(ui.link(), undefined)
    await ui.click('核对所选成片'); assert.ok(ui.link())
    const failed = clone(ui.props.batch); failed.clips[0] = clip(20, 'FAILED'); ui.props.batch = failed; await ui.render()
    assert.equal(ui.link(), undefined); assert.equal(ui.checkboxes()[0]!.props.checked, true); assert.match(textOf(ui.root), /不会自动漏片/)
    assert.ok(ui.buttons().find(v => textOf(v) === '核对所选成片')!.props.disabled)
  } finally { ui.close() }
})

test('MP4 package panel aborts hidden, switched-source, inactive, busy and unmounted reads and ignores their late responses', async () => {
  for (const reason of ['hidden', 'source', 'inactive', 'busy', 'unmount']) {
    const ui = await mountPanel(); let resolve!: (value: unknown) => void
    try {
      await ui.click('全选已完成'); ui.setRead(() => new Promise(done => { resolve = done })); await ui.click('核对所选成片')
      assert.equal(ui.calls.length, 1)
      if (reason === 'hidden') { ui.doc.hidden = true; ui.doc.dispatchEvent(new Event('visibilitychange')); await flush() }
      if (reason === 'source') { ui.props.sourceKey = id(8); await ui.render(); assert.ok(ui.checkboxes().every(v => !v.props.checked)) }
      if (reason === 'inactive') { ui.props.active = false; await ui.render() }
      if (reason === 'busy') { ui.props.busy = true; await ui.render() }
      if (reason === 'unmount') renderer.render(null, ui.root)
      assert.equal(ui.calls[0]!.signal.aborted, true, reason)
      resolve(clone(ui.props.batch)); await flush(); assert.equal(ui.link(), undefined, reason)
    } finally { ui.close() }
  }
})

test('MP4 package panel retains selection on read failure and old finally cannot clear a new check', async () => {
  const ui = await mountPanel(); let oldResolve!: (value: unknown) => void, newResolve!: (value: unknown) => void
  try {
    await ui.click('全选已完成'); ui.setRead(async () => { throw { code: 'NETWORK_UNAVAILABLE' } }); await ui.click('核对所选成片')
    assert.match(textOf(ui.root), /当前选择与编辑内容保留/); assert.equal(ui.checkboxes().filter(v => v.props.checked).length, 2)
    ui.setRead(() => new Promise(done => { oldResolve = done })); await ui.click('核对所选成片')
    ui.doc.hidden = true; ui.doc.dispatchEvent(new Event('visibilitychange')); await flush()
    ui.doc.hidden = false; ui.doc.dispatchEvent(new Event('visibilitychange')); await flush()
    ui.setRead(() => new Promise(done => { newResolve = done })); await ui.click('核对所选成片')
    oldResolve(clone(ui.props.batch)); await flush(); assert.ok(ui.buttons().find(v => textOf(v) === '正在核对所选成片…')!.props.disabled)
    assert.equal(ui.link(), undefined); newResolve(clone(ui.props.batch)); await flush(); assert.ok(ui.link())
    const staleLink = ui.link()!; ui.doc.hidden = true; let prevented = false
    staleLink.props.onClick({ preventDefault: () => { prevented = true } }); await flush(); assert.equal(prevented, true); assert.equal(ui.link(), undefined)
  } finally { ui.close() }
})

test('topic and intent deliveries use the shared selector with active source guards and honest existing subtitle notices', () => {
  for (const filename of ['ExportPanel.vue', 'IntentClipPanel.vue']) {
    const source = readFileSync(new URL(`../${filename}`, import.meta.url), 'utf8')
    assert.match(source, /import VideoPackagePanel from '\.\/VideoPackagePanel\.vue'/)
    assert.match(source, /<VideoPackagePanel[^>]+:batch=[^>]+:source-key=[^>]+:active=[^>]+:busy=/)
    assert.match(source, /点击“下载本批素材包”后，服务端将校验文件并打包/)
    assert.doesNotMatch(source, /素材包已准备好/)
  }
})
