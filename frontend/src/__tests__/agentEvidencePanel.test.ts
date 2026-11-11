import test from 'node:test'
import assert from 'node:assert/strict'
import { h, ref } from 'vue'
import * as videoAgent from '../videoAgent.ts'
import * as evidence from '../agentEvidenceAnswer.ts'
import * as review from '../agentEvidenceReview.ts'
import * as speech from '../agentSpeechLookup.ts'
import * as executors from '../executors.ts'
import * as visual from '../agentVisualEvidence.ts'
import * as focus from '../agentFocus.ts'
import * as followup from '../agentFrameFollowup.ts'
import * as sequence from '../sequencePreview.ts'
import * as anchors from '../answerClip.ts'
import * as topics from '../topics.ts'
import * as recovery from '../agentSubmissionRecovery.ts'
import * as history from '../agentSessionHistory.ts'
import * as refresh from '../agentRefresh.ts'
import { evidenceSample, cloneEvidence } from './support/agentEvidenceFixture.ts'
import { speechSample } from './support/agentSpeechLookupFixture.ts'
import { MemoryStorage } from './support/agentSubmissionFixture.ts'
import { allHostNodes, compileHostComponent, flushHost, hostNode, hostRenderer, hostText } from './support/vueHost.ts'
class HttpError extends Error { code: string; requestId?: string; status?: number; constructor(code: string, requestId?: string, status?: number) { super(code); this.code = code; this.requestId = requestId; this.status = status } }
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }
async function settle(check: () => boolean) { const end = Date.now() + 2000; while (!check()) { if (Date.now() > end) throw Error('SFC state did not settle'); await new Promise(done => setTimeout(done, 2)); await flushHost() } }
function executorSnapshot(withNew = true, online = 0) { const stages = withNew ? [...executors.EXECUTOR_STAGES] : executors.EXECUTOR_STAGES.slice(0, -1); return { generated_at: '2026-11-11T00:00:00Z', heartbeat_timeout_seconds: 35, online_workers: online, total_slots: online, busy_slots: 0, available_slots: online, workers: [], stages: stages.map(stage_type => ({ stage_type, online_workers: online, total_slots: online, busy_slots: 0, available_slots: online, reason: online ? 'AVAILABLE' : 'NO_EXECUTOR' })), resources: ['FFMPEG', 'ASR', 'TEXT'].map(name => ({ name, limit: 1, in_use: 0 })) } }
async function mount(active = true) {
  const old = { document: globalThis.document, Document: globalThis.Document, window: globalThis.window, localStorage: globalThis.localStorage, ResizeObserver: globalThis.ResizeObserver }
  class Doc extends EventTarget {}
  const document = Object.assign(new Doc(), { hidden: false, baseURI: 'http://test/' }), window = Object.assign(new EventTarget(), { setTimeout, clearTimeout }), storage = new MemoryStorage()
  Object.assign(globalThis, { document, Document: Doc, window, localStorage: storage, ResizeObserver: class { observe() {} disconnect() {} } })
  const sample = evidenceSample(), session = { ...sample.session, turns: [] } as videoAgent.AgentSession, calls: Array<{ path: string; init: any; stored?: string | null }> = []
  let submitted: videoAgent.AgentTurn | null = null
  const defaultHandle = async (path: string, init: any = {}): Promise<unknown> => {
    if (path === '/api/agent-capabilities') return { visual_mode: 'frames', visual_model_id: 'synthetic-vl', max_frames: 32, max_clip_duration_ms: 12000, clip_sample_interval_ms: 500 }
    if (path === '/api/agent-capabilities/focus') return { visual_focus_supported: true }
    if (path === '/api/agent-capabilities/evidence') return { structured_evidence_supported: true }
    if (path === '/api/agent-capabilities/speech-lookup') return { speech_lookup_supported: true, worker_protocol: 2 }
    if (path.startsWith('/api/executors')) return executorSnapshot()
    if (path.includes('/agent-session-history')) return { schema_version: '1.0', job_id: session.job_id, items: [], limit: 20, next_cursor: null, active_count: 0 }
    if (init.method === 'POST' && path === `/api/jobs/${session.job_id}/agent-sessions`) return cloneEvidence(session)
    if (path.includes('/turn-submissions/')) { assert.ok(submitted); return cloneEvidence(submitted) }
    if (path === `/api/agent-sessions/${session.id}`) return cloneEvidence(session)
    if (init.method === 'POST' && path === `/api/agent-sessions/${session.id}/turns`) {
      const body = JSON.parse(init.body), turn = cloneEvidence(sample.turn)
      turn.question = body.question; turn.budget.max_text_calls = body.max_text_calls; turn.status = 'QUEUED'; turn.stage.status = 'READY'; turn.result = null
      if (body.visual_mode !== 'frames') { delete turn.visual_mode; delete turn.evidence_mode; turn.stage.stage_type = 'SEGMENT' }
      else if (body.evidence_mode !== 'structured') { delete turn.evidence_mode; turn.stage.stage_type = 'AGENT_VISUAL' }
      if (body.evidence_policy === 'speech_lookup_v1') turn.evidence_policy = 'speech_lookup_v1'
      submitted = turn; session.turns = [turn]; return cloneEvidence(turn)
    }
    throw Error(`Unexpected ${init.method ?? 'GET'} ${path}`)
  }
  let handle = defaultHandle
  const api = (path: string, init: any = {}) => { calls.push({ path, init, stored: init.headers?.['Idempotency-Key'] ? [...storage.values.values()].find(value => JSON.parse(value).key === init.headers['Idempotency-Key']) : undefined }); return handle(path, init) }
  const nullComponent = { render: () => null }, cards = compileHostComponent(new URL('../AgentEvidenceAnswer.vue', import.meta.url), { './agentEvidenceAnswer': evidence, './agentEvidenceReview': review, './agentSpeechLookup': speech, './agentVisualEvidence': visual, './topics': topics })
  const component = compileHostComponent(new URL('../VideoAgentPanel.vue', import.meta.url), { './api': { api, ApiError: HttpError, post: (path: string, body?: unknown, headers: any = {}) => api(path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) }) },
    './FailureDiagnostic.vue': nullComponent, './TextRequestProgress.vue': nullComponent, './AgentAnswer.vue': nullComponent, './AgentEvidenceAnswer.vue': cards, './AgentVisualEvidence.vue': nullComponent, './AgentNotePanel.vue': nullComponent,
    './agentRefresh': refresh, './agentSessionHistory': history, './agentVisualEvidence': visual, './agentFocus': focus, './agentFrameFollowup': followup, './sequencePreview': sequence, './answerClip': anchors, './topics': topics,
    './videoAgent': videoAgent, './agentSubmissionRecovery': recovery, './agentEvidenceAnswer': evidence, './agentSpeechLookup': speech, './executors': executors })
  const exposed = ref<any>(null), root = hostNode('root'), props = { jobId: session.job_id, videoId: session.video_id, videoHash: session.source.video_sha256, videoUrl: '/original.mp4', active }
  const wrapper = { setup(_props: unknown, { attrs }: any) { return () => h(component, { ...attrs, ref: exposed }) } }, render = async () => { hostRenderer.render(h(wrapper, { ...props }), root); await flushHost() }
  await render(); await flushHost()
  const nodes = () => allHostNodes(root), button = (name: string) => { const node = nodes().find(node => node.type === 'button' && hostText(node) === name); assert.ok(node, `${name}: ${hostText(root)}`); return node }
  const ui = { root, props, exposed, document, window, storage, calls, session, sample, render, nodes, button, defaultHandle, setHandle: (value: typeof handle) => { handle = value },
    question: () => nodes().find(node => node.type === 'textarea')!,
    fill: async (value: string) => { ui.question().props['onUpdate:modelValue'](value); await flushHost() },
    setVisual: async (value: 'frames' | 'none') => { nodes().find(node => node.type === 'input' && node.props['true-value'] === 'frames')!.props['onUpdate:modelValue'](value); await flushHost() },
    setEvidence: async (value: boolean) => { nodes().find(node => node.type === 'input' && !node.props['true-value'] && node.props.type === 'checkbox')!.props['onUpdate:modelValue'](value); await flushHost() },
    evidenceCheckbox: () => nodes().find(node => node.type === 'input' && !node.props['true-value'] && node.props.type === 'checkbox'),
    setLookup: async (value: boolean) => { nodes().find(node => node.props['aria-label'] === '回答前核对讲者原句')!.props['onUpdate:modelValue'](value); await flushHost() },
    lookupCheckbox: () => nodes().find(node => node.props['aria-label'] === '回答前核对讲者原句'),
    click: async (name: string) => { const node = button(name); assert.equal(!!node.props.disabled, false, name); node.props.onClick({}); await flushHost() },
    submit: async () => { nodes().find(node => node.type === 'form')!.props.onSubmit({ preventDefault() {} }); await flushHost() },
    close: () => { hostRenderer.render(null, root); Object.assign(globalThis, old) } }
  return ui
}

test('production Agent panel makes only active capability reads and explicitly opts into dedicated evidence queue even when workers are offline', async () => {
  const ui = await mount(false)
  try {
    assert.equal(ui.calls.length, 0); ui.props.active = true; await ui.render(); await settle(() => ui.calls.some(call => call.path.startsWith('/api/executors')))
    await ui.setVisual('frames'); assert.equal(ui.evidenceCheckbox()!.props.disabled, false); assert.match(hostText(ui.root), /提交后等待支持分项协议的执行器/)
    await ui.setEvidence(true); assert.equal(ui.exposed.value.dirty, true); await ui.fill('分项问题'); await ui.submit(); await settle(() => !ui.exposed.value.pending)
    const turnPost = ui.calls.find(call => call.init.method === 'POST' && call.path.endsWith('/turns'))!; assert.ok(turnPost,hostText(ui.root)+JSON.stringify(ui.calls.map(v=>v.path)))
    assert.equal(JSON.parse(turnPost.init.body).evidence_mode, 'structured'); assert.equal(JSON.parse(turnPost.stored!).input, turnPost.init.body)
    assert.equal(ui.question().value, ''); assert.equal(ui.exposed.value.dirty, false)
  } finally { ui.close() }
})

test('actual capability 404 closes new opt-in but preserves a checked draft; 503 or malformed200 never masquerades as old service', async () => {
  for (const status of [404, 503, 200]) {
    const ui = await mount()
    try {
      await ui.setVisual('frames'); await ui.setEvidence(true); await ui.fill('保留问题')
      ui.setHandle(async (path, init) => { if (path === '/api/agent-capabilities/evidence') { if (status === 200) return { structured_evidence_supported: true, extra: true }; throw new HttpError('HTTP_'+status, undefined, status) } return ui.defaultHandle(path, init) })
      await ui.click('检查画面能力'); await settle(() => hostText(ui.root).includes(status === 404 ? '当前服务版本未提供' : '暂不能核对分项协议'))
      assert.equal(ui.question().value, '保留问题'); assert.equal(ui.evidenceCheckbox()!.checked, true); assert.equal(ui.button('开始提问 ↑').props.disabled, true)
      assert.equal(ui.calls.filter(call => call.init.method === 'POST').length, 0)
      await ui.setEvidence(false); assert.equal(ui.button('开始提问 ↑').props.disabled, false)
    } finally { ui.close() }
  }
})

test('missing evidence queue blocks new mode but original receipt recovers without executor or capability checks and does not clear a differently toggled draft', async () => {
  const ui = await mount()
  try {
    await ui.setVisual('frames'); await ui.setEvidence(true); await ui.fill('同一个问题')
    ui.setHandle(async (path, init) => { const value = await ui.defaultHandle(path, init); if (path.endsWith('/turns') && init.method === 'POST') throw new HttpError('NETWORK_UNAVAILABLE'); return value })
    await ui.submit(); await settle(() => !ui.exposed.value.pending); assert.ok(ui.storage.length); await ui.setEvidence(false)
    ui.setHandle(async (path, init) => { if (path === '/api/agent-capabilities/evidence') throw new HttpError('HTTP_404', undefined, 404); if (path.startsWith('/api/executors')) return executorSnapshot(false); return ui.defaultHandle(path, init) })
    const before = ui.calls.length; await ui.click('核对原提交'); await settle(() => !ui.exposed.value.pending && ui.storage.length === 0)
    assert.equal(ui.question().value, '同一个问题'); assert.equal(ui.evidenceCheckbox()!.checked, false)
    assert.ok(!ui.calls.slice(before).some(call => call.path.includes('capabilities') || call.path.startsWith('/api/executors') || call.init.method === 'POST'))
    assert.equal(ui.calls.filter(call => call.init.method === 'POST' && call.path.endsWith('/turns')).length, 1)
  } finally { ui.close() }
})

test('old visual/text request bodies omit evidence_mode; toggling frames off retains structured draft and cannot silently downgrade', async () => {
  for (const frames of [false, true]) {
    const ui = await mount()
    try { if (frames) await ui.setVisual('frames'); await ui.fill('旧请求'); await ui.submit(); await settle(() => !ui.exposed.value.pending); const post = ui.calls.find(call => call.path.endsWith('/turns') && call.init.method === 'POST')!; assert.deepEqual(JSON.parse(post.init.body), { question: '旧请求', max_text_calls: 8, ...(frames ? { visual_mode: 'frames' } : {}) }) }
    finally { ui.close() }
  }
  const ui = await mount()
  try { await ui.setVisual('frames'); await ui.setEvidence(true); await ui.fill('不要降级'); await ui.setVisual('none'); assert.equal(ui.button('开始提问 ↑').props.disabled, true); assert.equal(ui.evidenceCheckbox()!.checked, true); await ui.submit(); assert.equal(ui.calls.filter(call => call.init.method === 'POST').length, 0); assert.match(hostText(ui.root), /切回“结合画面”或明确关闭/); assert.equal(ui.question().value, '不要降级') }
  finally { ui.close() }
})

test('hidden late capability reply cannot replace a new visible 404 or enable its pending structured draft', async () => {
  const ui = await mount()
  try {
    await ui.setVisual('frames'); await ui.setEvidence(true); const late = deferred<unknown>()
    ui.setHandle((path, init) => path === '/api/agent-capabilities/evidence' ? late.promise : ui.defaultHandle(path, init)); await ui.click('检查画面能力')
    const old = ui.calls.findLast(call => call.path === '/api/agent-capabilities/evidence')!
    ui.props.active = false; await ui.render(); assert.equal(old.init.signal.aborted, true)
    ui.setHandle(async (path, init) => { if (path === '/api/agent-capabilities/evidence') throw new HttpError('HTTP_404', undefined, 404); return ui.defaultHandle(path, init) }); ui.props.active = true; await ui.render(); await settle(() => hostText(ui.root).includes('当前服务版本未提供'))
    late.resolve({ structured_evidence_supported: true }); await flushHost(); assert.match(hostText(ui.root), /当前服务版本未提供/); assert.equal(ui.evidenceCheckbox()!.checked, true)
  } finally { ui.close() }
})

test('a verified old queue snapshot cannot admit structured writes and does not silently discard the requested mode', async () => {
  const ui = await mount()
  try {
    await ui.setVisual('frames'); await ui.setEvidence(true); await ui.fill('等待新队列')
    ui.setHandle((path, init) => path.startsWith('/api/executors') ? Promise.resolve(executorSnapshot(false)) : ui.defaultHandle(path, init))
    await ui.click('检查画面能力'); await settle(() => hostText(ui.root).includes('暂不能核对分项协议')); assert.equal(ui.button('开始提问 ↑').props.disabled, true)
    await ui.submit(); await settle(() => !ui.exposed.value.pending); assert.equal(ui.calls.filter(call => call.init.method === 'POST').length, 0); assert.equal(ui.evidenceCheckbox()!.checked, true); assert.equal(ui.question().value, '等待新队列')
  } finally { ui.close() }
})

test('hidden original POST can finish without clearing a new question/mode and recovery only reads its immutable original', async () => {
  const ui = await mount()
  try {
    await ui.setVisual('frames'); await ui.setEvidence(true); await ui.fill('原分项问题'); const response = deferred<unknown>(); let queued: unknown
    ui.setHandle(async (path, init) => { const value = await ui.defaultHandle(path, init); if (path.endsWith('/turns') && init.method === 'POST') { queued = value; return response.promise } return value })
    await ui.submit(); await settle(() => !!queued); assert.equal(ui.exposed.value.pending, true)
    ui.props.active = false; await ui.render(); await ui.setEvidence(false); await ui.fill('新草稿问题'); const original = [...ui.storage.values.values()]
    response.resolve(queued); await flushHost(); assert.deepEqual([...ui.storage.values.values()], original); assert.equal(ui.question().value, '新草稿问题')
    ui.setHandle(ui.defaultHandle); ui.props.active = true; await ui.render(); await ui.click('核对原提交'); await settle(() => !ui.exposed.value.pending && ui.storage.length === 0)
    assert.equal(ui.question().value, '新草稿问题'); assert.equal(ui.evidenceCheckbox()!.checked, false); assert.equal(ui.calls.filter(call => call.path.endsWith('/turns') && call.init.method === 'POST').length, 1)
  } finally { ui.close() }
})

test('actual main answer branch adopts validated 1.3 cards once and retains speech/visual/note/followup affordances', async () => {
  const ui = await mount()
  try {
    ui.setHandle((path, init) => init.method === 'POST' && path.endsWith('/agent-sessions') ? Promise.resolve(cloneEvidence(ui.sample.session)) : ui.defaultHandle(path, init))
    await ui.click('＋ 新对话'); await settle(() => hostText(ui.root).includes('前后采样 · b03'))
    const text = hostText(ui.root); assert.equal((text.match(/来源与结构核对不代表每项语义已被规则证明/g) ?? []).length, 1)
    assert.match(text, /选择此原句/); assert.match(text, /视频原句依据/); assert.match(text, /原句依据 · b01/); assert.match(text, /前侧采样 · f0001/); assert.match(text, /后侧采样 · f0002/)
    assert.equal(ui.nodes().filter(node => node.type === 'section' && node.props['aria-label'] === '分项证据回答').length, 1)
  } finally { ui.close() }
})

test('speech policy is explicit and v2 preflight finishes before any new session/turn POST; original durable body binds the policy', async () => {
  const ui = await mount(false)
  try {
    assert.equal(ui.calls.length, 0); ui.props.active = true; await ui.render(); await ui.setVisual('frames'); await ui.setEvidence(true)
    assert.equal(ui.lookupCheckbox()!.checked, false); await ui.setLookup(true); await ui.fill('先查讲者原句')
    await ui.submit(); await settle(() => !ui.exposed.value.pending)
    const writes = ui.calls.filter(call => call.init.method === 'POST'), body = JSON.parse(writes.at(-1)!.init.body)
    assert.equal(body.evidence_policy, 'speech_lookup_v1'); assert.equal(JSON.parse(writes.at(-1)!.stored!).input, writes.at(-1)!.init.body)
    const firstWrite = ui.calls.indexOf(writes[0]!), preflight = ui.calls.findLastIndex(call => call.path.includes('required_worker_protocol=2'))
    assert.ok(preflight >= 0 && preflight < firstWrite); assert.ok(ui.calls.slice(0, firstWrite).some(call => call.path === '/api/agent-capabilities/speech-lookup'))
    assert.equal(ui.question().value, ''); assert.equal(ui.lookupCheckbox(), undefined); assert.equal(ui.exposed.value.dirty, false)
  } finally { ui.close() }
})

test('speech404/503/malformed200 or absent v2 queue blocks new writes and preserves the exact policy/question', async () => {
  for (const mode of ['404', '503', 'malformed', 'missing-queue']) {
    const ui = await mount()
    try {
      await ui.setVisual('frames'); await ui.setEvidence(true); await ui.setLookup(true); await ui.fill('保留预查要求')
      ui.setHandle(async (path, init) => {
        if (path === '/api/agent-capabilities/speech-lookup') { if (mode === 'malformed') return { speech_lookup_supported: true, worker_protocol: 1 }; if (mode === '404' || mode === '503') throw new HttpError('HTTP_'+mode, undefined, Number(mode)) }
        if (mode === 'missing-queue' && path.includes('required_worker_protocol=2')) return executorSnapshot(false)
        return ui.defaultHandle(path, init)
      })
      await ui.click('检查画面能力'); await settle(() => hostText(ui.root).includes(mode === '404' ? '当前服务版本未提供回答前原句预查' : '暂不能核对原句预查协议'))
      assert.equal(ui.lookupCheckbox()!.checked, true); assert.equal(ui.question().value, '保留预查要求'); assert.equal(ui.button('开始提问 ↑').props.disabled, true)
      await ui.submit(); await settle(() => !ui.exposed.value.pending); assert.equal(ui.calls.filter(call => call.init.method === 'POST').length, 0); assert.equal(ui.lookupCheckbox()!.checked, true)
    } finally { ui.close() }
  }
})

test('turning either outer mode off preserves requested speech policy and blocks silent downgrade; only an explicit uncheck removes it', async () => {
  const ui = await mount()
  try {
    await ui.setVisual('frames'); await ui.setEvidence(true); await ui.setLookup(true); await ui.fill('不要改成旧模式')
    await ui.setEvidence(false); assert.equal(ui.lookupCheckbox()!.checked, true); assert.equal(ui.button('开始提问 ↑').props.disabled, true)
    await ui.submit(); assert.match(hostText(ui.root), /请恢复“结合画面”和“分项证据回答”/); assert.equal(ui.calls.filter(call => call.init.method === 'POST').length, 0)
    await ui.setEvidence(true); await ui.setVisual('none'); await ui.submit(); assert.equal(ui.lookupCheckbox()!.checked, true); assert.equal(ui.calls.filter(call => call.init.method === 'POST').length, 0)
    await ui.setVisual('frames'); await ui.setLookup(false); await ui.submit(); await settle(() => !ui.exposed.value.pending)
    const turnPost = ui.calls.find(call => call.init.method === 'POST' && call.path.endsWith('/turns'))!; assert.ok(!Object.hasOwn(JSON.parse(turnPost.init.body), 'evidence_policy'))
  } finally { ui.close() }
})

test('original speech-policy GET recovery ignores current capability failure and cannot clear a differently toggled draft', async () => {
  for (const originalPolicy of [false, true]) {
    const ui = await mount()
    try {
      await ui.setVisual('frames'); await ui.setEvidence(true); if (originalPolicy) await ui.setLookup(true); await ui.fill('原问题正文相同')
      ui.setHandle(async (path, init) => { const value = await ui.defaultHandle(path, init); if (path.endsWith('/turns') && init.method === 'POST') throw new HttpError('NETWORK_UNAVAILABLE'); return value })
      await ui.submit(); await settle(() => !ui.exposed.value.pending); await ui.setLookup(!originalPolicy)
      ui.setHandle(async (path, init) => { if (path === '/api/agent-capabilities/speech-lookup') throw new HttpError('HTTP_404', undefined, 404); return ui.defaultHandle(path, init) })
      const before = ui.calls.length; await ui.click('核对原提交'); await settle(() => !ui.exposed.value.pending && ui.storage.length === 0)
      assert.equal(ui.question().value, '原问题正文相同'); assert.equal(ui.lookupCheckbox()!.checked, !originalPolicy)
      assert.ok(!ui.calls.slice(before).some(call => call.path.includes('capabilities') || call.path.includes('/api/executors') || call.init.method === 'POST'))
      assert.equal(ui.calls.filter(call => call.path.endsWith('/turns') && call.init.method === 'POST').length, 1)
    } finally { ui.close() }
  }
})

test('hidden speech-policy POST completion preserves its original entry and a new draft; late capability cannot reenable after404', async () => {
  const ui = await mount()
  try {
    await ui.setVisual('frames'); await ui.setEvidence(true); await ui.setLookup(true); await ui.fill('原预查提交')
    const response = deferred<unknown>(); let queued: unknown
    ui.setHandle(async (path, init) => { const value = await ui.defaultHandle(path, init); if (path.endsWith('/turns') && init.method === 'POST') { queued = value; return response.promise } return value })
    await ui.submit(); await settle(() => !!queued); ui.props.active = false; await ui.render(); await ui.setLookup(false); await ui.fill('隐藏后的新问题')
    const saved = [...ui.storage.values.values()]; response.resolve(queued); await flushHost(); assert.deepEqual([...ui.storage.values.values()], saved); assert.equal(ui.question().value, '隐藏后的新问题')
  } finally { ui.close() }
  const other = await mount()
  try {
    await other.setVisual('frames'); await other.setEvidence(true); await other.setLookup(true); const late = deferred<unknown>()
    other.setHandle((path, init) => path === '/api/agent-capabilities/speech-lookup' ? late.promise : other.defaultHandle(path, init)); await other.click('检查画面能力')
    const old = other.calls.findLast(call => call.path === '/api/agent-capabilities/speech-lookup')!; other.props.active = false; await other.render(); assert.equal(old.init.signal.aborted, true)
    other.setHandle(async (path, init) => { if (path === '/api/agent-capabilities/speech-lookup') throw new HttpError('HTTP_404', undefined, 404); return other.defaultHandle(path, init) }); other.props.active = true; await other.render(); await settle(() => hostText(other.root).includes('当前服务版本未提供回答前原句预查'))
    late.resolve({ speech_lookup_supported: true, worker_protocol: 2 }); await flushHost(); assert.match(hostText(other.root), /当前服务版本未提供回答前原句预查/); assert.equal(other.lookupCheckbox()!.checked, true)
  } finally { other.close() }
})

test('actual 1.4 history rendering labels the policy step as program work and keeps its scope/status separate from speech citations', async () => {
  const ui = await mount()
  try {
    const packet = speechSample('focus_zero_is_valid').session
    ui.setHandle((path, init) => init.method === 'POST' && path.endsWith('/agent-sessions') ? Promise.resolve(cloneEvidence(packet)) : ui.defaultHandle(path, init))
    await ui.click('＋ 新对话'); await settle(() => hostText(ui.root).includes('程序原句预查 · 回答前核对讲者原句'))
    assert.match(hostText(ui.root), /程序按预查策略读取/); assert.match(hostText(ui.root), /用户位置触发/)
    assert.equal(ui.nodes().filter(node => node.props['aria-label'] === '程序原句预查').length, 1); assert.equal(ui.nodes().filter(node => node.props['aria-label'] === '证据核查摘要').length, 1)
    assert.equal(ui.exposed.value.dirty, false, 'Viewing a source policy does not select it for the next question')
  } finally { ui.close() }
})
