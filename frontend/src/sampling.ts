export const DEFAULT_OCR_MAX_FRAMES = 120

function validFrameBudget(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 1 && value <= 120
}

/** Only OCR tasks submit a frame budget; speech-only analysis never consumes it. */
export function newAnalysisConfig(visualProvider: 'ocr' | 'none', maxTextCalls: number, ocrMaxFrames: unknown = DEFAULT_OCR_MAX_FRAMES) {
  const common = { analysis_mode: 'topics' as const, visual_provider: visualProvider, max_text_calls: maxTextCalls, ocr_sampling: 'uniform' as const }
  if (visualProvider === 'none') return common
  if (!validFrameBudget(ocrMaxFrames)) throw new RangeError('画面取样上限须为 1～120 的整数。')
  return { ...common, ocr_max_frames: ocrMaxFrames }
}

/** Explain the frozen config; missing budgets retain the original 16-frame behavior. */
export function samplingLabel(config: unknown): string {
  if (!config || typeof config !== 'object' || Array.isArray(config)) return '画面取证配置未提供'
  const value = config as Record<string, unknown>
  if (value.visual_provider === 'none') return '仅使用语音转写'
  if (value.visual_provider !== undefined && value.visual_provider !== 'ocr') return '画面取证策略未知'
  const maxFrames = value.ocr_max_frames === undefined ? 16 : value.ocr_max_frames
  if (!validFrameBudget(maxFrames)) return '画面取样上限未知'
  if (value.ocr_sampling === 'uniform') return `整段视频均匀取样 · 最多 ${maxFrames} 帧`
  if (value.ocr_sampling === undefined || value.ocr_sampling === 'prefix') return `前段固定间隔取样 · 最多 ${maxFrames} 帧 · 旧策略`
  return '画面取证策略未知'
}
