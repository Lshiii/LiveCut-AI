import { editSpans } from './editTimeline.ts'
import type { SequencePreviewPart } from './sequencePreview.ts'

const maxCells = 48

/** Lay portraits closer together and keep the number of video seeks bounded on wide screens. */
export function filmstripCellCount(width: number, height: number, sourceAspect: number) {
  if (![width, height, sourceAspect].every(value => Number.isFinite(value) && value > 0)) return 0
  const cellWidth = Math.max(32, height * sourceAspect)
  return Math.min(maxCells, Math.max(1, Math.round(width / cellWidth)))
}

export function thumbnailSize(sourceWidth: number, sourceHeight: number) {
  const scale = Math.min(1, 240 / Math.max(sourceWidth, sourceHeight))
  return { width: Math.max(1, Math.round(sourceWidth * scale)), height: Math.max(1, Math.round(sourceHeight * scale)) }
}

/** Sample the visible edit in equal time cells. A thumbnail always comes from retained footage. */
export function filmstripTimes(parts: readonly SequencePreviewPart[], from: number, to: number, count: number) {
  const spans = editSpans(parts)
  if (!Number.isSafeInteger(count) || count < 1 || count > maxCells || !Number.isSafeInteger(from)
      || !Number.isSafeInteger(to) || from < 0 || to <= from || to > (spans.at(-1)?.editEndMs ?? 0)) return []
  return Array.from({ length: count }, (_, i) => {
    const at = from + Math.floor((to - from) * (i + .5) / count)
    const span = spans.find(part => at < part.editEndMs)!
    const source = span.start_ms + (at - span.editStartMs)
    // Reuse a nearby frame while a handle moves, clamped inside the selected part.
    return Math.max(span.start_ms, Math.min(span.end_ms - 1, Math.floor(source / 500) * 500))
  })
}

const frames = new Map<string, string>()
function keep(key: string, url: string) {
  if (frames.size >= 96) frames.delete(frames.keys().next().value!)
  frames.set(key, url)
}

/** A single muted decoder per visible strip, sequential seeks, no playback or server-side jobs. */
export async function readFilmstrip(url: string, times: readonly number[], signal: AbortSignal,
  publish: (index: number, image: string) => void) {
  let media: HTMLVideoElement | undefined
  const canvas = document.createElement('canvas')
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  function waitFor(video: HTMLVideoElement, event: string, action: () => void) {
    return new Promise<void>((resolve, reject) => {
      function done() { cleanup(); resolve() }
      function fail() { cleanup(); reject(new Error('Frame unavailable')) }
      function cleanup() {
        clearTimeout(timer); video.removeEventListener(event, done); video.removeEventListener('error', fail)
        signal.removeEventListener('abort', fail)
      }
      const timer = window.setTimeout(fail, 5000)
      video.addEventListener(event, done, { once: true }); video.addEventListener('error', fail, { once: true })
      signal.addEventListener('abort', fail, { once: true })
      if (signal.aborted) { fail(); return }
      try { action() } catch { fail() }
    })
  }
  try {
    for (const [index, ms] of times.entries()) {
      if (signal.aborted) return
      const key = url + '|' + ms, cached = frames.get(key)
      if (cached) { publish(index, cached); continue }
      if (!media) {
        media = document.createElement('video'); media.muted = true; media.playsInline = true; media.preload = 'metadata'
        await waitFor(media, 'loadedmetadata', () => { media!.src = url })
        if (!media.videoWidth || !media.videoHeight) return
        const size = thumbnailSize(media.videoWidth, media.videoHeight)
        canvas.width = size.width
        canvas.height = size.height
      }
      if (!Number.isFinite(media.duration) || ms >= media.duration * 1000) return
      if (media.currentTime !== ms / 1000 || media.readyState < 2) {
        await waitFor(media, 'seeked', () => { media!.currentTime = ms / 1000 })
      }
      if (signal.aborted || !media.videoWidth || !media.videoHeight) return
      // Cache the full frame at its native aspect. Each timeline fits it to its own cell.
      ctx.drawImage(media, 0, 0, canvas.width, canvas.height)
      const image = canvas.toDataURL('image/jpeg', .68)
      keep(key, image); publish(index, image)
    }
  } finally {
    if (media) { media.removeAttribute('src'); media.load() }
  }
}
