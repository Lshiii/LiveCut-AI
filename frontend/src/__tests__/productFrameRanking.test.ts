import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import type { IntentSnapshot } from '../intentClips.ts'
import { parseFrameRanking, rankingRequest } from '../productFrameRanking.ts'

const part = { start_utterance_id: 'a', end_utterance_id: 'b', start_ms: 1000, end_ms: 8000 }
const candidates = [{ id: 'p1', parts: [part] }]
const snapshot = { status: 'PLAN_READY', id: 'clip', video_id: 'video', plan_hash: 'p', source: {video_sha256:'v',transcript_hash:'t',duration_ms:9000},
  utterances: [{utterance_id:'a', start_ms:1000,end_ms:4000},{utterance_id:'b',start_ms:4100,end_ms:8000}] } as IntentSnapshot
const bytes = Buffer.from([255,216,255,217])
const frame = { timestamp_ms:4000,requested_ms:3999,source_pts:4,source_time_base:'1/1',bytes:4,sha256:createHash('sha256').update(bytes).digest('hex'),
  jpeg_base64:bytes.toString('base64'),width:20,height:20 }
const response = { request_id:'clip',video_id:'video',plan_hash:'p',source_hash:'v',transcript_hash:'t',label:'鱼柳',model_id:'test',rows:[{id:'p1',sample_count:1,score:0.7,frame}] }
test('visual ranking derives bound ASR ids and validates selected frame bytes and time',async()=>{
  assert.deepEqual(rankingRequest(snapshot,'鱼柳',candidates).candidates[0]?.parts,[{start_utterance_id:'a',end_utterance_id:'b'}])
  assert.equal((await parseFrameRanking(response,snapshot,'鱼柳',candidates)).rows[0]?.frame?.timestamp_ms,4000)
  assert.throws(()=>rankingRequest(snapshot,'鱼柳',[{id:'p',parts:[{...part,end_ms:7999}]}]))
  for(const altered of [{...frame,jpeg_base64:'AAAAAA=='},{...frame,timestamp_ms:5000},{...frame,source_pts:8,timestamp_ms:8000}]) {
    await assert.rejects(parseFrameRanking({...response,rows:[{...response.rows[0],frame:altered}]},snapshot,'鱼柳',candidates))
  }
  await assert.rejects(parseFrameRanking({...response,source_hash:'other'},snapshot,'鱼柳',candidates))
})
