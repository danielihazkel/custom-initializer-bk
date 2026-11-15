import type { ReactNode } from 'react'

/**
 * Shows text written in a small Markdown subset — the one a content page is written in.
 *
 * Blocks, separated by blank lines: `#`/`##`/`###` headings, paragraphs, `-`/`*` bullet lists,
 * `1.` numbered lists, `>` callouts and a `---` rule. Inline: `**bold**`, `*em*`, `` `code` `` and
 * `[text](target)`. A marker without its closing half is plain text.
 *
 * The text is someone's input, so nothing in it is ever treated as markup: every run becomes a
 * React text node, and a link is drawn only for an `http(s)://` or `mailto:` address or a page of
 * this app (`#/…`) — anything else (`javascript:` included) stays the words it was typed as.
 */
type Run =
  | { kind: 'text' | 'bold' | 'em' | 'code'; text: string }
  | { kind: 'link'; text: string; href: string; external: boolean }

type Block =
  | { kind: 'heading'; level: number; runs: Run[] }
  | { kind: 'paragraph' | 'callout'; runs: Run[] }
  | { kind: 'bullets' | 'numbers'; items: Run[][] }
  | { kind: 'rule' }

const HEADING = /^(#{1,3})\s+(.*)$/
const BULLET = /^[-*]\s+(.*)$/
const NUMBERED = /^\d{1,3}[.)]\s+(.*)$/
const RULE = /^-{3,}$/

/** The address a link may open, or null when it must stay text. */
function linkOf(target: string): { href: string; external: boolean } | null {
  const lower = target.toLowerCase()
  if (lower.startsWith('https://') && target.length > 8) return { href: target, external: true }
  if (lower.startsWith('http://') && target.length > 7) return { href: target, external: true }
  if (lower.startsWith('mailto:') && target.length > 7) return { href: target, external: true }
  if (target.startsWith('#/') && target.length > 2) return { href: target, external: false }
  return null
}

function inline(text: string): Run[] {
  const runs: Run[] = []
  let plain = ''
  const flush = () => {
    if (plain) runs.push({ kind: 'text', text: plain })
    plain = ''
  }
  let i = 0
  while (i < text.length) {
    const c = text[i]
    let end: number
    if (c === '`' && (end = text.indexOf('`', i + 1)) > i + 1) {
      flush()
      runs.push({ kind: 'code', text: text.slice(i + 1, end) })
      i = end + 1
    } else if (text.startsWith('**', i) && (end = text.indexOf('**', i + 2)) > i + 2) {
      flush()
      runs.push({ kind: 'bold', text: text.slice(i + 2, end) })
      i = end + 2
    } else if (c === '*' && (end = text.indexOf('*', i + 1)) > i + 1) {
      flush()
      runs.push({ kind: 'em', text: text.slice(i + 1, end) })
      i = end + 1
    } else if (c === '[' && (end = text.indexOf('](', i + 1)) > i + 1 && text.indexOf(')', end + 2) > end + 2) {
      const close = text.indexOf(')', end + 2)
      const label = text.slice(i + 1, end)
      const link = linkOf(text.slice(end + 2, close).trim())
      flush()
      // A target that may not be opened leaves the whole thing as it was typed.
      if (link) runs.push({ kind: 'link', text: label, ...link })
      else runs.push({ kind: 'text', text: text.slice(i, close + 1) })
      i = close + 1
    } else {
      plain += c
      i++
    }
  }
  flush()
  return runs
}

function parse(source: string): Block[] {
  const blocks: Block[] = []
  let paragraph: string[] = []
  let callout: string[] = []
  let items: Run[][] = []
  let listKind: 'bullets' | 'numbers' | null = null
  for (const raw of source.replace(/\r\n?/g, '\n').split('\n')) {
    const line = raw.trim()
    const bullet = BULLET.exec(line)
    const numbered = NUMBERED.exec(line)
    const itemKind = RULE.test(line) ? null : bullet ? 'bullets' : numbered ? 'numbers' : null
    // A block ends where a line of another kind (or a blank one) starts.
    if (listKind && itemKind !== listKind) {
      blocks.push({ kind: listKind, items })
      items = []
      listKind = null
    }
    if (callout.length > 0 && !line.startsWith('>')) {
      blocks.push({ kind: 'callout', runs: inline(callout.join(' ')) })
      callout = []
    }
    const paragraphLine = line !== '' && itemKind == null && !line.startsWith('>') && !HEADING.test(line) && !RULE.test(line)
    if (paragraph.length > 0 && !paragraphLine) {
      blocks.push({ kind: 'paragraph', runs: inline(paragraph.join(' ')) })
      paragraph = []
    }
    if (line === '') continue
    const heading = HEADING.exec(line)
    if (heading) blocks.push({ kind: 'heading', level: heading[1].length, runs: inline(heading[2].trim()) })
    else if (RULE.test(line)) blocks.push({ kind: 'rule' })
    else if (itemKind) {
      listKind = itemKind
      items.push(inline(((itemKind === 'bullets' ? bullet : numbered) as RegExpExecArray)[1].trim()))
    } else if (line.startsWith('>')) callout.push(line.slice(1).trim())
    else paragraph.push(line)
  }
  if (listKind) blocks.push({ kind: listKind, items })
  if (callout.length > 0) blocks.push({ kind: 'callout', runs: inline(callout.join(' ')) })
  if (paragraph.length > 0) blocks.push({ kind: 'paragraph', runs: inline(paragraph.join(' ')) })
  return blocks
}

function draw(runs: Run[]): ReactNode {
  return runs.map((run, i) => {
    switch (run.kind) {
      case 'bold': return <strong key={i} className="font-semibold">{run.text}</strong>
      case 'em': return <em key={i}>{run.text}</em>
      case 'code': return <code key={i} className="rounded bg-surface-2 px-1 py-0.5 font-mono text-[0.85em]">{run.text}</code>
      case 'link': return run.external
        ? <a key={i} href={run.href} target="_blank" rel="noopener noreferrer" className="font-medium text-brand hover:underline">{run.text}</a>
        : <a key={i} href={run.href} className="font-medium text-brand hover:underline">{run.text}</a>
      default: return <span key={i}>{run.text}</span>
    }
  })
}

const HEADING_CLASS = ['text-base font-semibold', 'text-sm font-semibold', 'text-sm font-medium']

export function Markdown({ source }: { source: string }) {
  return (
    <div className="space-y-2 break-words">
      {parse(source).map((block, i) => {
        switch (block.kind) {
          case 'heading': return <p key={i} role="heading" aria-level={block.level + 2} className={HEADING_CLASS[block.level - 1]}>{draw(block.runs)}</p>
          case 'callout': return <blockquote key={i} className="border-s-2 border-brand ps-3 text-muted">{draw(block.runs)}</blockquote>
          case 'bullets': return <ul key={i} className="list-disc space-y-1 ps-5">{block.items.map((item, j) => <li key={j}>{draw(item)}</li>)}</ul>
          case 'numbers': return <ol key={i} className="list-decimal space-y-1 ps-5">{block.items.map((item, j) => <li key={j}>{draw(item)}</li>)}</ol>
          case 'rule': return <hr key={i} className="border-border" />
          default: return <p key={i}>{draw(block.runs)}</p>
        }
      })}
    </div>
  )
}
