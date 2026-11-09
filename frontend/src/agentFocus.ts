export interface AgentFocusHint { job_id: string; video_id: string; source_hash: string; timestamp_ms: number }
export interface AgentFocusOwner { job_id: string; video_id: string; source_hash: string; duration_ms: number }
export interface AgentFocusPlayer { currentTime: number; duration: number; readyState: number; seeking: boolean; currentSrc: string; pause(): void }
export class AgentFocusError extends Error {
  code: string
  constructor(code = 'AGENT_FOCUS_NOT_READY') { super(code); this.code = code }
}
export function parseAgentFocusCapability(raw: unknown): true {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw) || Object.keys(raw).length !== 1
    || !Object.hasOwn(raw, 'focus_timestamp_supported') || (raw as {focus_timestamp_supported:unknown}).focus_timestamp_supported !== true) throw new AgentFocusError('AGENT_FOCUS_UNAVAILABLE')
  return true
}
export function focusHintMatches(hint: AgentFocusHint, owner: Pick<AgentFocusOwner, 'job_id' | 'video_id' | 'source_hash'>): boolean {
  return hint.job_id === owner.job_id && hint.video_id === owner.video_id && hint.source_hash === owner.source_hash
}
export interface AgentFocusCapture {
  player: AgentFocusPlayer | null; owner: AgentFocusOwner; expectedSourceUrl: string
  active: boolean; visible: boolean; frames: boolean; supported: boolean; pending: boolean; pendingPreview: boolean
}
export function canCaptureAgentFocus(input: AgentFocusCapture): boolean {
  const player = input.player
  return !!player && input.active && input.visible && input.frames && input.supported && !input.pending && !input.pendingPreview
    && player.readyState >= 2 && !player.seeking && player.currentSrc === input.expectedSourceUrl && !!player.currentSrc
    && Number.isFinite(player.currentTime) && player.currentTime >= 0 && Number.isFinite(player.duration) && player.duration > 0
    && Number.isSafeInteger(input.owner.duration_ms) && input.owner.duration_ms > 0
    && Number.isSafeInteger(Math.floor(player.currentTime * 1000)) && Math.floor(player.currentTime * 1000) < input.owner.duration_ms
    && player.currentTime < player.duration
}
/** Explicit synchronous capture only: cancel old playback, pause, recheck, then freeze a hint. No frame is claimed. */
export function captureAgentFocus(input: AgentFocusCapture, cancelPreview: () => void): AgentFocusHint {
  if (!canCaptureAgentFocus(input)) throw new AgentFocusError()
  cancelPreview(); input.player!.pause()
  if (!canCaptureAgentFocus(input)) throw new AgentFocusError()
  return Object.freeze({ job_id: input.owner.job_id, video_id: input.owner.video_id, source_hash: input.owner.source_hash, timestamp_ms: Math.floor(input.player!.currentTime * 1000) })
}
