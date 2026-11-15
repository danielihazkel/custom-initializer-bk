import { useRef, useState } from 'react'
import type { KeyboardEvent, ReactNode } from 'react'
import { Bold, Code, Heading2, Italic, Link, List, ListOrdered, Quote } from 'lucide-react'
import { t } from '@shared/i18n'
import { inputClass } from './Field'
import { Markdown } from './Markdown'

interface Props {
  /** The field's name, read to a screen reader: the toolbar stands between the label and the text. */
  label: string
  value: string | null | undefined
  /** The text, or null once it is empty. */
  onChange: (next: string | null) => void
  disabled?: boolean
  'aria-invalid'?: boolean
  'aria-describedby'?: string
  'aria-required'?: boolean
}

/** What a toolbar button does: puts marks around the selection, or starts every selected line. */
type Mark =
  | { kind: 'wrap'; open: string; close: string }
  | { kind: 'lines'; prefix: (line: number) => string }

const BOLD: Mark = { kind: 'wrap', open: '**', close: '**' }
const ITALIC: Mark = { kind: 'wrap', open: '*', close: '*' }
const CODE: Mark = { kind: 'wrap', open: '`', close: '`' }
const LINK: Mark = { kind: 'wrap', open: '[', close: '](https://)' }
const HEADING: Mark = { kind: 'lines', prefix: () => '## ' }
const BULLETS: Mark = { kind: 'lines', prefix: () => '- ' }
const NUMBERS: Mark = { kind: 'lines', prefix: line => `${line + 1}. ` }
const CALLOUT: Mark = { kind: 'lines', prefix: () => '> ' }

/** The text with the mark applied to `[start, end)`, and what to select afterwards. */
function applied(text: string, start: number, end: number, mark: Mark): { text: string; from: number; to: number } {
  if (mark.kind === 'wrap') {
    const inner = text.slice(start, end)
    return {
      text: text.slice(0, start) + mark.open + inner + mark.close + text.slice(end),
      from: start + mark.open.length,
      to: start + mark.open.length + inner.length,
    }
  }
  // Whole lines: from the start of the first one the selection touches to the end of the last.
  const first = text.lastIndexOf('\n', start - 1) + 1
  const after = text.indexOf('\n', end)
  const last = after < 0 ? text.length : after
  const lines = text.slice(first, last).split('\n')
  // Lines that all carry the mark already lose it: the button is a toggle.
  const marked = lines.every((line, i) => line.startsWith(mark.prefix(i)))
  const next = lines.map((line, i) => (marked ? line.slice(mark.prefix(i).length) : mark.prefix(i) + line)).join('\n')
  return { text: text.slice(0, first) + next + text.slice(last), from: first, to: first + next.length }
}

/**
 * A Markdown field: the text to write, with a few buttons for the marks, and how it will read.
 * The preview is the component the details show the value with, so what is seen here is what is
 * saved — and, like there, nothing typed is ever treated as markup.
 */
export function MarkdownEditor({ label, value, onChange, disabled, ...aria }: Props) {
  const area = useRef<HTMLTextAreaElement>(null)
  const [preview, setPreview] = useState(false)
  const text = value ?? ''

  function mark(m: Mark) {
    const el = area.current
    if (!el || disabled) return
    const next = applied(el.value, el.selectionStart, el.selectionEnd, m)
    onChange(next.text === '' ? null : next.text)
    // The selection is put back once the new text is in the field.
    requestAnimationFrame(() => {
      el.focus()
      el.setSelectionRange(next.from, next.to)
    })
  }

  function onKey(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (!(e.ctrlKey || e.metaKey) || e.altKey) return
    const key = e.key.toLowerCase()
    const m = key === 'b' ? BOLD : key === 'i' ? ITALIC : key === 'k' ? LINK : null
    if (!m) return
    e.preventDefault()
    mark(m)
  }

  const tab = (on: boolean) =>
    `rounded-md px-2.5 py-1 text-xs font-medium transition-colors ${on ? 'bg-brand/10 text-brand' : 'text-muted hover:text-fg'}`
  const tool = (title: string, m: Mark, icon: ReactNode) => (
    <button
      type="button"
      onClick={() => mark(m)}
      disabled={disabled}
      title={title}
      aria-label={title}
      className="rounded-md p-1.5 text-muted transition-colors hover:bg-surface-2 hover:text-fg focus:outline-none focus:ring-2 focus:ring-ring/40 disabled:opacity-40"
    >
      {icon}
    </button>
  )

  return (
    <div className="space-y-1.5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div role="group" aria-label={label} className="flex items-center gap-1">
          <button type="button" aria-pressed={!preview} onClick={() => setPreview(false)} className={tab(!preview)}>
            {t('mdWrite')}
          </button>
          <button type="button" aria-pressed={preview} onClick={() => setPreview(true)} className={tab(preview)}>
            {t('mdPreview')}
          </button>
        </div>
        {!preview && (
          <div className="flex flex-wrap items-center gap-0.5">
            {tool(t('mdBold'), BOLD, <Bold className="h-4 w-4" />)}
            {tool(t('mdItalic'), ITALIC, <Italic className="h-4 w-4" />)}
            {tool(t('mdHeading'), HEADING, <Heading2 className="h-4 w-4" />)}
            {tool(t('mdBullets'), BULLETS, <List className="h-4 w-4" />)}
            {tool(t('mdNumbers'), NUMBERS, <ListOrdered className="h-4 w-4" />)}
            {tool(t('mdCallout'), CALLOUT, <Quote className="h-4 w-4" />)}
            {tool(t('mdLink'), LINK, <Link className="h-4 w-4" />)}
            {tool(t('mdCode'), CODE, <Code className="h-4 w-4" />)}
          </div>
        )}
      </div>
      {preview ? (
        <div className="min-h-[144px] rounded-lg border border-border bg-surface px-3 py-2 text-sm text-fg">
          {text.trim() === '' ? <p className="text-muted">{t('mdEmpty')}</p> : <Markdown source={text} />}
        </div>
      ) : (
        <textarea
          ref={area}
          aria-label={label}
          className={`${inputClass} min-h-[144px] resize-y font-mono${disabled ? ' cursor-not-allowed bg-surface-2 text-muted' : ''}`}
          value={text}
          onChange={e => onChange(e.target.value === '' ? null : e.target.value)}
          onKeyDown={onKey}
          disabled={disabled}
          {...aria}
        />
      )}
    </div>
  )
}
