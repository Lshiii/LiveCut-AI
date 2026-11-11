import { ManualClipError, parseManualClipInput, type ManualClipInput, type ManualClipRevisionInput } from './manualClips.ts'

export interface ManualClipPending {
  version: 1; operation: 'CREATE' | 'REVISION' | 'EXPORT'; job_id: string; video_id: string; plan_id: string | null; key: string; input: string
}
export interface ManualClipReceiptStore { read(entry?: ManualClipPending): string | null; write(value: string): void; remove(entry?: ManualClipPending): void; entries?(): ManualClipPending[] }
export interface ManualClipReceiptStorage { readonly length: number; key(index: number): string | null; getItem(key: string): string | null; setItem(key: string, value: string): void; removeItem(key: string): void }
export interface ManualClipExportInput { revision: number; plan_hash: string; profile: 'h264-aac-v1' }
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
function invalid(code = 'MANUAL_CLIP_LOCAL_RECEIPT_INVALID'): never { throw new ManualClipError(code) }
function uuid(value: unknown): value is string { return typeof value === 'string' && UUID.test(value) }
export function parseManualClipExportInput(raw: unknown): ManualClipExportInput {
  if (!object(raw) || Object.keys(raw).length !== 3 || !Number.isSafeInteger(raw.revision) || (raw.revision as number) < 1
    || typeof raw.plan_hash !== 'string' || !/^[a-f0-9]{64}$/.test(raw.plan_hash) || raw.profile !== 'h264-aac-v1') return invalid('MANUAL_CLIP_INVALID')
  return Object.freeze({ revision: raw.revision as number, plan_hash: raw.plan_hash, profile: 'h264-aac-v1' })
}
function parseBody(raw: unknown, operation: ManualClipPending['operation']): ManualClipInput | ManualClipRevisionInput | ManualClipExportInput {
  return operation === 'EXPORT' ? parseManualClipExportInput(raw) : parseManualClipInput(raw, operation === 'REVISION')
}
export function parseManualClipPending(raw: unknown, jobId?: string, videoId?: string): ManualClipPending {
  if (!object(raw) || Object.keys(raw).length !== 7 || !['version', 'operation', 'job_id', 'video_id', 'plan_id', 'key', 'input'].every(field => Object.hasOwn(raw, field))
    || raw.version !== 1 || !['CREATE', 'REVISION', 'EXPORT'].includes(String(raw.operation)) || !uuid(raw.job_id) || !uuid(raw.video_id) || !uuid(raw.key)
    || jobId !== undefined && raw.job_id !== jobId || videoId !== undefined && raw.video_id !== videoId
    || raw.operation === 'CREATE' && raw.plan_id !== null || raw.operation !== 'CREATE' && !uuid(raw.plan_id)
    || typeof raw.input !== 'string' || raw.input.length > 10000) invalid()
  try { parseBody(JSON.parse(raw.input), raw.operation as ManualClipPending['operation']) } catch { invalid() }
  return Object.freeze({ version: 1, operation: raw.operation as ManualClipPending['operation'], job_id: raw.job_id, video_id: raw.video_id,
    plan_id: raw.plan_id as string | null, key: raw.key, input: raw.input })
}
/** Prefix only. Each original request UUID owns a separate immutable physical record. */
export function manualClipReceiptSlot(jobId: string): string { if (!uuid(jobId)) invalid(); return `livecut-manual-clip-submission-v2-${jobId}-` }
export function manualClipReceiptStorageKey(entry: ManualClipPending): string { const value = parseManualClipPending(entry); return manualClipReceiptSlot(value.job_id) + value.key }
export function createManualClipReceiptStore(storage: ManualClipReceiptStorage, jobId: string, videoId: string): ManualClipReceiptStore {
  const prefix = manualClipReceiptSlot(jobId)
  const ownKey = (entry: ManualClipPending) => manualClipReceiptStorageKey(parseManualClipPending(entry, jobId, videoId))
  const entries = () => {
    try {
      const keys = new Set<string>()
      for (let index = 0; index < storage.length; index++) { const key = storage.key(index); if (key?.startsWith(prefix)) keys.add(key) }
      if (keys.size > 100) invalid('MANUAL_CLIP_STORAGE_LIMIT')
      const result: ManualClipPending[] = []
      for (const key of [...keys].sort()) {
        const raw = storage.getItem(key); if (raw === null) continue
        const entry = parseManualClipPending(JSON.parse(raw), jobId, videoId)
        if (ownKey(entry) !== key) invalid()
        result.push(entry)
      }
      return result
    } catch (error) { if (error instanceof ManualClipError) throw error; return invalid('MANUAL_CLIP_STORAGE_UNAVAILABLE') }
  }
  return {
    entries,
    read(entry) { try { if (entry) return storage.getItem(ownKey(entry)); const first = entries()[0]; return first ? storage.getItem(ownKey(first)) : null } catch (error) { if (error instanceof ManualClipError) throw error; return invalid('MANUAL_CLIP_STORAGE_UNAVAILABLE') } },
    write(raw) {
      const entry = parseManualClipPending(JSON.parse(raw), jobId, videoId), key = ownKey(entry)
      try {
        const existing = storage.getItem(key)
        if (existing !== null && !same(parseManualClipPending(JSON.parse(existing), jobId, videoId), entry)) invalid('MANUAL_CLIP_PENDING_EXISTS')
        storage.setItem(key, raw)
      } catch (error) { if (error instanceof ManualClipError) throw error; invalid('MANUAL_CLIP_STORAGE_UNAVAILABLE') }
    },
    remove(entry) { if (!entry) invalid(); try { storage.removeItem(ownKey(entry)) } catch { invalid('MANUAL_CLIP_STORAGE_UNAVAILABLE') } },
  }
}
export function scanManualClipPending(store: ManualClipReceiptStore, jobId: string, videoId?: string): ManualClipPending[] {
  if (store.entries) return store.entries().map(entry => parseManualClipPending(entry, jobId, videoId))
  const value = readManualClipPending(store, jobId, videoId); return value ? [value] : []
}
export function readManualClipPending(store: ManualClipReceiptStore, jobId: string, videoId?: string): ManualClipPending | null {
  let raw: string | null
  try { raw = store.read() } catch { return invalid('MANUAL_CLIP_STORAGE_UNAVAILABLE') }
  if (raw === null) return null
  try { return parseManualClipPending(JSON.parse(raw), jobId, videoId) } catch (error) {
    if (error instanceof ManualClipError && error.code === 'MANUAL_CLIP_STORAGE_UNAVAILABLE') throw error
    return invalid()
  }
}
function same(a: ManualClipPending, b: ManualClipPending): boolean {
  return a.version === b.version && a.operation === b.operation && a.job_id === b.job_id && a.video_id === b.video_id && a.plan_id === b.plan_id && a.key === b.key && a.input === b.input
}
/** Returns only after writing and rereading the original immutable body. Call immediately before POST. */
export function prepareManualClipPending(store: ManualClipReceiptStore, jobId: string, videoId: string, operation: ManualClipPending['operation'], planId: string | null,
  input: string | ManualClipInput | ManualClipRevisionInput | ManualClipExportInput, newKey: () => string): ManualClipPending {
  let body: string
  try { body = typeof input === 'string' ? input : JSON.stringify(parseBody(input, operation)); parseBody(JSON.parse(body), operation) } catch { return invalid('MANUAL_CLIP_INVALID') }
  const current = readManualClipPending(store, jobId, videoId)
  if (current) invalid('MANUAL_CLIP_PENDING_EXISTS')
  const entry = parseManualClipPending({ version: 1, operation, job_id: jobId, video_id: videoId, plan_id: planId, key: newKey(), input: body }, jobId, videoId)
  try { store.write(JSON.stringify(entry)) } catch { return invalid('MANUAL_CLIP_STORAGE_UNAVAILABLE') }
  assertManualClipPending(store, entry); return entry
}
export function assertManualClipPending(store: ManualClipReceiptStore, entry: ManualClipPending): void {
  const checked = parseManualClipPending(entry)
  let current: ManualClipPending | null
  try { const raw = store.read(checked); current = raw === null ? null : parseManualClipPending(JSON.parse(raw), checked.job_id, checked.video_id) } catch { return invalid('MANUAL_CLIP_STORAGE_UNAVAILABLE') }
  if (!current || !same(current, checked)) invalid('MANUAL_CLIP_STORAGE_UNAVAILABLE')
}
/** Only the identical own entry may be removed; a later record or malformed record is retained. */
export function clearManualClipPending(store: ManualClipReceiptStore, entry: ManualClipPending): boolean {
  let current: ManualClipPending | null
  try { const raw = store.read(entry); current = raw === null ? null : parseManualClipPending(JSON.parse(raw), entry.job_id, entry.video_id) } catch { return invalid('MANUAL_CLIP_STORAGE_UNAVAILABLE') }
  if (!current || !same(current, parseManualClipPending(entry))) return false
  try { store.remove(entry); return store.read(entry) === null } catch { return invalid('MANUAL_CLIP_STORAGE_UNAVAILABLE') }
}
export function manualClipWriteUrl(entry: ManualClipPending): string {
  const value = parseManualClipPending(entry)
  return value.operation === 'CREATE' ? `/api/jobs/${value.job_id}/manual-clip-plans` : `/api/manual-clip-plans/${value.plan_id}/${value.operation === 'REVISION' ? 'revisions' : 'exports'}`
}
export function manualClipSubmissionUrl(entry: ManualClipPending): string {
  const value = parseManualClipPending(entry)
  return value.operation === 'CREATE' ? `/api/jobs/${value.job_id}/manual-clip-plan-submissions/${value.key}`
    : `/api/manual-clip-plans/${value.plan_id}/${value.operation === 'REVISION' ? 'revision-submissions' : 'export-submissions'}/${value.key}`
}
export function manualClipSubmissionMissing(error: unknown, entry?: Pick<ManualClipPending, 'operation'>): boolean {
  return object(error) && error.status === 404 && error.code === (entry?.operation === 'EXPORT' ? 'MANUAL_CLIP_EXPORT_SUBMISSION_NOT_FOUND' : 'MANUAL_CLIP_SUBMISSION_NOT_FOUND')
}
export type ManualLocalReceipt = ManualClipPending
export const restoreManualClipPending = readManualClipPending
export const removeManualClipPending = clearManualClipPending
