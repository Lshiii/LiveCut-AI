import test from 'node:test'
import assert from 'node:assert/strict'
import { h } from 'vue'
import * as sequence from '../sequencePreview.ts'
import { transcriptTime } from '../transcriptSearch.ts'
import { allHostNodes, compileHostComponent, flushHost, hostNode, hostRenderer, hostText } from './support/vueHost.ts'

async function mount() {
  const old = { document: globalThis.document, Document: globalThis.Document, window: globalThis.window }
  class Doc extends EventTarget {}
  const document = Object.assign(new Doc(), { hidden: false, baseURI: 'http://test/' }), timers: Array<() => void> = []
  Object.assign(globalThis, { document, Document: Doc, window: { setTimeout: (callback: () => void) => { timers.push(callback); return timers.length }, clearTimeout() {} } })
  const component = compileHostComponent(new URL('../ManualClipPreview.vue', import.meta.url), { './sequencePreview': sequence, './transcriptSearch': { transcriptTime } })
  const props = { videoUrl: '/a.mp4', sourceKey: 'source-a', range: { start_ms: 1000, end_ms: 3000 } as { start_ms: number; end_ms: number } | null, active: true }, root = hostNode('root')
  const render = async () => { hostRenderer.render(h(component, { ...props }), root); await flushHost() }; await render()
  const nodes = () => allHostNodes(root), video = () => nodes().find(node => node.type === 'video')!
  return { props, root, document, timers, render, video, click: async (label: string) => { const button = nodes().find(node => node.type === 'button' && hostText(node) === label)!; assert.ok(button, label); assert.equal(!!button.props.disabled, false); button.props.onClick(); await flushHost() }, close: () => { hostRenderer.render(null, root); Object.assign(globalThis, old) } }
}

test('manual preview pending metadata is revoked by invalid range/hidden and cannot revive when identical source/range returns', async () => {
  const ui = await mount()
  try {
    const video = ui.video(); video.readyState = 0; await ui.click('预览完整人工区间'); assert.match(hostText(ui.root), /正在准备人工区间定位/)
    ui.props.range = null; await ui.render(); assert.doesNotMatch(hostText(ui.root), /正在准备人工区间定位/)
    ui.props.range = { start_ms: 1000, end_ms: 3000 }; await ui.render(); video.readyState = 2; video.props.onLoadedmetadata({ currentTarget: video }); await flushHost(); assert.equal(video.playCalls, 0)
    await ui.click('预览完整人工区间'); assert.equal(video.paused, false); ui.document.hidden = true; ui.document.dispatchEvent(new Event('visibilitychange')); await flushHost(); assert.equal(video.paused, true)
    ui.document.hidden = false; video.props.onLoadedmetadata({ currentTarget: video }); await flushHost(); assert.equal(video.playCalls, 1)
  } finally { ui.close() }
})

test('manual preview all detached media handlers and old timeout leave current pending and playing owner alone', async () => {
  const ui = await mount()
  try {
    const a = ui.video(); a.readyState = 0; await ui.click('预览完整人工区间'); const oldTimer = ui.timers.at(-1)!
    ui.props.videoUrl = '/b.mp4'; ui.props.sourceKey = 'source-b'; await ui.render(); const b = ui.video(); assert.notEqual(a, b)
    b.readyState = 0; await ui.click('预览完整人工区间'); oldTimer(); await flushHost(); assert.match(hostText(ui.root), /正在准备人工区间定位/)
    for (const handler of ['onLoadedmetadata', 'onTimeupdate', 'onPlaying', 'onSeeking', 'onSeeked', 'onPause', 'onEnded', 'onRatechange', 'onPlay', 'onError']) a.props[handler]({ currentTarget: a })
    await flushHost(); assert.match(hostText(ui.root), /正在准备人工区间定位/); assert.doesNotMatch(hostText(ui.root), /暂时无法播放/); assert.equal(b.playCalls, 0)
    b.readyState = 2; b.props.onLoadedmetadata({ currentTarget: b }); await flushHost(); assert.equal(b.paused, false)
    a.props.onError({ currentTarget: a }); a.props.onPause({ currentTarget: a }); await flushHost(); assert.equal(b.paused, false)
    b.currentSrc = ''; b.props.onError({ currentTarget: b }); await flushHost(); assert.equal(b.paused, true); assert.match(hostText(ui.root), /原视频暂时无法播放/)
  } finally { ui.close() }
})

test('manual owned 15 second metadata timeout clears pending and never performs a delayed seek', async () => {
  const ui = await mount()
  try {
    const video = ui.video(); video.readyState = 0; await ui.click('定位人工片段起点'); ui.timers.at(-1)!(); await flushHost()
    assert.doesNotMatch(hostText(ui.root), /正在准备人工区间定位/); assert.match(hostText(ui.root), /尚未准备好/)
    video.readyState = 2; video.props.onLoadedmetadata({ currentTarget: video }); await flushHost(); assert.equal(video.currentTime, 0); assert.equal(video.playCalls, 0)
  } finally { ui.close() }
})
