import assert from 'node:assert/strict'
import test from 'node:test'
import { h } from 'vue'
import * as mapping from '../editTimeline.ts'
import { topicTime } from '../topics.ts'
import { allHostNodes, compileHostComponent, flushHost, hostNode, hostRenderer } from './support/vueHost.ts'

test('only explicit scrubbing emits seeks, with a stable zoom window during a drag and keyboard millisecond precision', async () => {
  const component = compileHostComponent(new URL('../CutScrubber.vue', import.meta.url), { './editTimeline': mapping, './topics': { topicTime } })
  const root = hostNode('root'), events: number[] = []
  let positionMs: number | null = 7000, disabled = false
  const render = async () => {
    hostRenderer.render(h(component, { parts: [{start_ms: 1000,end_ms: 11000}, {start_ms: 20000,end_ms: 30000}],
      positionMs, disabled, onSeek: (value: number) => { events.push(value) } }), root)
    await flushHost()
  }
  try {
    await render()
    const nodes = () => allHostNodes(root)
    const slider = nodes().find(node => node.props.role === 'slider')!
    const zoom = nodes().find(node => node.props['aria-label'] === '放大时间轴')!
    zoom.props.onClick()
    await flushHost()
    assert.deepEqual(events, [])
    let captured: number | null = null
    Object.assign(slider, { getBoundingClientRect: () => ({ left: 10, width: 100 }),
      setPointerCapture: (id: number) => { captured = id }, hasPointerCapture: (id: number) => captured === id,
      releasePointerCapture: () => { captured = null } })
    const pointer = { pointerId: 7, button: 0, isPrimary: true, clientX: 90, preventDefault() {} }
    slider.props.onPointerdown(pointer)
    assert.equal(events.at(-1), 10000)
    slider.props.onPointermove({ ...pointer, clientX: 110 })
    assert.equal(events.at(-1), 12000)
    slider.props.onPointerup({ ...pointer, clientX: 110, type: 'pointerup' })
    assert.equal(captured, null)
    assert.equal(events.length, 2)
    positionMs = 10000
    await render()
    assert.match(slider.props['aria-valuetext'], /原片 00:20.000/)
    for (const [key, shiftKey, ms] of [['ArrowLeft', false, 9999], ['ArrowRight', true, 10100], ['End', false, 20000]] as const) {
      slider.props.onKeydown({ key, shiftKey, preventDefault() {} })
      assert.equal(events.at(-1), ms)
    }
    positionMs = null
    disabled = true
    await render()
    assert.match(slider.props['aria-valuetext'], /保留区间外/)
    slider.props.onKeydown({key: 'Home', preventDefault() {}})
    slider.props.onPointerdown(pointer)
    assert.equal(events.length, 5)
  } finally { hostRenderer.render(null, root) }
})
