import { newestAgentSession, parseAgentSession, type AgentSession, type AgentSource } from './videoAgent.ts'
export interface AgentSessionHistoryOwner { job_id: string; video_id: string; video_sha256: string }
export interface AgentSessionSummary {
  id: string; job_id: string; video_id: string; created_at: string; source: AgentSource
  first_question: string | null; turn_count: number; active_turn_count: number
}
export interface AgentSessionHistoryPage {
  schema_version: '1.0'; job_id: string; items: AgentSessionSummary[]; limit: number; next_cursor: string | null; active_count: number
}
export class AgentSessionHistoryError extends Error { constructor() { super('INVALID_AGENT_SESSION_HISTORY') } }
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/, HASH = /^[a-f0-9]{64}$/
const row = (raw: unknown): raw is Record<string, unknown> => raw !== null && typeof raw === 'object' && !Array.isArray(raw)
const exact = (raw: unknown, keys: string[]): raw is Record<string, unknown> => row(raw) && Object.keys(raw).length === keys.length && keys.every(key => Object.hasOwn(raw, key))
const integer = (raw: unknown, maximum = Number.MAX_SAFE_INTEGER): raw is number => Number.isSafeInteger(raw) && (raw as number) >= 0 && (raw as number) <= maximum
const uuid = (raw: unknown): raw is string => typeof raw === 'string' && UUID.test(raw)
const cursor = (raw: unknown): raw is string => typeof raw === 'string' && /^[A-Za-z0-9_-]{1,1024}$/.test(raw)
function invalid(): never { throw new AgentSessionHistoryError() }
export function agentHistoryTimeKey(raw: unknown): string {
  if (typeof raw !== 'string') invalid()
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(raw)
  if (!match || !Number.isFinite(Date.parse(raw)) || new Date(`${match[1]}Z`).toISOString().slice(0, 19) !== match[1]) invalid()
  return `${match[1]}.${(match[2] ?? '').padEnd(9, '0')}Z`
}
export function compareAgentSessionHistory(a: Pick<AgentSessionSummary, 'created_at' | 'id'>, b: Pick<AgentSessionSummary, 'created_at' | 'id'>): number {
  const left = agentHistoryTimeKey(a.created_at), right = agentHistoryTimeKey(b.created_at)
  return left === right ? a.id === b.id ? 0 : a.id > b.id ? -1 : 1 : left > right ? -1 : 1
}
export function parseAgentSessionSummary(raw: unknown, owner: AgentSessionHistoryOwner): AgentSessionSummary {
  if (!exact(raw, ['id', 'job_id', 'video_id', 'created_at', 'source', 'first_question', 'turn_count', 'active_turn_count'])
    || !uuid(raw.id) || !uuid(owner.job_id) || raw.job_id !== owner.job_id || !uuid(owner.video_id) || raw.video_id !== owner.video_id
    || !HASH.test(owner.video_sha256) || !exact(raw.source, ['video_sha256', 'transcript_hash', 'duration_ms'])
    || raw.source.video_sha256 !== owner.video_sha256 || typeof raw.source.transcript_hash !== 'string' || !HASH.test(raw.source.transcript_hash)
    || !integer(raw.source.duration_ms) || raw.source.duration_ms < 1 || !integer(raw.turn_count, 100) || !integer(raw.active_turn_count, raw.turn_count)
    || (raw.turn_count === 0 ? raw.first_question !== null : typeof raw.first_question !== 'string' || !raw.first_question.trim() || raw.first_question.length > 2000)) invalid()
  agentHistoryTimeKey(raw.created_at)
  return { id: raw.id, job_id: owner.job_id, video_id: owner.video_id, created_at: raw.created_at as string,
    source: { video_sha256: owner.video_sha256, transcript_hash: raw.source.transcript_hash, duration_ms: raw.source.duration_ms },
    first_question: raw.first_question as string | null, turn_count: raw.turn_count, active_turn_count: raw.active_turn_count }
}
export function parseAgentSessionHistoryPage(raw: unknown, owner: AgentSessionHistoryOwner, limit = 20, requestedCursor: string | null = null): AgentSessionHistoryPage {
  if (!integer(limit, 30) || limit < 1 || requestedCursor !== null && !cursor(requestedCursor)
    || !exact(raw, ['schema_version', 'job_id', 'items', 'limit', 'next_cursor', 'active_count']) || raw.schema_version !== '1.0'
    || raw.job_id !== owner.job_id || raw.limit !== limit || !Array.isArray(raw.items) || raw.items.length > limit || !integer(raw.active_count)
    || !(raw.next_cursor === null || cursor(raw.next_cursor)) || raw.next_cursor !== null && (raw.items.length !== limit || raw.next_cursor === requestedCursor)) invalid()
  const items = raw.items.map(value => parseAgentSessionSummary(value, owner))
  if (new Set(items.map(item => item.id)).size !== items.length || items.some((item, index) => index > 0 && compareAgentSessionHistory(items[index - 1]!, item) >= 0)
    || items.reduce((count, item) => count + item.active_turn_count, 0) > raw.active_count) invalid()
  return { schema_version: '1.0', job_id: owner.job_id, items, limit, next_cursor: raw.next_cursor as string | null, active_count: raw.active_count }
}
/** Only a verified full compatibility response may supply full-session cache entries. */
export function parseLegacyAgentSessions(raw: unknown, owner: AgentSessionHistoryOwner): AgentSession[] {
  if (!exact(raw, ['items']) || !Array.isArray(raw.items) || raw.items.length > 100) invalid()
  const items = raw.items.map(value => parseAgentSession(value, owner.job_id, owner.video_id, owner.video_sha256))
  if (new Set(items.map(value => value.id)).size !== items.length) invalid()
  return items.sort((a, b) => { try { return compareAgentSessionHistory(a, b) } catch { return Date.parse(b.created_at) - Date.parse(a.created_at) || (a.id > b.id ? -1 : a.id < b.id ? 1 : 0) } })
}
export function mergeLegacyAgentSessions(known: readonly AgentSession[], incoming: readonly AgentSession[], owner: AgentSessionHistoryOwner): AgentSession[] {
  const next = new Map(known.map(value => [value.id, value]))
  for (const raw of incoming) {
    const value = parseAgentSession(raw, owner.job_id, owner.video_id, owner.video_sha256, raw.id)
    next.set(value.id, newestAgentSession(next.get(value.id), value))
  }
  return [...next.values()].sort((a, b) => { try { return compareAgentSessionHistory(a, b) } catch { return Date.parse(b.created_at) - Date.parse(a.created_at) } })
}
export function agentSessionSummaryFromDetail(value: AgentSession): AgentSessionSummary {
  return { id: value.id, job_id: value.job_id, video_id: value.video_id, created_at: value.created_at, source: { ...value.source },
    first_question: value.turns[0]?.question ?? null, turn_count: value.turns.length,
    active_turn_count: value.turns.filter(turn => turn.status === 'QUEUED' || turn.status === 'RUNNING').length }
}
export function agentSessionHistoryRouteMissing(error: unknown): boolean {
  return row(error) && error.status === 404 && ['HTTP_404', 'RESOURCE_NOT_FOUND'].includes(error.code as string)
}
export type AgentHistoryNavigation = 'refresh' | 'older' | 'newer' | 'latest'
export class AgentSessionHistoryPager {
  protocol: 'unknown' | 'modern' | 'legacy' = 'unknown'
  view: 'modern' | 'legacy' = 'modern'
  items: AgentSessionSummary[] = []; loaded = false
  activeCount: number | null = null; legacyKnownActiveCount = 0
  currentCursor: string | null = null; nextCursor: string | null = null; back: (string | null)[] = []
  legacyRows: AgentSessionSummary[] = []; legacyOffset = 0
  readonly limit = 20
  get upgradeAvailable() { return this.view === 'legacy' && this.protocol === 'modern' }
  get unsupportedOldPage() { return this.view === 'modern' && this.currentCursor !== null && this.protocol === 'legacy' }
  get canOlder() { return !this.unsupportedOldPage && (this.view === 'legacy' ? this.legacyOffset + this.limit < this.legacyRows.length : this.nextCursor !== null) }
  get canNewer() { return !this.unsupportedOldPage && (this.view === 'legacy' ? this.legacyOffset > 0 : this.back.length > 0) }
  get pageNumber() { return this.view === 'legacy' ? Math.floor(this.legacyOffset / this.limit) + 1 : this.back.length + 1 }
  cursorFor(navigation: AgentHistoryNavigation) { return navigation === 'latest' || this.view === 'legacy' ? null : navigation === 'older' ? this.nextCursor : navigation === 'newer' ? this.back.at(-1) ?? null : this.currentCursor }
  probe() { this.protocol = 'unknown' }
  markLegacy() { this.protocol = 'legacy' }
  acceptModern(page: AgentSessionHistoryPage, navigation: AgentHistoryNavigation, requestedCursor: string | null) {
    this.protocol = 'modern'; this.activeCount = page.active_count; this.loaded = true
    if (this.view === 'legacy' && navigation === 'refresh') return
    if (navigation === 'older') this.back = [...this.back, this.currentCursor]
    else if (navigation === 'newer') this.back = this.back.slice(0, -1)
    else if (navigation === 'latest' || this.view === 'legacy') this.back = []
    this.view = 'modern'; this.currentCursor = requestedCursor; this.nextCursor = page.next_cursor; this.items = page.items
  }
  acceptLegacy(values: AgentSessionSummary[], navigation: AgentHistoryNavigation) {
    this.legacyKnownActiveCount = values.reduce((count, value) => count + value.active_turn_count, 0)
    this.activeCount = null; this.markLegacy(); this.loaded = true
    if (navigation === 'refresh' && (this.legacyOffset > 0 || this.currentCursor !== null)) {
      if (this.view === 'legacy') {
        const updates = new Map(values.map(value => [value.id, value])); this.legacyRows = this.legacyRows.map(value => updates.get(value.id) ?? value)
        this.items = this.legacyRows.slice(this.legacyOffset, this.legacyOffset + this.limit)
      }
      return
    }
    this.view = 'legacy'; this.legacyRows = values; this.legacyOffset = 0; this.items = values.slice(0, this.limit)
    this.currentCursor = null; this.nextCursor = null; this.back = []
  }
  stepLegacy(direction: 'older' | 'newer') {
    if (this.view !== 'legacy' || direction === 'older' && !this.canOlder || direction === 'newer' && !this.canNewer) return
    this.legacyOffset += direction === 'older' ? this.limit : -this.limit
    this.items = this.legacyRows.slice(this.legacyOffset, this.legacyOffset + this.limit)
  }
  navigateLocally(navigation: Exclude<AgentHistoryNavigation, 'refresh'>): boolean {
    if (this.view !== 'legacy' || navigation === 'latest') return false
    this.stepLegacy(navigation); return true
  }
  observeDetail(value: AgentSession) {
    const next = agentSessionSummaryFromDetail(value)
    this.items = this.items.map(item => item.id === next.id && next.turn_count >= item.turn_count ? next : item)
  }
}
export function agentSessionHistoryHasActiveWork(pager: Pick<AgentSessionHistoryPager, 'activeCount' | 'legacyKnownActiveCount'>, selected: AgentSession | null): boolean {
  return (pager.activeCount ?? pager.legacyKnownActiveCount) > 0 || !!selected?.turns.some(turn => turn.status === 'QUEUED' || turn.status === 'RUNNING')
}
export async function readAgentSessionHistory(options: { pager: AgentSessionHistoryPager; owner: AgentSessionHistoryOwner;
  cursor: string | null; probe: boolean; fetch(path: string): Promise<unknown>; current(): boolean }):
  Promise<{ kind: 'modern'; page: AgentSessionHistoryPage } | { kind: 'legacy'; details: AgentSession[]; items: AgentSessionSummary[] } | null> {
  const { pager, owner } = options
  if (!uuid(owner.job_id) || options.cursor !== null && !cursor(options.cursor)) invalid()
  if (options.probe) pager.probe()
  if (pager.protocol !== 'legacy') {
    const query = new URLSearchParams({ limit: String(pager.limit) }); if (options.cursor !== null) query.set('cursor', options.cursor)
    try {
      const raw = await options.fetch(`/api/jobs/${owner.job_id}/agent-session-history?${query}`)
      if (!options.current()) return null
      return { kind: 'modern', page: parseAgentSessionHistoryPage(raw, owner, pager.limit, options.cursor) }
    } catch (error) { if (!options.current()) return null; if (!agentSessionHistoryRouteMissing(error)) throw error; pager.markLegacy() }
  }
  const raw = await options.fetch(`/api/jobs/${owner.job_id}/agent-sessions`)
  if (!options.current()) return null
  const details = parseLegacyAgentSessions(raw, owner)
  return { kind: 'legacy', details, items: details.map(agentSessionSummaryFromDetail) }
}
