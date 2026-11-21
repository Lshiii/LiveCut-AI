<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { normalizeVideoAlias, videoAliasLimit } from './videoAliases'

const props = defineProps<{ original: string; alias: string; temporary: boolean }>()
const emit = defineEmits<{ save: [name: string] }>()
const dialog = ref<HTMLDialogElement>(), input = ref<HTMLInputElement>(), trigger = ref<HTMLButtonElement>()
const draft = ref('')
const normalized = computed(() => normalizeVideoAlias(draft.value))

async function open() {
  draft.value = props.alias
  if (!dialog.value?.open) dialog.value?.showModal()
  await nextTick()
  input.value?.focus()
}
function close() {
  dialog.value?.close()
  trigger.value?.focus()
}
function apply(name: string | null) {
  if (name === null) return
  emit('save', name); close()
}
onBeforeUnmount(() => dialog.value?.close())
</script>

<template>
  <div class="video-identity">
    <div class="identity-title">
      <h1 :title="alias || original">{{ alias || original }}</h1>
      <button ref="trigger" class="alias-trigger" type="button" aria-label="编辑视频备注名" title="编辑视频备注名" @click="open">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="m15 5 4 4M4 20l4-1 12-12a2.8 2.8 0 0 0-4-4L4 15z"/></svg><span>备注</span>
      </button>
    </div>
    <p v-if="alias" class="original-name" :title="original">原文件 · {{ original }}</p>
    <p v-if="temporary" class="alias-note" role="status">浏览器存储受限，本次更改仅在当前页面有效。</p>
    <dialog ref="dialog" class="alias-dialog" aria-label="视频备注名" @cancel.stop.prevent="close" @keydown.esc.stop.prevent="close">
      <form @submit.prevent="apply(normalized)">
        <h2>视频备注名</h2>
        <p class="alias-help">备注保存在此浏览器，用于辨认和搜索素材。</p>
        <label>备注名<input ref="input" v-model="draft" :maxlength="videoAliasLimit" placeholder="例如：Qwen3 直播回放" autocomplete="off"></label>
        <p class="alias-original"><span>原文件名</span>{{ original }}</p>
        <div class="alias-actions">
          <button v-if="alias" type="button" class="alias-clear" @click="apply('')">清除备注</button>
          <button type="button" @click="close">取消</button>
          <button type="submit" class="alias-save" :disabled="normalized === null || (normalized === alias && !temporary)">保存备注</button>
        </div>
      </form>
    </dialog>
  </div>
</template>

<style scoped>
.identity-title { display: flex; align-items: center; gap: 8px; min-width: 0; }
.identity-title h1 { min-width: 0; margin: 0; }
.alias-trigger { display: inline-flex; align-items: center; gap: 3px; flex: none; padding: 3px 5px; min-height: 26px; color: #59636a; background: none; border: 0; font-size: 12px; }
.alias-trigger:hover { background: #f3efea; }
.original-name, .alias-note { margin: 0; color: #58626a; font-size: 12px; line-height: 1.4; }
.original-name { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.alias-note { color: #925021; }
.alias-dialog { position: fixed; inset: 0; box-sizing: border-box; width: min(390px, calc(100vw - 32px)); margin: auto; padding: 20px; border: 1px solid #dedbd4; border-radius: 12px; color: #29313a; background: white; box-shadow: 0 16px 60px #171a1b29; }
.alias-dialog::backdrop { background: #25292e55; }
.alias-dialog form { display: grid; gap: 12px; }
.alias-dialog h2 { margin: 0; font-size: 18px; line-height: 1.4; }
.alias-help, .alias-original { margin: 0; color: #546069; font-size: 13px; line-height: 1.55; overflow-wrap: anywhere; }
.alias-dialog label { display: grid; gap: 4px; font-size: 14px; font-weight: 600; }
.alias-dialog input { box-sizing: border-box; min-width: 0; width: 100%; min-height: 38px; padding: 7px 9px; border: 1px solid #cbc7c1; border-radius: 5px; font: inherit; }
.alias-original span { display: block; font-weight: 600; }
.alias-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 7px; }
.alias-actions button { min-height: 36px; padding: 6px 10px; border: 1px solid #d6d2ce; border-radius: 5px; background: white; color: #38444b; }
.alias-actions .alias-save { color: white; background: #a84628; border-color: #a84628; }
.alias-actions .alias-clear { margin-right: auto; padding-inline: 0; border: 0; color: #784735; }
.alias-actions button:disabled { opacity: .5; }
@media(max-width: 700px) { .identity-title { gap: 3px; } .alias-trigger span { display: none; } }
</style>
