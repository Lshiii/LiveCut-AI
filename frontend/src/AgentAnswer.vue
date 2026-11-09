<script setup lang="ts">
import { computed, createTextVNode, defineComponent, h, type PropType } from 'vue'
import { parseAgentAnswer, parseEscapedAgentAnswer, type AgentAnswerRun } from './agentAnswer'

const props = defineProps<{ text: string; escapedMarkdown?: boolean }>()
const blocks = computed(() => props.escapedMarkdown ? parseEscapedAgentAnswer(props.text) : parseAgentAnswer(props.text))
const InlineText = defineComponent({
  props: { runs: { type: Array as PropType<AgentAnswerRun[]>, required: true } },
  setup: props => () => props.runs.map(run => {
    const node = run.kind === 'code' ? h('code', run.text) : createTextVNode(run.text)
    return run.strong ? h('strong', [node]) : node
  }),
})
function headingTag(level: number) { return `h${Math.min(6, level + 2)}` }
</script>

<template>
  <div class="agent-answer-body">
    <template v-for="(block, index) in blocks" :key="index">
      <p v-if="block.kind === 'paragraph'" class="answer-paragraph">
        <template v-for="(line, lineIndex) in block.lines" :key="lineIndex"><span class="answer-line"><InlineText :runs="line" /></span><br v-if="lineIndex + 1 < block.lines.length"></template>
      </p>
      <component :is="headingTag(block.level)" v-else-if="block.kind === 'heading'" class="answer-heading"><InlineText :runs="block.content" /></component>
      <component :is="block.ordered ? 'ol' : 'ul'" v-else-if="block.kind === 'list'" class="answer-list" role="list">
        <li v-for="(item, itemIndex) in block.items" :key="itemIndex">
          <span class="answer-list-marker">{{ item.marker }}</span>
          <div class="answer-list-content"><template v-for="(line, lineIndex) in item.lines" :key="lineIndex"><span class="answer-line"><InlineText :runs="line" /></span><br v-if="lineIndex + 1 < item.lines.length"></template></div>
        </li>
      </component>
      <div v-else class="answer-space" :style="{ height: `${block.lines * .65}em` }" aria-hidden="true"></div>
    </template>
  </div>
</template>

<style scoped>
.agent-answer-body { min-width: 0; color: #1f2937; font-size: inherit; line-height: 1.85; white-space: normal; overflow-wrap: anywhere; }
.answer-paragraph { margin: 0; font: inherit; color: inherit; }
.answer-line, .answer-heading { white-space: pre-wrap; }
.answer-heading { margin: .9em 0 .35em; color: #1e293b; font-size: 15px; line-height: 1.65; font-weight: 650; }
.answer-heading:first-child { margin-top: 0; }
h3.answer-heading { font-size: 16px; }
.answer-list { margin: .45em 0; padding: 0; list-style: none; }
.answer-list > li { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 9px; align-items: start; margin: .35em 0; }
.answer-list-marker { color: #64748b; font-variant-numeric: tabular-nums; }
.answer-list-content { min-width: 0; }
.answer-space { min-height: 0; }
.agent-answer-body :deep(strong) { color: #1e293b; font-weight: 650; }
.agent-answer-body :deep(code) { border: 1px solid #e2e8f0; border-radius: 4px; padding: .08em .3em; background: #f1f5f9; color: #334155; font-family: Consolas, 'Courier New', monospace; font-size: .92em; white-space: break-spaces; overflow-wrap: anywhere; }
</style>
