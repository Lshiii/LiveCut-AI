import assert from 'node:assert/strict'
import test from 'node:test'
import { h } from 'vue'
import * as playback from '../sequencePreview.ts'
import { topicTime } from '../topics.ts'
import { allHostNodes, compileHostComponent, flushHost, hostNode, hostRenderer, hostText } from './support/vueHost.ts'

test('boundary audition plays only the retained edge and stops when the scratch selection changes', async () => {
  const original = globalThis.document
  Object.assign(globalThis, { document: { baseURI: 'http://test/', hidden: false, addEventListener() {}, removeEventListener() {} } })
  const component = compileHostComponent(new URL('../RangeAudition.vue', import.meta.url), {
    './sequencePreview': playback, './topics': { topicTime },
  })
  const root = hostNode('root')
  const render = async (start_ms: number, end_ms: number, active=true) => {
    hostRenderer.render(h(component, { videoUrl: '/source.mp4', part: { start_ms, end_ms }, active }), root)
    await flushHost()
  }
  const button = (name: string) => allHostNodes(root).find(n => n.type === 'button' && hostText(n) === name)!
  try {
    await render(12000, 20000)
    const video = allHostNodes(root).find(n => n.type === 'video')!
    video.props.onLoadedmetadata({ currentTarget: video })
    await flushHost()
    assert.equal(video.playCalls, 0)
    button('听结尾').props.onClick()
    await flushHost()
    assert.equal(video.currentTime, 17)
    assert.equal(video.paused, false)
    await render(18000, 20000)
    assert.equal(video.paused, true)
    button('听开头').props.onClick()
    await flushHost()
    assert.equal(video.currentTime, 18)
    video.currentTime = 20.05
    video.props.onTimeupdate({ currentTarget: video })
    assert.equal(video.paused, true)
    assert.equal(video.currentTime, 20)
    await render(18000, 20000, false)
    assert.equal(button('听整段').props.disabled, true)
    button('听整段').props.onClick()
    assert.equal(video.playCalls, 2)
  } finally {
    hostRenderer.render(null, root)
    Object.assign(globalThis, { document: original })
  }
})
