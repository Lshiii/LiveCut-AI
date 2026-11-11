import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import * as ts from 'typescript'
import { h } from 'vue'
import * as transcript from '../transcriptSearch.ts'
import * as preview from '../sequencePreview.ts'
import { allHostNodes, compileHostComponent, flushHost, hostNode, hostRenderer, hostText, type HostNode } from './support/vueHost.ts'

const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const owner = { job_id: id(1), video_id: id(2), generation: 3, source_video_sha256: 'a'.repeat(64) }
const source = { ...owner, transcript_sha256: 'b'.repeat(64), analysis_sha256: 'c'.repeat(64), duration_ms: 10000, source_fingerprint: 'd'.repeat(64) }
const utterance = (n: number) => ({ utterance_id: `u${n}`, start_ms: n * 1000, end_ms: (n + 1) * 1000, text: `原句 ${n} 缓存 <script>原文</script>` })
const page = (q = '', offset = 0) => ({ schema_version: '1.0', data_kind: 'transcript_page', source, q, offset, limit: 20,
  total_utterances: 4, matched_utterances: 4, next_offset: offset === 0 ? 2 : null, items: [utterance(offset), utterance(offset + 1)] })
const context = (id: string) => { const n = Number(id.slice(1)); return { schema_version: '1.0', data_kind: 'transcript_context', source,
  center: utterance(n), previous: n ? utterance(n - 1) : null, next: n < 3 ? utterance(n + 1) : null } }
function deferred<T>() { let resolve!: (value: T) => void, reject!: (error: unknown) => void; const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail }); return { promise, resolve, reject } }
async function mount(active = false) {
  const oldDocument = globalThis.document, oldDocumentClass = globalThis.Document, oldWindow = globalThis.window, oldNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator')
  class HostDocument extends EventTarget {}
  const doc = Object.assign(new HostDocument(), { hidden: false, baseURI: 'http://test/', activeElement: null })
  Object.assign(globalThis, { document: doc, Document: HostDocument, window: { setTimeout, clearTimeout } })
  const written: string[] = []; Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { clipboard: { writeText: async (value: string) => { written.push(value) } } } })
  const calls: Array<{ path: string; init: { signal: AbortSignal } }> = []
  let read: (path: string) => Promise<unknown> = async path => {
    const url = new URL(path, 'http://test/')
    return url.pathname.endsWith('/context') ? context(url.searchParams.get('utterance_id')!) : page(url.searchParams.get('q')!, Number(url.searchParams.get('offset')))
  }
  const component = compileHostComponent(new URL('../TranscriptSearchPanel.vue', import.meta.url), {
    './api': { api: (path: string, init: { signal: AbortSignal }) => { calls.push({ path, init }); return read(path) } },
    './sequencePreview': preview, './transcriptSearch': transcript,
    './ManualClipPanel.vue': { setup(_props: unknown, { expose }: { expose(value: unknown): void }) { expose({ dirty: false, pending: false, unresolved: false, summary: { hasRange: false, start: null, end: null }, setBoundary: () => true }); return () => null } },
  })
  const root = hostNode('root'), props = { jobId: owner.job_id, videoId: owner.video_id, generation: owner.generation, videoHash: owner.source_video_sha256,
    videoUrl: '/video.mp4', videoName: '原视频.mp4', active }
  const render = async () => { hostRenderer.render(h(component, { ...props }), root); await flushHost() }
  await render()
  const nodes = () => allHostNodes(root)
  const button = (label: string) => { const value = nodes().find(v => v.type === 'button' && hostText(v) === label); assert.ok(value, label); return value }
  return { root, props, calls, doc, written, render, nodes, button, setRead: (value: typeof read) => { read = value },
    video: () => nodes().find(v => v.type === 'video')!, input: () => nodes().find(v => v.type === 'input')!,
    results: () => nodes().filter(v => v.type === 'button' && v.props['aria-label']?.startsWith('选择原句 ')),
    click: async (label: string) => { const value = button(label); assert.equal(!!value.props.disabled, false, label); value.props.onClick?.({}); await flushHost() },
    choose: async (index: number) => { const value = nodes().filter(v => v.type === 'button' && v.props['aria-label']?.startsWith('选择原句 '))[index]!; value.props.onClick(); await flushHost() },
    submit: async () => { nodes().find(v => v.type === 'form')!.props.onSubmit({ preventDefault() {} }); await flushHost() },
    close: () => { hostRenderer.render(null, root); Object.assign(globalThis, { document: oldDocument, Document: oldDocumentClass, window: oldWindow });
      if (oldNavigator) Object.defineProperty(globalThis, 'navigator', oldNavigator); else Reflect.deleteProperty(globalThis, 'navigator') },
  }
}

test('transcript production panel only reads when active visible, does not fetch context/autoplay on load and renders HTML literally', async () => {
  const ui = await mount()
  try {
    assert.equal(ui.calls.length, 0); assert.equal(ui.video().props.src, undefined)
    ui.doc.hidden = true; ui.props.active = true; await ui.render(); assert.equal(ui.calls.length, 0)
    ui.doc.hidden = false; ui.doc.dispatchEvent(new Event('visibilitychange')); await flushHost()
    assert.equal(ui.calls.length, 1); assert.ok(ui.calls[0]!.path.startsWith(`/api/jobs/${owner.job_id}/transcript?`))
    assert.equal(ui.results().length, 2); assert.equal(ui.video().playCalls, 0)
    assert.equal(ui.nodes().filter(v => v.type === 'script').length, 0); assert.match(hostText(ui.root), /<script>原文<\/script>/)
    assert.deepEqual(Object.keys(ui.calls[0]!.init), ['signal'])
    await ui.choose(1); assert.equal(ui.calls.length, 2); assert.ok(ui.calls[1]!.path.includes('/context?'))
    assert.equal(ui.video().playCalls, 0); assert.match(hostText(ui.root), /所选原句 · 00:01\.000/)
  } finally { ui.close() }
})

test('transcript production panel keeps unsubmitted query text through fixed-query pagination and submits only on explicit search', async () => {
  const ui = await mount(true)
  try {
    ui.input().props['onUpdate:modelValue']('新搜索词'); await flushHost(); assert.equal(ui.calls.length, 1)
    await ui.click('下一页'); assert.equal(new URL(ui.calls[1]!.path, 'http://test').searchParams.get('q'), '')
    assert.equal(ui.input().value, '新搜索词'); assert.match(hostText(ui.root), /3–4 项/)
    await ui.submit(); assert.equal(new URL(ui.calls[2]!.path, 'http://test').searchParams.get('q'), '新搜索词')
    assert.match(hostText(ui.root), /已读取结果：“新搜索词”/); assert.equal(ui.input().value, '新搜索词')
  } finally { ui.close() }
})

test('transcript production panel keeps old query range on 503, retries explicitly and source switch discards late reads', async () => {
  const ui = await mount(true)
  try {
    ui.input().props['onUpdate:modelValue']('新查询'); ui.setRead(async () => { throw { code: 'HTTP_503' } }); await ui.submit()
    assert.match(hostText(ui.root), /下方仍为上次成功结果，查询“全部原句”/)
    assert.ok(ui.results().every(v => v.props.disabled)); assert.equal(ui.input().value, '新查询')
    ui.setRead(async () => page('新查询')); await ui.click('重试读取'); assert.equal(ui.results().length, 2)
    const old = deferred<unknown>(); ui.setRead(() => old.promise); await ui.submit(); const pendingSignal = ui.calls.at(-1)!.init.signal
    ui.props.videoHash = 'e'.repeat(64); ui.setRead(async () => ({ ...page(), source: { ...source, source_video_sha256: 'e'.repeat(64) } })); await ui.render()
    assert.equal(pendingSignal.aborted, true); assert.equal(ui.input().value, '')
    old.resolve(page('新查询')); await flushHost(); assert.doesNotMatch(hostText(ui.root), /已读取结果：“新查询”/)
  } finally { ui.close() }
})

test('transcript selected quote copies only one ASR sentence, original filename and precise source time', async () => {
  const ui = await mount(true)
  try {
    await ui.choose(1); await ui.click('复制所选原句引用')
    assert.equal(ui.written.length, 1); assert.equal(ui.written[0], transcript.transcriptQuote('原视频.mp4', utterance(1)))
    assert.ok(!ui.written[0]!.includes(utterance(0).text)); assert.match(hostText(ui.root), /ASR 尚未经人工校对/)
    ui.props.active = false; await ui.render(); assert.doesNotMatch(hostText(ui.root), /已复制所选原句/)
  } finally { ui.close() }
})

test('transcript explicit locate pauses without play, context preview stops on manual seek and stale metadata cannot revive a prior query', async () => {
  const ui = await mount(true)
  try {
    await ui.choose(1); const video = ui.video()
    await ui.click('定位所选原句'); assert.equal(video.currentTime, 1); assert.equal(video.playCalls, 0); assert.equal(video.paused, true)
    await ui.click('连续预览前后文'); assert.equal(video.currentTime, 0); assert.equal(video.playCalls, 1); assert.equal(video.paused, false)
    video.seeking = true; video.currentTime = 7; video.props.onSeeking({ currentTarget: video }); await flushHost()
    assert.equal(video.paused, true); assert.ok(ui.button('连续预览前后文'))
    video.seeking = false; video.readyState = 0; video.currentTime = 5
    await ui.click('定位所选原句'); assert.match(hostText(ui.root), /正在准备原视频定位/)
    ui.input().props['onUpdate:modelValue']('another'); await ui.submit()
    video.readyState = 2; video.props.onLoadedmetadata({ currentTarget: video }); await flushHost()
    assert.equal(video.currentTime, 5); assert.equal(video.playCalls, 1)
  } finally { ui.close() }
})

test('transcript hiding cancels playback and a late play promise cannot pause a new current preview', async () => {
  const ui = await mount(true)
  try {
    await ui.choose(1); const video = ui.video(), old = deferred<void>()
    video.play = () => { video.playCalls++; video.paused = false; return old.promise }
    await ui.click('连续预览前后文'); assert.equal(video.paused, false)
    ui.props.active = false; await ui.render(); assert.equal(video.paused, true); assert.equal(video.props.src, undefined)
    ui.props.active = true; await ui.render()
    video.play = () => { video.playCalls++; video.paused = false; return Promise.resolve() }
    await ui.click('连续预览前后文'); const pauses = video.pauseCalls
    old.resolve(); await flushHost(); assert.equal(video.paused, false); assert.equal(video.pauseCalls, pauses)
    ui.doc.hidden = true; ui.doc.dispatchEvent(new Event('visibilitychange')); await flushHost(); assert.equal(video.paused, true)
  } finally { ui.close() }
})

test('transcript source switch and unmount cancel pending metadata location without later seek or autoplay', async () => {
  for (const reason of ['source', 'unmount']) {
    const ui = await mount(true)
    try {
      await ui.choose(1); const oldVideo = ui.video(); oldVideo.readyState = 0; oldVideo.currentTime = 6
      await ui.click('定位所选原句'); assert.match(hostText(ui.root), /正在准备原视频定位/)
      if (reason === 'source') { ui.props.videoUrl = '/new-video.mp4'; await ui.render() }
      else hostRenderer.render(null, ui.root)
      oldVideo.readyState = 2; oldVideo.props.onLoadedmetadata({ currentTarget: oldVideo }); await flushHost()
      assert.equal(oldVideo.currentTime, 6); assert.equal(oldVideo.playCalls, 0)
      oldVideo.props.onPlay({ currentTarget: oldVideo }); assert.equal(oldVideo.paused, true)
    } finally { ui.close() }
  }
})

test('transcript context source failure cancels pending metadata intent and same-fingerprint recovery cannot revive it', async () => {
  for (const code of ['TRANSCRIPT_SOURCE_CHANGED', 'TRANSCRIPT_SOURCE_INVALID']) {
    const ui = await mount(true)
    try {
      const pendingContext = deferred<unknown>(); ui.setRead(path => path.includes('/context?') ? pendingContext.promise : Promise.resolve(page()))
      await ui.choose(1); const video = ui.video(); video.readyState = 0; video.currentTime = 6
      await ui.click('定位所选原句'); assert.match(hostText(ui.root), /正在准备原视频定位/)
      pendingContext.reject({ code }); await flushHost()
      assert.doesNotMatch(hostText(ui.root), /正在准备原视频定位/); assert.equal(ui.button('定位所选原句').props.disabled, true)
      assert.equal(video.paused, true)
      ui.setRead(async path => path.includes('/context?') ? context('u1') : page())
      await ui.click('重新核对来源'); assert.equal(ui.button('定位所选原句').props.disabled, false)
      video.readyState = 2; video.props.onLoadedmetadata({ currentTarget: video }); await flushHost()
      assert.equal(video.currentTime, 6); assert.equal(video.playCalls, 0); assert.doesNotMatch(hostText(ui.root), /正在准备原视频定位/)
    } finally { ui.close() }
  }
})

test('transcript context source failure stops reachable native playback while the selected context is still loading', async () => {
  const ui = await mount(true)
  try {
    const pendingContext = deferred<unknown>(); ui.setRead(() => pendingContext.promise)
    await ui.choose(1); const video = ui.video()
    await video.play(); video.props.onPlay({ currentTarget: video }); assert.equal(video.paused, false)
    pendingContext.reject({ code: 'TRANSCRIPT_SOURCE_INVALID' }); await flushHost()
    assert.equal(video.paused, true); assert.equal(ui.button('定位所选原句').props.disabled, true)
  } finally { ui.close() }
})

test('transcript replaced video events cannot affect the current pending/playing owner and current empty-src errors still surface', async () => {
  const ui = await mount(true)
  try {
    await ui.choose(1); const old = ui.video()
    ui.props.videoUrl = '/replacement.mp4'; await ui.render(); await ui.choose(1)
    const current = ui.video(); assert.notEqual(old, current); current.readyState = 0; current.currentTime = 6
    await ui.click('定位所选原句'); assert.match(hostText(ui.root), /正在准备原视频定位/)
    current.readyState = 2
    old.seeking = true; old.paused = true; old.currentTime = 8
    const oldEvents = () => {
      for (const name of ['onError', 'onPause', 'onSeeking', 'onLoadedmetadata', 'onSeeked', 'onTimeupdate', 'onPlaying', 'onEnded', 'onRatechange', 'onPlay']) old.props[name]({ currentTarget: old })
    }
    const pendingPauses = current.pauseCalls; oldEvents(); await flushHost()
    assert.equal(current.currentTime, 6); assert.equal(current.pauseCalls, pendingPauses)
    assert.match(hostText(ui.root), /正在准备原视频定位/); assert.doesNotMatch(hostText(ui.root), /原视频暂时无法播放/)
    current.props.onLoadedmetadata({ currentTarget: current }); await flushHost()
    assert.equal(current.currentTime, 1); assert.doesNotMatch(hostText(ui.root), /正在准备原视频定位/)
    await ui.click('连续预览前后文'); assert.equal(current.paused, false)
    const playingPauses = current.pauseCalls; oldEvents(); await flushHost()
    assert.equal(current.paused, false); assert.equal(current.pauseCalls, playingPauses); assert.doesNotMatch(hostText(ui.root), /原视频暂时无法播放/)
    current.currentSrc = ''; current.props.onError({ currentTarget: current }); await flushHost()
    assert.equal(current.paused, true); assert.match(hostText(ui.root), /原视频暂时无法播放，请稍后重试/)
  } finally { ui.close() }
})

test('Workbench actual tab keyboard order includes transcript and preserves legacy commercial/task fallbacks', async () => {
  const source = readFileSync(new URL('../Workbench.vue', import.meta.url), 'utf8'), script = parse(source).descriptor.scriptSetup!.content
  const ast = ts.createSourceFile('workbench.ts', script, ts.ScriptTarget.ES2022, true, ts.ScriptKind.TS)
  const fn = ast.statements.find(value => ts.isFunctionDeclaration(value) && value.name?.text === 'tabKey')!.getText(ast)
  const code = ts.transpileModule(`${fn}; exports.tabKey = tabKey`, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
  const activeTab = { value: 'agent' }, topic = { value: true }, results = { value: true }, focused: string[] = [], exported: { tabKey?: (event: any) => void } = {}
  new Function('activeTab', 'topicResultsAvailable', 'resultsAvailable', 'setTab', 'nextTick', 'document', 'exports', code)(activeTab, topic, results,
    (value: string) => { activeTab.value = value }, (callback: () => void) => Promise.resolve().then(callback),
    { getElementById: (id: string) => ({ focus: () => focused.push(id) }) }, exported)
  const key = async (key: string) => { exported.tabKey!({ key, preventDefault() {} }); await flushHost(); return activeTab.value }
  assert.equal(await key('ArrowRight'), 'transcript'); assert.equal(await key('ArrowRight'), 'topics'); assert.equal(await key('ArrowRight'), 'clips')
  assert.equal(await key('End'), 'task'); assert.equal(await key('Home'), 'agent'); assert.equal(await key('ArrowLeft'), 'task')
  assert.ok(focused.includes('workbench-tab-transcript'))
  topic.value = false; activeTab.value = 'topics'; assert.equal(await key('ArrowRight'), 'task'); assert.equal(await key('Home'), 'topics')
  results.value = false; activeTab.value = 'task'; assert.equal(await key('ArrowLeft'), 'task')
  for (const tab of ['agent', 'transcript', 'topics', 'clips', 'task']) assert.ok(source.includes(`v-show="activeTab === '${tab}'"`))
  assert.match(source, /<TranscriptSearchPanel[^>]+:active="activeTab === 'transcript'"/)
  const css = readFileSync(new URL('../workbench.css', import.meta.url), 'utf8'); assert.match(css, /\.workspace-tabs \{[^}]*overflow-x: auto/)
})
