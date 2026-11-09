<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { visualEvidenceUrl, type AgentVisualFrame } from './agentVisualEvidence'
import type { AgentTurn } from './videoAgent'
import { topicTime } from './topics'
const props = defineProps<{ turn: AgentTurn; active: boolean; followupUnavailable?: string | null }>()
const emit = defineEmits<{ locate: [timestamp: number]; preview: [range: { start_ms: number; end_ms: number }]; followup: [frameId: string] }>()
const failed = ref<Record<string, boolean>>({}); let alive = true
const frames = computed(() => props.turn.result?.schema_version !== '1.0' ? props.turn.result?.visual_evidence ?? [] : [])
const citations = computed(() => props.turn.result?.visual_citations ?? [])
const observations = computed(() => props.turn.result?.tool_calls.filter(call => call.tool_name === 'inspect_frames' || call.tool_name === 'inspect_clip') ?? [])
function frame(id: string) { return frames.value.find(value => value.frame_id === id)! }
function imageKey(value: AgentVisualFrame) { return `${props.turn.id}:${value.frame_id}:${value.sha256}` }
function imageFailed(value: AgentVisualFrame) {
  if (alive && frames.value.some(item => imageKey(item) === imageKey(value))) failed.value = { ...failed.value, [imageKey(value)]: true }
}
function observedTimes(ids: string[]) { return ids.map(id => topicTime(frame(id).timestamp_ms)).join('、') }
onBeforeUnmount(() => { alive = false })
</script>

<template>
  <section v-if="turn.result?.schema_version === '1.1' || turn.result?.schema_version === '1.2'" class="agent-visual-evidence" aria-label="本轮画面依据">
    <h4>画面依据 · 引用 {{ citations.length }} 帧 / 本轮观察 {{ frames.length }} 帧</h4>
    <p class="visual-boundary">仅表示实际抽查的画面；不包含原始音轨，不等于连续观看或完整动作证明。原句依据单独列出。</p>
    <p v-if="turn.focus_timestamp_ms !== undefined" class="visual-boundary">用户附加位置 {{ topicTime(turn.focus_timestamp_ms) }} 是定位提示；下方截图有独立实际 PTS，像素不保证与浏览器暂停画面完全相同。</p>
    <p v-if="followupUnavailable" class="visual-boundary visual-followup-unavailable">{{ followupUnavailable }}</p>
    <div class="visual-cards"><article v-for="citation in citations" :key="`${turn.id}:${citation.frame_id}`" class="visual-card">
      <img v-if="!failed[imageKey(frame(citation.frame_id))]" :key="imageKey(frame(citation.frame_id))" :src="visualEvidenceUrl(turn.id, citation.frame_id)" :alt="`第 ${turn.sequence} 轮画面 ${citation.frame_id}，原视频 ${topicTime(frame(citation.frame_id).timestamp_ms)}`" loading="lazy" decoding="async" @error="imageFailed(frame(citation.frame_id))">
      <p v-else class="visual-image-error" role="status">截图暂不可用，文字回答仍保留。可按下方时间定位原视频。</p>
      <div class="visual-card-copy"><strong>{{ citation.frame_id }} · 原视频 {{ topicTime(frame(citation.frame_id).timestamp_ms) }}</strong><p>{{ citation.explanation }}</p><div class="visual-card-actions"><button class="text-button" :disabled="!active" @click="emit('locate', frame(citation.frame_id).timestamp_ms)">定位画面时间 ↗</button><button type="button" class="text-button" :disabled="!active || followupUnavailable !== null" :title="followupUnavailable ?? '将此画面的实际时间用于下一轮位置提示，发送后重新取帧。'" @click="emit('followup', citation.frame_id)">在此时间继续提问</button></div><small>画面时间来自源 PTS；画面不能作为原句剪辑锚点。继续提问只附加时间，发送后重新取帧。</small></div>
    </article></div>
    <details v-if="observations.length" class="visual-observations"><summary>画面观察范围 · {{ observations.length }} 步</summary><article v-for="call in observations" :key="call.step">
      <p v-if="call.tool_name === 'inspect_clip'"><strong>第 {{ call.step }} 步 · 短片抽样</strong> 请求 {{ topicTime(Number(call.arguments.start_ms)) }} → {{ topicTime(Number(call.arguments.end_ms)) }}，按 2fps 采样画面。</p>
      <p v-else><strong>第 {{ call.step }} 步 · {{ call.origin === 'USER_FOCUS' ? '用户附加位置触发取帧' : '指定时间看图' }}</strong> 请求 {{ (call.arguments.timestamps_ms as number[]).map(topicTime).join('、') }}。</p>
      <p v-if="call.origin">{{ call.origin === 'USER_FOCUS' ? '程序按用户定位提示执行；不是模型自主选择。' : '模型选择的后续工具。' }}</p>
      <p v-if="call.result_frame_ids?.length">实际观察 {{ call.result_frame_ids.length }} 帧：{{ observedTimes(call.result_frame_ids) }}。采样间隙的内容未由这些帧证明。</p><p v-else>该步没有返回可用帧，不能据此声称已观察整个区间。</p>
      <button v-if="call.tool_name === 'inspect_clip'" class="text-button" :disabled="!active" @click="emit('preview', {start_ms:Number(call.arguments.start_ms),end_ms:Number(call.arguments.end_ms)})">回看请求区间</button>
    </article></details>
  </section>
</template>

<style scoped>
.visual-card-actions{display:flex;flex-wrap:wrap;gap:6px 12px;margin:8px 0}.visual-card-actions button{max-width:100%;font-size:12px;line-height:1.7;text-align:left}
.agent-visual-evidence{margin:12px 0;padding:12px;border:1px solid #dbe4ed;border-radius:8px;background:#f8fbfd;color:#334155;min-width:0}.agent-visual-evidence h4{margin:0;font-size:13px}.visual-boundary,.visual-observations{font-size:12px;line-height:1.7}.visual-cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(240px,100%),1fr));gap:10px}.visual-card{min-width:0;background:white;border:1px solid #e2e8f0;border-radius:7px;overflow:hidden}.visual-card img{display:block;width:100%;max-height:260px;object-fit:contain;background:#e8eef2;min-height:80px}.visual-card-copy{padding:10px;overflow-wrap:anywhere;font-size:13px;line-height:1.8}.visual-card-copy p{white-space:pre-wrap;margin:8px 0}.visual-card-copy small{display:block;color:#64748b;font-size:11px}.visual-image-error{padding:18px;color:#985c32;font-size:12px;line-height:1.7}.visual-observations{margin-top:10px}.visual-observations summary{cursor:pointer}.visual-observations article{padding:8px 0;border-top:1px solid #e2e8f0;overflow-wrap:anywhere}.visual-observations p{margin:4px 0}
</style>
