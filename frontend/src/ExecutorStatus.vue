<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, type AnalysisJob } from './api'
import { ExecutorMonitor, executorQueueHint, type ExecutorState } from './executors'

// Workbench keys this component by the selected job. Unmount aborts the old
// request and its timers before another job can adopt an availability report.
const props = defineProps<{ job: AnalysisJob | null }>()
const state = ref<ExecutorState>({ status: 'loading', snapshot: null })
const hint = computed(() => executorQueueHint(props.job, state.value))
const snapshot = computed(() => state.value.snapshot)
const resourceNames = { FFMPEG: '媒体处理', ASR: '语音转写', TEXT: '文本分析' }
const title = computed(() => {
  if (state.value.status === 'loading') return '正在检查执行服务'
  if (state.value.status === 'unavailable') return '执行器状态暂不可用'
  if (state.value.status === 'stale') return '执行器状态已过期，等待刷新'
  return snapshot.value?.online_workers ? `${snapshot.value.online_workers} 个执行器在线` : '暂无在线执行器'
})
const monitor = new ExecutorMonitor({
  fetchSnapshot: (jobId, signal) => api<unknown>(`/api/executors${jobId ? `?job_id=${encodeURIComponent(jobId)}` : ''}`, { signal }),
  onState: value => { state.value = value },
})
function checkedAt(value: string) { return new Date(value).toLocaleTimeString('zh-CN', { hour12: false }) }
onMounted(() => monitor.select(props.job?.id ?? null))
onUnmounted(() => monitor.dispose())
</script>

<template>
  <section class="executor-status" aria-label="执行服务">
    <div class="executor-overview" role="status" aria-live="polite">
      <div class="executor-heading"><span class="executor-dot" :class="{ available: state.status === 'fresh' && snapshot && snapshot.online_workers > 0 }"></span><h2>执行服务</h2><span>{{ title }}</span></div>
      <span v-if="snapshot" class="executor-capacity">空闲 {{ snapshot.available_slots }} / 在线容量 {{ snapshot.total_slots }}</span>
    </div>
    <p v-if="hint" class="executor-hint" :class="{ warning: hint.warning }" role="status">{{ hint.text }}</p>
    <p v-else-if="state.status === 'unavailable' || state.status === 'stale'" class="executor-hint">正在重新检查执行器，不影响已生成结果的审核和回看。</p>
    <details v-if="snapshot" class="executor-details">
      <summary>容量详情<span>最近检查 {{ checkedAt(snapshot.generated_at) }}<template v-if="job"> · 当前任务范围</template></span></summary>
      <div class="executor-resources"><span v-for="resource in snapshot.resources" :key="resource.name">{{ resourceNames[resource.name] }} <b>{{ resource.in_use }} / {{ resource.limit }}</b></span></div>
      <p class="executor-footnote">资源数量表示执行中的处理阶段；暂时离线的执行器，其未结束任务仍可能占用资源。</p>
      <ul v-if="snapshot.workers.length" class="executor-workers"><li v-for="worker in snapshot.workers.slice(0, 8)" :key="worker.worker_id"><span>{{ worker.worker_id }}</span><span>{{ worker.status === 'ONLINE' ? '在线' : '离线' }} · 使用 {{ worker.busy_slots }} / {{ worker.slots }}</span></li></ul>
      <p v-if="snapshot.workers.length > 8" class="executor-footnote">另有 {{ snapshot.workers.length - 8 }} 个执行器记录。</p>
    </details>
  </section>
</template>

<style scoped>
.executor-status { margin: 0 0 24px; padding: 16px 20px; background: #fff; border: 1px solid #e1e7db; border-radius: 8px; color: #65745d; min-width: 0; }
.executor-overview, .executor-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 9px; min-width: 0; }
.executor-overview { justify-content: space-between; gap: 10px 20px; font-size: 11px; }
.executor-heading h2 { font-size: 12px; color: #52664a; font-weight: 600; }
.executor-heading > span:last-child { overflow-wrap: anywhere; }
.executor-dot { height: 6px; width: 6px; flex-shrink: 0; border-radius: 50%; background: #a5aaa1; }
.executor-dot.available { background: #74947f; }
.executor-capacity { font-variant-numeric: tabular-nums; color: #718469; }
.executor-hint { margin-top: 11px; font-size: 11px; line-height: 1.8; overflow-wrap: anywhere; }
.executor-hint.warning { color: #a4753c; }
.executor-details { margin-top: 12px; border-top: 1px solid #edf0e9; padding-top: 11px; font-size: 11px; }
.executor-details summary { cursor: pointer; line-height: 1.8; }
.executor-details summary > span { color: #829078; margin-left: 12px; overflow-wrap: anywhere; }
.executor-resources { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.executor-resources > span { background: #f5f8f1; border-radius: 4px; padding: 6px 9px; }
.executor-resources b { font-weight: 500; margin-left: 6px; font-variant-numeric: tabular-nums; }
.executor-footnote { font-size: 10px; margin-top: 10px; color: #829078; line-height: 1.8; }
.executor-workers { padding: 0; margin: 10px 0 0; list-style: none; }
.executor-workers li { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 4px 12px; padding: 5px 0; }
.executor-workers li > span { overflow-wrap: anywhere; min-width: 0; }
@media (max-width: 650px) { .executor-status { padding: 15px 17px; margin-bottom: 20px; } .executor-details summary > span { display: block; margin-left: 0; } }
</style>
