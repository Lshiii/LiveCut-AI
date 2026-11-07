import { copyCaptionCues, type CaptionCue } from './captions.ts'

export interface CaptionTextChange { id: string; before: string; after: string; occurrences: number }
export interface CaptionTextPreview {
  query: string; replacement: string; ignoreCase: boolean; scopeIds: string[]
  changes: CaptionTextChange[]; occurrences: number; error: string
}
export interface CaptionTextResult { cues: CaptionCue[] | null; error: string }

const MAX_CUES = 10000, MAX_QUERY = 200, MAX_TEXT = 2000, MAX_BYTES = 2 * 1024 * 1024
const ID = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/
const encoder = new TextEncoder()
const validQuery = (query: unknown): query is string => typeof query === 'string' && !!query.trim() && query.length <= MAX_QUERY
const validText = (value: unknown): value is string => typeof value === 'string' && !!value.trim() && value.length <= MAX_TEXT
const failure = (error: string): CaptionTextResult => ({ cues: null, error })
function pattern(query: string, ignoreCase: boolean): RegExp {
  return new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), ignoreCase ? 'giu' : 'gu')
}
function validCues(cues: readonly CaptionCue[]): boolean {
  if (!Array.isArray(cues) || cues.length > MAX_CUES) return false
  const ids = new Set<string>()
  for (const cue of cues) {
    if (!cue || typeof cue.id !== 'string' || !ID.test(cue.id) || ids.has(cue.id)
      || typeof cue.text !== 'string' || cue.text.length > MAX_TEXT) return false
    ids.add(cue.id)
  }
  return true
}
function withinTotalBytes(cues: readonly CaptionCue[], changed: ReadonlyMap<string, string>): boolean {
  let bytes = 0
  for (const cue of cues) {
    bytes += encoder.encode(changed.get(cue.id) ?? cue.text).byteLength
    if (bytes > MAX_BYTES) return false
  }
  return true
}
function exactKeys(value: unknown, keys: string[]): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key))
}
function validChanges(changes: unknown): changes is CaptionTextChange[] {
  if (!Array.isArray(changes) || changes.length > MAX_CUES) return false
  const ids = new Set<string>()
  for (const change of changes) {
    if (!exactKeys(change, ['id', 'before', 'after', 'occurrences'])
      || typeof change.id !== 'string' || !ID.test(change.id) || ids.has(change.id)
      || !validText(change.before) || !validText(change.after) || change.before === change.after
      || !Number.isSafeInteger(change.occurrences) || (change.occurrences as number) < 1 || (change.occurrences as number) > MAX_TEXT) return false
    ids.add(change.id)
  }
  return true
}

/** Literal Unicode matching; replacement syntax and regular expressions are never interpreted. */
export function captionTextMatches(text: string, query: string, ignoreCase: boolean): number {
  if (typeof text !== 'string' || !validQuery(query) || typeof ignoreCase !== 'boolean') return 0
  return [...text.matchAll(pattern(query, ignoreCase))].length
}

/** This preview only describes local text edits, never timing, review permission or original ASR. */
export function previewCaptionTextReplacement(cues: readonly CaptionCue[], query: string, replacement: string,
  ignoreCase: boolean, scopeIds: readonly string[]): CaptionTextPreview {
  const preview: CaptionTextPreview = { query, replacement, ignoreCase, scopeIds: [], changes: [], occurrences: 0, error: '' }
  try {
    if (!validQuery(query)) { preview.error = '查找内容不能为空或只有空白，最多 200 字。'; return preview }
    if (typeof replacement !== 'string' || replacement.length > MAX_TEXT || typeof ignoreCase !== 'boolean') {
      preview.error = '替换内容最多 2000 字，请检查大小写设置。'; return preview
    }
    if (!validCues(cues) || !Array.isArray(scopeIds) || scopeIds.length > MAX_CUES) {
      preview.error = '字幕标识或替换范围无效，请重新生成预览。'; return preview
    }
    const known = new Set(cues.map(cue => cue.id)), scope = new Set<string>()
    for (const id of scopeIds) {
      if (typeof id !== 'string' || !known.has(id) || scope.has(id)) {
        preview.error = '替换范围已变化或含重复字幕，请重新生成预览。'; return preview
      }
      scope.add(id); preview.scopeIds.push(id)
    }
    const changed = new Map<string, string>()
    for (const cue of cues) {
      if (!scope.has(cue.id)) continue
      const occurrences = captionTextMatches(cue.text, query, ignoreCase)
      if (!occurrences) continue
      // A callback keeps "$&", "$1", backslashes and markup as literal subtitle text.
      const after = cue.text.replace(pattern(query, ignoreCase), () => replacement)
      if (after === cue.text) continue
      if (!validText(after)) {
        preview.error = '替换后有字幕为空、只有空白或超过 2000 字；请调整替换内容。'; return { ...preview, changes: [], occurrences: 0 }
      }
      changed.set(cue.id, after)
      preview.changes.push({ id: cue.id, before: cue.text, after, occurrences })
      preview.occurrences += occurrences
    }
    if (!withinTotalBytes(cues, changed)) {
      preview.error = '替换后字幕文字总量超过 2 MiB，请精简替换内容。'; return { ...preview, changes: [], occurrences: 0 }
    }
    return preview
  } catch { return { ...preview, changes: [], occurrences: 0, error: '无法生成替换预览，请检查字幕并重试。' } }
}

/** Recompute instead of trusting a previously displayed or caller-constructed patch. */
export function applyCaptionTextReplacement(cues: readonly CaptionCue[], preview: CaptionTextPreview): CaptionTextResult {
  try {
    if (!exactKeys(preview, ['query', 'replacement', 'ignoreCase', 'scopeIds', 'changes', 'occurrences', 'error'])
      || preview.error !== '' || !validChanges(preview.changes) || !Array.isArray(preview.scopeIds))
      return failure('替换预览无效，请重新预览后再应用。')
    const fresh = previewCaptionTextReplacement(cues, preview.query, preview.replacement, preview.ignoreCase, preview.scopeIds)
    if (fresh.error || fresh.occurrences !== preview.occurrences || fresh.changes.length !== preview.changes.length
      || fresh.scopeIds.length !== preview.scopeIds.length || fresh.scopeIds.some((id, index) => id !== preview.scopeIds[index])
      || fresh.changes.some((change, index) => {
        const prior = preview.changes[index]!
        return change.id !== prior.id || change.before !== prior.before || change.after !== prior.after || change.occurrences !== prior.occurrences
      })) return failure('字幕或替换范围已变化，请重新预览后再应用。')
    if (!fresh.changes.length) return failure('没有需要替换的字幕文字。')
    const changed = new Map(fresh.changes.map(change => [change.id, change.after]))
    const result = copyCaptionCues(cues)
    for (const cue of result) { const text = changed.get(cue.id); if (text !== undefined) cue.text = text }
    return { cues: result, error: '' }
  } catch { return failure('替换预览无效，请重新预览后再应用。') }
}

/** Restore all affected texts or none; later edits outside the patch, times and review flags survive. */
export function undoCaptionTextReplacement(cues: readonly CaptionCue[], changes: readonly CaptionTextChange[]): CaptionTextResult {
  try {
    if (!validCues(cues) || !validChanges(changes) || !changes.length) return failure('撤销记录无效，未改变字幕。')
    const current = new Map(cues.map(cue => [cue.id, cue]))
    if (changes.some(change => current.get(change.id)?.text !== change.after))
      return failure('替换过的字幕已被修改或删除，未覆盖后续编辑；请手动核对。')
    const before = new Map(changes.map(change => [change.id, change.before]))
    if (!withinTotalBytes(cues, before)) return failure('撤销后字幕文字总量超过 2 MiB，未改变字幕。')
    const result = copyCaptionCues(cues)
    for (const cue of result) { const text = before.get(cue.id); if (text !== undefined) cue.text = text }
    return { cues: result, error: '' }
  } catch { return failure('撤销记录无效，未改变字幕。') }
}
