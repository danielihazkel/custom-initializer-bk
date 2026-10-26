import { useEffect, useRef, useState } from 'react'
import { SlidersHorizontal } from 'lucide-react'
import { t } from '../i18n'

interface Props {
  columns: { key: string; label: string }[]
  hidden: string[]
  onChange: (hidden: string[]) => void
  /** Forgets the list's saved settings: columns, rows per page, sort and view. */
  onReset?: () => void
}

/** "Columns": shows or hides a list's columns. The first column — the row's name — always shows. */
export function ColumnMenu({ columns, hidden, onChange, onReset }: Props) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => { if (!rootRef.current?.contains(e.target as Node)) setOpen(false) }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !e.defaultPrevented) {
        e.preventDefault()
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      window.removeEventListener('keydown', onKey)
    }
  }, [open])

  const toggle = (key: string) => onChange(hidden.includes(key) ? hidden.filter(k => k !== key) : [...hidden, key])

  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        className="inline-flex items-center gap-2 rounded-lg border border-border bg-surface px-3 py-2 text-sm font-medium text-fg shadow-sm transition-colors hover:bg-surface-2"
      >
        <SlidersHorizontal className="h-4 w-4" />
        {t('columns')}
      </button>
      {open && (
        <div role="group" aria-label={t('columns')} className="absolute end-0 z-30 mt-2 w-60 rounded-xl border border-border bg-surface p-2 shadow-lg">
          {columns.map((c, i) => (
            <label key={c.key} className="flex items-center gap-2 rounded-md px-2 py-1.5 text-sm text-fg hover:bg-surface-2">
              <input
                type="checkbox"
                checked={i === 0 || !hidden.includes(c.key)}
                disabled={i === 0}
                onChange={() => toggle(c.key)}
                className="h-4 w-4 rounded border-border accent-brand"
              />
              {c.label}
            </label>
          ))}
          {onReset && (
            <button
              type="button"
              onClick={() => { onReset(); setOpen(false) }}
              className="mt-1 w-full rounded-md border-t border-border px-2 py-1.5 text-start text-sm text-muted transition-colors hover:bg-surface-2 hover:text-fg"
            >
              {t('resetView')}
            </button>
          )}
        </div>
      )}
    </div>
  )
}
