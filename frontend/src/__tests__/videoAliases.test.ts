import assert from 'node:assert/strict'
import test from 'node:test'
import { createVideoAliases, normalizeVideoAlias, videoAliasLimit, videoSearchName } from '../videoAliases.ts'
import { workbenchTasks } from '../workbenchTasks.ts'
import type { AnalysisJob } from '../api.ts'

function memoryStorage() {
  const records = new Map<string, string>()
  return {
    records, getItem: (key: string) => records.get(key) ?? null,
    setItem: (key: string, value: string) => { records.set(key, value) },
    removeItem: (key: string) => { records.delete(key) },
  }
}
test('aliases survive reopening, search both names across historical analyses, and clear a single video', () => {
  const disk = memoryStorage(), edit = createVideoAliases(() => disk)
  const filename = 'link-import-f00a.mp4', other = 'link-import-dead.mp4'
  assert.equal(edit.save('video-a', '  Qwen3   公开课  '), true)
  edit.save('video-b', '访谈')
  const reopened = createVideoAliases(() => disk)
  reopened.load(['video-a', 'video-b'])
  const lookup = (id: string) => videoSearchName(id === 'video-a' ? filename : other, reopened.names.get(id))
  const jobs = [{ id: 'j1', video_id: 'video-a', generation: 1, status: 'REVIEW_READY', created_at: 'a' },
    { id: 'j2', video_id: 'video-a', generation: 2, status: 'REVIEW_READY', created_at: 'b' },
    { id: 'j3', video_id: 'video-b', generation: 1, status: 'FAILED', created_at: 'b' }] as AnalysisJob[]
  for (const term of ['qwen3 公开课', filename]) {
    assert.deepEqual(workbenchTasks(jobs, 'recent', term, 'ALL', lookup).map(job => job.id), ['j2', 'j1'])
  }
  reopened.save('video-a', '')
  assert.equal(lookup('video-a'), filename)
  assert.equal(reopened.names.get('video-b'), '访谈')
})
test('storage denial keeps session edits and clears even if an older persisted alias exists', () => {
  const disk = memoryStorage(), stable = createVideoAliases(() => disk)
  stable.save('video', '旧备注')
  let blocked = false
  const aliases = createVideoAliases(() => { if (blocked) throw new Error('storage denied'); return disk })
  aliases.load(['video'])
  blocked = true
  aliases.save('video', '会话备注')
  assert.equal(aliases.temporary.has('video'), true)
  assert.equal(aliases.names.get('video'), '会话备注')
  aliases.save('video', '')
  blocked = false; aliases.storageChanged(null)
  assert.equal(aliases.names.get('video'), '')
  aliases.save('video', '恢复存储')
  assert.equal(aliases.temporary.has('video'), false)
  stable.storageChanged(null)
  assert.equal(stable.names.get('video'), '恢复存储')
})
test('invalid stored names are ignored and invalid edits preserve a previously accepted alias', () => {
  const disk = memoryStorage(), aliases = createVideoAliases(() => disk)
  aliases.save('one', '存在')
  const invalid = 'a'.repeat(videoAliasLimit + 1)
  assert.equal(aliases.save('one', invalid), false)
  assert.equal(aliases.names.get('one'), '存在')
  const [key] = disk.records.keys()
  disk.records.set(key!, invalid)
  aliases.storageChanged(key!)
  assert.equal(aliases.names.get('one'), '')
  assert.equal(normalizeVideoAlias('one\0two'), null)
})
