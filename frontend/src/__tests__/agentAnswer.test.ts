import assert from 'node:assert/strict'
import test from 'node:test'
import { parseAgentAnswer, parseAgentAnswerInline, parseEscapedAgentAnswer } from '../agentAnswer.ts'

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

test('note preview decodes escaped filenames and timestamps once while the ordinary answer path remains unchanged', () => {
  const original = String.raw`视频\-v1\.2\.mp4 · 00\:02\:42\.533`
  assert.deepEqual(parseEscapedAgentAnswer(original), [{ kind: 'paragraph', lines: [[
    { kind: 'text', text: '视频-v1.2.mp4 · 00:02:42.533', strong: false },
  ]] }])
  assert.equal((parseAgentAnswer(original)[0] as any).lines[0][0].text, original)
})

test('note preview decodes a single entity layer into text, including renderer indentation and supplementary Unicode', () => {
  const original = '&amp;lt;img&amp;gt; &lt;img src=x&gt; &#32;A&#9;B &#x1F600; &#128512; &#92;*'
  const block = parseEscapedAgentAnswer(original)[0]
  assert.equal(block?.kind, 'paragraph')
  assert.equal((block as any).lines[0][0].text, '&lt;img&gt; <img src=x>  A\tB 😀 😀 \\*')
  for (const invalid of ['&#0;', '&#xD800;', '&#1114112;', '&unknown;']) {
    assert.equal((parseEscapedAgentAnswer(invalid)[0] as any).lines[0][0].text, invalid)
  }
})

test('decoding follows AST parsing so literal emphasis, headings, lists and links never become active markup', () => {
  const original = String.raw`\*\*字面强调\*\* &ast;\n\# 字面标题\n\- 字面列表`
  const literal = parseEscapedAgentAnswer(original)[0] as any
  assert.equal(literal.kind, 'paragraph'); assert.equal(literal.lines[0][0].strong, false)
  assert.match(literal.lines[0][0].text, /^\*\*字面强调\*\*/)
  const blocks = parseEscapedAgentAnswer(String.raw`\# 字面标题` + '\n' + String.raw`\- 字面列表` + '\n&#35; 实体标题')
  assert.equal(blocks.length, 1); assert.equal(blocks[0]?.kind, 'paragraph')
  const strong = parseEscapedAgentAnswer(String.raw`**已强调\: &lt;原句&gt;**`)[0] as any
  assert.equal(strong.lines[0][0].text, '已强调: <原句>'); assert.equal(strong.lines[0][0].strong, true)
})

test('inline code retains its original escapes and entities while adjacent note text is decoded', () => {
  const original = '`&lt; \\* &#32;` 外部\\: &amp;lt;'
  assert.deepEqual((parseEscapedAgentAnswer(original)[0] as any).lines[0], [
    { kind: 'code', text: '&lt; \\* &#32;', strong: false }, { kind: 'text', text: ' 外部: &lt;', strong: false },
  ])
})
