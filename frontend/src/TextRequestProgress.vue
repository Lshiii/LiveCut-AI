<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { advanceTextProgress, textProgressCopy, textProgressSignature, type TextProgress } from './textProgress'
const props = defineProps<{ progress?: TextProgress; terminal?: boolean }>()
const now = ref(performance.now()), anchoredAt = ref(now.value)
let timer: ReturnType<typeof setInterval> | undefined
watch(() => textProgressSignature(props.progress), () => {
  anchoredAt.value = performance.now(); now.value = anchoredAt.value
}, { immediate: true })
const copy = computed(() => props.progress ? textProgressCopy(advanceTextProgress(props.progress, anchoredAt.value, now.value)) : null)
onMounted(() => { timer = setInterval(() => { now.value = performance.now() }, 1000) })
onBeforeUnmount(() => { if (timer !== undefined) clearInterval(timer) })
</script>

<template>
  <div v-if="progress && copy && (!terminal || progress.state === 'EXHAUSTED')" class="text-recovery" :class="{ exhausted: progress.state === 'EXHAUSTED' }" role="status" aria-live="polite" aria-label="文本请求恢复进度">
    <strong>{{ copy.title }}</strong><p>{{ copy.detail }}</p>
  </div>
</template>

<style scoped>
.text-recovery { margin: 9px 0; border-left: 3px solid #96a981; padding: 9px 11px; background: #f3f6ef; color: #56684a; line-height: 1.7; overflow-wrap: anywhere; min-width: 0; }
.text-recovery strong { display: block; font-size: 12px; font-weight: 600; }
.text-recovery p { margin: 3px 0 0; font-size: 11px; }
.text-recovery.exhausted { border-color: #dc9f70; background: #fcf4ed; color: #936541; }
</style>
