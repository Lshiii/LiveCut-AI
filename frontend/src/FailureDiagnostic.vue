<script setup lang="ts">
import { computed } from 'vue'
import { diagnosticView, safeErrorCode } from './diagnostics'
import { textRecoveryFailureCopy } from './textProgress'

const props = defineProps<{ diagnostic?: unknown; errorCode: string | null; attempt: number }>()
const code = computed(() => safeErrorCode(props.errorCode))
const view = computed(() => diagnosticView(props.diagnostic, props.attempt, props.errorCode))
const recoveryFailure = computed(() => textRecoveryFailureCopy(props.errorCode))
</script>

<template>
  <div v-if="code" class="failure-diagnostic" role="note" aria-label="失败诊断">
    <template v-if="recoveryFailure"><p><strong>{{ recoveryFailure.title }}</strong></p><p class="failure-suggestion">{{ recoveryFailure.detail }}</p></template>
    <template v-else-if="view">
      <p class="failure-title"><span>{{ view.category }}</span><strong>{{ view.title }}</strong></p>
      <ul v-if="view.details.length" class="failure-details"><li v-for="detail in view.details" :key="detail">{{ detail }}</li></ul>
      <p class="failure-suggestion">{{ view.suggestion }}</p>
    </template>
    <p v-else class="failure-legacy">此记录没有详细诊断，可结合错误码检查执行记录。</p>
    <code>{{ code }}</code>
  </div>
</template>

<style scoped>
.failure-diagnostic { max-width: 100%; margin-top: 10px; padding: 11px 12px; background: color-mix(in srgb, var(--studio-warning) 12%, var(--workspace-surface)); border-left: 2px solid color-mix(in srgb, var(--studio-warning) 55%, var(--workspace-border)); border-radius: 3px; color: var(--studio-warning); overflow-wrap: anywhere; }
.failure-diagnostic p { margin: 0; font-size: 11px; line-height: 1.7; color: var(--studio-warning); }
.failure-diagnostic .failure-title { display: flex; align-items: baseline; flex-wrap: wrap; gap: 6px 8px; }
.failure-title > span { color: var(--workspace-muted); font-size: 10px; }
.failure-title > strong { font-weight: 500; }
.failure-diagnostic .failure-suggestion { margin-top: 6px; }
.failure-details { display: flex; flex-wrap: wrap; gap: 2px 12px; padding: 0; margin: 5px 0 0; list-style: none; font-size: 10px; line-height: 1.7; color: var(--workspace-muted); }
.failure-details > li { display: block; position: static; padding: 0; }
.failure-details > li::before { display: none; }
.failure-diagnostic code { display: block; margin-top: 7px; font-size: 9px; line-height: 1.6; color: var(--workspace-muted); overflow-wrap: anywhere; }
</style>
