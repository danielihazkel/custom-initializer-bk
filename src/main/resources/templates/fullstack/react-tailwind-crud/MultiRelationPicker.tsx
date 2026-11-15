import { useOptions } from '@shared/api'
import { t } from '@shared/i18n'
import { inputClass } from './Field'

type Id = string | number

interface Props {
  /** The list endpoint of the entity being linked to (`/api/tags`). */
  path: string
  /** Its key, and the field that names a row (the key itself when it has none). */
  valueKey: string
  labelKey?: string
  /** Whether the key is a number — ids are sent as the API types them. */
  numeric?: boolean
  /** The linked rows' ids, and their labels in the same order (what the API sent beside them). */
  value: Id[]
  labels: string[]
  disabled?: boolean
  onChange: (ids: Id[], labels: string[]) => void
  'aria-invalid'?: boolean
  'aria-describedby'?: string
  'aria-required'?: boolean
}

/**
 * Picks any number of rows of another entity — a many-to-many link. The picked rows show as
 * chips, each with a remove button; the select below adds one of the rest.
 */
export function MultiRelationPicker({ path, valueKey, labelKey, numeric, value, labels, disabled, onChange, ...aria }: Props) {
  const { options, loading } = useOptions<Record<string, unknown>>(path)
  const idOf = (o: Record<string, unknown>): Id => (numeric ? Number(o[valueKey]) : String(o[valueKey]))
  const nameOf = (o: Record<string, unknown>) => {
    const name = labelKey ? o[labelKey] : undefined
    return name == null || name === '' ? '#' + String(o[valueKey]) : String(name)
  }
  const picked = new Set(value.map(String))
  const rest = options.filter(o => !picked.has(String(o[valueKey])))
  // A label for every id: the API's own, else the option's (a row picked a moment ago).
  const labelAt = (i: number) => {
    if (labels[i] != null) return labels[i]
    const option = options.find(o => String(o[valueKey]) === String(value[i]))
    return option ? nameOf(option) : '#' + String(value[i])
  }

  function add(raw: string) {
    const option = options.find(o => String(o[valueKey]) === raw)
    if (!option) return
    onChange([...value, idOf(option)], [...value.map((_, i) => labelAt(i)), nameOf(option)])
  }

  function remove(index: number) {
    onChange(value.filter((_, i) => i !== index), value.map((_, i) => labelAt(i)).filter((_, i) => i !== index))
  }

  return (
    <div className="space-y-2">
      {value.length > 0 && (
        <ul className="flex flex-wrap gap-1.5">
          {value.map((id, i) => (
            <li key={String(id)} className="inline-flex items-center gap-1 rounded-full bg-surface-2 px-2.5 py-1 text-xs text-fg">
              {labelAt(i)}
              {!disabled && (
                <button type="button" onClick={() => remove(i)} aria-label={`${t('removeLink')}: ${labelAt(i)}`}
                        className="rounded-full px-1 text-muted hover:text-fg focus:outline-none focus:ring-2 focus:ring-ring/40">
                  ×
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      <select className={inputClass} value="" disabled={disabled || loading || rest.length === 0}
              onChange={e => add(e.target.value)} {...aria}>
        <option value="">{loading ? t('loading') : rest.length === 0 ? t('noMoreToAdd') : t('addLink')}</option>
        {rest.map(o => <option key={String(o[valueKey])} value={String(o[valueKey])}>{nameOf(o)}</option>)}
      </select>
    </div>
  )
}
