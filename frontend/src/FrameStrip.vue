<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { filmstripCellCount, filmstripTimes, readFilmstrip } from './filmstrip'
import type { SequencePreviewPart } from './sequencePreview'

const props = defineProps<{ url: string; parts: readonly SequencePreviewPart[]; fromMs: number; toMs: number; sourceAspect: number }>()
const emit = defineEmits<{ cover: [url: string, image: string] }>()
const el = ref<HTMLElement | null>(null), size = ref({ width: 0, height: 0 }), visible = ref(false), pageVisible = ref(!document.hidden)
const count = computed(() => filmstripCellCount(size.value.width, size.value.height, props.sourceAspect))
const times = computed(() => filmstripTimes(props.parts, props.fromMs, props.toMs, count.value))
const images = ref<string[]>([])
let observer: IntersectionObserver | undefined, resize: ResizeObserver | undefined
function visibility() { pageVisible.value = !document.hidden }
watch([() => props.url, () => times.value.join(','), visible, pageVisible], ([url, signature, inView, shown], _, cleanup) => {
  images.value = []
  if (typeof url !== 'string' || !url || !signature || !inView || !shown) return
  const abort = new AbortController()
  const samples = [...times.value]
  const timer = window.setTimeout(() => {
    void readFilmstrip(url, samples, abort.signal, (i, image) => {
      if (abort.signal.aborted) return
      images.value[i] = image
      if (i === 0) emit('cover', url, image)
    }).catch(() => { /* A missing thumbnail leaves the timeline fully interactive. */ })
  }, 230)
  cleanup(() => { clearTimeout(timer); abort.abort() })
}, { flush: 'sync' })
onMounted(() => {
  if (!el.value) return
  resize = new ResizeObserver(([entry]) => {
    if (entry) size.value = { width: entry.contentRect.width, height: entry.contentRect.height }
  })
  resize.observe(el.value)
  observer = new IntersectionObserver(entries => { visible.value = !!entries[0]?.isIntersecting }); observer.observe(el.value)
  document.addEventListener('visibilitychange', visibility)
})
onBeforeUnmount(() => { resize?.disconnect(); observer?.disconnect(); document.removeEventListener('visibilitychange', visibility) })
</script>
<template>
  <div ref="el" class="frame-strip" aria-hidden="true">
    <span v-for="(_, index) in times" :key="index"><img v-if="images[index]" :src="images[index]" alt="" draggable="false"></span>
  </div>
</template>
<style scoped>
.frame-strip { display:flex; overflow:hidden; width:100%; height:100%; pointer-events:none; }
.frame-strip span { flex:1 1 0; min-width:0; background:#233138; }
.frame-strip span + span { border-left:1px solid #101417; }
.frame-strip img { display:block; width:100%; height:100%; object-fit:cover; }
</style>
