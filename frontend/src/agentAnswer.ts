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
