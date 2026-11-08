import { newestIntentSnapshot, parseIntentSnapshot, type IntentSnapshot } from './intentClips.ts'
import { IntentManualReviewError, manualReviewVerified } from './intentManualReview.ts'

export interface IntentHistorySummary {
  id: string; job_id: string; video_id: string; instruction: string; status: IntentSnapshot['status']; state_version: number
  created_at: string; updated_at: string; budget: IntentSnapshot['budget']; source: IntentSnapshot['source']
  revision: number; plan_hash: string | null; source_mode: 'INSTRUCTION' | 'ANSWER_CITATIONS'
}
export interface IntentHistoryPage {
  schema_version: '1.0'; job_id: string; items: IntentHistorySummary[]; limit: number; next_cursor: string | null; active_count: number
}
export class IntentHistoryError extends Error { constructor() { super('INVALID_CLIP_HISTORY_RESPONSE') } }
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/, HASH = /^[a-f0-9]{64}$/
const row = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
const exact = (value: unknown, names: string[]): value is Record<string, unknown> => row(value) && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name))
const integer = (value: unknown, min = 0): value is number => Number.isSafeInteger(value) && (value as number) >= min
const uuid = (value: unknown): value is string => typeof value === 'string' && UUID.test(value)
const hash = (value: unknown): value is string => typeof value === 'string' && HASH.test(value)
const cursor = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9_-]{1,1024}$/.test(value)
function invalid(): never { throw new IntentHistoryError() }
/** Preserve Instant fractional precision; Date.parse alone loses the keyset's microseconds. */
export function intentHistoryTimeKey(value: unknown): string {
  if (typeof value !== 'string') invalid()
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(value)
  if (!match || !Number.isFinite(Date.parse(value)) || new Date(`${match[1]}Z`).toISOString().slice(0, 19) !== match[1]) invalid()
  return `${match[1]}.${(match[2] ?? '').padEnd(9, '0')}Z`
}
export function compareIntentHistory(a: Pick<IntentHistorySummary, 'created_at' | 'id'>, b: Pick<IntentHistorySummary, 'created_at' | 'id'>): number {
  const left = intentHistoryTimeKey(a.created_at), right = intentHistoryTimeKey(b.created_at)
  return left !== right ? left > right ? -1 : 1 : a.id === b.id ? 0 : a.id > b.id ? -1 : 1
}
export function parseIntentHistorySummary(raw: unknown, jobId: string): IntentHistorySummary {
  if (!exact(raw, ['id', 'job_id', 'video_id', 'instruction', 'status', 'state_version', 'created_at', 'updated_at', 'budget', 'source', 'revision', 'plan_hash', 'source_mode'])
    || !uuid(raw.id) || !uuid(jobId) || raw.job_id !== jobId || !uuid(raw.video_id) || typeof raw.instruction !== 'string' || !raw.instruction.trim() || raw.instruction.length > 2000
    || !['QUEUED', 'RUNNING', 'PLAN_READY', 'FAILED', 'CANCELLED'].includes(raw.status as string) || !integer(raw.state_version, 1)
    || !exact(raw.budget, ['max_text_calls', 'text_calls_reserved']) || !integer(raw.budget.max_text_calls, 1) || raw.budget.max_text_calls > 24
    || !integer(raw.budget.text_calls_reserved) || raw.budget.text_calls_reserved > raw.budget.max_text_calls
    || !exact(raw.source, ['video_sha256', 'transcript_hash', 'duration_ms']) || !hash(raw.source.video_sha256) || !hash(raw.source.transcript_hash) || !integer(raw.source.duration_ms, 1)
    || !integer(raw.revision) || !(raw.plan_hash === null || hash(raw.plan_hash)) || !['INSTRUCTION', 'ANSWER_CITATIONS'].includes(raw.source_mode as string)) invalid()
  intentHistoryTimeKey(raw.created_at); intentHistoryTimeKey(raw.updated_at)
  return { id: raw.id, job_id: jobId, video_id: raw.video_id, instruction: raw.instruction, status: raw.status as IntentSnapshot['status'], state_version: raw.state_version,
    created_at: raw.created_at as string, updated_at: raw.updated_at as string, budget: { max_text_calls: raw.budget.max_text_calls, text_calls_reserved: raw.budget.text_calls_reserved },
    source: { video_sha256: raw.source.video_sha256, transcript_hash: raw.source.transcript_hash, duration_ms: raw.source.duration_ms },
    revision: raw.revision, plan_hash: raw.plan_hash as string | null, source_mode: raw.source_mode as IntentHistorySummary['source_mode'] }
}
export function parseIntentHistoryPage(raw: unknown, jobId: string, limit = 20, requestedCursor: string | null = null): IntentHistoryPage {
  if (!uuid(jobId) || !integer(limit, 1) || limit > 30 || requestedCursor !== null && !cursor(requestedCursor)
    || !exact(raw, ['schema_version', 'job_id', 'items', 'limit', 'next_cursor', 'active_count']) || raw.schema_version !== '1.0' || raw.job_id !== jobId
    || raw.limit !== limit || !Array.isArray(raw.items) || raw.items.length > limit || !integer(raw.active_count)
    || !(raw.next_cursor === null || cursor(raw.next_cursor)) || raw.next_cursor !== null && (raw.items.length !== limit || raw.next_cursor === requestedCursor)) invalid()
  const items = raw.items.map(value => parseIntentHistorySummary(value, jobId))
  if (new Set(items.map(item => item.id)).size !== items.length || items.some((item, index) => index > 0 && compareIntentHistory(items[index - 1]!, item) >= 0)
    || items.filter(item => item.status === 'QUEUED' || item.status === 'RUNNING').length > raw.active_count) invalid()
  return { schema_version: '1.0', job_id: jobId, items, limit, next_cursor: raw.next_cursor as string | null, active_count: raw.active_count }
}
export function intentHistoryFromDetail(raw: unknown, jobId: string): IntentHistorySummary {
  const value = parseIntentSnapshot(raw, jobId)
  return summaryFromValidatedDetail(value, jobId)
}
function summaryFromValidatedDetail(value: IntentSnapshot, jobId: string): IntentHistorySummary {
  // Already validated by the complete legacy/detail contract. Do not apply new
  // summary-only canonical/min-version rules retroactively to that older API.
  return { id: value.id, job_id: jobId, video_id: value.video_id, instruction: value.instruction, status: value.status,
    state_version: value.state_version, created_at: value.created_at, updated_at: value.updated_at,
    budget: { max_text_calls: value.budget.max_text_calls, text_calls_reserved: value.budget.text_calls_reserved },
    source: { video_sha256: value.source.video_sha256, transcript_hash: value.source.transcript_hash, duration_ms: value.source.duration_ms },
    revision: value.revision, plan_hash: value.plan_hash, source_mode: value.source_answer ? 'ANSWER_CITATIONS' : 'INSTRUCTION' }
}
export function parseLegacyIntentHistory(raw: unknown, jobId: string): IntentHistorySummary[] {
  return parseLegacyIntentHistoryDetails(raw, jobId).map(value => summaryFromValidatedDetail(value, jobId))
}
export function parseLegacyIntentHistoryDetails(raw: unknown, jobId: string): IntentSnapshot[] {
  if (!exact(raw, ['items']) || !Array.isArray(raw.items) || raw.items.length > 100) invalid()
  const items = raw.items.map(value => parseIntentSnapshot(value, jobId)).sort((a, b) => {
    try { return compareIntentHistory(a, b) }
    catch { return Date.parse(b.created_at) - Date.parse(a.created_at) || (a.id === b.id ? 0 : a.id > b.id ? -1 : 1) }
  })
  if (new Set(items.map(item => item.id)).size !== items.length) invalid()
  return items
}
/** Only the missing route's actual HTTP 404 permits the old, heavier list request. */
export function intentHistoryRouteMissing(error: unknown): boolean {
  return row(error) && error.status === 404 && ['RESOURCE_NOT_FOUND', 'HTTP_404'].includes(error.code as string)
}
/** Metadata has no authority to populate the full-detail cache or acknowledge a saved draft. */
export function mergeVerifiedIntentDetail(known: IntentSnapshot | null | undefined, raw: unknown, jobId: string, requestId?: string): IntentSnapshot {
  const incoming = parseIntentSnapshot(raw, jobId, requestId)
  if (incoming.manual_review && !manualReviewVerified(incoming)) throw new IntentManualReviewError()
  return newestIntentSnapshot(known, incoming)
}
export function cacheLegacyIntentDetails(known: ReadonlyMap<string, IntentSnapshot>, values: readonly unknown[], jobId: string): Map<string, IntentSnapshot> {
  const next = new Map(known)
  for (const raw of values) { const value = parseIntentSnapshot(raw, jobId); next.set(value.id, mergeVerifiedIntentDetail(next.get(value.id), value, jobId, value.id)) }
  return next
}

export type IntentHistoryNavigation = 'refresh' | 'older' | 'newer' | 'latest'
export async function readIntentHistory(options: {
  pager: IntentHistoryPager; jobId: string; cursor: string | null; probe: boolean
  fetch(path: string): Promise<unknown>; current(): boolean
}): Promise<{ kind: 'modern'; page: IntentHistoryPage } | { kind: 'legacy'; rows: IntentHistorySummary[]; details: IntentSnapshot[] } | null> {
  const { pager, jobId } = options
  if (!uuid(jobId) || options.cursor !== null && !cursor(options.cursor)) invalid()
  if (options.probe) pager.probe()
  if (pager.protocol !== 'legacy') {
    const query = new URLSearchParams({ limit: String(pager.limit) })
    if (options.cursor !== null) query.set('cursor', options.cursor)
    try {
      const raw = await options.fetch(`/api/jobs/${jobId}/clip-request-history?${query}`)
      if (!options.current()) return null
      return { kind: 'modern', page: parseIntentHistoryPage(raw, jobId, pager.limit, options.cursor) }
    } catch (error) {
      if (!options.current()) return null
      if (!intentHistoryRouteMissing(error)) throw error
      pager.markLegacy()
    }
  }
  const raw = await options.fetch(`/api/jobs/${jobId}/clip-requests`)
  if (!options.current()) return null
  const details = parseLegacyIntentHistoryDetails(raw, jobId)
  return { kind: 'legacy', rows: details.map(value => summaryFromValidatedDetail(value, jobId)), details }
}
/** Page navigation is deliberately independent of selected request and editable draft state. */
export class IntentHistoryPager {
  protocol: 'unknown' | 'modern' | 'legacy' = 'unknown'
  view: 'modern' | 'legacy' = 'modern'
  items: IntentHistorySummary[] = []
  activeCount: number | null = null
  legacyKnownActiveCount = 0
  currentCursor: string | null = null
  nextCursor: string | null = null
  back: (string | null)[] = []
  legacyRows: IntentHistorySummary[] = []
  legacyOffset = 0
  loaded = false
  readonly limit = 20
  get upgradeAvailable() { return this.view === 'legacy' && this.protocol === 'modern' }
  get unsupportedOldPage() { return this.view === 'modern' && this.currentCursor !== null && this.protocol === 'legacy' }
  get canOlder() { return !this.unsupportedOldPage && (this.view === 'legacy' ? this.legacyOffset + this.limit < this.legacyRows.length : this.nextCursor !== null) }
  get canNewer() { return !this.unsupportedOldPage && (this.view === 'legacy' ? this.legacyOffset > 0 : this.back.length > 0) }
  get pageNumber() { return this.view === 'legacy' ? Math.floor(this.legacyOffset / this.limit) + 1 : this.back.length + 1 }
  probe() { this.protocol = 'unknown' }
  markLegacy() { this.protocol = 'legacy' }
  cursorFor(navigation: IntentHistoryNavigation): string | null {
    if (navigation === 'latest' || this.view === 'legacy') return null
    if (navigation === 'older') return this.nextCursor
    if (navigation === 'newer') return this.back.at(-1) ?? null
    return this.currentCursor
  }
  acceptModern(page: IntentHistoryPage, navigation: IntentHistoryNavigation, requestedCursor: string | null) {
    this.protocol = 'modern'; this.activeCount = page.active_count; this.loaded = true
    if (this.view === 'legacy' && navigation === 'refresh') return
    if (navigation === 'older') this.back = [...this.back, this.currentCursor]
    else if (navigation === 'newer') this.back = this.back.slice(0, -1)
    else if (navigation === 'latest' || this.view === 'legacy') this.back = []
    this.view = 'modern'; this.currentCursor = requestedCursor; this.nextCursor = page.next_cursor; this.items = page.items
  }
  acceptLegacy(rows: IntentHistorySummary[], navigation: IntentHistoryNavigation) {
    // This count belongs to the latest fully validated compatibility response,
    // not the frozen page window and not the job's complete history.
    this.legacyKnownActiveCount = rows.filter(item => item.status === 'QUEUED' || item.status === 'RUNNING').length
    this.activeCount = null; this.markLegacy(); this.loaded = true
    if (navigation === 'refresh' && (this.legacyOffset > 0 || this.currentCursor !== null)) {
      if (this.view === 'legacy') {
        const updates = new Map(rows.map(item => [item.id, item]))
        this.legacyRows = this.legacyRows.map(item => updates.get(item.id) ?? item)
        this.items = this.legacyRows.slice(this.legacyOffset, this.legacyOffset + this.limit)
      }
      return
    }
    this.view = 'legacy'; this.legacyRows = rows; this.legacyOffset = 0; this.items = rows.slice(0, this.limit)
    this.currentCursor = null; this.nextCursor = null; this.back = []
  }
  stepLegacy(direction: 'older' | 'newer') {
    if (this.view !== 'legacy' || direction === 'older' && !this.canOlder || direction === 'newer' && !this.canNewer) return
    this.legacyOffset += direction === 'older' ? this.limit : -this.limit
    this.items = this.legacyRows.slice(this.legacyOffset, this.legacyOffset + this.limit)
  }
  observeDetail(value: IntentSnapshot) {
    const incoming = intentHistoryFromDetail(value, value.job_id)
    this.items = this.items.map(item => item.id === incoming.id && incoming.state_version >= item.state_version && incoming.revision >= item.revision ? incoming : item)
  }
}
