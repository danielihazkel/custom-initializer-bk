import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { Pencil } from 'lucide-react'
import { t } from '../i18n'

export type InlineKind = 'text' | 'number' | 'boolean' | 'date' | 'datetime' | 'enum'

interface Props {
  /** The column's heading, for the edit button's label. */
  label: string
  kind: InlineKind
  options?: { value: string; label: string }[]
  /** The field's current value. */
  value: unknown
  /** What the cell shows when it is not being edited (the column's own rendering). */
  display: ReactNode
  /** Saves the new value; a rejection keeps the editor open so nothing typed is lost. */
  onSave: (value: unknown) => Promise<void>
}

/** The value as the editor's input holds it. */
function toInput(kind: InlineKind, value: unknown): string {
  if (value == null) return ''
  if (kind === 'datetime') return String(value).slice(0, 16)
  if (kind === 'date') return String(value).slice(0, 10)
  return String(value)
}

/** The input's text as the field's value: blank is null, numbers and booleans are typed. */
function fromInput(kind: InlineKind, text: string): unknown {
  if (text === '') return null
  if (kind === 'number') return Number(text)
  if (kind === 'boolean') return text === 'true'
  return text
}

/** A table cell that can be edited in place: a pencil opens an input for the one field; Enter
 *  or leaving the input saves, Escape puts the value back. */
export function InlineCell({ label, kind, options, value, display, onSave }: Props) {
  const [editing, setEditing] = useState(false)
  const [text, setText] = useState('')
  const [saving, setSaving] = useState(false)
  const inputRef = useRef<HTMLInputElement & HTMLSelectElement>(null)

  useEffect(() => {
    if (editing) inputRef.current?.focus()
  }, [editing])

  function open() {
    setText(toInput(kind, value))
    setEditing(true)
  }
  async function commit(next = text) {
    if (saving) return
    if (next === toInput(kind, value)) {
      setEditing(false)
      return
    }
    setSaving(true)
    try {
      await onSave(fromInput(kind, next))
      setEditing(false)
    } catch {
      inputRef.current?.focus()
    } finally {
      setSaving(false)
    }
  }
  function onKeyDown(e: KeyboardEvent) {
    if (e.key === 'Enter') {
      e.preventDefault()
      void commit()
    } else if (e.key === 'Escape') {
      // Only the cell: a drawer or dialog listening for Escape skips a handled event.
      e.preventDefault()
      e.stopPropagation()
      setEditing(false)
    }
  }

  if (!editing) {
    return (
      <span className="group/cell inline-flex items-center gap-1.5">
        <span>{display}</span>
        <button
          type="button"
          onClick={e => { e.stopPropagation(); open() }}
          className="rounded p-0.5 text-muted opacity-0 transition-opacity hover:text-fg focus:opacity-100 group-hover/cell:opacity-100"
          aria-label={t('editX', { x: label })}
          title={t('editX', { x: label })}
        >
          <Pencil className="h-3.5 w-3.5" />
        </button>
      </span>
    )
  }

  const common = {
    ref: inputRef,
    value: text,
    disabled: saving,
    'aria-label': label,
    onKeyDown,
    onBlur: () => { void commit() },
    onClick: (e: { stopPropagation: () => void }) => e.stopPropagation(),
    className: 'w-full min-w-[8rem] rounded-md border border-border bg-surface px-2 py-1 text-sm text-fg focus:border-brand focus:outline-none disabled:opacity-60',
  }
  if (kind === 'enum' || kind === 'boolean') {
    const choices = kind === 'boolean'
      ? [{ value: 'true', label: t('trueLabel') }, { value: 'false', label: t('falseLabel') }]
      : options ?? []
    return (
      // A choice is the whole edit, so picking one saves it.
      <select {...common} onChange={e => { setText(e.target.value); void commit(e.target.value) }}>
        <option value="">—</option>
        {choices.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    )
  }
  const type = kind === 'number' ? 'number' : kind === 'date' ? 'date' : kind === 'datetime' ? 'datetime-local' : 'text'
  return <input {...common} type={type} step={kind === 'number' ? 'any' : undefined} onChange={e => setText(e.target.value)} />
}
