<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AgentAnswer from './AgentAnswer.vue'
import { AgentReadScope, type AgentSessionIdentity, type AgentTurn } from './videoAgent'
import { agentNoteErrorCopy, agentNoteOwner, copyAgentNoteMarkdown, downloadAgentNoteZip, readAgentNote, type AgentNotePreview } from './agentNote'

const props = defineProps<{ session: AgentSessionIdentity; turn: AgentTurn; expanded: boolean; active: boolean }>()
const emit = defineEmits<{ toggle: []; close: [] }>()
const note = ref<AgentNotePreview | null>(null), action = ref(''), error = ref(''), notice = ref('')
const scope = new AgentReadScope(); let alive = true
const contextKey = computed(() => JSON.stringify([props.session.id, props.session.job_id, props.session.video_id, props.session.source,
  props.turn.id, props.turn.status, props.turn.result]))
const scopes = { VIDEO: '来自视频', GENERAL: '通用知识补充', INSUFFICIENT: '视频证据不足' }
function invalidate() { scope.invalidate(); action.value = ''; error.value = ''; notice.value = ''; note.value = null }
function begin(value: string) {
  scope.invalidate(); const read = scope.begin(), key = contextKey.value
  action.value = value; error.value = ''; notice.value = ''
  return { ...read, current: () => alive && props.active && props.expanded && !document.hidden && key === contextKey.value && read.current() }
}
async function load() {
  if (!alive || !props.active || !props.expanded || document.hidden || action.value) return
  const read = begin('read')
  try { const value = await readAgentNote(agentNoteOwner(props.session, props.turn), read); if (read.current()) note.value = value }
  catch (value) { if (read.current()) error.value = agentNoteErrorCopy(value) }
  finally { if (read.current()) action.value = ''; read.release() }
}
async function copy() {
  if (!note.value || action.value || !props.active || document.hidden) return
  const value = note.value, read = begin('copy')
  try {
    await copyAgentNoteMarkdown(value, navigator.clipboard?.writeText.bind(navigator.clipboard), read.current)
    if (read.current()) notice.value = '文字笔记已复制；截图文件请下载含图 ZIP。'
  } catch (value) { if (read.current()) error.value = agentNoteErrorCopy(value) }
  finally { if (read.current()) action.value = ''; read.release() }
}
async function download() {
  if (!note.value || action.value || !props.active || document.hidden) return
  const value = note.value, read = begin('download')
  try {
    await downloadAgentNoteZip(agentNoteOwner(props.session, props.turn), value, read, (blob, filename) => {
      if (!read.current()) return
      const url = URL.createObjectURL(blob), link = document.createElement('a')
      link.href = url; link.download = filename; document.body.append(link)
      try { if (read.current()) link.click() } finally { link.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 1000) }
    })
    if (read.current()) notice.value = '已准备含图 ZIP；解压后打开 note.md，可离线查看引用截图。'
  } catch (value) { if (read.current()) error.value = agentNoteErrorCopy(value) }
  finally { if (read.current()) action.value = ''; read.release() }
}
function hide() { invalidate(); if (props.expanded) emit('close') }
function visibilityChanged() { if (document.hidden) hide() }
watch(() => props.expanded, open => { invalidate(); if (open && props.active && !document.hidden) void load() }, { flush: 'sync' })
watch(() => props.active, active => { if (!active) hide() }, { flush: 'sync' })
watch(contextKey, hide, { flush: 'sync' })
onMounted(() => { document.addEventListener('visibilitychange', visibilityChanged) })
onBeforeUnmount(() => { alive = false; scope.dispose(); document.removeEventListener('visibilitychange', visibilityChanged) })
</script>

<template>
  <section class="agent-note" aria-label="本轮学习笔记">
    <button type="button" class="text-button agent-note-toggle" :disabled="!active" :aria-expanded="expanded" :aria-controls="`agent-note-${turn.id}`" @click="emit('toggle')">{{ expanded ? '收起学习笔记' : '学习笔记' }} <span aria-hidden="true">{{ expanded ? '▴' : '▾' }}</span></button>
    <div v-if="expanded" :id="`agent-note-${turn.id}`" class="agent-note-body">
      <p class="agent-note-help">保存本轮问题、原回答与时间依据。直接整理已有结果，不新增模型请求；图片通过 ZIP 交付。</p>
      <p v-if="action === 'read'" role="status">正在核对本轮笔记与来源…</p>
      <p v-if="error" class="agent-note-error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
      <template v-if="note">
        <div class="agent-note-source"><strong>{{ scopes[note.answer_scope] }}</strong><span>{{ note.source_filename }}</span><small>原句 {{ note.citation_count }} 条 · 截图 {{ note.image_count }} 张</small></div>
        <div class="agent-note-actions"><button type="button" class="secondary" :disabled="!!action || !active" @click="copy">{{ action === 'copy' ? '正在复制…' : '复制文字笔记' }}</button><button type="button" class="secondary" :disabled="!!action || !active" @click="download">{{ action === 'download' ? '正在准备 ZIP…' : '下载含图 ZIP' }}</button></div>
        <div class="agent-note-preview" tabindex="0" aria-label="本轮学习笔记文字"><AgentAnswer :text="note.markdown" escaped-markdown /></div>
      </template>
      <button v-else-if="!action" type="button" class="secondary" :disabled="!active" @click="load">重新读取笔记</button>
    </div>
  </section>
</template>

<style scoped>
.agent-note{margin:12px 0;min-width:0;border-top:1px solid #e2e8f0;padding-top:9px}.agent-note-toggle{font-size:12px;color:#475569}.agent-note-body{min-width:0;margin-top:8px;padding:12px;border:1px solid #dce5ee;border-radius:8px;background:#f8fbfd;font-size:12px;line-height:1.7;overflow-wrap:anywhere}.agent-note-help{color:#64748b}.agent-note-error{color:#985c32}.agent-note-source{display:flex;flex-wrap:wrap;gap:4px 10px;margin:10px 0;color:#475569;min-width:0}.agent-note-source span{max-width:100%;overflow-wrap:anywhere}.agent-note-source small{flex-basis:100%;font-size:11px}.agent-note-actions{display:flex;flex-wrap:wrap;gap:8px;margin:10px 0}.agent-note-actions button{max-width:100%;font-size:12px}.agent-note-preview{max-height:320px;min-width:0;overflow:auto;padding:12px;border:1px solid #e2e8f0;border-radius:6px;background:white;overscroll-behavior:contain;font-size:13px;overflow-wrap:anywhere}.agent-note-preview :deep(.agent-answer-body){font-size:13px}.agent-note-preview:focus-visible{outline:2px solid #8ba8c7;outline-offset:2px}
</style>
