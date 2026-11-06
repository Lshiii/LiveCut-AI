import test from 'node:test'
import assert from 'node:assert/strict'
import type { AnalysisJob } from '../api.ts'
import { workbenchTasks } from '../workbenchTasks.ts'

const job = (id: string, video_id: string, generation: number, status: string, created_at: string) =>
  ({ id, video_id, generation, status, created_at } as AnalysisJob)
const jobs = [
  job('old-a', 'a', 1, 'FAILED', '2026-11-01T00:00:00Z'),
  job('new-b', 'b', 1, 'REVIEW_READY', '2026-11-03T00:00:00Z'),
  job('new-a', 'a', 2, 'REVIEW_READY', '2026-11-02T00:00:00Z'),
]
const name = (id: string) => id === 'a' ? '缓存.mp4' : '课程.mp4'
test('recent videos show each latest generation without changing the input or hiding all-history access', () => {
  const original = [...jobs]
  assert.deepEqual(workbenchTasks(jobs, 'recent', '', 'ALL', name).map(j => j.id), ['new-b', 'new-a'])
  assert.deepEqual(workbenchTasks(jobs, 'all', '', 'ALL', name).map(j => j.id), ['new-b', 'new-a', 'old-a'])
  assert.deepEqual(jobs, original)
})
test('search covers history even in recent-video view and matches filename or job id', () => {
  assert.deepEqual(workbenchTasks(jobs, 'recent', ' OLD-A ', 'ALL', name).map(j => j.id), ['old-a'])
  assert.deepEqual(workbenchTasks(jobs, 'recent', '缓存', 'ALL', name).map(j => j.id), ['new-a', 'old-a'])
  assert.deepEqual(workbenchTasks(jobs, 'recent', '缓存', 'FAILED', name).map(j => j.id), ['old-a'])
})
test('status filtering describes the latest analysis and cannot resurrect an old failed generation', () => {
  assert.deepEqual(workbenchTasks(jobs, 'recent', '', 'FAILED', name), [])
  assert.deepEqual(workbenchTasks(jobs, 'all', '', 'FAILED', name).map(j => j.id), ['old-a'])
  assert.deepEqual(workbenchTasks([], 'recent', '', 'ALL', name), [])
})
test('generation takes priority for a video when source timestamps are out of order', () => {
  const input = [job('later-clock', 'a', 1, 'FAILED', '2026-11-05T00:00:00Z'), ...jobs]
  assert.deepEqual(workbenchTasks(input, 'recent', '', 'ALL', name).map(j => j.id), ['new-b', 'new-a'])
})
