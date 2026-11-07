import assert from 'node:assert/strict'
import test from 'node:test'
import { parseAgentAnswer, parseAgentAnswerInline } from '../agentAnswer.ts'

test('paragraphs preserve physical line breaks and separate blank lines', () => {
  assert.deepEqual(parseAgentAnswer('第一行\r\n第二行\r\n\r\n下一段'), [
    { kind: 'paragraph', lines: [[{ kind: 'text', text: '第一行', strong: false }],
                               [{ kind: 'text', text: '第二行', strong: false }]] },
    { kind: 'space', lines: 1 },
    { kind: 'paragraph', lines: [[{ kind: 'text', text: '下一段', strong: false }]] },
  ])
  assert.deepEqual(parseAgentAnswer(''), [])
  assert.deepEqual(parseAgentAnswer('\n\n'), [{ kind: 'space', lines: 3 }])
})

test('headings and both list kinds retain all item content and original numbering', () => {
  const blocks = parseAgentAnswer('### 关键结论\n03. 先看原句\n8) 再核对边界\n- 保留证据\n+ 不自动确认')
  assert.deepEqual(blocks.map(block => block.kind), ['heading', 'list', 'list'])
  const [heading, ordered, unordered] = blocks
  assert.equal(heading?.kind === 'heading' && heading.level, 3)
  assert.ok(ordered?.kind === 'list' && ordered.ordered)
  assert.deepEqual(ordered.items.map(item => item.marker), ['03.', '8)'])
  assert.equal(ordered.items[1]?.lines[0]?.[0]?.text, '再核对边界')
  assert.ok(unordered?.kind === 'list' && !unordered.ordered)
  assert.deepEqual(unordered.items.map(item => item.marker), ['-', '+'])
})

test('emphasis and inline code can combine while code shields markdown markers', () => {
  assert.deepEqual(parseAgentAnswerInline('请 **核对 `00:12` 边界**，再看 `**原样**`。'), [
    { kind: 'text', text: '请 ', strong: false },
    { kind: 'text', text: '核对 ', strong: true },
    { kind: 'code', text: '00:12', strong: true },
    { kind: 'text', text: ' 边界', strong: true },
    { kind: 'text', text: '，再看 ', strong: false },
    { kind: 'code', text: '**原样**', strong: false },
    { kind: 'text', text: '。', strong: false },
  ])
})

test('HTML, links and unsupported or unmatched syntax stay literal text', () => {
  for (const original of [
    '<script>alert("x")</script><img src=x onerror=alert(1)>',
    '[查看](javascript:alert(1)) https://example.invalid/?x=<tag>&y=1',
    '```html', '``code``', '***未支持的样式***', '*斜体*', '**未闭合', '`未闭合',
    '\\**被转义的语法**', '####### 七级不是小标题', '##没有分隔空格',
  ]) {
    const blocks = parseAgentAnswer(original)
    assert.deepEqual(blocks, [{ kind: 'paragraph', lines: [[
      { kind: 'text', text: original, strong: false },
    ]] }], original)
  }
})

test('indented continuations and unsupported nested syntax remain visible inside lists', () => {
  const blocks = parseAgentAnswer('1. 第一项\n  补充原文\n  - 嵌套语法原样保留\n2. 第二项\n普通段落')
  assert.equal(blocks.length, 2)
  const list = blocks[0]
  assert.ok(list?.kind === 'list')
  assert.deepEqual(list.items[0]?.lines.map(line => line.map(run => run.text).join('')),
                   ['第一项', '  补充原文', '  - 嵌套语法原样保留'])
  assert.equal(list.items[1]?.marker, '2.')
  assert.equal(blocks[1]?.kind, 'paragraph')
})

test('timestamps, decimal values, unicode and long tokens are never truncated or relabeled', () => {
  const text = `00:12–00:18 原句 😀\n3.14 是数值而非列表\n${'长'.repeat(3000)}`
  const blocks = parseAgentAnswer(text)
  assert.ok(blocks[0]?.kind === 'paragraph')
  assert.equal(blocks[0].lines.map(line => line.map(run => run.text).join('')).join('\n'), text)
})
