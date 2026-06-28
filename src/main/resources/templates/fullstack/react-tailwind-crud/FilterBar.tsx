import { useState } from 'react'
import { SlidersHorizontal, X } from 'lucide-react'

export type FilterKind = 'enum' | 'boolean' | 'temporal' | 'numeric'

export interface FilterDescriptor {
  /** Base field name. Emitted query params derive from it: enum/boolean → `name`,
   *  temporal → `nameFrom`/`nameTo`, numeric → `nameMin`/`nameMax`. */
  name: string
  label: string
  kind: FilterKind
  /** Allowed values for an enum filter. */
  options?: string[]
  /** Input type for a temporal filter. */
  inputType?: 'date' | 'datetime-local'
}

/** Query-param values keyed exactly as the backend reads them. Empty strings are dropped upstream. */
export type FilterValues = Record<string, string>

interface Props {
  filters: FilterDescriptor[]
  values: FilterValues
  onChange: (next: FilterValues) => void
}

const fieldClass =
  'rounded-lg border border-border bg-surface px-2.5 py-1.5 text-sm text-fg shadow-sm focus:border-ring focus:outline-none focus:ring-2 focus:ring-ring/40'

/**
 * Type-aware filter bar. Each descriptor renders the right control for its field kind and writes
 * one or two query-param entries into a flat `values` map the data hook forwards verbatim. The
 * whole bar is collapsible and shows an active-filter count.
 */
export function FilterBar({ filters, values, onChange }: Props) {
  const [open, setOpen] = useState(false)

  function set(key: string, value: string) {
    const next = { ...values }
    if (value === '') delete next[key]
    else next[key] = value
    onChange(next)
  }

  const activeCount = Object.values(values).filter(v => v !== '').length

  return (
    <div className="rounded-xl border border-border bg-surface shadow-sm">
      <div className="flex items-center justify-between px-3 py-2">
        <button
          onClick={() => setOpen(o => !o)}
          className="inline-flex items-center gap-2 text-sm font-medium text-fg"
          aria-expanded={open}
        >
          <SlidersHorizontal className="h-4 w-4 text-muted" />
          Filters
          {activeCount > 0 && (
            <span className="rounded-full bg-brand px-2 py-0.5 text-xs font-semibold text-white">{activeCount}</span>
          )}
        </button>
        {activeCount > 0 && (
          <button
            onClick={() => onChange({})}
            className="inline-flex items-center gap-1 text-xs text-muted transition-colors hover:text-fg"
          >
            <X className="h-3.5 w-3.5" />
            Clear
          </button>
        )}
      </div>

      {open && (
        <div className="flex flex-wrap items-end gap-4 border-t border-border px-3 py-3">
          {filters.map(f => (
            <div key={f.name} className="flex flex-col gap-1">
              <label className="text-[11px] font-semibold uppercase tracking-wider text-muted">{f.label}</label>
              {f.kind === 'enum' && (
                <select className={fieldClass} value={values[f.name] ?? ''} onChange={e => set(f.name, e.target.value)}>
                  <option value="">Any</option>
                  {(f.options ?? []).map(o => <option key={o} value={o}>{o}</option>)}
                </select>
              )}
              {f.kind === 'boolean' && (
                <select className={fieldClass} value={values[f.name] ?? ''} onChange={e => set(f.name, e.target.value)}>
                  <option value="">Any</option>
                  <option value="true">True</option>
                  <option value="false">False</option>
                </select>
              )}
              {f.kind === 'temporal' && (
                <div className="flex items-center gap-1">
                  <input type={f.inputType ?? 'date'} className={fieldClass} value={values[`${f.name}From`] ?? ''} onChange={e => set(`${f.name}From`, e.target.value)} aria-label={`${f.label} from`} />
                  <span className="text-xs text-muted">–</span>
                  <input type={f.inputType ?? 'date'} className={fieldClass} value={values[`${f.name}To`] ?? ''} onChange={e => set(`${f.name}To`, e.target.value)} aria-label={`${f.label} to`} />
                </div>
              )}
              {f.kind === 'numeric' && (
                <div className="flex items-center gap-1">
                  <input type="number" placeholder="Min" className={`${fieldClass} w-24`} value={values[`${f.name}Min`] ?? ''} onChange={e => set(`${f.name}Min`, e.target.value)} aria-label={`${f.label} min`} />
                  <span className="text-xs text-muted">–</span>
                  <input type="number" placeholder="Max" className={`${fieldClass} w-24`} value={values[`${f.name}Max`] ?? ''} onChange={e => set(`${f.name}Max`, e.target.value)} aria-label={`${f.label} max`} />
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
