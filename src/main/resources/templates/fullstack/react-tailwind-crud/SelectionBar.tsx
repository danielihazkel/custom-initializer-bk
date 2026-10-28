import { PencilLine, Trash2 } from 'lucide-react'
import { t } from '@shared/i18n'

/** A field the selected rows can all be set to one value of; `kind` picks the value control. */
interface EditableField {
  label: string
  kind: string
  options?: { value: string; label: string }[]
}

interface Props {
  /** How many rows are selected. */
  count: number
  onClear: () => void
  /** Delete the selected rows (shown only when given). */
  onDelete?: () => void
  /** Bulk edit (shown only when given): the fields by name, the one picked and its new value. */
  fields?: Record<string, EditableField>
  field?: string
  onFieldChange?: (field: string) => void
  value?: string
  onValueChange?: (value: string) => void
  onApply?: () => void
  applying?: boolean
}

/** The bar over a table with rows selected: their number, bulk edit, Clear and Delete. */
export function SelectionBar({ count, onClear, onDelete, fields, field = '', onFieldChange, value = '', onValueChange, onApply, applying }: Props) {
  const cls = 'rounded-lg border border-border bg-surface px-2 py-1.5 text-sm text-fg'
  const meta = field ? fields?.[field] : undefined
  const setValue = (v: string) => onValueChange?.(v)
  return (
    <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-brand/40 bg-brand/5 px-4 py-2.5">
      <span className="text-sm font-medium text-fg">{t('nSelected', { n: count })}</span>
      <div className="flex flex-wrap items-center gap-2">
        {fields && (
          <>
            <select
              value={field}
              onChange={e => onFieldChange?.(e.target.value)}
              className={cls}
              aria-label={t('fieldToEdit')}
            >
              <option value="">{t('setField')}</option>
              {Object.entries(fields).map(([name, f]) => <option key={name} value={name}>{f.label}</option>)}
            </select>
            {meta && (meta.kind === 'boolean' ? (
              <select value={value} onChange={e => setValue(e.target.value)} className={cls} aria-label={t('newValue')}>
                <option value="">—</option>
                <option value="true">{t('trueLabel')}</option>
                <option value="false">{t('falseLabel')}</option>
              </select>
            ) : meta.kind === 'enum' ? (
              <select value={value} onChange={e => setValue(e.target.value)} className={cls} aria-label={t('newValue')}>
                <option value="">—</option>
                {meta.options?.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            ) : (
              <input
                type={meta.kind === 'number' ? 'number' : meta.kind === 'date' ? 'date' : meta.kind === 'datetime' ? 'datetime-local' : 'text'}
                value={value}
                onChange={e => setValue(e.target.value)}
                className={cls}
                placeholder={t('newValue')}
                aria-label={t('newValue')}
              />
            ))}
            <button
              onClick={onApply}
              disabled={!field || applying}
              className="inline-flex items-center gap-2 rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-on-primary transition-colors hover:bg-primary-deep disabled:opacity-50"
            >
              <PencilLine className="h-4 w-4" />
              {t('apply')}
            </button>
          </>
        )}
        <button onClick={onClear} className="rounded-lg px-3 py-1.5 text-sm text-muted transition-colors hover:text-fg">{t('clear')}</button>
        {onDelete && (
          <button
            onClick={onDelete}
            className="inline-flex items-center gap-2 rounded-lg bg-danger px-3 py-1.5 text-sm font-medium text-white transition-colors hover:opacity-90"
          >
            <Trash2 className="h-4 w-4" />
            {t('delete')}
          </button>
        )}
      </div>
    </div>
  )
}
