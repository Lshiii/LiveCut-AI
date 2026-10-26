<script setup lang="ts">
import { computed } from 'vue'
import { diagnosticView, safeErrorCode } from './diagnostics'

const props = defineProps<{ diagnostic?: unknown; errorCode: string | null; attempt: number }>()
const code = computed(() => safeErrorCode(props.errorCode))
const view = computed(() => diagnosticView(props.diagnostic, props.attempt, props.errorCode))
</script>

<template>
  <div v-if="code" class="failure-diagnostic" role="note" aria-label="失败诊断">
    <template v-if="view">
      <p class="failure-title"><span>{{ view.category }}</span><strong>{{ view.title }}</strong></p>
      <ul v-if="view.details.length" class="failure-details"><li v-for="detail in view.details" :key="detail">{{ detail }}</li></ul>
      <p class="failure-suggestion">{{ view.suggestion }}</p>
    </template>
    <p v-else class="failure-legacy">此记录没有详细诊断，可结合错误码检查执行记录。</p>
    <code>{{ code }}</code>
  </div>
</template>

<style scoped>
.failure-diagnostic { max-width: 100%; margin-top: 10px; padding: 11px 12px; background: #fff8f1; border-left: 2px solid #deb08f; border-radius: 3px; color: #85684f; overflow-wrap: anywhere; }
.failure-diagnostic p { margin: 0; font-size: 11px; line-height: 1.7; color: #85684f; }
.failure-diagnostic .failure-title { display: flex; align-items: baseline; flex-wrap: wrap; gap: 6px 8px; }
.failure-title > span { color: #a08063; font-size: 10px; }
.failure-title > strong { font-weight: 500; }
.failure-diagnostic .failure-suggestion { margin-top: 6px; }
.failure-details { display: flex; flex-wrap: wrap; gap: 2px 12px; padding: 0; margin: 5px 0 0; list-style: none; font-size: 10px; line-height: 1.7; color: #9b8066; }
.failure-details > li { display: block; position: static; padding: 0; }
.failure-details > li::before { display: none; }
.failure-diagnostic code { display: block; margin-top: 7px; font-size: 9px; line-height: 1.6; color: #a38267; overflow-wrap: anywhere; }
</style>
