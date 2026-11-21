import { editSpans } from './editTimeline.ts'
import type { SequencePreviewPart } from './sequencePreview.ts'

/** Sample the visible edit in equal time cells. A thumbnail always comes from retained footage. */
export function filmstripTimes(parts: readonly SequencePreviewPart[], from: number, to: number, count: number) {
  const spans = editSpans(parts)
  if (!Number.isSafeInteger(count) || count < 1 || count > 12 || !Number.isSafeInteger(from)
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
  canvas.width = 136; canvas.height = 76
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
      }
      if (!Number.isFinite(media.duration) || ms >= media.duration * 1000) return
      if (media.currentTime !== ms / 1000 || media.readyState < 2) {
        await waitFor(media, 'seeked', () => { media!.currentTime = ms / 1000 })
      }
      if (signal.aborted || !media.videoWidth || !media.videoHeight) return
      // Preserve portrait and letterboxed sources in the filmstrip.
      const scale = Math.min(canvas.width / media.videoWidth, canvas.height / media.videoHeight)
      const w = media.videoWidth * scale, h = media.videoHeight * scale
      ctx.fillStyle = '#0b0d10'; ctx.fillRect(0, 0, canvas.width, canvas.height)
      ctx.drawImage(media, (canvas.width - w) / 2, (canvas.height - h) / 2, w, h)
      const image = canvas.toDataURL('image/jpeg', .68)
      keep(key, image); publish(index, image)
    }
  } finally {
    if (media) { media.removeAttribute('src'); media.load() }
  }
}
