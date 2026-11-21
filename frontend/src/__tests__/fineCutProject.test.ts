import assert from 'node:assert/strict'
import test from 'node:test'
import { buildFineCutProject, type FineCutOwner } from '../fineCutProject.ts'

const time = (value: number) => ({ OTIO_SCHEMA: 'RationalTime.1', value, rate: 1000 })
const range = (start: number, end: number) => ({ OTIO_SCHEMA: 'TimeRange.1', start_time: time(start), duration: time(end - start) })
const owner: FineCutOwner = {
  requestId:'req', videoId:'video', sourceHash:'a'.repeat(64), transcriptHash:'b'.repeat(64), revision:3,
  planHash:'c'.repeat(64), outputId:'out', goalId:'why', durationMs:12000, evidenceIds:['u1','u2','u3','u8'], manualReviewHash:'d'.repeat(64),
}
const base = [
  {start_ms: 1010, end_ms: 3010, start_utterance_id: 'u1', end_utterance_id:'u3'},
  {start_ms: 9000, end_ms: 10800, start_utterance_id: 'u8', end_utterance_id:'u8'},
]
const trim = [{start_ms:1510,end_ms:2510},{start_ms:9979,end_ms:9980}]
function marker(utterance_id: string, start: number, end: number) {
  return { OTIO_SCHEMA:'Marker.2', name:utterance_id, color:'GREEN', marked_range:range(start,end), metadata:{livecut:{utterance_id}} }
}
function project() {
  const name = '课程 #1.mp4'
  const reference = { OTIO_SCHEMA:'ExternalReference.1', name, target_url:'./' + encodeURIComponent(name),
    available_range:range(0,12000), available_image_bounds:null, metadata:{livecut:{source_sha256:owner.sourceHash}} }
  const item = { enabled:true, effects:[], markers:[], source_range:null }
  const tracks = ['Video','Audio'].map(kind => ({...item,OTIO_SCHEMA:'Track.1',kind,name:kind==='Video'?'V1':'A1',
    children:base.map((part,i) => ({...item,OTIO_SCHEMA:'Clip.1',name:'讲解 · '+(i+1),source_range:range(part.start_ms,part.end_ms),
      media_reference:structuredClone(reference),metadata:{livecut:{start_utterance_id:part.start_utterance_id,end_utterance_id:part.end_utterance_id}},
      markers:kind==='Audio'?[]:i ? [marker('u8',9000,10800)] : [marker('u1',1010,1400),marker('u2',1500,2000),marker('u3',2100,3010)] })) }))
  return { OTIO_SCHEMA:'Timeline.1', name:'讲解', global_start_time:time(0), metadata:{livecut:{approved_revision:3,
    plan_hash:owner.planHash,manual_review_hash:owner.manualReviewHash,source_filename:name,source_sha256:owner.sourceHash,
    clip_request_id:owner.requestId, video_id:owner.videoId, output_id:owner.outputId, goal_id:owner.goalId,
    transcript_hash:owner.transcriptHash,evidence_utterance_ids:[...owner.evidenceIds]}},
    tracks:{...item,OTIO_SCHEMA:'Stack.1',name:'tracks',children:tracks} }
}

test('trim V1 and A1 by the same milliseconds and clip picture markers in their original source clock', () => {
  const raw=project(), untouched=structuredClone(raw)
  const out=buildFineCutProject(raw,owner,base,trim)
  const clips=(out.tracks as typeof raw.tracks).children.map(track=>track.children)
  assert.deepEqual(clips[0]!.map(c=>c.source_range), [range(1510,2510),range(9979,9980)])
  assert.deepEqual(clips[1]!.map(c=>c.source_range), clips[0]!.map(c=>c.source_range))
  assert.deepEqual(clips[0]![0]!.media_reference,raw.tracks.children[0]!.children[0]!.media_reference)
  assert.deepEqual(clips[0]![0]!.markers.map(m=>m.marked_range),[range(1510,2000),range(2100,2510)])
  assert.deepEqual(clips[0]![1]!.markers[0]!.marked_range,range(9979,9980))
  assert.deepEqual(clips[1]!.flatMap(c=>c.markers),[])
  assert.deepEqual(clips[0]![1]!.markers[0]!.metadata.livecut,{
    utterance_id:'u8',source_start_ms:9979,source_end_ms:9980,edit_start_ms:1000,edit_end_ms:1001,partial_utterance:true,
    approved_source:{utterance_id:'u8',source_start_ms:9000,source_end_ms:10800,edit_start_ms:2000,edit_end_ms:3800},
  })
  const proof=(out.metadata as {livecut:Record<string, unknown>}).livecut
  assert.equal(proof.edit_kind,'manual_fine_cut'); assert.equal(proof.approved_revision,undefined); assert.equal(proof.plan_hash,undefined)
  assert.deepEqual(proof.evidence_utterance_ids,['u2','u3','u8'])
  assert.equal(proof.duration_ms,1001); assert.deepEqual(proof.edit_points_ms,[1000])
  assert.deepEqual(proof.approved_source,{...raw.metadata.livecut,duration_ms:3800,edit_points_ms:[2000]})
  assert.deepEqual(raw,untouched)
  assert.throws(()=>buildFineCutProject(out,owner,base,trim))
})

test('a foreign request, misaligned tracks, changed clock, unrelated markers or out-of-bounds trimming fails closed', () => {
  for (const mutate of [
    (p: ReturnType<typeof project>) => { p.metadata.livecut.manual_review_hash='e'.repeat(64) },
    (p: ReturnType<typeof project>) => { p.tracks.children[1]!.children[0]!.source_range.duration.value-- },
    (p: ReturnType<typeof project>) => { p.tracks.children[1]!.children[0]!.metadata.livecut.end_utterance_id='other' },
    (p: ReturnType<typeof project>) => { p.tracks.children[1]!.children[0]!.media_reference.target_url='https://example.com/source.mp4' },
    (p: ReturnType<typeof project>) => { p.tracks.children[0]!.children[0]!.source_range.start_time.rate=25 },
    (p: ReturnType<typeof project>) => { p.tracks.children[0]!.children[1]!.media_reference.available_range.duration.value-- },
    (p: ReturnType<typeof project>) => { p.tracks.children[0]!.children[0]!.markers[0]!.metadata.livecut.utterance_id='signoff' },
    (p: ReturnType<typeof project>) => { p.tracks.children[0]!.children[0]!.markers.pop() },
    (p: ReturnType<typeof project>) => { p.tracks.children[1]!.children[0]!.markers.push(marker('u1',1010,1400)) },
  ]) {
    const p=project();mutate(p)
    assert.throws(()=>buildFineCutProject(p,owner,base,trim))
  }
  assert.throws(()=>buildFineCutProject(project(),{...owner,requestId:'other'},base,trim))
  for (const cuts of [[],trim.slice(0,1),[{start_ms:1009,end_ms:2500},trim[1]!],[trim[0]!,{start_ms:9100,end_ms:9100}]]) {
    assert.throws(()=>buildFineCutProject(project(),owner,base,cuts))
  }
})
