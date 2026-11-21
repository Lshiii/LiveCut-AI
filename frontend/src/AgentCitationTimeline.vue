<script setup lang="ts">
import { computed } from 'vue'
import type { AgentCitation } from './videoAgent'
import { topicTime } from './topics'

const props = defineProps<{
  durationMs: number | null
  currentMs: number | null
  citations: ReadonlyArray<Pick<AgentCitation, 'utterance_id' | 'start_ms' | 'end_ms'>>
  selectedId?: string | null
}>()

const duration = computed(() => typeof props.durationMs === 'number' && Number.isSafeInteger(props.durationMs)
  && props.durationMs > 0 ? props.durationMs : null)
const current = computed(() => duration.value !== null && typeof props.currentMs === 'number'
  && Number.isFinite(props.currentMs) && props.currentMs >= 0 && props.currentMs <= duration.value ? props.currentMs : null)
const playhead = computed(() => current.value !== null && duration.value !== null ? current.value / duration.value * 100 : null)
const ranges = computed(() => {
  const total = duration.value
  if (total === null) return []
  return props.citations.flatMap((citation, index) => {
    if (!Number.isSafeInteger(citation.start_ms) || !Number.isSafeInteger(citation.end_ms)
      || citation.start_ms < 0 || citation.end_ms <= citation.start_ms || citation.end_ms > total) return []
    return [{ key: `${citation.utterance_id}:${index}`, id: citation.utterance_id,
      left: citation.start_ms / total * 100, width: (citation.end_ms - citation.start_ms) / total * 100 }]
  })
})
const ticks = [0, 25, 50, 75, 100]
const axisTime = (value: number) => topicTime(value).split('.')[0]
</script>

<template>
  <figure class="citation-timeline agent-citation-timeline" aria-label="视频引用时间轴" :data-duration-ms="duration ?? undefined" :data-current-ms="current ?? undefined">
    <figcaption class="citation-timeline-readout">
      <span>当前 <strong>{{ current === null ? '—' : topicTime(current) }}</strong></span>
      <span class="citation-timeline-total">/ 总长 <strong>{{ duration === null ? '—' : topicTime(duration) }}</strong></span>
    </figcaption>
    <div class="citation-timeline-graphic" :class="{ unavailable: duration === null }" aria-hidden="true">
      <span v-for="tick in ticks" :key="tick" class="citation-timeline-tick" :class="{ end: tick === 100 }" :style="{ left: `${tick}%` }"></span>
      <div class="citation-timeline-track">
        <span v-if="playhead !== null" class="citation-timeline-progress" :style="{ width: `${playhead}%` }"></span>
        <span v-for="range in ranges" :key="range.key" class="citation-timeline-range" :class="{ selected: range.id === selectedId }" :data-utterance-id="range.id"
          :style="{ left: `${range.left}%`, width: `${range.width}%` }"></span>
      </div>
      <span v-if="playhead !== null" class="citation-timeline-playhead" :style="{ left: `${playhead}%` }"></span>
    </div>
    <div class="citation-timeline-axis" aria-hidden="true">
      <span>{{ duration === null ? '—' : axisTime(0) }}</span>
      <span>{{ duration === null ? '—' : axisTime(duration / 2) }}</span>
      <span>{{ duration === null ? '—' : axisTime(duration) }}</span>
    </div>
  </figure>
</template>

<style scoped>
.citation-timeline {
  margin: 0;
  min-width: 0;
  padding: 8px 12px;
  border: 1px solid var(--workspace-border);
  border-radius: 8px;
  background: var(--studio-well);
  color: var(--workspace-muted);
  font-size: 12px;
  line-height: 1.6;
}
.citation-timeline-readout { display: flex; flex-wrap: wrap; align-items: baseline; gap: 0 8px; }
.citation-timeline-readout > span { white-space: nowrap; }
.citation-timeline-readout strong { margin-left: 4px; color: var(--workspace-text); font-weight: 400; }
.citation-timeline-readout strong, .citation-timeline-axis { font-family: ui-monospace, SFMono-Regular, Consolas, 'Liberation Mono', monospace; font-variant-numeric: tabular-nums; }
.citation-timeline-total { margin-left: auto; }
.citation-timeline-graphic { position: relative; height: 24px; margin-top: 4px; overflow: hidden; pointer-events: none; user-select: none; }
.citation-timeline-tick { position: absolute; top: 2px; bottom: 2px; width: 1px; background: var(--workspace-border); opacity: .65; }
.citation-timeline-tick.end { transform: translateX(-1px); }
.citation-timeline-track { position: absolute; top: 6px; right: 0; left: 0; height: 12px; overflow: hidden; border-block: 1px solid var(--workspace-border); background: var(--workspace-video); }
.citation-timeline-progress { position: absolute; inset: 0 auto 0 0; background: var(--workspace-accent-soft); }
.citation-timeline-range { position: absolute; top: 2px; height: 6px; border-radius: 2px; color: color-mix(in srgb, var(--workspace-accent) 45%, var(--studio-raised)); background: currentColor; box-shadow: 0 0 0 .5px currentColor; }
.citation-timeline-range.selected { color: var(--workspace-accent); }
.citation-timeline-playhead { position: absolute; top: 2px; bottom: 2px; width: 1px; background: var(--workspace-text); transform: translateX(-.5px); }
.citation-timeline-graphic.unavailable { opacity: .45; }
.citation-timeline-axis { display: flex; justify-content: space-between; gap: 8px; color: var(--workspace-muted); font-size: 12px; line-height: 1.4; }
</style>
