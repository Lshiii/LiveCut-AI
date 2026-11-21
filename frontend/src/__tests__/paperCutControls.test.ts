import assert from 'node:assert/strict'
import test from 'node:test'
import { h } from 'vue'
import * as editTimeline from '../editTimeline.ts'
import * as paperCut from '../paperCut.ts'
import * as preview from '../sequencePreview.ts'
import { topicTime } from '../topics.ts'
import { allHostNodes, compileHostComponent, flushHost, hostNode, hostRenderer, hostText, type HostNode } from './support/vueHost.ts'

function button(root: HostNode, text: string) {
  const node = allHostNodes(root).find(candidate => candidate.type === 'button' && hostText(candidate).trim() === text)
  assert.ok(node, text)
  return node
}

test('range selection skips protected rows; filtering preserves batch selection and the unfiltered page position', async () => {
  const prior = { document: globalThis.document, Document: globalThis.Document }
  class TestDocument extends EventTarget { hidden = false }
  Object.assign(globalThis, { document: new TestDocument(), Document: TestDocument })
  const root = hostNode('root')
  const placeholder = { render: () => null }
  const component = compileHostComponent(new URL('../PaperCutEditor.vue', import.meta.url), {
    './paperCut': paperCut, './sequencePreview': preview, './topics': { topicTime },
    './editTimeline': editTimeline, './CutScrubber.vue': placeholder,
    './PaperCutBudget.vue': placeholder, './PaperCutSuggestion.vue': placeholder,
  })
  const rows = Array.from({ length: 62 }, (_, i) => ({
    utterance: { utterance_id: `u${i}`, start_ms: i * 1000, end_ms: (i + 1) * 1000, text: `sentence[${i}]` },
    evidence: true, anchor: i === 31, partIndex: 0,
  }))
  try {
    hostRenderer.render(h(component, { source: { rows, originalDurationMs: 62000 }, context: {}, title: '草稿', goal: '',
      videoUrl: '', token: 1, active: true, stale: false }), root)
    const dialog = allHostNodes(root).find(node => node.type === 'dialog')!
    dialog.showModal = () => { dialog.open = true }
    dialog.close = () => { dialog.open = false }
    await flushHost()
    button(root, '下一页').props.onClick()
    await flushHost()
    const boxes = allHostNodes(root).filter(node => node.props.type === 'checkbox')
    boxes[0]!.props.onClick({ currentTarget: { checked: true }, shiftKey: false })
    boxes[3]!.props.onClick({ currentTarget: { checked: true }, shiftKey: true })
    await flushHost()
    assert.equal(boxes[1]!.props.disabled, true)
    assert.deepEqual(boxes.slice(0, 4).map(node => node.props.checked), [true, false, true, true])
    const list = allHostNodes(root).find(node => node.props.class === 'paper-rows')!
    list.scrollTop = 420
    const search = allHostNodes(root).find(node => node.props.type === 'search')!
    search.props['onUpdate:modelValue']('sentence[2]')
    await flushHost()
    assert.match(hostText(root), /含未显示 3 句/)
    assert.equal(list.scrollTop, 0)
    search.props['onUpdate:modelValue']('')
    await flushHost()
    assert.equal(list.scrollTop, 420)
    assert.match(hostText(root), /2 \/ 3/)
    button(root, '剪掉选中').props.onClick()
    await flushHost()
    assert.match(hostText(root), /已剪掉 3 句/)
    button(root, '撤销').props.onClick()
    await flushHost()
    assert.match(hostText(root), /已剪掉 0 句/)
  } finally {
    hostRenderer.render(null, root)
    Object.assign(globalThis, prior)
  }
})

test('selecting a cut stops the previous audition and keeps the same source boundary after an earlier edit', async () => {
  const scrub = { emits: ['seek'], setup(_: unknown, { emit }: { emit: (name: 'seek', ms: number) => void }) {
    return () => h('button', { onClick: () => emit('seek', 123) }, '定位成片游标')
  } }
  const component = compileHostComponent(new URL('../EditJoinTimeline.vue', import.meta.url), {
    './editTimeline': editTimeline, './joinBlackFrames': {}, './topics': { topicTime }, './CutScrubber.vue': scrub,
  })
  const root = hostNode('root'), located: number[] = [], scrubbed: number[] = []
  const edits: [number, string, Event][] = []
  let stops = 0, editable = true
  const parts = [2000, 4000, 6000].map(start_ms => ({ start_ms, end_ms: start_ms + 500 }))
  const render = async () => {
    hostRenderer.render(h(component, { parts: [...parts], videoUrl: '/video', disabled: false, activeJoin: 0, canAdjust: editable,
      onStop: () => { stops++ }, onSeek: (ms: number) => located.push(ms), onScrub: (ms: number) => scrubbed.push(ms),
      onAdjustBoundary: (index: number, field: string, event: Event) => edits.push([index, field, event]) }), root)
    await flushHost()
  }
  try {
    await render()
    const pins = () => allHostNodes(root).filter(node => node.props.class === 'cut-pin')
    pins()[1]!.props.onClick()
    await flushHost()
    assert.equal(stops, 1)
    assert.equal(pins()[1]!.props['aria-pressed'], true)
    parts.unshift({ start_ms: 0, end_ms: 800 })
    await render()
    assert.equal(pins()[2]!.props['aria-pressed'], true)
    button(root, '前段出点 00:04.500').props.onClick()
    button(root, '后段入点 00:06.000').props.onClick()
    assert.deepEqual(located, [4499, 6000])
    const trigger = new Event('click')
    button(root, '调整前段结尾').props.onClick(trigger)
    button(root, '调整后段开头').props.onClick(trigger)
    assert.deepEqual(edits, [[2, 'end', trigger], [3, 'start', trigger]])
    editable = false
    await render()
    assert.equal(button(root, '调整前段结尾').props.disabled, true)
    assert.equal(button(root, '后段入点 00:06.000').props.disabled, false)
    parts.splice(1)
    await render()
    assert.equal(pins().length, 0)
    button(root, '定位成片游标').props.onClick()
    assert.deepEqual(scrubbed, [123])
    assert.deepEqual(located, [4499, 6000])
  } finally { hostRenderer.render(null, root) }
})
