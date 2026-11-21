import assert from 'node:assert/strict'
import test from 'node:test'
import { h } from 'vue'
import * as captions from '../captions.ts'
import * as renders from '../captionRenders.ts'
import { allHostNodes, compileHostComponent, flushHost, hostNode, hostRenderer, hostText } from './support/vueHost.ts'

test('confirmed frame navigation follows the selected cue, supports gaps, and renders only a valid explicit request', async () => {
  const component = compileHostComponent(new URL('../CaptionFramePreview.vue', import.meta.url), {
    './captions': captions, './captionRenders': renders,
  })
  const root = hostNode('div'), originalWindow = globalThis.window, originalFetch = globalThis.fetch
  Object.assign(globalThis, { window: { setTimeout, clearTimeout } })
  let request: RequestInit | undefined, path = '', complete: ((value: Response) => void) | undefined
  globalThis.fetch = (input, init) => {
    path = String(input); request = init
    return new Promise(resolve => { complete = resolve })
  }
  const cue = (id: string, start_ms: number, end_ms: number) => ({ id, start_ms, end_ms, text: id })
  const snapshot = {
    export_id: '11111111-1111-4111-8111-111111111111', clip_id: '22222222-2222-4222-8222-222222222222',
    source: { media_duration_ms: 3500, clip_sha256: 'c'.repeat(64) }, status: 'CONFIRMED',
    revision: 2, confirmed_revisions: [2], content_hash: 'd'.repeat(64),
    cues: [cue('one', 200, 1000), cue('two', 2000, 2600), cue('last', 3200, 4000)],
  }
  const mount = async (enabled: boolean) => {
    hostRenderer.render(h(component, { caption: snapshot, stylePreset: 'DARK_BOX_V1', frame: 'SOURCE', enabled, initialCueId: 'two' }), root)
    await flushHost()
  }
  const nodes = () => allHostNodes(root)
  const button = (name: string) => nodes().find(node => node.type === 'button' && hostText(node) === name)!
  const input = () => nodes().find(node => node.props['aria-label'] === '预览时间码')!
  const enterTime = async (value: string) => { input().props.onInput({ target: { value } }); await flushHost() }
  try {
    await mount(true)
    assert.equal(input().props.value, '00:02.300')
    await enterTime('00:01.200')
    assert.equal(hostText(nodes().find(node => node.type === 'blockquote')!), '这个时间点没有字幕')
    button('上一句').props.onClick(); await flushHost()
    assert.equal(input().props.value, '00:00.600')
    assert.equal(button('上一句').props.disabled, true)
    await enterTime('00:03.499')
    assert.equal(button('下一句').props.disabled, true)
    await enterTime('00:03.500')
    assert.equal(button('预览这一帧').props.disabled, true)
    void button('预览这一帧').props.onClick()
    assert.equal(request, undefined)
    await enterTime('00:02.123')
    void button('预览这一帧').props.onClick()
    assert.match(path, /\/caption-renders\/preview\?at_ms=2123$/)
    assert.deepEqual(JSON.parse(String(request?.body)), {
      caption_revision: 2, caption_content_hash: snapshot.content_hash, style_preset: 'DARK_BOX_V1', frame_preset: 'SOURCE',
    })
    await mount(false)
    assert.equal(request?.signal?.aborted, true)
    complete!(new Response(new Uint8Array([1]), { headers: { 'Content-Type': 'image/jpeg' } }))
    await flushHost()
    assert.equal(nodes().some(node => node.type === 'img'), false)
  } finally {
    hostRenderer.render(null, root)
    Object.assign(globalThis, { window: originalWindow, fetch: originalFetch })
  }
})
