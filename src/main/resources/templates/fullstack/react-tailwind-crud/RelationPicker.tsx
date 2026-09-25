import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { ChevronDown, X } from 'lucide-react'
import { api } from '@shared/api'
import { t } from '../i18n'

type Row = Record<string, unknown>

interface Props {
  /** The target's list endpoint, e.g. `/api/customers` — searched with `?q=`, a row read by `/{id}`. */
  path: string
  /** The target's key and its readable field (the option label). */
  valueKey: string
  labelKey: string
  value: string | number | null | undefined
  /** The picked key as a string, or null when cleared; the caller converts it. */
  onChange: (next: string | null) => void
  className: string
  /** Shown while nothing is picked. */
  placeholder?: string
  /** What the clear button says (and, blank, an unset filter means). */
  clearLabel?: string
  'aria-invalid'?: boolean
  'aria-describedby'?: string
  'aria-required'?: boolean
  'aria-label'?: string
}

const PAGE = 20

/**
 * A searchable picker for a many-to-one link: type to search the target's list on the server
 * (its `q` text search), so a link to any of thousands of rows can be made — a plain <select>
 * could only list the first page. The picked row's name is read by id when it is not among the
 * rows on screen.
 */
export function RelationPicker({ path, valueKey, labelKey, value, onChange, className, placeholder, clearLabel, ...aria }: Props) {
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [rows, setRows] = useState<Row[]>([])
  const [loading, setLoading] = useState(false)
  const [active, setActive] = useState(0)
  const [picked, setPicked] = useState<{ key: string; label: string } | null>(null)
  const listId = useId()
  const box = useRef<HTMLDivElement>(null)
  const nameOf = (row: Row) => `${String(row[labelKey] ?? '')} (#${String(row[valueKey])})`
  const key = value == null || value === '' ? null : String(value)

  // The picked row's name: known once picked here, else read by id (a record opened for edit).
  useEffect(() => {
    if (key == null || picked?.key === key) return
    let live = true
    api.get<Row>(`${path}/${encodeURIComponent(key)}`)
      .then(row => { if (live) setPicked({ key, label: nameOf(row) }) })
      .catch(() => { if (live) setPicked({ key, label: `#${key}` }) })
    return () => { live = false }
    // nameOf only reads the two keys, which are part of the path's shape.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, key])

  // The matches for what is typed, a moment after typing stops; a newer answer wins.
  useEffect(() => {
    if (!open) return
    let live = true
    const timer = setTimeout(() => {
      setLoading(true)
      const q = query.trim() ? `&q=${encodeURIComponent(query.trim())}` : ''
      api.get<{ content: Row[] }>(`${path}?size=${PAGE}${q}`)
        .then(page => { if (live) { setRows(page.content ?? []); setActive(0) } })
        .catch(() => { if (live) setRows([]) })
        .finally(() => { if (live) setLoading(false) })
    }, 250)
    return () => { live = false; clearTimeout(timer) }
  }, [open, query, path])

  // A click elsewhere closes the list.
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [open])

  function choose(row: Row) {
    const k = String(row[valueKey])
    setPicked({ key: k, label: nameOf(row) })
    setQuery('')
    setOpen(false)
    onChange(k)
  }

  function onKey(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); setActive(a => Math.min(a + 1, rows.length - 1)) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive(a => Math.max(a - 1, 0)) }
    else if (e.key === 'Enter' && open && rows[active]) { e.preventDefault(); choose(rows[active]) }
    else if (e.key === 'Escape' && open) { e.preventDefault(); setOpen(false); setQuery('') }
  }

  const shown = open ? query : key == null ? '' : picked?.key === key ? picked.label : ''
  return (
    <div ref={box} className="relative">
      <input
        {...aria}
        type="text"
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        aria-autocomplete="list"
        autoComplete="off"
        className={`${className} pe-14`}
        placeholder={key != null && !open ? '' : placeholder ?? t('search')}
        value={shown}
        onFocus={() => setOpen(true)}
        onChange={e => { setQuery(e.target.value); setOpen(true) }}
        onKeyDown={onKey}
      />
      <span className="pointer-events-none absolute inset-y-0 end-0 flex items-center gap-1 pe-2 text-muted">
        {key != null && (
          <button
            type="button"
            className="pointer-events-auto rounded p-0.5 hover:bg-surface-2 hover:text-fg"
            aria-label={clearLabel ?? t('clear')}
            title={clearLabel ?? t('clear')}
            onClick={() => { onChange(null); setQuery('') }}
          >
            <X className="h-3.5 w-3.5" />
          </button>
        )}
        <ChevronDown className="h-4 w-4" />
      </span>
      {open && (
        <ul
          id={listId}
          role="listbox"
          className="absolute z-30 mt-1 max-h-64 w-full overflow-auto rounded-lg border border-border bg-surface py-1 text-sm shadow-lg"
        >
          {loading && rows.length === 0 && <li className="px-3 py-2 text-muted">{t('loading')}</li>}
          {!loading && rows.length === 0 && <li className="px-3 py-2 text-muted">{t('noMatchingRecords')}</li>}
          {rows.map((row, i) => {
            const k = String(row[valueKey])
            return (
              <li
                key={k}
                role="option"
                aria-selected={k === key}
                onMouseDown={e => { e.preventDefault(); choose(row) }}
                onMouseEnter={() => setActive(i)}
                className={`cursor-pointer px-3 py-1.5 ${i === active ? 'bg-surface-2' : ''} ${k === key ? 'font-semibold text-brand' : 'text-fg'}`}
              >
                {nameOf(row)}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
