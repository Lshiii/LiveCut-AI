import type { AgentSession, AgentTurn } from '../../videoAgent.ts'
import type { AgentSubmissionEntry, AgentSubmissionStorage } from '../../agentSubmissionRecovery.ts'

export const JOB = '11111111-1111-4111-8111-111111111111', VIDEO = '22222222-2222-4222-8222-222222222222', SESSION = '33333333-3333-4333-8333-333333333333'
export const HASH = 'a'.repeat(64)
export const owner = { job_id: JOB, video_id: VIDEO, video_sha256: HASH }
export const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
export function session(): AgentSession { return { id: SESSION, job_id: JOB, video_id: VIDEO, created_at: '2026-11-08T00:00:00Z',
  source: { video_sha256: HASH, transcript_hash: 'b'.repeat(64), duration_ms: 5000 }, turns: [] } }
export function turn(status: AgentTurn['status'] = 'SUCCEEDED', sequence = 1): AgentTurn {
  return { id: `44444444-4444-4444-8444-${String(sequence).padStart(12, '0')}`, session_id: SESSION, job_id: JOB, question: '原问题A', sequence,
    status, state_version: 4, created_at: '2026-11-08T00:00:01Z', updated_at: '2026-11-08T00:00:02Z', budget: { max_text_calls: 8, text_calls_reserved: 2 },
    stage: { id: `55555555-5555-4555-8555-${String(sequence).padStart(12, '0')}`, stage_type: 'SEGMENT', status: status === 'SUCCEEDED' ? 'SUCCEEDED' : status === 'CANCELLED' ? 'CANCELLED' : status === 'FAILED' ? 'FAILED' : 'READY', attempt: 1, error_code: null },
    result: status !== 'SUCCEEDED' ? null : { schema_version: '1.0', session_id: SESSION, turn_id: `44444444-4444-4444-8444-${String(sequence).padStart(12, '0')}`, job_id: JOB, video_id: VIDEO,
      source_hash: HASH, transcript_hash: 'b'.repeat(64), question: '原问题A', answer: '已有视频回答', answer_scope: 'VIDEO', citations: [{ utterance_id: 'u1', start_ms: 0, end_ms: 1000, text: '视频原句' }],
      tool_calls: [{ step: 1, tool_name: 'read_context', arguments: { utterance_id: 'u1', before: 0, after: 0 }, result_utterance_ids: ['u1'] }],
      provenance: { text_model_id: 'synthetic', prompt_version: 'video-agent-v1', retrieval_mode: 'none' }, suggested_clip_instruction: null } }
}
export function entry(kind: AgentSubmissionEntry['kind'] = 'TURN', key = 'original-key'): AgentSubmissionEntry {
  return { schema_version: 'agent-submission-v1', kind, key, ...owner, session_id: kind === 'TURN' ? SESSION : null,
    source: kind === 'TURN' ? clone(session().source) : null, input: kind === 'TURN' ? JSON.stringify({ question: '原问题A', max_text_calls: 8 }) : '{}' }
}
export class MemoryStorage implements AgentSubmissionStorage {
  readonly values = new Map<string, string>(); writes = 0; removes = 0; failWrite = false; failRead = false; onWrite?: (key: string, value: string) => void
  get length() { if (this.failRead) throw Error('storage disabled'); return this.values.size }
  key(index: number) { return [...this.values.keys()][index] ?? null }
  getItem(key: string) { if (this.failRead) throw Error('storage disabled'); return this.values.get(key) ?? null }
  setItem(key: string, value: string) { this.writes++; if (this.failWrite) throw Error('quota'); this.values.set(key, value); this.onWrite?.(key, value) }
  removeItem(key: string) { this.removes++; this.values.delete(key) }
}
