import { captionDraftInvalid, captionPayload, copyCaptionCue, sameCaptionSource, type CaptionCue, type CaptionSnapshot, type CaptionTimeDrafts } from './captions.ts'
import type { DraftAssessment } from './browserDrafts.ts'

type EditableCue = Pick<CaptionCue, 'id' | 'start_ms' | 'end_ms' | 'text' | 'reviewed'>
export interface DeletedCaptionCue { cue: CaptionCue; index: number }
export interface CaptionDraftValue {
  schema_version: 1; export_id: string; clip_id: string; base_revision: number; content_hash: string; needs_check: boolean
  source: CaptionSnapshot['source']; cues: EditableCue[]; time_drafts: CaptionTimeDrafts
  deleted: { cue: EditableCue; index: number }[]
}
export interface RestoredCaptionDraft { cues: CaptionCue[]; timeDrafts: CaptionTimeDrafts; deleted: DeletedCaptionCue[]; needsCheck: boolean }

const ID = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/
const UUID = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i
const HASH = /^[a-f0-9]{64}$/
const MAX_CUES = 10000, MAX_TEXT_BYTES = 2 * 1024 * 1024, MAX_TIME_LENGTH = 128
const FORBIDDEN = new Set(['__proto__', 'constructor', 'prototype'])
const encoder = new TextEncoder()
function object(value: unknown, keys: readonly string[], required: readonly string[] = keys): value is Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)
    || ![null, Object.prototype].includes(Object.getPrototypeOf(value))) return false
  const descriptors = Object.getOwnPropertyDescriptors(value)
  return Reflect.ownKeys(descriptors).every(key => typeof key === 'string' && !FORBIDDEN.has(key)
    && keys.includes(key) && descriptors[key]!.enumerable && 'value' in descriptors[key]!)
    && required.every(key => Object.hasOwn(descriptors, key))
}
function array(value: unknown, max: number): value is unknown[] {
  return Array.isArray(value) && value.length <= max && Reflect.ownKeys(value).length === value.length + 1
    && Object.keys(value).every((key, index) => key === String(index) && 'value' in Object.getOwnPropertyDescriptor(value, key)!)
}
const integer = (value: unknown, max = Number.MAX_SAFE_INTEGER): value is number => Number.isSafeInteger(value) && (value as number) >= 0 && (value as number) <= max
const identifier = (value: unknown): value is string => typeof value === 'string' && ID.test(value) && !FORBIDDEN.has(value)
const hash = (value: unknown): value is string => typeof value === 'string' && HASH.test(value)
const editable = (cue: EditableCue): EditableCue => ({ id: cue.id, start_ms: cue.start_ms, end_ms: cue.end_ms, text: cue.text, reviewed: cue.reviewed })
function copySource(source: CaptionSnapshot['source']): CaptionSnapshot['source'] {
  return { snapshot_hash: source.snapshot_hash, clip_sha256: source.clip_sha256, transcript_hash: source.transcript_hash,
    duration_ms: source.duration_ms, media_duration_ms: source.media_duration_ms, timing_basis: source.timing_basis,
    parts: source.parts.map(part => ({ start_ms: part.start_ms, end_ms: part.end_ms })) }
}
function copyTimes(drafts: CaptionTimeDrafts): CaptionTimeDrafts {
  return Object.fromEntries(Object.entries(drafts).map(([id, fields]) => [id, {
    ...(fields.start_ms === undefined ? {} : { start_ms: fields.start_ms }),
    ...(fields.end_ms === undefined ? {} : { end_ms: fields.end_ms }),
  }]))
}

/** Only editable data and source identity enter browser storage; no original transcript snapshot. */
export function captionDraftValue(snapshot: CaptionSnapshot, cues: readonly CaptionCue[], timeDrafts: CaptionTimeDrafts,
  deleted: readonly DeletedCaptionCue[] = [], needsCheck = false): CaptionDraftValue {
  return { schema_version: 1, export_id: snapshot.export_id, clip_id: snapshot.clip_id, base_revision: snapshot.revision,
    content_hash: snapshot.content_hash, needs_check: needsCheck, source: copySource(snapshot.source), cues: cues.map(editable),
    time_drafts: copyTimes(timeDrafts), deleted: deleted.map(item => ({ index: item.index, cue: editable(item.cue) })) }
}

function read(raw: unknown): CaptionDraftValue | null {
  try { return readValue(raw) } catch { return null }
}
function readValue(raw: unknown): CaptionDraftValue | null {
  if (!object(raw, ['schema_version', 'export_id', 'clip_id', 'base_revision', 'content_hash', 'needs_check', 'source', 'cues', 'time_drafts', 'deleted'])
    || raw.schema_version !== 1 || typeof raw.export_id !== 'string' || !UUID.test(raw.export_id)
    || typeof raw.clip_id !== 'string' || !UUID.test(raw.clip_id) || !integer(raw.base_revision) || !hash(raw.content_hash) || typeof raw.needs_check !== 'boolean'
    || !object(raw.source, ['snapshot_hash', 'clip_sha256', 'transcript_hash', 'duration_ms', 'media_duration_ms', 'timing_basis', 'parts'])
    || !hash(raw.source.snapshot_hash) || !hash(raw.source.clip_sha256) || !hash(raw.source.transcript_hash)
    || !integer(raw.source.duration_ms) || raw.source.duration_ms < 1 || !integer(raw.source.media_duration_ms) || raw.source.media_duration_ms < 1
    || raw.source.timing_basis !== 'SOURCE_EDL_SENTENCE_MS' || !array(raw.source.parts, 12) || !raw.source.parts.length
    || !array(raw.cues, MAX_CUES) || !array(raw.deleted, MAX_CUES) || raw.cues.length + raw.deleted.length > MAX_CUES) return null
  let end = 0, duration = 0, bytes = 0
  for (const part of raw.source.parts) {
    if (!object(part, ['start_ms', 'end_ms']) || !integer(part.start_ms) || !integer(part.end_ms)
      || part.start_ms < end || part.end_ms <= part.start_ms) return null
    duration += part.end_ms - part.start_ms; end = part.end_ms
  }
  if (!Number.isSafeInteger(duration) || duration !== raw.source.duration_ms) return null
  const ids = new Set<string>(), liveIds = new Set<string>()
  function cue(value: unknown): value is EditableCue {
    if (!object(value, ['id', 'start_ms', 'end_ms', 'text', 'reviewed']) || !identifier(value.id) || ids.has(value.id)
      || !integer(value.start_ms) || !integer(value.end_ms) || typeof value.text !== 'string' || value.text.length > 2000
      || typeof value.reviewed !== 'boolean') return false
    ids.add(value.id); bytes += encoder.encode(value.text).byteLength
    return bytes <= MAX_TEXT_BYTES
  }
  for (const value of raw.cues) { if (!cue(value)) return null; liveIds.add(value.id) }
  for (const item of raw.deleted) {
    if (!object(item, ['index', 'cue']) || !integer(item.index, MAX_CUES) || !cue(item.cue)) return null
  }
  if (raw.time_drafts === null || typeof raw.time_drafts !== 'object' || Array.isArray(raw.time_drafts)
    || !object(raw.time_drafts, [...liveIds], [])) return null
  for (const [id, fields] of Object.entries(raw.time_drafts)) {
    if (!liveIds.has(id) || !object(fields, ['start_ms', 'end_ms'], [])) return null
    for (const value of Object.values(fields)) {
      if (typeof value !== 'string' || value.length > MAX_TIME_LENGTH) return null
      bytes += encoder.encode(value).byteLength
      if (bytes > MAX_TEXT_BYTES) return null
    }
  }
  return raw as unknown as CaptionDraftValue
}
function sameSource(value: CaptionDraftValue, snapshot: CaptionSnapshot): boolean {
  return value.export_id === snapshot.export_id && value.clip_id === snapshot.clip_id
    && JSON.stringify(copySource(value.source)) === JSON.stringify(copySource(snapshot.source))
}
function rebuild(value: CaptionDraftValue, snapshot: CaptionSnapshot): RestoredCaptionDraft | null {
  if (!sameSource(value, snapshot) || value.base_revision !== snapshot.revision || value.content_hash !== snapshot.content_hash) return null
  const source = new Map(snapshot.cues.map((cue, index) => [cue.id, { cue, index }]))
  if (source.size !== snapshot.cues.length || value.cues.length + value.deleted.length !== source.size) return null
  let previous = -1
  for (const cue of value.cues) {
    const known = source.get(cue.id)
    if (!known || known.index <= previous) return null
    previous = known.index
  }
  const order = value.cues.map(cue => cue.id)
  for (const item of [...value.deleted].reverse()) {
    if (!source.has(item.cue.id) || item.index > order.length) return null
    order.splice(item.index, 0, item.cue.id)
  }
  if (order.some((id, index) => id !== snapshot.cues[index]?.id)) return null
  function restore(cue: EditableCue): CaptionCue {
    const original = source.get(cue.id)!.cue
    return { ...copyCaptionCue(original), ...cue, requires_review: original.boundary_partial && !cue.reviewed }
  }
  return { cues: value.cues.map(restore), timeDrafts: copyTimes(value.time_drafts), needsCheck: value.needs_check,
    deleted: value.deleted.map(item => ({ index: item.index, cue: restore(item.cue) })) }
}

export function assessCaptionDraft(raw: unknown, snapshot: CaptionSnapshot | null): DraftAssessment {
  const value = read(raw)
  if (!value) return { title: '无法恢复的字幕备份', description: '备份格式或数据范围不符合要求。', compatible: false, reason: '可下载核对，不能写入当前字幕。' }
  const title = `字幕草稿 · 基于 r${value.base_revision}`
  const first = value.cues[0], preview = first ? [...first.text.replace(/\s+/gu, ' ').trim()] : []
  const excerpt = first ? preview.length ? `首条字幕：${preview.slice(0, 72).join('')}${preview.length > 72 ? '…' : ''}` : '首条字幕含空文本' : ''
  const description = `${value.cues.length} 条保留字幕，${value.deleted.length} 条删除记录${captionDraftInvalid(value.time_drafts) ? '，含待修正时间' : ''}${value.needs_check ? '，保存结果待核对' : ''}。${excerpt ? ` ${excerpt}` : ''}`
  if (!snapshot) return { title, description, compatible: false, reason: '请先读取服务端字幕，再核对来源与版本。' }
  if (!sameSource(value, snapshot)) return { title, description, compatible: false, reason: '成片或转写来源不同，只能查看或下载。' }
  if (!captionDraftInvalid(value.time_drafts) && JSON.stringify({ cues: value.cues.map(editable) }) === JSON.stringify(captionPayload(snapshot.cues))
    && (snapshot.revision > value.base_revision || !value.needs_check && snapshot.revision === value.base_revision && snapshot.content_hash === value.content_hash))
    return { title, description, compatible: false, reason: '备份内容已在服务端，无需恢复。' }
  if (value.base_revision !== snapshot.revision || value.content_hash !== snapshot.content_hash)
    return { title, description, compatible: false, reason: '服务端字幕版本已变化，只能查看或下载，不会覆盖新版本。' }
  if (!rebuild(value, snapshot)) return { title, description, compatible: false, reason: '字幕标识、顺序或删除记录与当前来源不符。' }
  return { title, description, compatible: true }
}

/** The caller must obtain a fresh parsed server snapshot and keep its editor ownership guard. */
export function restoreCaptionDraft(raw: unknown, snapshot: CaptionSnapshot): RestoredCaptionDraft | null {
  const value = read(raw)
  return value && assessCaptionDraft(value, snapshot).compatible ? rebuild(value, snapshot) : null
}

/** A same-head GET is an observation permitting an explicit retry, never proof that a lost PUT did not commit. */
export function captionSaveCheck(known: CaptionSnapshot, incoming: CaptionSnapshot, cues: CaptionCue[], timeDrafts: CaptionTimeDrafts): 'saved' | 'retry' | 'conflict' {
  try { sameCaptionSource(known, incoming) } catch { return 'conflict' }
  if (incoming.revision < known.revision || known.confirmed_revisions.some(revision => !incoming.confirmed_revisions.includes(revision))) return 'conflict'
  const server = JSON.stringify(captionPayload(incoming.cues))
  if (incoming.revision === known.revision && server !== JSON.stringify(captionPayload(known.cues))) return 'conflict'
  if (!captionDraftInvalid(timeDrafts) && server === JSON.stringify(captionPayload(cues))) return 'saved'
  return incoming.revision === known.revision && incoming.content_hash === known.content_hash ? 'retry' : 'conflict'
}
