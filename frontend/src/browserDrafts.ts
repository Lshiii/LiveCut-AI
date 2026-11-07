export type DraftKind = 'caption' | 'intent'
export interface DraftAssessment { title: string; description: string; compatible: boolean; reason?: string }
export interface DraftEntry { schema_version: 1; kind: DraftKind; document_id: string; writer_id: string; entry_id: string; updated_at: number; data: unknown }
export interface DraftStorage { readonly length: number; key(index: number): string | null; getItem(key: string): string | null; setItem(key: string, value: string): void; removeItem(key: string): void }

const UUID = '[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}'
const writerPattern = new RegExp(`^${UUID}$`, 'i'), documentPattern = new RegExp(`^${UUID}(?::${UUID})?$`, 'i')
export const DRAFT_PREFIX = 'livecut:draft:v1:'
export const MAX_DRAFT_BYTES = 4 * 1024 * 1024
export const MAX_DRAFT_DOWNLOAD_BYTES = 16 * 1024 * 1024
const plain = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
function namespace(kind: DraftKind, documentId: string) {
  if (!['caption', 'intent'].includes(kind) || !documentPattern.test(documentId)) throw Error('INVALID_DRAFT_ID')
  return `${DRAFT_PREFIX}${kind}:${documentId.toLowerCase()}:`
}
export function browserDraftKey(kind: DraftKind, documentId: string, writerId: string, entryId: string) {
  if (!writerPattern.test(writerId) || !writerPattern.test(entryId)) throw Error('INVALID_DRAFT_ID')
  return namespace(kind, documentId) + writerId.toLowerCase() + ':' + entryId.toLowerCase()
}
function json(value: unknown, limit = MAX_DRAFT_BYTES): string {
  const result = JSON.stringify(value, (_, item: unknown) => {
    if (item === undefined || ['function', 'symbol', 'bigint'].includes(typeof item) || typeof item === 'number' && !Number.isFinite(item)) throw Error('INVALID_DRAFT_JSON')
    return item
  })
  if (!result || new TextEncoder().encode(result).byteLength > limit) throw Error('DRAFT_TOO_LARGE')
  return result
}
export function copyDraftData(value: unknown): unknown { return JSON.parse(json(value, MAX_DRAFT_DOWNLOAD_BYTES)) }
function decode(raw: string, kind: DraftKind, documentId: string, limit = MAX_DRAFT_BYTES): DraftEntry | null {
  try {
    if (new TextEncoder().encode(raw).byteLength > limit) return null
    const value: unknown = JSON.parse(raw)
    if (!plain(value) || Object.keys(value).length !== 7 || value.schema_version !== 1 || value.kind !== kind
      || value.document_id !== documentId.toLowerCase() || typeof value.writer_id !== 'string' || !writerPattern.test(value.writer_id)
      || typeof value.entry_id !== 'string' || !writerPattern.test(value.entry_id)
      || !Number.isSafeInteger(value.updated_at) || (value.updated_at as number) < 0 || (value.updated_at as number) > 8.64e15
      || !Object.hasOwn(value, 'data')) return null
    return { schema_version: 1, kind, document_id: value.document_id, writer_id: value.writer_id.toLowerCase(), entry_id: value.entry_id.toLowerCase(), updated_at: value.updated_at as number, data: value.data }
  } catch { return null }
}
function problem(value: unknown) {
  const name = plain(value) || value instanceof Error ? value.name : undefined
  if (name === 'QuotaExceededError' || name === 'NS_ERROR_DOM_QUOTA_REACHED') return '本机空间不足，当前修改仍在本页。请下载备份或移除不再需要的旧备份。'
  if (value instanceof Error && value.message === 'DRAFT_TOO_LARGE') return '草稿超过本机单份备份上限，当前修改仍在本页。请先下载备份。'
  return '本机备份未完成，当前修改仍在本页。请允许浏览器存储，或先下载草稿备份。'
}

/** Immutable version keys: removing a historical entry cannot remove a concurrent new backup. */
export class BrowserDraftStore {
  private readonly storage: () => DraftStorage
  private readonly now: () => number
  private readonly newId: () => string
  constructor(storage: () => DraftStorage, now: () => number = Date.now, newId: () => string = () => crypto.randomUUID()) {
    this.storage = storage; this.now = now; this.newId = newId
  }
  list(kind: DraftKind, documentId: string): { entries: DraftEntry[]; error: string } {
    const entries: DraftEntry[] = []
    try {
      const prefix = namespace(kind, documentId), storage = this.storage()
      let unreadable = false
      for (let index = 0; index < storage.length; index++) {
        const key = storage.key(index)
        if (!key?.startsWith(prefix)) continue
        const raw = storage.getItem(key), entry = raw === null ? null : decode(raw, kind, documentId)
        if (!entry || browserDraftKey(kind, documentId, entry.writer_id, entry.entry_id) !== key) { unreadable = true; continue }
        entries.push(entry)
      }
      entries.sort((a, b) => b.updated_at - a.updated_at || a.writer_id.localeCompare(b.writer_id))
      return { entries, error: unreadable ? '有本机备份无法读取，原记录仍保留。其他有效备份可以继续使用。' : '' }
    } catch (value) { return { entries, error: problem(value) } }
  }
  save(kind: DraftKind, documentId: string, writerId: string, data: unknown): { entry: DraftEntry | null; error: string } {
    try {
      const entryId = this.newId(), key = browserDraftKey(kind, documentId, writerId, entryId), entry: DraftEntry = {
        schema_version: 1, kind, document_id: documentId.toLowerCase(), writer_id: writerId.toLowerCase(), entry_id: entryId.toLowerCase(), updated_at: this.now(), data,
      }
      if (!Number.isSafeInteger(entry.updated_at) || entry.updated_at < 0 || entry.updated_at > 8.64e15) throw Error('INVALID_DRAFT_TIME')
      const serialized = json(entry)
      const storage = this.storage()
      if (storage.getItem(key) !== null) throw Error('DRAFT_ID_COLLISION')
      storage.setItem(key, serialized)
      return { entry: decode(serialized, kind, documentId), error: '' }
    } catch (value) { return { entry: null, error: problem(value) } }
  }
  remove(entry: DraftEntry): { removed: boolean; error: string } {
    try {
      const key = browserDraftKey(entry.kind, entry.document_id, entry.writer_id, entry.entry_id), storage = this.storage(), raw = storage.getItem(key)
      if (raw === null) return { removed: true, error: '' }
      const latest = decode(raw, entry.kind, entry.document_id)
      if (!latest || json(latest) !== json(entry)) return { removed: false, error: '这份备份已被其他页面更新，未移除。请重新查看后再决定。' }
      storage.removeItem(key)
      return { removed: true, error: '' }
    } catch (value) { return { removed: false, error: problem(value) } }
  }
}

/** Local download only. This is a recovery file, never a confirmed server revision. */
export function draftFile(entry: DraftEntry): { filename: string; text: string } {
  return { filename: `livecut-${entry.kind}-draft-${entry.entry_id.slice(0, 8)}.json`, text: json(entry, MAX_DRAFT_DOWNLOAD_BYTES) }
}
/** Imported files remain candidates until the editor verifies their domain and source. */
export function importDraftFile(text: string, kind: DraftKind, documentId: string): DraftEntry | null {
  try { namespace(kind, documentId); return decode(text, kind, documentId, MAX_DRAFT_DOWNLOAD_BYTES) }
  catch { return null }
}
