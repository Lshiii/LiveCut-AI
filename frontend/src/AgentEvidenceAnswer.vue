<script setup lang="ts">
import { computed, ref } from 'vue'
import { evidenceLabels, evidenceWarnings } from './agentEvidenceAnswer'
import { visualEvidenceUrl } from './agentVisualEvidence'
import type { AgentCitation, AgentTurn } from './videoAgent'
import { topicTime } from './topics'
const props = defineProps<{ turn: AgentTurn; active: boolean }>()
const emit = defineEmits<{ locate: [timestamp: number]; citation: [source: AgentCitation] }>()
const blocks = computed(() => props.turn.result?.answer_blocks ?? [])
const failed = ref<Record<string, boolean>>({})
function frame(id: string) { return props.turn.result!.visual_evidence!.find(value => value.frame_id === id)! }
function source(id: string) { return props.turn.result!.citations.find(value => value.utterance_id === id)! }
function imageKey(id: string) { return `${props.turn.id}:${id}:${frame(id).sha256}` }
function imageFailed(event: Event, id: string) { if (props.active && (event.currentTarget as HTMLImageElement).getAttribute('data-evidence-owner') === imageKey(id)) failed.value = { ...failed.value, [imageKey(id)]: true } }
</script>
<template>
  <section class="agent-evidence-answer" aria-label="分项证据回答">
    <p class="evidence-validation-boundary">分项区分原句、画面、采样、推断与缺口。来源与结构核对不代表每项语义已被规则证明。</p>
    <article v-for="block in blocks" :key="block.block_id" class="evidence-answer-block" :data-evidence-kind="block.kind">
      <h4>{{ evidenceLabels[block.kind] }} · {{ block.block_id }}</h4><p class="evidence-block-text">{{ block.text }}</p>
      <p v-if="evidenceWarnings[block.kind]" class="evidence-block-warning">{{ evidenceWarnings[block.kind] }}</p>
      <div v-if="block.utterance_ids.length" class="evidence-block-speech"><button v-for="id in block.utterance_ids" :key="id" type="button" class="text-button" :disabled="!active" @click="emit('citation', source(id))">原句 {{ id }} · {{ topicTime(source(id).start_ms) }} → {{ topicTime(source(id).end_ms) }}</button></div>
      <div v-if="block.frame_ids.length && block.kind !== 'SAMPLED_CHANGE'" class="evidence-block-speech"><button v-for="id in block.frame_ids" :key="id" type="button" class="text-button" :disabled="!active" @click="emit('locate', frame(id).timestamp_ms)">画面 {{ id }} · 实际 {{ topicTime(frame(id).timestamp_ms) }}</button></div>
      <div v-if="block.kind === 'SAMPLED_CHANGE'" class="evidence-block-frames"><figure v-for="(id, index) in block.frame_ids" :key="imageKey(id)">
        <img v-if="!failed[imageKey(id)]" :data-evidence-owner="imageKey(id)" :src="active ? visualEvidenceUrl(turn.id, id) : undefined" :alt="`${block.kind === 'SAMPLED_CHANGE' ? index === 0 ? '前侧采样' : '后侧采样' : '画面依据'} ${id} · ${topicTime(frame(id).timestamp_ms)}`" loading="lazy" @error="imageFailed($event, id)"><p v-else class="evidence-block-warning">截图暂不可用；证据ID和时间保留，请核对原视频。</p>
        <figcaption><strong v-if="block.kind === 'SAMPLED_CHANGE'">{{ index === 0 ? '前侧采样' : '后侧采样' }} · </strong>{{ id }} · 实际 {{ topicTime(frame(id).timestamp_ms) }}</figcaption><button type="button" class="text-button" :disabled="!active" @click="emit('locate', frame(id).timestamp_ms)">定位 {{ id }} 画面时间</button>
      </figure></div>
    </article>
  </section>
</template>
<style scoped>
.agent-evidence-answer{min-width:0}.evidence-validation-boundary,.evidence-block-warning{font-size:12px;line-height:1.8;color:#7c684d;overflow-wrap:anywhere}.evidence-answer-block{padding:12px;margin:12px 0;border:1px solid #dbe4ed;border-radius:8px;background:#fafcfd;min-width:0}.evidence-answer-block h4{font-size:13px;margin:0 0 8px;color:#475569}.evidence-block-text{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.85;margin:0}.evidence-block-speech{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}.evidence-answer-block button{max-width:100%;white-space:normal;font-size:12px;text-align:left;overflow-wrap:anywhere}.evidence-block-frames{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(150px,100%),1fr));gap:10px;margin-top:10px}.evidence-block-frames figure{min-width:0;margin:0}.evidence-block-frames img{display:block;width:100%;max-height:150px;object-fit:contain;background:#e8eef2}.evidence-block-frames figcaption{font-size:12px;line-height:1.8;overflow-wrap:anywhere;margin-top:5px}
</style>
