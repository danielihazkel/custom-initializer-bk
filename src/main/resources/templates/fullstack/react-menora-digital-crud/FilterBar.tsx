import { useState } from 'react'
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

/** Query-param values keyed exactly as the backend reads them. Empty strings are dropped upstream. */
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
                <select className={fieldClass} value={values[f.name] ?? ''} onChange={e => set(f.name, e.target.value)}>
                  <option value="">{t('any')}</option>
                  {(f.options ?? []).map(toOption).map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select>
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
