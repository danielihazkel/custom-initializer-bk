import { useEffect, useRef, useState } from 'react'
import { ChevronDown, X } from 'lucide-react'
import { useOptions } from '@shared/api'
import { RelationPicker } from './RelationPicker'
import { Chip } from '@shared/ui/menora'
import { t } from '../i18n'

export type FilterKind = 'enum' | 'boolean' | 'temporal' | 'numeric' | 'relation'

export interface FilterDescriptor {
  /** Base field name. Emitted query params derive from it: enum/boolean/relation → `name`,
   *  temporal → `nameFrom`/`nameTo`, numeric → `nameMin`/`nameMax`. */
  name: string
  label: string
  kind: FilterKind
  /** Allowed values for an enum filter. */
  /** Enum choices: a bare value, or a value with its display label. */
  options?: Array<string | { value: string; label: string }>
  /** Input type for a temporal filter. */
  inputType?: 'date' | 'datetime-local'
  /** Relation filter: list endpoint of the referenced entity, and which of its fields supply the
   *  option value (its primary key) and the human-readable label (falls back to the value). */
  optionsPath?: string
  optionValue?: string
  optionLabel?: string
  /** The referenced list searches its label: pick by typing instead of from one page of options. */
  searchable?: boolean
}

/** Normalizes an enum option to its value/label pair (a bare string labels itself). */
function toOption(o: string | { value: string; label: string }): { value: string; label: string } {
  return typeof o === 'string' ? { value: o, label: o } : o
}

/** The values of a multi-value filter: `status=OPEN,IN_PROGRESS` matches either. */
function splitValues(value: string | undefined): string[] {
  return value ? value.split(',').filter(v => v !== '') : []
}

/** An enum filter that takes any number of values: a button naming the choice, over checkboxes. */
function EnumMultiSelect({ f, value, onChange, className }: {
  f: FilterDescriptor
  value: string
  onChange: (value: string) => void
  className: string
}) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const options = (f.options ?? []).map(toOption)
  const picked = splitValues(value)

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

  // Kept in the options' order, so the same choice always reads (and routes) the same way.
  const toggle = (v: string) => {
    const next = picked.includes(v) ? picked.filter(p => p !== v) : [...picked, v]
    onChange(options.map(o => o.value).filter(o => next.includes(o)).join(','))
  }
  const summary = picked.length === 0
    ? t('any')
    : picked.length === 1
      ? options.find(o => o.value === picked[0])?.label ?? picked[0]
      : t('nSelected', { n: picked.length })

  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        aria-haspopup="true"
        aria-label={`${f.label}: ${summary}`}
        className={`${className} inline-flex min-w-32 items-center justify-between gap-2`}
      >
        <span className="truncate">{summary}</span>
        <ChevronDown className="h-3.5 w-3.5 shrink-0 text-muted" aria-hidden="true" />
      </button>
      {open && (
        <div role="group" aria-label={f.label} className="absolute start-0 z-30 mt-1 max-h-72 w-56 overflow-y-auto rounded-xl border border-border bg-surface p-2 shadow-lg">
          {options.map(o => (
            <label key={o.value} className="flex items-center gap-2 rounded-md px-2 py-1.5 text-sm text-fg hover:bg-surface-2">
              <input
                type="checkbox"
                checked={picked.includes(o.value)}
                onChange={() => toggle(o.value)}
                className="h-4 w-4 rounded border-border accent-brand"
              />
              {o.label}
            </label>
          ))}
        </div>
      )}
    </div>
  )
}

/** A <select> over the referenced entity's rows, e.g. "Customer" on the orders page. */
function RelationSelect({ f, value, onChange, className }: {
  f: FilterDescriptor
  value: string
  onChange: (value: string) => void
  className: string
}) {
  const { options, loading } = useOptions<Record<string, unknown>>(f.optionsPath ?? '')
  const valueKey = f.optionValue ?? 'id'
  return (
    <select className={className} value={value} onChange={e => onChange(e.target.value)} aria-label={f.label}>
      <option value="">{loading ? t('loading') : t('any')}</option>
      {options.map(o => {
        const v = String(o[valueKey])
        const label = f.optionLabel ? String(o[f.optionLabel] ?? '') : ''
        return <option key={v} value={v}>{label ? `${label} (#${v})` : v}</option>
      })}
    </select>
  )
}

/** Query-param values keyed exactly as the backend reads them. Empty strings are dropped upstream;
 *  an enum filter holding several values joins them with commas. */
export type FilterValues = Record<string, string>

interface Props {
  filters: FilterDescriptor[]
  values: FilterValues
  onChange: (next: FilterValues) => void
}

/** Every control wears the design system's form-control look (`.mn-field`). */
const fieldClass = 'mn-field'

/**
 * Type-aware filter bar. Each descriptor renders the right control for its field kind and writes
 * one or two query-param entries into a flat `values` map the data hook forwards verbatim. The
 * whole bar is a lined `.mn-panel`, collapsible, with the active-filter count on a Chip.
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
    <div className="mn-panel mn-panel--lined">
      <div className="flex items-center justify-between px-3 py-2">
        <button
          type="button"
          onClick={() => setOpen(o => !o)}
          className="mn mn-btn mn-btn--text"
          aria-expanded={open}
        >
          <ChevronDown className={`h-4 w-4 transition-transform ${open ? 'rotate-180' : ''}`} aria-hidden="true" />
          {t('filters')}
          {activeCount > 0 && <Chip label={String(activeCount)} weight={600} compact />}
        </button>
        {activeCount > 0 && (
          <button type="button" onClick={() => onChange({})} className="mn mn-btn mn-btn--text">
            <X className="h-4 w-4" aria-hidden="true" />
            {t('clear')}
          </button>
        )}
      </div>

      {open && (
        <div className="flex flex-wrap items-end gap-4 border-t border-border px-4 py-4">
          {filters.map(f => (
            <div key={f.name} className="flex flex-col gap-1">
              <label className="text-[11px] font-semibold uppercase tracking-wider text-muted">{f.label}</label>
              {f.kind === 'enum' && (
                <EnumMultiSelect f={f} value={values[f.name] ?? ''} onChange={v => set(f.name, v)} className={fieldClass} />
              )}
              {f.kind === 'boolean' && (
                <select className={fieldClass} value={values[f.name] ?? ''} onChange={e => set(f.name, e.target.value)}>
                  <option value="">{t('any')}</option>
                  <option value="true">{t('trueLabel')}</option>
                  <option value="false">{t('falseLabel')}</option>
                </select>
              )}
              {f.kind === 'temporal' && (
                <div className="flex items-center gap-1">
                  <input type={f.inputType ?? 'date'} className={fieldClass} value={values[`${f.name}From`] ?? ''} onChange={e => set(`${f.name}From`, e.target.value)} aria-label={t('xFrom', { x: f.label })} />
                  <span className="text-xs text-muted">–</span>
                  <input type={f.inputType ?? 'date'} className={fieldClass} value={values[`${f.name}To`] ?? ''} onChange={e => set(`${f.name}To`, e.target.value)} aria-label={t('xTo', { x: f.label })} />
                </div>
              )}
              {f.kind === 'relation' && (
                f.searchable && f.optionLabel
                  ? <RelationPicker path={f.optionsPath ?? ''} valueKey={f.optionValue ?? 'id'} labelKey={f.optionLabel} value={values[f.name] ?? ''} onChange={v => set(f.name, v ?? '')} className={fieldClass} placeholder={t('any')} aria-label={f.label} />
                  : <RelationSelect f={f} value={values[f.name] ?? ''} onChange={v => set(f.name, v)} className={fieldClass} />
              )}
              {f.kind === 'numeric' && (
                <div className="flex items-center gap-1">
                  <input type="number" placeholder={t('min')} className={`${fieldClass} w-28`} value={values[`${f.name}Min`] ?? ''} onChange={e => set(`${f.name}Min`, e.target.value)} aria-label={t('xMin', { x: f.label })} />
                  <span className="text-xs text-muted">–</span>
                  <input type="number" placeholder={t('max')} className={`${fieldClass} w-28`} value={values[`${f.name}Max`] ?? ''} onChange={e => set(`${f.name}Max`, e.target.value)} aria-label={t('xMax', { x: f.label })} />
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
