/** Explain the persisted strategy; a missing field retains the original Worker behavior. */
export function samplingLabel(config: unknown): string {
  if (!config || typeof config !== 'object' || Array.isArray(config)) return '画面取证配置未提供'
  const value = config as Record<string, unknown>
  if (value.visual_provider === 'none') return '仅使用语音转写'
  if (value.visual_provider !== undefined && value.visual_provider !== 'ocr') return '画面取证策略未知'
  if (value.ocr_sampling === 'uniform') return '整段视频均匀取样 · 最多 16 帧'
  if (value.ocr_sampling === undefined || value.ocr_sampling === 'prefix') return '前段固定间隔取样 · 旧策略'
  return '画面取证策略未知'
}
