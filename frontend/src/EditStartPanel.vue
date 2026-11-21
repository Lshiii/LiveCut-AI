<script setup lang="ts">
defineProps<{ disabled: boolean }>()
const emit = defineEmits<{ describe: []; range: []; resume: [] }>()
</script>

<template>
  <section class="edit-start" aria-label="开始剪辑">
    <header><h2>开始剪辑</h2><button class="text-button" :disabled="disabled" @click="emit('resume')">继续剪辑 →</button></header>
    <button class="edit-path" :disabled="disabled" @click="emit('describe')">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5h16v11H9l-5 4zM8 9h8m-8 3h5"/></svg>
      <span><strong>描述需求找片段</strong><small>写下想保留的内容，让 AI 找候选</small></span><i aria-hidden="true">›</i>
    </button>
    <button class="edit-path" :disabled="disabled" @click="emit('range')">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 4H4v16h4m8-16h4v16h-4M8 9h8m-8 5h5"/></svg>
      <span><strong>按原句选片段</strong><small>自己选起句和止句，保留中间原音画</small></span><i aria-hidden="true">›</i>
    </button>
  </section>
</template>

<style scoped>
.edit-start { min-width:0; padding:16px; color:var(--workspace-text); border-bottom:1px solid var(--workspace-border); }
header { display:flex; align-items:center; justify-content:space-between; gap:8px; margin-bottom:10px; }
h2 { font-size:16px; font-weight:650; }
.edit-path { display:flex; align-items:center; gap:10px; width:100%; min-height:61px; padding:9px 11px; text-align:left; border:1px solid var(--workspace-border); border-radius:7px; background:var(--studio-raised); color:var(--workspace-text); }
.edit-path + .edit-path { margin-top:7px; }
.edit-path:hover,.edit-path:focus-visible { border-color:var(--workspace-accent); background:var(--workspace-accent-soft); }
.edit-path svg { width:23px; height:23px; fill:none; stroke:var(--workspace-accent); stroke-width:1.6; stroke-linecap:round; stroke-linejoin:round; flex:none; }
.edit-path span { flex:1; min-width:0; }
.edit-path strong,.edit-path small { display:block; }
.edit-path strong { font-size:14px; }
.edit-path small { margin-top:3px; color:var(--workspace-muted); font-size:12px; }
.edit-path i { color:var(--workspace-muted); font:20px/1 sans-serif; }
@media(max-width:760px) { .edit-start { padding:12px; border:1px solid var(--workspace-border); border-radius:9px; background:var(--workspace-surface); } }
</style>
