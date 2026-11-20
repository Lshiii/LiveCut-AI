import type { EditJoin } from './editTimeline.ts'

export interface JoinSample {
  side: 'before' | 'after'
  sourceMs: number
  editMs: number
}

// Keep every requested time inside a retained, half-open source interval.
// Sampling each side separately prevents a deleted gap from appearing in the report.
export function joinSamples(join: EditJoin): JoinSample[] {
  const samples: JoinSample[] = []
  for (const side of ['before', 'after'] as const) {
    const before = side === 'before'
    const part = join.audition[before ? 0 : 1]
    const edge = before ? part.end_ms : part.start_ms
    const start = Math.max(part.start_ms, edge - 400)
    const end = Math.min(part.end_ms, edge + 400)
    // Always include the last millisecond even for a sub-frame interval.
    for (let at = start; at < end;) {
      samples.push({ side, sourceMs: at, editMs: join.at_ms + at - edge })
      if (at === end - 1) break
      at = Math.min(at + 25, end - 1)
    }
  }
  return samples
}

/** Require an almost completely dark, decoded image. A stationary image alone says nothing. */
export function blackFrame(pixels: Uint8ClampedArray): boolean {
  let dark = 0
  if (!pixels.length || pixels.length % 4) throw new Error('未取得可读取的视频帧。')
  for (let i = 0; i < pixels.length; i += 4) {
    if (pixels[i + 3] !== 255) throw new Error('未取得完整的视频帧。')
    if (pixels[i]! <= 24 && pixels[i + 1]! <= 24 && pixels[i + 2]! <= 24) dark++
  }
  return dark >= .98 * (pixels.length / 4)
}

/** One clickable sample per consecutive dark run, keeping both sides of the cut distinct. */
export async function findBlackRuns(join: EditJoin, readBlack: (ms: number) => Promise<boolean>, signal: AbortSignal) {
  const marks: JoinSample[] = []
  let previousDarkSide: JoinSample['side'] | null = null
  for (const sample of joinSamples(join)) {
    signal.throwIfAborted()
    const dark = await readBlack(sample.sourceMs)
    signal.throwIfAborted()
    if (dark && sample.side !== previousDarkSide) marks.push(sample)
    previousDarkSide = dark ? sample.side : null
  }
  return marks
}

function waitForVideo(video: HTMLVideoElement, event: 'loadedmetadata' | 'seeked' | 'loadeddata', signal: AbortSignal,
  deadline: number, start?: () => void) {
  signal.throwIfAborted()
  return new Promise<void>((resolve, reject) => {
    const remaining = deadline - performance.now()
    if (remaining <= 0) { reject(new Error('读取视频帧超时，请重试。')); return }
    const timer = window.setTimeout(() => finish(new Error('读取视频帧超时，请重试。')), Math.min(8000, remaining))
    const onReady = () => finish()
    const onError = () => finish(new Error('无法读取原视频帧，请检查视频是否可播放及是否允许跨域读取。'))
    const onAbort = () => finish(signal.reason)
    function finish(error?: unknown) {
      window.clearTimeout(timer)
      video.removeEventListener(event, onReady)
      video.removeEventListener('error', onError)
      signal.removeEventListener('abort', onAbort)
      if (error) reject(error)
      else resolve()
    }
    video.addEventListener(event, onReady)
    video.addEventListener('error', onError)
    signal.addEventListener('abort', onAbort, { once: true })
    try { start?.() } catch (error) { finish(error) }
  })
}

export async function inspectJoinBlackFrames(url: string, join: EditJoin, signal: AbortSignal): Promise<JoinSample[]> {
  const video = document.createElement('video')
  video.crossOrigin = 'anonymous'
  video.muted = true
  video.playsInline = true
  video.preload = 'metadata'
  const canvas = document.createElement('canvas')
  canvas.width = 64
  canvas.height = 36
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  if (!ctx) throw new Error('当前浏览器无法读取视频帧。')
  const deadline = performance.now() + 30000
  try {
    await waitForVideo(video, 'loadedmetadata', signal, deadline, () => { video.src = url })
    if (!video.videoWidth || !Number.isFinite(video.duration)) throw new Error('原视频没有可读取的画面。')
    return await findBlackRuns(join, async ms => {
      if (ms >= video.duration * 1000) throw new Error('剪点超出原视频时长，请重新核对。')
      if (video.currentTime !== ms / 1000 || video.readyState < video.HAVE_CURRENT_DATA) {
        await waitForVideo(video, 'seeked', signal, deadline, () => { video.currentTime = ms / 1000 })
      }
      if (video.readyState < video.HAVE_CURRENT_DATA) await waitForVideo(video, 'loadeddata', signal, deadline)
      ctx.clearRect(0, 0, canvas.width, canvas.height)
      ctx.drawImage(video, 0, 0, canvas.width, canvas.height)
      return blackFrame(ctx.getImageData(0, 0, canvas.width, canvas.height).data)
    }, signal)
  } finally {
    video.removeAttribute('src')
    video.load()
  }
}
