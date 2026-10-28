import { Trash2 } from 'lucide-react'
import { t } from '@shared/i18n'
import { Chip, Button } from '@shared/ui/menora'

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

/** The lined panel over a table with rows selected: their number as a Chip, bulk edit, Clear and Delete. */
export function SelectionBar({ count, onClear, onDelete, fields, field = '', onFieldChange, value = '', onValueChange, onApply, applying }: Props) {
  const cls = 'mn-field'
  const meta = field ? fields?.[field] : undefined
  const setValue = (v: string) => onValueChange?.(v)
  return (
    <div className="mn-panel mn-panel--lined mb-4 flex flex-wrap items-center justify-between gap-3 px-4 py-2.5">
      <Chip weight={600} label={t('nSelected', { n: count })} />
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
            {/* Native button: the Button port has no `disabled`. */}
            <button
              type="button"
              onClick={onApply}
              disabled={!field || applying}
              className="mn mn-btn mn-btn--primary mn-btn--compact"
            >
              {t('apply')}
            </button>
          </>
        )}
        <Button variant="text" label={t('clear')} onClick={onClear} />
        {onDelete && (
          <button
            type="button"
            onClick={onDelete}
            className="inline-flex items-center gap-2 rounded-full bg-danger px-4 py-2 text-sm font-medium text-white transition-colors hover:opacity-90"
          >
            <Trash2 className="h-4 w-4" />
            {t('delete')}
          </button>
        )}
      </div>
    </div>
  )
}
