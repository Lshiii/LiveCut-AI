import assert from 'node:assert/strict'
import test from 'node:test'
import { createRenderer, h } from 'vue'
import * as timeline from '../editTimeline.ts'
import * as draft from '../fineCutDraft.ts'
import * as project from '../fineCutProject.ts'
import * as preview from '../sequencePreview.ts'
import * as topics from '../topics.ts'
import { allHostNodes, compileHostComponent, flushHost, hostNode, hostText, type HostNode } from './support/vueHost.ts'

// These components use native modal methods and v-model on a native select.
const renderer = createRenderer<HostNode, HostNode>({
  createElement(type) {
    const el = hostNode(type)
    if (type === 'dialog') {
      el.showModal = () => { el.open = true }
      el.close = () => { el.open = false }
    }
    if (type === 'select') Object.defineProperty(el, 'options', { get: () => el.children.filter(n => n.type === 'option') })
    return el
  },
  createText: value => hostNode('#text', value), createComment: value => hostNode('#comment', value),
  setText: (el, value) => { el.text = value }, setElementText: (el, value) => { el.text = value; el.children = [] },
  parentNode: el => el.parent ?? null,
  nextSibling: el => el.parent?.children[(el.parent.children.indexOf(el) + 1)] ?? null,
  patchProp: (el, key, _old, value) => { el.props[key] = value; if (key === 'value') el._value = value },
  insert: (el, parent, before) => {
    const old = el.parent?.children.indexOf(el) ?? -1
    if (old >= 0) el.parent!.children.splice(old, 1)
    el.parent = parent
    const pos = before ? parent.children.indexOf(before) : -1
    parent.children.splice(pos < 0 ? parent.children.length : pos, 0, el)
  },
  remove: el => {
    const pos = el.parent?.children.indexOf(el) ?? -1
    if (pos >= 0) el.parent!.children.splice(pos, 1)
    el.parent = undefined
  },
})
const bounds = compileHostComponent(new URL('../FineCutBounds.vue', import.meta.url), { './topics':topics })
const editor = compileHostComponent(new URL('../FineCutEditor.vue', import.meta.url), {
  './FineCutBounds.vue':bounds, './CutScrubber.vue':{render:()=>null}, './editTimeline':timeline, './fineCutDraft':draft,
  './fineCutProject':project, './sequencePreview':preview, './topics':topics,
})
async function mount() {
  const old = { document:globalThis.document, window:globalThis.window, localStorage:globalThis.localStorage }
  const win = Object.assign(new EventTarget(), { setTimeout, clearTimeout })
  const doc = Object.assign(new EventTarget(), { hidden:false, baseURI:'http://test/' })
  Object.assign(globalThis, { window:win, document:doc, localStorage:{getItem:()=>null,setItem() {}} })
  const root=hostNode('root')
  renderer.render(h(editor, { parts:[{start_ms:4000,end_ms:5000,start_utterance_id:'a',end_utterance_id:'b'},
    {start_ms:10000,end_ms:11000,start_utterance_id:'c',end_utterance_id:'d'}], owner:{}, videoUrl:'/original',
    title:'精剪',projectKey:'test',timelineUrl:'/unused',active:true }),root)
  await flushHost()
  const find = (match:(n:HostNode)=>boolean) => { const node=allHostNodes(root).find(match); assert.ok(node); return node }
  return {
    win, get:(name:string)=>find(n=>n.props['aria-label']===name),
    button:(name:string)=>find(n=>n.type==='button' && hostText(n).trim()===name),
    segment:()=>find(n=>n.type==='select' && n.parent?.props.class==='fine-segment'),
    close() { renderer.render(null,root); Object.assign(globalThis,old) },
  }
}

test('invalid or uncommitted boundary text blocks download and part switching; a committed one-millisecond interval is valid', async () => {
  const ui=await mount()
  try {
    const input=ui.get('入点原片时间'), download=ui.button('下载精剪时间线 (.otio)')
    const lockedControls = ['出点原片时间','出点提前','入点推迟','拖动入点','拖动出点']
    for (const text of ['bad','00:03.999','00:05.000']) {
      input.props.onInput({target:{value:text}})
      input.props.onChange()
      await flushHost()
      assert.equal(input.props['aria-invalid'],true)
      assert.equal(download.props.disabled,true)
      assert.equal(ui.segment().props.disabled,true)
      assert.equal(input.props.disabled,false)
      for (const name of lockedControls) {
        assert.equal(ui.get(name).props.disabled,true,name)
      }
      ui.get('拖动出点').props.onInput({target:{valueAsNumber:4800}})
      await flushHost()
      assert.equal(input.props.value,text)
      assert.equal(ui.get('出点原片时间').props.value,'00:05.000')
      assert.equal(ui.get('拖动入点').props.value,4000)
    }
    input.props.onInput({target:{value:'00:04.100'}})
    input.props.onChange()
    await flushHost()
    for (const name of lockedControls) {
      assert.equal(ui.get(name).props.disabled,false,name)
    }
    assert.equal(download.props.disabled,false)
    input.props.onInput({target:{value:'00:04.999'}})
    await flushHost()
    assert.equal(input.props['aria-invalid'],false)
    assert.equal(download.props.disabled,true)
    input.props.onChange()
    await flushHost()
    assert.equal(ui.get('拖动入点').props.value,4999)
    assert.equal(ui.get('入点推迟').props.disabled,true)
    assert.equal(ui.get('出点提前').props.disabled,true)
    assert.equal(ui.segment().props.disabled,false)
    assert.equal(download.props.disabled,false)
  } finally { ui.close() }
})

test('one undo restores a whole handle drag; an edit to another segment has its own history entry', async () => {
  const ui=await mount()
  try {
    const grip=ui.get('拖动出点'), undo=ui.button('撤销上次调整')
    grip.props.onPointerdown()
    for (const value of [4800,4500,4000]) {
      grip.props.onInput({target:{valueAsNumber:value}})
      await flushHost()
    }
    ui.win.dispatchEvent(new Event('pointerup'))
    assert.equal(grip.props.value,4001)
    ui.segment().props.onChange({target:{value:'1'}})
    await flushHost()
    assert.equal(ui.get('入点原片时间').props.value,'00:10.000')
    ui.get('入点推迟').props.onClick()
    await flushHost()
    assert.equal(ui.get('拖动入点').props.value,10100)
    undo.props.onClick()
    await flushHost()
    assert.equal(ui.segment().props.value,1)
    assert.equal(ui.get('拖动入点').props.value,10000)
    undo.props.onClick()
    await flushHost()
    assert.equal(ui.segment().props.value,0)
    assert.equal(ui.get('拖动出点').props.value,5000)
    assert.equal(undo.props.disabled,true)
  } finally { ui.close() }
})
