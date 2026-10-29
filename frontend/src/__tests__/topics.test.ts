import test from 'node:test'
import assert from 'node:assert/strict'
import { parseTopicReview, parseTopicTime, splitTopic, topicPayload, topicTime, topicValidation, TopicResponseError, type TopicSegment } from '../topics.ts'

const jobId = '00000000-0000-4000-8000-000000000001'
test('time editor accepts minute or hour timecodes and rejects ambiguous or invalid boundaries', () => {
  assert.equal(parseTopicTime('5:00'), 300000)
  assert.equal(parseTopicTime('07:00.001'), 420001)
  assert.equal(parseTopicTime('1:02:03.1'), 3723100)
  assert.equal(parseTopicTime('120:00.000'), 7200000)
  for (const value of ['', '300000', '-1:00', '05:60', '1:60:00', '5:0', '05:00.0001', 'NaN', '9007199254740992:00']) assert.equal(parseTopicTime(value), null)
  for (const value of [0, 300001, 5399980, 7200000]) assert.equal(parseTopicTime(topicTime(value)), value)
})
const segment = (): TopicSegment => ({ id: 'topic:1', start_ms: 300000, end_ms: 420000, title: '缓存失效', summary: '讨论失效时间和随机过期策略。', keywords: ['缓存', 'TTL'], enabled: false, origin: 'model', boundary_source: 'model', content_source: 'model', original_prediction_id: 'topic:1' })
function snapshot() {
  return { job_id: jobId, video_id: '00000000-0000-4000-8000-000000000080', generation: 1, analysis_mode: 'topics', data_kind: 'model_result', duration_ms: 600000, revision: 0, status: 'DRAFT', source_hash: 'a'.repeat(64), confirmed_revisions: [], segments: [segment()], utterances: [{ utterance_id: 'u:1', start_ms: 300000, end_ms: 420000, text: '我们给缓存增加随机过期时间。', source_chunk_id: 'chunk:1' }] }
}

test('topic review keeps a real unselected draft and sends no product or provenance fields as edits', () => {
  const value = parseTopicReview(snapshot(), jobId)
  assert.equal(value.revision, 0)
  assert.equal(value.segments[0]!.enabled, false)
  assert.deepEqual(Object.keys(topicPayload(value.segments).segments[0]!).sort(), ['enabled', 'end_ms', 'id', 'keywords', 'start_ms', 'summary', 'title'])
  const payload = topicPayload(value.segments)
  payload.segments[0]!.keywords.push('手动')
  assert.deepEqual(value.segments[0]!.keywords, ['缓存', 'TTL'])
})

test('topic parsing rejects foreign resource, legacy content, malformed confirmation and invalid source times', () => {
  const invalid: unknown[] = [null, { ...snapshot(), job_id: 'other' }, { ...snapshot(), analysis_mode: 'commerce' }, { ...snapshot(), data_kind: 'synthetic_fixture' }, { ...snapshot(), status: 'CONFIRMED' }, { ...snapshot(), confirmed_revisions: [0] }, { ...snapshot(), confirmed_revisions: [1, 1] }, { ...snapshot(), duration_ms: 1.5 }, { ...snapshot(), segments: [{ ...segment(), title: '' }] }, { ...snapshot(), utterances: [{ ...snapshot().utterances[0], end_ms: 600001 }] }]
  for (const value of invalid) assert.throws(() => parseTopicReview(value, jobId), TopicResponseError)
  const confirmed = { ...snapshot(), status: 'CONFIRMED', confirmed_revisions: [0] }
  assert.equal(parseTopicReview(confirmed, jobId).status, 'CONFIRMED')
})

test('editor validates selected overlap, integer bounds, content limits, duplicate keywords and ids', () => {
  assert.equal(topicValidation([segment()], 600000), '')
  const first = { ...segment(), enabled: true }, second = { ...segment(), id: 'topic:2', enabled: true, start_ms: 420000, end_ms: 600000 }
  assert.equal(topicValidation([second, first], 600000), '', 'touching endpoints and unsorted editor cards remain valid')
  assert.match(topicValidation([first, { ...second, start_ms: 419999 }], 600000), /重叠/)
  assert.equal(topicValidation([first, { ...second, enabled: false, start_ms: 419999 }], 600000), '', 'disabled suggestions may overlap a manual correction')
  for (const update of [{ start_ms: 1.5 }, { start_ms: -1 }, { end_ms: 600001 }, { end_ms: 300000 }, { title: 'x'.repeat(121) }, { summary: ' ' }, { keywords: ['缓存', '缓存'] }, { keywords: ['x'.repeat(41)] }]) {
    assert.notEqual(topicValidation([{ ...segment(), ...update }], 600000), '')
  }
  assert.match(topicValidation([segment(), segment()], 600000), /标识重复/)
})

test('manual split uses the in-segment playhead or midpoint without changing the model card or sharing keyword arrays', () => {
  const original = { ...segment(), enabled: true }
  const pair = splitTopic(original, 360001, 'manual:2')!
  assert.equal(pair[0].end_ms, 360001); assert.equal(pair[1].start_ms, 360001)
  assert.equal(pair[0].id, original.id); assert.equal(pair[1].origin, 'manual'); assert.equal(pair[1].original_prediction_id, null)
  assert.equal(pair[0].enabled, true); assert.equal(pair[1].enabled, true)
  assert.equal(original.end_ms, 420000)
  pair[1].keywords.push('后半段'); assert.deepEqual(original.keywords, ['缓存', 'TTL']); assert.deepEqual(pair[0].keywords, ['缓存', 'TTL'])
  assert.equal(splitTopic(original, 100, 'manual:2')![1].start_ms, 360000)
  assert.equal(splitTopic({ ...original, start_ms: 0, end_ms: 1 }, 0, 'manual:2'), null)
  assert.equal(splitTopic(original, 360000, original.id), null)
  assert.equal(topicTime(300001), '05:00.001'); assert.equal(topicTime(7200000), '120:00.000')
})
