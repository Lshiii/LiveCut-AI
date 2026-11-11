import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { compileScript, parse } from '@vue/compiler-sfc'
import { ModuleKind, ScriptTarget, transpileModule } from 'typescript'
import { createRenderer, markRaw, nextTick } from 'vue'

export interface HostNode {
  type: string; text: string; props: Record<string, any>; children: HostNode[]; parent?: HostNode
  [key: string]: any
}
export function hostNode(type: string, text = ''): HostNode {
  const value: HostNode = { type, tagName: type.toUpperCase(), text, props: {}, children: [], style: { display: '' }, value: '', scrollTop: 0, scrollHeight: 0, clientHeight: 0, scrollTo: () => {}, focus: () => {}, querySelector: () => null, querySelectorAll: () => [], getBoundingClientRect: () => ({ top: 0 }), scrollIntoView: () => {}, addEventListener: () => {}, removeEventListener: () => {}, getRootNode: () => globalThis.document }
  if (type === 'video') Object.assign(value, { currentTime: 0, currentSrc: '', readyState: 2, paused: true, seeking: false, ended: false, playbackRate: 1,
    playCalls: 0, pauseCalls: 0, play: () => { value.playCalls++; value.paused = false; return Promise.resolve() },
    pause: () => { value.pauseCalls++; value.paused = true } })
  return markRaw(value)
}
export const hostRenderer = createRenderer<HostNode, HostNode>({
  createElement: type => hostNode(type), createText: text => hostNode('#text', text), createComment: text => hostNode('#comment', text),
  setText: (target, text) => { target.text = text }, setElementText: (target, text) => { target.text = text; for (const child of target.children) child.parent = undefined; target.children = [] },
  parentNode: target => target.parent ?? null,
  nextSibling: target => { const index = target.parent?.children.indexOf(target) ?? -1; return index < 0 ? null : target.parent?.children[index + 1] ?? null },
  patchProp: (target, key, _previous, value) => { target.props[key] = value; if (target.type === 'video' && key === 'src') target.currentSrc = value ? new URL(value, 'http://test/').href : '' },
  insert: (target, parent, anchor) => { const prior = target.parent?.children.indexOf(target) ?? -1; if (prior >= 0) target.parent!.children.splice(prior, 1); target.parent = parent;
    const index = anchor ? parent.children.indexOf(anchor) : -1; if (index < 0) parent.children.push(target); else parent.children.splice(index, 0, target) },
  remove: target => { const index = target.parent?.children.indexOf(target) ?? -1; if (index >= 0) target.parent!.children.splice(index, 1); target.parent = undefined },
})
export const allHostNodes = (node: HostNode): HostNode[] => [node, ...node.children.flatMap(allHostNodes)]
export const hostText = (node: HostNode): string => node.text + node.children.map(hostText).join('')
export const flushHost = async () => { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick() }
const require = createRequire(import.meta.url)
export function compileHostComponent(url: URL, modules: Record<string, unknown>) {
  const descriptor = parse(readFileSync(url, 'utf8')).descriptor
  const code = transpileModule(compileScript(descriptor, { id: 'host-test', inlineTemplate: true, templateOptions: { compilerOptions: { hoistStatic: false } } }).content,
    { compilerOptions: { module: ModuleKind.CommonJS, target: ScriptTarget.ES2022 } }).outputText
  const exports: { default?: any } = {}
  new Function('require', 'exports', code)((name: string) => Object.hasOwn(modules, name) ? modules[name] : require(name), exports)
  return exports.default
}
