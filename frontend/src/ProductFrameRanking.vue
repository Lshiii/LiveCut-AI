<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { IntentSnapshot } from './intentClips.ts'
import { parseFrameRanking, rankingRequest, type FrameCandidate, type FrameRanking } from './productFrameRanking.ts'
import { topicTime } from './topics.ts'

const props = defineProps<{ snapshot: IntentSnapshot; candidates: FrameCandidate[]; label?: string; active?: boolean; canChoose?: boolean }>()
const emit = defineEmits<{ seek: [ms: number]; choose: [id: string] }>()
const labelInput = ref(props.label ?? ''), busy = ref(false), error = ref(''), ranking = ref<FrameRanking | null>(null)
const texts = computed(() => new Map(props.candidates.map(c => [c.id, c.text])))
let operation: AbortController | null = null
function reset() {
  operation?.abort()
  operation = null
  busy.value = false
  ranking.value = null
  error.value = ''
}
watch(() => props.label, value => { labelInput.value = value || '' })
watch(() => [props.snapshot.id, props.snapshot.plan_hash, props.snapshot.source.video_sha256, props.snapshot.source.transcript_hash,
  JSON.stringify(props.candidates), labelInput.value, props.active], reset, { flush: 'sync' })
onBeforeUnmount(reset)
async function rank() {
  if (props.active === false || busy.value || !labelInput.value.trim()) return
  reset()
  const controller = new AbortController()
  operation = controller
  busy.value = true
  const timeout = setTimeout(() => controller.abort(), 165000)
  try {
    const snapshot = props.snapshot, candidates = props.candidates, label = labelInput.value.trim()
    const body = rankingRequest(snapshot, label, candidates)
    const response = await fetch(`/api/clip-requests/${snapshot.id}/product-frames`, { method: 'POST', cache: 'no-store', signal: controller.signal,
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    const json = await response.json()
    if (!response.ok) throw new Error(json.error_code || String(response.status))
    const result = await parseFrameRanking(json, snapshot, label, candidates)
    if (operation === controller) ranking.value = result
  } catch (err) {
    if (operation !== controller) return
    const code = err instanceof Error ? err.message : ''
    error.value = code === 'PRODUCT_FRAME_MODEL_UNAVAILABLE' || code === 'PRODUCT_FRAME_HELPER_UNAVAILABLE'
      ? '本机商品画面模型尚未就绪。可继续按原句选段。' : code === 'PRODUCT_FRAME_BUSY'
      ? '另一个画面比对正在运行，请稍后再试。' : code === 'PRODUCT_FRAME_SOURCE_CHANGED'
      ? '剪辑方案或来源已经更新，请刷新候选。' : '画面比对未完成，请核对候选原句后重试。'
  } finally {
    clearTimeout(timeout)
    if (operation === controller) { busy.value = false; operation = null }
  }
}
</script>
<template>
  <details class="product-frames">
    <summary>按商品画面比较口播候选</summary>
    <label>要找的商品或展示画面<input v-model="labelInput" maxlength="80" placeholder="例如：主播举起鱼柳包装" /></label>
    <button class="secondary" type="button" :disabled="busy || active === false || !labelInput.trim()" @click="rank">{{ busy ? '正在比对原片画面…' : '按画面匹配度排序' }}</button>
    <p v-if="error" role="status">{{ error }}</p>
    <template v-if="ranking">
      <p class="help">按候选区间内最高的图文相似度排序。点击画面回看原片，报价和规格请核对口播。</p>
      <article v-for="(row, index) in ranking.rows" :key="row.id">
        <button v-if="row.frame" class="frame-review" type="button" @click="emit('seek', row.frame.timestamp_ms)">
          <img :src="'data:image/jpeg;base64,' + row.frame.jpeg_base64" :alt="`候选画面 ${index + 1}`" />
          <span>{{ topicTime(row.frame.timestamp_ms) }} · 相似度 {{ row.score?.toFixed(3) }}</span>
          <small>{{ row.sample_count }} 帧中最匹配</small>
        </button>
        <p v-else>此区间没有可核对的采样帧</p>
        <span>{{ texts.get(row.id) || row.id }}</span>
        <button v-if="canChoose && row.frame" type="button" class="text-button" @click="emit('choose', row.id)">选用这条备选</button>
      </article>
    </template>
  </details>
</template>
<style scoped>
.product-frames { margin: .65rem 0; }
summary { cursor: pointer; font-weight: 600; }
label { display: flex; flex-wrap: wrap; align-items: center; gap: .5rem; margin: .65rem 0; }
input { flex: 1; min-width: 9rem; }
article { display: flex; gap: .7rem; align-items: center; flex-wrap: wrap; border-top: 1px solid var(--line, #ddd); padding: .55rem 0; }
article > span { flex: 1; min-width: 8rem; }
.frame-review { display: grid; gap: .2rem; background: transparent; text-align: left; color: inherit; }
.frame-review img { max-width: 7rem; max-height: 5rem; object-fit: contain; }
</style>
