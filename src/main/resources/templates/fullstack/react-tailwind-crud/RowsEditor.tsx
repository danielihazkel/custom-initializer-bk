import type { ReactNode } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import { t } from '@shared/i18n'

export interface RowColumn {
  /** The property of a row this column edits. */
  key: string
  label: string
  required?: boolean
}

interface Props<T> {
  label: string
  columns: RowColumn[]
  rows: T[]
  onChange: (rows: T[]) => void
  /** The input for one column of one row; `set` merges a change into that row. */
  cell: (row: T, column: string, set: (patch: Partial<T>) => void, index: number) => ReactNode
  /** What is wrong with one cell, if anything. */
  errorOf?: (index: number, column: string) => string | undefined
  /** No more rows than this can be saved at once. */
  max?: number
}

/**
 * The rows that are part of a record — an order's lines — edited in the record's own form: a
 * small table with an input per cell, a row added at the end and any row removed. Nothing is
 * saved here; the rows travel with the record when it is.
 */
export function RowsEditor<T extends object>({ label, columns, rows, onChange, cell, errorOf, max = 200 }: Props<T>) {
  const setRow = (index: number, patch: Partial<T>) =>
    onChange(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)))
  const remove = (index: number) => onChange(rows.filter((_, i) => i !== index))
  const add = () => onChange([...rows, {} as T])

  return (
    <fieldset className="space-y-2 border-t border-border pt-4">
      <legend className="mb-1 text-sm font-semibold text-fg">
        {label} <span className="font-normal text-muted">({rows.length})</span>
      </legend>
      {rows.length === 0 ? (
        <p className="text-sm text-muted">{t('noRows')}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-max border-separate border-spacing-x-2 border-spacing-y-1 text-sm">
            <thead>
              <tr>
                {columns.map(column => (
                  <th key={column.key} scope="col" className="text-start text-xs font-medium text-muted">
                    {column.label}
                    {column.required && <span aria-hidden className="text-danger"> *</span>}
                  </th>
                ))}
                <th scope="col"><span className="sr-only">{t('removeRow')}</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row, index) => (
                <tr key={index} className="align-top">
                  {columns.map(column => {
                    const error = errorOf?.(index, column.key)
                    return (
                      <td key={column.key} className="min-w-[8rem]">
                        {cell(row, column.key, patch => setRow(index, patch), index)}
                        {error && <p className="mt-0.5 text-xs text-danger">{error}</p>}
                      </td>
                    )
                  })}
                  <td className="w-8">
                    <button
                      type="button"
                      onClick={() => remove(index)}
                      aria-label={t('removeRow')}
                      title={t('removeRow')}
                      className="rounded p-2 text-muted hover:bg-surface-2 hover:text-danger"
                    >
                      <Trash2 size={16} aria-hidden />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <button
        type="button"
        onClick={add}
        disabled={rows.length >= max}
        className="inline-flex items-center gap-1.5 rounded-md border border-border px-3 py-1.5 text-sm text-fg hover:bg-surface-2 disabled:opacity-50"
      >
        <Plus size={16} aria-hidden />
        {t('addRow')}
      </button>
    </fieldset>
  )
}
