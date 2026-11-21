import { editSpans } from './editTimeline.ts'
import type { SequencePreviewPart } from './sequencePreview.ts'

export const copyCuts = (parts: readonly SequencePreviewPart[]) =>
  parts.map(({ start_ms, end_ms }) => ({ start_ms, end_ms }))

export function validFineCuts(base: readonly SequencePreviewPart[], cuts: unknown): cuts is SequencePreviewPart[] {
  return base.length > 0 && base.length <= 12 && editSpans(base).length === base.length
    && Array.isArray(cuts) && cuts.length === base.length && cuts.every((part, i) => part
      && Number.isSafeInteger(part.start_ms) && Number.isSafeInteger(part.end_ms)
      && base[i]!.start_ms <= part.start_ms && part.start_ms < part.end_ms && part.end_ms <= base[i]!.end_ms)
}

/** Source intervals that play a half-open window of the assembled edit, including windows across joins. */
export function playWindow(parts: readonly SequencePreviewPart[], start: number, end: number): SequencePreviewPart[] {
  return editSpans(parts).filter(span => span.editEndMs > start && span.editStartMs < end && start < end)
    .map(span => ({
      start_ms: span.start_ms + Math.max(0, start - span.editStartMs),
      end_ms: span.end_ms - Math.max(0, span.editEndMs - end),
    }))
}

/** A second open tab must never overwrite a draft its editor hasn't seen. */
export function fineDraftStore(storage: Storage, projectKey: string, base: readonly SequencePreviewPart[]) {
  const key = 'livecut.fine-cut.v1.' + projectKey
  let previous = storage.getItem(key)
  let cuts = copyCuts(base)
  if (previous !== null) {
    const saved = JSON.parse(previous)
    if (saved?.version !== 1 || saved.project !== projectKey || !validFineCuts(base, saved.cuts)) {
      throw new Error('本机精剪草稿无法读取。可使用当前剪点试听或下载。')
    }
    cuts = copyCuts(saved.cuts)
  }
  return { cuts, write(parts: readonly SequencePreviewPart[]) {
    if (!validFineCuts(base, parts)) throw new Error('剪点超出了粗剪范围。')
    if (storage.getItem(key) !== previous) throw new Error('另一页面已更新此草稿。可先下载当前时间线，再重新打开。')
    const value = JSON.stringify({ version: 1, project: projectKey, cuts: copyCuts(parts) })
    storage.setItem(key, value)
    previous = value
  } }
}
