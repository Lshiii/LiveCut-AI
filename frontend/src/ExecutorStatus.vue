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
      <div class="executor-summary"><div class="executor-heading"><span class="executor-dot" :class="{ available: state.status === 'fresh' && snapshot && snapshot.online_workers > 0 }" aria-hidden="true"></span><h2>执行服务</h2></div><p class="executor-title">{{ title }}</p></div>
      <div v-if="snapshot" class="executor-capacity"><span>空闲 <b>{{ snapshot.available_slots }}</b></span><span>在线容量 <b>{{ snapshot.total_slots }}</b></span></div>
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
.executor-status { min-width: 0; margin: 0; padding: 20px; background: var(--workspace-surface, #fff); border: 1px solid var(--workspace-border, #e4e0d8); border-radius: 12px; color: var(--workspace-text, #292621); }
.executor-overview, .executor-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; min-width: 0; }
.executor-overview { justify-content: space-between; gap: 16px 24px; }
.executor-summary { min-width: 0; }
.executor-heading h2 { margin: 0; color: var(--workspace-muted, #675f56); font-size: 13px; font-weight: 500; line-height: 20px; }
.executor-title { margin: 4px 0 0; font-size: 15px; line-height: 24px; overflow-wrap: anywhere; }
.executor-dot { height: 6px; width: 6px; flex-shrink: 0; border-radius: 50%; background: #8b8379; }
.executor-dot.available { background: #39704d; }
.executor-capacity { display: flex; gap: 16px; color: var(--workspace-muted, #675f56); font-size: 12px; line-height: 20px; font-variant-numeric: tabular-nums; }
.executor-capacity > span { display: flex; flex-direction: column; gap: 4px; }
.executor-capacity b { color: var(--workspace-text, #292621); font-size: 18px; font-weight: 500; line-height: 24px; }
.executor-hint { margin-top: 16px; font-size: 13px; line-height: 24px; color: var(--workspace-muted, #675f56); overflow-wrap: anywhere; }
.executor-hint.warning { padding: 8px 12px; border-left: 2px solid var(--workspace-accent, #a94424); border-radius: 0 4px 4px 0; color: #874023; background: var(--workspace-accent-soft, #f9ede7); }
.executor-details { margin-top: 16px; border-top: 1px solid var(--workspace-border, #e4e0d8); padding-top: 12px; font-size: 13px; line-height: 20px; }
.executor-details summary { cursor: pointer; padding: 4px 0; line-height: 24px; }
.executor-details summary:focus-visible { outline: 2px solid var(--workspace-accent, #a94424); outline-offset: 4px; border-radius: 4px; }
.executor-details summary > span { color: var(--workspace-muted, #675f56); margin-left: 12px; font-size: 12px; overflow-wrap: anywhere; }
.executor-resources { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.executor-resources > span { background: var(--workspace-canvas, #f4f3ef); border-radius: 4px; padding: 8px 12px; color: var(--workspace-muted, #675f56); }
.executor-resources b { color: var(--workspace-text, #292621); font-weight: 500; margin-left: 8px; font-variant-numeric: tabular-nums; }
.executor-footnote { margin-top: 12px; color: var(--workspace-muted, #675f56); font-size: 12px; line-height: 20px; }
.executor-workers { padding: 0; margin: 12px 0 0; list-style: none; }
.executor-workers li { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 4px 12px; padding: 8px 0; border-top: 1px solid var(--workspace-border, #e4e0d8); font-size: 12px; line-height: 20px; }
.executor-workers li > span { overflow-wrap: anywhere; min-width: 0; }
.executor-workers li > span:last-child { color: var(--workspace-muted, #675f56); font-variant-numeric: tabular-nums; }
@media (max-width: 650px) {
  .executor-status { padding: 16px; }
  .executor-details summary > span { display: block; margin-left: 0; }
}
</style>
