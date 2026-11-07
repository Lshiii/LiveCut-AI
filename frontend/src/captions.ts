import type { ExportJob } from './api.ts'

export interface CaptionCue {
  id: string; part_index: number; source_utterance_id: string
  source_start_ms: number; source_end_ms: number; retained_start_ms: number; retained_end_ms: number
  start_ms: number; end_ms: number; text: string; source_text: string
  boundary_partial: boolean; reviewed: boolean; requires_review: boolean
}
export interface CaptionSnapshot {
  export_id: string; clip_id: string; clip_title: string; content_url: string
  revision: number; status: 'DRAFT' | 'CONFIRMED'; confirmed_revisions: number[]; content_hash: string
  source: { snapshot_hash: string; clip_sha256: string; transcript_hash: string; duration_ms: number; media_duration_ms: number
    timing_basis: 'SOURCE_EDL_SENTENCE_MS'; parts: { start_ms: number; end_ms: number }[] }
  cues: CaptionCue[]
}
export type CaptionTimeDrafts = Record<string, Partial<Record<'start_ms' | 'end_ms', string>>>
/** Cue fields are primitive values; copy explicitly so reactive proxies are safe too. */
export function copyCaptionCue(cue: CaptionCue): CaptionCue {
  return { id: cue.id, part_index: cue.part_index, source_utterance_id: cue.source_utterance_id,
    source_start_ms: cue.source_start_ms, source_end_ms: cue.source_end_ms,
    retained_start_ms: cue.retained_start_ms, retained_end_ms: cue.retained_end_ms,
    start_ms: cue.start_ms, end_ms: cue.end_ms, text: cue.text, source_text: cue.source_text,
    boundary_partial: cue.boundary_partial, reviewed: cue.reviewed, requires_review: cue.requires_review }
}
export function copyCaptionCues(cues: readonly CaptionCue[]): CaptionCue[] { return cues.map(copyCaptionCue) }
export class CaptionResponseError extends Error { constructor() { super('INVALID_CAPTION_RESPONSE') } }
export class CaptionBatchError extends Error {
  code: 'BATCH_INCOMPLETE' | 'CAPTIONS_MISSING' | 'CAPTIONS_UNCONFIRMED'
  clips: string[]
  constructor(code: CaptionBatchError['code'], clips: string[]) { super(code); this.code = code; this.clips = clips }
}
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i
const ID = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/
const HASH = /^[a-f0-9]{64}$/
const row = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
const integer = (value: unknown, minimum = 0, maximum = Number.MAX_SAFE_INTEGER): value is number => Number.isSafeInteger(value) && (value as number) >= minimum && (value as number) <= maximum
const text = (value: unknown, max: number): value is string => typeof value === 'string' && !!value.trim() && value.length <= max
const hash = (value: unknown): value is string => typeof value === 'string' && HASH.test(value)
function invalid(): never { throw new CaptionResponseError() }
export function captionBase(exportId: string, clipId: string) {
  if (!UUID.test(exportId) || !UUID.test(clipId)) invalid()
  return `/api/exports/${exportId}/clips/${clipId}/captions`
}
export function captionPayload(cues: CaptionCue[]) {
  return { cues: cues.map(({ id, start_ms, end_ms, text, reviewed }) => ({ id, start_ms, end_ms, text, reviewed })) }
}
export function captionValidation(cues: CaptionCue[], duration: number, drafts: CaptionTimeDrafts = {}): string {
  if (!Array.isArray(cues) || cues.length > 10000 || !integer(duration, 1)) return '字幕数量或成片时长无效。'
  if (Object.values(drafts).some(fields => Object.values(fields).some(value => value !== undefined && parseCaptionTime(value) === null))) return '请把字幕时间填写为 分:秒.毫秒，例如 01:23.450。'
  const ids = new Set<string>(); let previous = 0, bytes = 0
  for (const cue of cues) {
    if (!text(cue.id, 128) || !ID.test(cue.id) || ids.has(cue.id)) return '字幕标识无效或重复，请重新读取。'
    ids.add(cue.id)
    if (!integer(cue.start_ms, previous) || !integer(cue.end_ms, cue.start_ms + 1, duration)) return '字幕时间须在成片范围内，按开始时间排列且互不重叠。'
    if (!text(cue.text, 2000) || typeof cue.reviewed !== 'boolean') return '每条字幕需有文字，最多 2000 字；不需要的字幕请删除。'
    previous = cue.end_ms; bytes += new TextEncoder().encode(cue.text).byteLength
  }
  return bytes > 2 * 1024 * 1024 ? '字幕文字总量超过 2 MiB，请精简后再保存。' : ''
}
export function parseCaptionSnapshot(raw: unknown, exportId: string, clipId: string, revision?: number): CaptionSnapshot {
  captionBase(exportId, clipId)
  if (!row(raw) || raw.export_id !== exportId || raw.clip_id !== clipId || !text(raw.clip_title, 120)
    || raw.content_url !== `/api/exports/${exportId}/clips/${clipId}/content` || !integer(raw.revision)
    || (revision !== undefined && raw.revision !== revision) || !['DRAFT', 'CONFIRMED'].includes(raw.status as string)
    || !hash(raw.content_hash) || !Array.isArray(raw.confirmed_revisions)
    || raw.confirmed_revisions.some(value => !integer(value))
    || new Set(raw.confirmed_revisions).size !== raw.confirmed_revisions.length || !row(raw.source)
    || !hash(raw.source.snapshot_hash) || !hash(raw.source.clip_sha256) || !hash(raw.source.transcript_hash)
    || !integer(raw.source.duration_ms, 1) || !integer(raw.source.media_duration_ms, 1)
    || raw.source.timing_basis !== 'SOURCE_EDL_SENTENCE_MS' || !Array.isArray(raw.source.parts)
    || !raw.source.parts.length || raw.source.parts.length > 12 || !Array.isArray(raw.cues)) invalid()
  let previousPart = 0, duration = 0
  for (const part of raw.source.parts) {
    if (!row(part) || !integer(part.start_ms, previousPart) || !integer(part.end_ms, part.start_ms + 1)) invalid()
    duration += part.end_ms - part.start_ms; previousPart = part.end_ms
  }
  if (!Number.isSafeInteger(duration) || duration !== raw.source.duration_ms) invalid()
  const cues: CaptionCue[] = raw.cues.map(value => {
    if (!row(value) || !text(value.id, 128) || !ID.test(value.id) || !integer(value.part_index, 0, (raw.source as { parts: unknown[] }).parts.length - 1)
      || !text(value.source_utterance_id, 128) || !ID.test(value.source_utterance_id)
      || !integer(value.source_start_ms) || !integer(value.source_end_ms, value.source_start_ms + 1)
      || !integer(value.retained_start_ms, value.source_start_ms) || !integer(value.retained_end_ms, value.retained_start_ms + 1, value.source_end_ms)
      || !text(value.source_text, 2000) || typeof value.boundary_partial !== 'boolean' || typeof value.reviewed !== 'boolean'
      || value.requires_review !== (value.boundary_partial && !value.reviewed)) invalid()
    const part = (raw.source as { parts: { start_ms: number; end_ms: number }[] }).parts[value.part_index]!
    if (value.retained_start_ms < part.start_ms || value.retained_end_ms > part.end_ms
      || value.boundary_partial !== (value.retained_start_ms !== value.source_start_ms || value.retained_end_ms !== value.source_end_ms)) invalid()
    return value as unknown as CaptionCue
  })
  if (captionValidation(cues, duration) || (raw.status === 'CONFIRMED') !== raw.confirmed_revisions.includes(raw.revision)
    || (raw.status === 'CONFIRMED' && cues.some(cue => cue.requires_review))) invalid()
  return { ...raw, cues } as unknown as CaptionSnapshot
}
export function sameCaptionSource(known: CaptionSnapshot, incoming: CaptionSnapshot) {
  if (known.export_id !== incoming.export_id || known.clip_id !== incoming.clip_id || known.content_url !== incoming.content_url || known.clip_title !== incoming.clip_title
    || ['snapshot_hash', 'clip_sha256', 'transcript_hash', 'duration_ms', 'media_duration_ms', 'timing_basis'].some(key => known.source[key as keyof CaptionSnapshot['source']] !== incoming.source[key as keyof CaptionSnapshot['source']])
    || JSON.stringify(known.source.parts.map(part => [part.start_ms, part.end_ms])) !== JSON.stringify(incoming.source.parts.map(part => [part.start_ms, part.end_ms]))) invalid()
  if (known.revision === incoming.revision && known.content_hash !== incoming.content_hash) invalid()
}
export function captionFileUrl(snapshot: CaptionSnapshot, revision: number, format: 'srt' | 'vtt' | 'txt' | 'json' | 'package') {
  if (!snapshot.confirmed_revisions.includes(revision)) invalid()
  return `${captionBase(snapshot.export_id, snapshot.clip_id)}/${format === 'package' ? 'package' : `files/${format}`}?revision=${revision}`
}
export function captionTime(ms: number) {
  return `${Math.floor(ms / 60000).toString().padStart(2, '0')}:${Math.floor(ms % 60000 / 1000).toString().padStart(2, '0')}.${(ms % 1000).toString().padStart(3, '0')}`
}
export function parseCaptionTime(value: string): number | null {
  const match = /^(\d+):([0-5]\d)\.(\d{3})$/.exec(value.trim())
  if (!match) return null
  const ms = Number(match[1]) * 60000 + Number(match[2]) * 1000 + Number(match[3])
  return Number.isSafeInteger(ms) ? ms : null
}
export function editCaptionTime(cue: CaptionCue, drafts: CaptionTimeDrafts, field: 'start_ms' | 'end_ms', value: string) {
  drafts[cue.id] = { ...drafts[cue.id], [field]: value }
  const parsed = parseCaptionTime(value)
  if (parsed !== null) cue[field] = parsed
}
export function captionDraftInvalid(drafts: CaptionTimeDrafts) {
  return Object.values(drafts).some(fields => Object.values(fields).some(value => value !== undefined && parseCaptionTime(value) === null))
}
/** Read every completed clip first; never quietly omit an unfinished or unconfirmed item. */
export async function prepareCaptionBatch(batch: Pick<ExportJob, 'id' | 'clips'>, fetchSnapshot: (url: string) => Promise<unknown>) {
  if (!UUID.test(batch.id) || !batch.clips.length || batch.clips.length > 100 || new Set(batch.clips.map(clip => clip.id)).size !== batch.clips.length) invalid()
  const incomplete = batch.clips.filter(clip => clip.status !== 'SUCCEEDED')
  if (incomplete.length) throw new CaptionBatchError('BATCH_INCOMPLETE', incomplete.map(clip => clip.title || clip.product_name || clip.id))
  const items: { clip_id: string; caption_revision: number }[] = [], missing: string[] = [], unconfirmed: string[] = []
  for (let offset = 0; offset < batch.clips.length; offset += 6) {
    const group = batch.clips.slice(offset, offset + 6)
    const reads = await Promise.allSettled(group.map(clip => fetchSnapshot(captionBase(batch.id, clip.id))))
    reads.forEach((read, index) => {
      const clip = group[index]!, name = clip.title || clip.product_name || clip.id
      if (read.status === 'rejected') {
        if (read.reason?.code === 'CAPTIONS_NOT_INITIALIZED') { missing.push(name); return }
        throw read.reason
      }
      const snapshot = parseCaptionSnapshot(read.value, batch.id, clip.id)
      if (!snapshot.confirmed_revisions.length) { unconfirmed.push(name); return }
      items.push({ clip_id: clip.id, caption_revision: Math.max(...snapshot.confirmed_revisions) })
    })
  }
  if (missing.length) throw new CaptionBatchError('CAPTIONS_MISSING', missing)
  if (unconfirmed.length) throw new CaptionBatchError('CAPTIONS_UNCONFIRMED', unconfirmed)
  const query = new URLSearchParams({ items: items.map(item => `${item.clip_id}.${item.caption_revision}`).join(',') })
  return { items, url: `/api/exports/${batch.id}/package?${query}` }
}
