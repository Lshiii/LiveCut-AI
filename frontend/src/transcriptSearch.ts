export interface TranscriptOwner { job_id: string; video_id: string; generation: number; source_video_sha256: string }
export interface TranscriptSource extends TranscriptOwner {
  transcript_sha256: string; analysis_sha256: string; duration_ms: number; source_fingerprint: string
}
export interface TranscriptUtterance { utterance_id: string; start_ms: number; end_ms: number; text: string }
export interface TranscriptPage {
  schema_version: '1.0'; data_kind: 'transcript_page'; source: TranscriptSource; q: string; offset: number; limit: number
  total_utterances: number; matched_utterances: number; next_offset: number | null; items: TranscriptUtterance[]
}
export interface TranscriptContext {
  schema_version: '1.0'; data_kind: 'transcript_context'; source: TranscriptSource
  center: TranscriptUtterance; previous: TranscriptUtterance | null; next: TranscriptUtterance | null
}
export interface TranscriptPageRequest { q: string; offset: number; limit: number; source: TranscriptSource | null }
export class TranscriptResponseError extends Error {
  code: string
  constructor(code = 'INVALID_TRANSCRIPT_RESPONSE') { super(code); this.code = code }
}
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/, HASH = /^[a-f0-9]{64}$/
const ID = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/
const sourceFields = ['job_id', 'video_id', 'generation', 'source_video_sha256', 'transcript_sha256', 'analysis_sha256', 'duration_ms', 'source_fingerprint'] as const
function object(value: unknown): value is Record<string, unknown> { return !!value && typeof value === 'object' && !Array.isArray(value) }
function integer(value: unknown, minimum = 0): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum }
function fields(value: unknown, keys: readonly string[]): asserts value is Record<string, unknown> {
  if (!object(value) || Object.keys(value).length !== keys.length || keys.some(key => !Object.hasOwn(value, key))) invalid()
}
function invalid(): never { throw new TranscriptResponseError() }
const normalized = (value: string) => value.normalize('NFKC').toLowerCase()
export function transcriptTerms(q: string): string[] { return normalized(q).split(/[\p{White_Space}\uFEFF]+/u).filter(Boolean) }
export function transcriptQueryError(q: string): string {
  if (q.length > 200) return '搜索文字最多 200 个字符单元，请缩短后重试。'
  if (transcriptTerms(q).length > 8) return '最多同时搜索 8 个词，词之间用空格分隔。'
  return ''
}
export function transcriptTime(ms: number, precise = false): string {
  const seconds = Math.floor(ms / 1000), hours = Math.floor(seconds / 3600), minutes = Math.floor(seconds / 60) % 60
  const result = `${hours ? `${String(hours).padStart(2, '0')}:` : ''}${String(minutes).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
  return precise ? `${result}.${String(ms % 1000).padStart(3, '0')}` : result
}
export function sameTranscriptSource(a: TranscriptSource, b: TranscriptSource): boolean { return sourceFields.every(field => a[field] === b[field]) }
function parseSource(raw: unknown, owner: TranscriptOwner, fixed?: TranscriptSource | null): TranscriptSource {
  fields(raw, sourceFields)
  if (!UUID.test(String(raw.job_id)) || !UUID.test(String(raw.video_id)) || !integer(raw.generation, 1)
    || ['source_video_sha256', 'transcript_sha256', 'analysis_sha256', 'source_fingerprint'].some(field => typeof raw[field] !== 'string' || !HASH.test(raw[field] as string))
    || !integer(raw.duration_ms, 1) || raw.job_id !== owner.job_id || raw.video_id !== owner.video_id
    || raw.generation !== owner.generation || raw.source_video_sha256 !== owner.source_video_sha256) invalid()
  const source = Object.freeze({ ...raw }) as unknown as TranscriptSource
  if (fixed && !sameTranscriptSource(fixed, source)) throw new TranscriptResponseError('TRANSCRIPT_SOURCE_CHANGED')
  return source
}
function utterance(raw: unknown, source: TranscriptSource): TranscriptUtterance {
  fields(raw, ['utterance_id', 'start_ms', 'end_ms', 'text'])
  if (typeof raw.utterance_id !== 'string' || !ID.test(raw.utterance_id) || !integer(raw.start_ms)
    || !integer(raw.end_ms, raw.start_ms + 1) || raw.end_ms > source.duration_ms
    || typeof raw.text !== 'string' || !raw.text.trim() || raw.text.length > 100000) invalid()
  return Object.freeze({ utterance_id: raw.utterance_id, start_ms: raw.start_ms, end_ms: raw.end_ms, text: raw.text })
}
export function parseTranscriptPage(raw: unknown, owner: TranscriptOwner, expected: TranscriptPageRequest): TranscriptPage {
  fields(raw, ['schema_version', 'data_kind', 'source', 'q', 'offset', 'limit', 'total_utterances', 'matched_utterances', 'next_offset', 'items'])
  if (raw.schema_version !== '1.0' || raw.data_kind !== 'transcript_page' || raw.q !== expected.q || transcriptQueryError(expected.q)
    || !integer(raw.offset) || raw.offset !== expected.offset || !integer(raw.limit, 1) || raw.limit > 50 || raw.limit !== expected.limit
    || !integer(raw.total_utterances, 1) || raw.total_utterances > 100000 || !integer(raw.matched_utterances) || raw.matched_utterances > raw.total_utterances
    || !Array.isArray(raw.items) || raw.items.length > raw.limit) invalid()
  const source = parseSource(raw.source, owner, expected.source), items = raw.items.map(value => utterance(value, source))
  const remaining = Math.max(0, raw.matched_utterances - raw.offset), ids = new Set<string>()
  let previousEnd = -1, textUnits = 0
  for (const item of items) {
    if (ids.has(item.utterance_id) || item.start_ms < previousEnd) invalid()
    ids.add(item.utterance_id); previousEnd = item.end_ms; textUnits += item.text.length
  }
  if (textUnits > 200000 || items.length > remaining || (!!remaining !== !!items.length)
    || raw.next_offset !== (items.length < remaining ? raw.offset + items.length : null)) invalid()
  return Object.freeze({ schema_version: '1.0', data_kind: 'transcript_page', source, q: expected.q, offset: raw.offset, limit: raw.limit,
    total_utterances: raw.total_utterances, matched_utterances: raw.matched_utterances, next_offset: raw.next_offset as number | null,
    items: Object.freeze(items) as unknown as TranscriptUtterance[] })
}
export function parseTranscriptContext(raw: unknown, owner: TranscriptOwner, source: TranscriptSource, center: TranscriptUtterance): TranscriptContext {
  fields(raw, ['schema_version', 'data_kind', 'source', 'center', 'previous', 'next'])
  if (raw.schema_version !== '1.0' || raw.data_kind !== 'transcript_context') invalid()
  const checkedSource = parseSource(raw.source, owner, source), checked = utterance(raw.center, checkedSource)
  if (!sameTranscriptUtterance(checked, center)) invalid()
  const previous = raw.previous === null ? null : utterance(raw.previous, checkedSource), next = raw.next === null ? null : utterance(raw.next, checkedSource)
  if (previous && (previous.utterance_id === center.utterance_id || previous.end_ms > center.start_ms)
    || next && (next.utterance_id === center.utterance_id || next.start_ms < center.end_ms)
    || previous && next && previous.utterance_id === next.utterance_id) invalid()
  return Object.freeze({ schema_version: '1.0', data_kind: 'transcript_context', source: checkedSource, center: checked, previous, next })
}
function sameTranscriptUtterance(a: TranscriptUtterance, b: TranscriptUtterance) {
  return a.utterance_id === b.utterance_id && a.start_ms === b.start_ms && a.end_ms === b.end_ms && a.text === b.text
}
export function transcriptPageUrl(owner: TranscriptOwner, request: TranscriptPageRequest): string {
  if (!UUID.test(owner.job_id) || transcriptQueryError(request.q) || !integer(request.offset) || !integer(request.limit, 1) || request.limit > 50) throw new TranscriptResponseError('INVALID_TRANSCRIPT_QUERY')
  const query = new URLSearchParams({ q: request.q, offset: String(request.offset), limit: String(request.limit) })
  if (request.source) query.set('source_fingerprint', request.source.source_fingerprint)
  return `/api/jobs/${owner.job_id}/transcript?${query}`
}
export function transcriptContextUrl(owner: TranscriptOwner, source: TranscriptSource, id: string): string {
  if (!UUID.test(owner.job_id) || !ID.test(id) || !HASH.test(source.source_fingerprint)) throw new TranscriptResponseError('INVALID_TRANSCRIPT_QUERY')
  return `/api/jobs/${owner.job_id}/transcript/context?${new URLSearchParams({ utterance_id: id, source_fingerprint: source.source_fingerprint })}`
}
export function transcriptQuote(filename: string, selected: TranscriptUtterance): string {
  return `原视频：${filename}\n原视频时间：${transcriptTime(selected.start_ms, true)} → ${transcriptTime(selected.end_ms, true)}\nASR 原句（未经人工校对）：\n${selected.text}`
}
/** Safe text-node highlighting. NFKC expansions retain their original character spans. */
export function transcriptHighlights(text: string, q: string): Array<{ text: string; hit: boolean }> {
  const terms = transcriptTerms(q), map: Array<{ start: number; end: number }> = []
  let canonical = '', offset = 0
  for (const char of text) {
    const part = normalized(char)
    for (let index = 0; index < part.length; index++) map.push({ start: offset, end: offset + char.length })
    canonical += part; offset += char.length
  }
  // Cross-character Unicode composition cannot be mapped precisely with a per-character span.
  if (!terms.length || canonical !== normalized(text)) return [{ text, hit: false }]
  const ranges: Array<{ start: number; end: number }> = []
  for (const term of terms) for (let index = canonical.indexOf(term); index >= 0; index = canonical.indexOf(term, index + term.length)) {
    ranges.push({ start: map[index]!.start, end: map[index + term.length - 1]!.end })
  }
  ranges.sort((a, b) => a.start - b.start || a.end - b.end)
  const merged: typeof ranges = []
  for (const range of ranges) {
    const previous = merged.at(-1)
    if (previous && range.start <= previous.end) previous.end = Math.max(previous.end, range.end)
    else merged.push({ ...range })
  }
  const result: Array<{ text: string; hit: boolean }> = []; let cursor = 0
  for (const range of merged) {
    if (range.start > cursor) result.push({ text: text.slice(cursor, range.start), hit: false })
    result.push({ text: text.slice(range.start, range.end), hit: true }); cursor = range.end
  }
  if (cursor < text.length || !result.length) result.push({ text: text.slice(cursor), hit: false })
  return result
}
function errorCode(value: unknown): string { return object(value) && typeof value.code === 'string' ? value.code : '' }
export function transcriptErrorCopy(value: unknown): string {
  const copy: Record<string, string> = {
    INVALID_TRANSCRIPT_QUERY: '搜索文字或页参数无效，请核对后重试。',
    TRANSCRIPT_SOURCE_CHANGED: '原句来源已变化，旧结果仅供查看。请明确重新核对来源。',
    TRANSCRIPT_SOURCE_INVALID: '原句产物暂未通过来源核验，旧结果仅供查看。请稍后重试。',
    TRANSCRIPT_NOT_READY: '当前任务的完整话题转写尚不可用，请确认分析已完成。',
    TRANSCRIPT_UTTERANCE_NOT_FOUND: '所选原句在该来源中不存在，请重新读取本页。',
    JOB_NOT_FOUND: '当前分析任务不存在，请重新选择视频。',
    NETWORK_UNAVAILABLE: '暂时无法连接原句服务，已保留上次成功结果。请重试。',
    REQUEST_TIMEOUT: '读取原句超时，已保留上次成功结果。请重试。',
  }
  return copy[errorCode(value)] ?? '原句响应不完整或与当前来源、查询不符，已保留上次成功结果。请重试。'
}
export interface TranscriptSearchState {
  page: TranscriptPage | null; requested: TranscriptPageRequest | null; context: TranscriptContext | null; selected: TranscriptUtterance | null
  pageLoading: boolean; contextLoading: boolean; pageError: string; contextError: string; sourceStale: boolean
  copyBusy: boolean; copyNotice: string; copyError: string
}
/** Read-only workspace state. Each page, context and clipboard operation owns an independent lifetime. */
export class TranscriptSearchWorkspace {
  readonly owner: TranscriptOwner
  private readonly io: { get(path: string, signal: AbortSignal): Promise<unknown>; changed(state: TranscriptSearchState): void }
  state: TranscriptSearchState = { page: null, requested: null, context: null, selected: null, pageLoading: false, contextLoading: false,
    pageError: '', contextError: '', sourceStale: false, copyBusy: false, copyNotice: '', copyError: '' }
  private pageController: AbortController | null = null
  private contextController: AbortController | null = null
  private pageVersion = 0; private contextVersion = 0; private copyVersion = 0
  private alive = true; private active = false; private visible = false
  private history: number[] = []
  private requestedNavigation: 'search' | 'refresh' | 'next' | 'previous' = 'refresh'
  constructor(owner: TranscriptOwner, io: { get(path: string, signal: AbortSignal): Promise<unknown>; changed(state: TranscriptSearchState): void }) {
    this.owner = Object.freeze({ ...owner }); this.io = io
  }
  private publish(change: Partial<TranscriptSearchState>) { this.state = { ...this.state, ...change }; this.io.changed(this.state) }
  available() { return this.alive && this.active && this.visible }
  usable() {
    const { page, requested } = this.state
    return this.available() && !!page && !!requested && !this.state.pageLoading && !this.state.pageError && !this.state.sourceStale
      && page.q === requested.q && page.offset === requested.offset
  }
  canUseSelection() { return this.usable() && !!this.state.selected && this.state.page!.items.some(item => sameTranscriptUtterance(item, this.state.selected!)) }
  canGoBack() { return this.usable() && this.history.length > 0 }
  private cancelCopy() { this.copyVersion++; this.publish({ copyBusy: false, copyNotice: '', copyError: '' }) }
  private cancelContext(clear = false) {
    this.contextVersion++; this.contextController?.abort(); this.contextController = null
    this.publish({ contextLoading: false, ...(clear ? { context: null, selected: null, contextError: '' } : {}) }); this.cancelCopy()
  }
  setAvailability(active: boolean, visible: boolean) {
    const was = this.available(); this.active = active; this.visible = visible
    if (!this.available()) {
      this.pageVersion++; this.pageController?.abort(); this.pageController = null
      this.publish({ pageLoading: false }); this.cancelContext()
    } else if (!was) void this.readPage(this.state.requested ?? { q: '', offset: 0, limit: 20, source: null }, this.requestedNavigation)
  }
  search(q: string) { if (this.available()) return this.readPage({ q, offset: 0, limit: 20, source: this.state.page?.source ?? null }, 'search') }
  retry() { if (this.available() && this.state.requested) return this.readPage(this.state.requested, this.requestedNavigation) }
  reloadSource() { if (this.available()) return this.readPage({ q: this.state.requested?.q ?? '', offset: 0, limit: 20, source: null }, 'search') }
  next() {
    const page = this.state.page
    if (this.usable() && page?.next_offset !== null && page?.next_offset !== undefined) return this.readPage({ q: page.q, offset: page.next_offset, limit: page.limit, source: page.source }, 'next')
  }
  previous() {
    const page = this.state.page, offset = this.history.at(-1)
    if (this.usable() && page && offset !== undefined) return this.readPage({ q: page.q, offset, limit: page.limit, source: page.source }, 'previous')
  }
  private async readPage(request: TranscriptPageRequest, navigation: 'search' | 'refresh' | 'next' | 'previous') {
    if (!this.available()) return
    let path: string
    try { path = transcriptPageUrl(this.owner, request) } catch (error) { this.publish({ pageError: transcriptErrorCopy(error) }); return }
    const prior = this.state.page, keepSelection = !!prior && request.q === prior.q && request.offset === prior.offset
    const selected = keepSelection ? this.state.selected : null
    this.pageController?.abort(); const controller = new AbortController(), version = ++this.pageVersion; this.pageController = controller
    this.requestedNavigation = navigation
    this.cancelContext(!keepSelection)
    this.publish({ requested: { ...request }, pageLoading: true, pageError: '' })
    const current = () => this.available() && !controller.signal.aborted && version === this.pageVersion
    try {
      const page = parseTranscriptPage(await this.io.get(path, controller.signal), this.owner, request)
      if (!current()) return
      if (!prior || !sameTranscriptSource(prior.source, page.source) || navigation === 'search') this.history = []
      else if (navigation === 'next') this.history.push(prior.offset)
      else if (navigation === 'previous') this.history.pop()
      const retained = selected && page.items.find(item => sameTranscriptUtterance(item, selected)) || null
      this.requestedNavigation = 'refresh'
      this.publish({ page, requested: { ...request, source: page.source }, sourceStale: false, pageLoading: false, selected: retained, context: retained ? this.state.context : null, contextError: '' })
      if (retained) void this.readContext(retained)
    } catch (error) {
      if (current()) this.publish({ pageError: transcriptErrorCopy(error), sourceStale: this.state.sourceStale || ['TRANSCRIPT_SOURCE_CHANGED', 'TRANSCRIPT_SOURCE_INVALID'].includes(errorCode(error)) })
    } finally {
      if (version === this.pageVersion) { this.pageController = null; this.publish({ pageLoading: false }) }
    }
  }
  select(id: string) {
    if (!this.usable()) return
    const selected = this.state.page!.items.find(item => item.utterance_id === id)
    if (!selected) return
    this.cancelContext(true); this.publish({ selected }); return this.readContext(selected)
  }
  retryContext() { if (this.canUseSelection()) return this.readContext(this.state.selected!) }
  private async readContext(selected: TranscriptUtterance) {
    if (!this.canUseSelection()) return
    this.contextController?.abort(); const controller = new AbortController(), version = ++this.contextVersion; this.contextController = controller
    const source = this.state.page!.source; this.publish({ contextLoading: true, contextError: '' })
    const current = () => this.canUseSelection() && !controller.signal.aborted && version === this.contextVersion && this.state.selected?.utterance_id === selected.utterance_id
    try {
      const raw = await this.io.get(transcriptContextUrl(this.owner, source, selected.utterance_id), controller.signal)
      if (!current()) return
      const context = parseTranscriptContext(raw, this.owner, source, selected)
      if (current()) this.publish({ context })
    } catch (error) {
      if (current()) this.publish({ contextError: transcriptErrorCopy(error), sourceStale: this.state.sourceStale || ['TRANSCRIPT_SOURCE_CHANGED', 'TRANSCRIPT_SOURCE_INVALID'].includes(errorCode(error)) })
    } finally {
      if (version === this.contextVersion) { this.contextController = null; this.publish({ contextLoading: false }) }
    }
  }
  async copySelected(filename: string, write: (value: string) => Promise<void>) {
    if (!this.canUseSelection() || this.state.copyBusy) return
    const selected = this.state.selected!, version = ++this.copyVersion, source = this.state.page!.source
    this.publish({ copyBusy: true, copyNotice: '', copyError: '' })
    const current = () => version === this.copyVersion && this.canUseSelection() && sameTranscriptSource(source, this.state.page!.source)
      && this.state.selected?.utterance_id === selected.utterance_id
    try { await write(transcriptQuote(filename, selected)); if (current()) this.publish({ copyNotice: '已复制所选原句、原视频名与时间；ASR 尚未经人工校对。' }) }
    catch { if (current()) this.publish({ copyError: '复制未完成，请重试，或手动复制下方引用文字。' }) }
    finally { if (version === this.copyVersion) this.publish({ copyBusy: false }) }
  }
  dispose() { this.alive = false; this.setAvailability(false, false) }
}
