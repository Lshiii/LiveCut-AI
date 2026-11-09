export type AgentAnswerRun = { kind: 'text' | 'code'; text: string; strong: boolean }
export type AgentAnswerListItem = { marker: string; lines: AgentAnswerRun[][] }
export type AgentAnswerBlock =
  | { kind: 'paragraph'; lines: AgentAnswerRun[][] }
  | { kind: 'heading'; level: number; content: AgentAnswerRun[] }
  | { kind: 'list'; ordered: boolean; items: AgentAnswerListItem[] }
  | { kind: 'space'; lines: number }

function escaped(text: string, index: number) {
  let slashes = 0
  while (index > 0 && text[--index] === '\\') slashes++
  return slashes % 2 === 1
}

/** A deliberately small text grammar. Everything else remains literal text. */
export function parseAgentAnswerInline(text: string, strong = false): AgentAnswerRun[] {
  const runs: AgentAnswerRun[] = []
  const pattern = /`([^`\r\n]+)`|\*\*([^*\r\n]+)\*\*/g
  let cursor = 0
  for (const match of text.matchAll(pattern)) {
    const index = match.index, end = index + match[0].length
    const code = match[1] !== undefined
    const marker = code ? '`' : '*'
    const closing = end - (code ? 1 : 2)
    if (text[index - 1] === marker || text[end] === marker
        || escaped(text, index) || escaped(text, closing)) continue
    if (index > cursor) runs.push({ kind: 'text', text: text.slice(cursor, index), strong })
    if (code) runs.push({ kind: 'code', text: match[1]!, strong })
    else runs.push(...parseAgentAnswerInline(match[2]!, true))
    cursor = end
  }
  if (cursor < text.length) runs.push({ kind: 'text', text: text.slice(cursor), strong })
  return runs
}

/** Parse blocks without producing HTML, URLs, executable attributes or hidden content. */
export function parseAgentAnswer(text: string): AgentAnswerBlock[] {
  if (!text) return []
  const blocks: AgentAnswerBlock[] = []
  let paragraph: Extract<AgentAnswerBlock, { kind: 'paragraph' }> | null = null
  let list: Extract<AgentAnswerBlock, { kind: 'list' }> | null = null
  for (const line of text.replace(/\r\n?/g, '\n').split('\n')) {
    if (!line.trim()) {
      paragraph = null; list = null
      const previous = blocks.at(-1)
      if (previous?.kind === 'space') previous.lines++
      else blocks.push({ kind: 'space', lines: 1 })
      continue
    }
    const heading = /^(#{1,6})[ \t]+(.+)$/.exec(line)
    if (heading) {
      blocks.push({ kind: 'heading', level: heading[1]!.length,
                    content: parseAgentAnswerInline(heading[2]!) })
      paragraph = null; list = null
      continue
    }
    const item = /^([-+*]|\d{1,9}[.)])[ \t]+(.+)$/.exec(line)
    if (item) {
      const ordered = /^\d/.test(item[1]!)
      if (!list || list.ordered !== ordered) {
        list = { kind: 'list', ordered, items: [] }
        blocks.push(list)
      }
      list.items.push({ marker: item[1]!, lines: [parseAgentAnswerInline(item[2]!)] })
      paragraph = null
      continue
    }
    if (list && /^[ \t]{2,}/.test(line)) {
      // Indented continuations remain visible, including unsupported nested
      // syntax. Do not invent a hierarchy or discard the original indentation.
      list.items.at(-1)!.lines.push(parseAgentAnswerInline(line))
      continue
    }
    list = null
    if (!paragraph) {
      paragraph = { kind: 'paragraph', lines: [] }
      blocks.push(paragraph)
    }
    paragraph.lines.push(parseAgentAnswerInline(line))
  }
  return blocks
}

/** Decode only the renderer's escaped text after parsing; never reinterpret it as markup. */
function decodeMarkdownText(text: string): string {
  const named: Record<string, string> = { '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&apos;': "'" }
  return text.replace(/\\([\x21-\x2f\x3a-\x40\x5b-\x60\x7b-\x7e])|&(?:amp|lt|gt|quot|apos|#(?:[0-9]{1,7}|[xX][0-9a-fA-F]{1,6}));/g,
    (token: string, punctuation: string | undefined) => {
      if (punctuation !== undefined) return punctuation
      if (named[token] !== undefined) return named[token]!
      const hex = token[2]?.toLowerCase() === 'x', point = Number.parseInt(token.slice(hex ? 3 : 2, -1), hex ? 16 : 10)
      return point > 0 && point <= 0x10ffff && !(point >= 0xd800 && point <= 0xdfff) ? String.fromCodePoint(point) : token
    })
}
/** Notes are safe Markdown documents, while ordinary answers retain their original grammar. */
export function parseEscapedAgentAnswer(text: string): AgentAnswerBlock[] {
  const runs = (line: AgentAnswerRun[]) => line.map(run => run.kind === 'code' ? { ...run } : { ...run, text: decodeMarkdownText(run.text) })
  return parseAgentAnswer(text).map(block => {
    if (block.kind === 'heading') return { ...block, content: runs(block.content) }
    if (block.kind === 'paragraph') return { ...block, lines: block.lines.map(runs) }
    if (block.kind === 'list') return { ...block, items: block.items.map(item => ({ ...item, lines: item.lines.map(runs) })) }
    return block
  })
}
