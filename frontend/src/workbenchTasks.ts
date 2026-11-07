import type { AnalysisJob } from './api.ts'

export type TaskListScope = 'recent' | 'all'

/** The latest analysis for each video; historical jobs remain searchable. */
export function workbenchTasks(
  jobs: AnalysisJob[], scope: TaskListScope, query: string, status: string,
  videoName: (id: string) => string,
): AnalysisJob[] {
  const sorted = [...jobs].sort((a, b) => b.created_at.localeCompare(a.created_at) || b.generation - a.generation || a.id.localeCompare(b.id))
  const term = query.trim().toLowerCase()
  let candidates = sorted
  if (scope === 'recent' && !term) {
    const latest = new Map<string, AnalysisJob>()
    for (const job of sorted) {
      const previous = latest.get(job.video_id)
      if (!previous || job.generation > previous.generation) latest.set(job.video_id, job)
    }
    candidates = sorted.filter(job => latest.get(job.video_id) === job)
  }
  return candidates.filter(job => (status === 'ALL' || job.status === status)
    && (!term || `${videoName(job.video_id)} ${job.id}`.toLowerCase().includes(term)))
}
