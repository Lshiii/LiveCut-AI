import { readFileSync } from 'node:fs'
import type { AgentSession, AgentTurn } from '../../videoAgent.ts'
export const speechFixture = JSON.parse(readFileSync(new URL('../../../../fixtures/agent-speech-lookup.synthetic.json', import.meta.url), 'utf8'))
export function speechSample(id = 'focus_zero_is_valid') {
  const sample = structuredClone(speechFixture.cases.find((value: any) => value.id === id)); if (!sample) throw Error(`Unknown synthetic case ${id}`)
  const result = sample.result, session: AgentSession = { id: result.session_id, job_id: result.job_id, video_id: result.video_id, created_at: '2026-11-11T00:00:00Z',
    source: { video_sha256: result.source_hash, transcript_hash: result.transcript_hash, duration_ms: sample.duration_ms }, turns: [] }
  const turn: AgentTurn = { id: result.turn_id, session_id: session.id, job_id: session.job_id, question: result.question, sequence: 1, visual_mode: 'frames', evidence_mode: 'structured', evidence_policy: 'speech_lookup_v1',
    ...(Object.hasOwn(result, 'focus_timestamp_ms') ? { focus_timestamp_ms: result.focus_timestamp_ms } : {}), status: 'SUCCEEDED', state_version: 3, created_at: '2026-11-11T00:00:01Z', updated_at: '2026-11-11T00:00:02Z',
    budget: { max_text_calls: 8, text_calls_reserved: 3 }, stage: { id: '55555555-5555-4555-8555-555555555555', stage_type: 'AGENT_EVIDENCE', status: 'SUCCEEDED', attempt: 1, error_code: null }, result }
  session.turns = [turn]; return { sample, session, turn }
}
