import { useEffect, useState } from 'react'
import { api } from '@shared/api'
import { t } from '@shared/i18n'

export interface RelatedColumn {
  /** The property of a row this column shows. */
  key: string
  label: string
  /** A property to fall back on when `key` is empty — a link's id where the row it points at has no label. */
  alt?: string
  /** An enum's display labels, by constant. */
  labels?: Record<string, string>
}

type Row = Record<string, unknown>

interface Page {
  content: Row[]
  totalElements: number
}

interface Props {
  title: string
  /** The list endpoint of the rows (`/api/order-lines`) and its filter on their link back (`orderId`). */
  path: string
  param: string
  /** The key of the record the rows belong to. */
  id: string | number | null | undefined
  columns: RelatedColumn[]
  /** How many rows there are, as the record itself says — shown until the rows have loaded. */
  count?: number | null
}

/** How many rows are shown; the rest are a filter away on the rows' own page. */
const SHOWN = 10

function cellText(row: Row, column: RelatedColumn): string {
  const value = row[column.key]
  if (value == null || value === '') {
    const other = column.alt ? row[column.alt] : null
    return other == null || other === '' ? '—' : '#' + String(other)
  }
  if (column.labels) return column.labels[String(value)] ?? String(value)
  return String(value)
}

/**
 * The rows of another entity that point at one record — an order's lines — as a read-only table:
 * the first few, and how many there are in all. They are edited on their own page.
 */
export function RelatedRows({ title, path, param, id, columns, count }: Props) {
  const [page, setPage] = useState<Page | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (id == null) return
    let active = true
    setPage(null)
    setFailed(false)
    api
      .get<Page>(`${path}?${param}=${encodeURIComponent(String(id))}&size=${SHOWN}`)
      .then(loaded => { if (active) setPage(loaded) })
      .catch(() => { if (active) setFailed(true) })
    return () => { active = false }
  }, [path, param, id])

  const rows = page?.content ?? []
  const total = page?.totalElements ?? count ?? null

  return (
    <section className="mt-6" data-related-rows={param}>
      <h3 className="text-[11px] font-semibold uppercase tracking-wider text-muted">
        {title}
        {total != null && <span className="ms-1.5 font-normal">({total})</span>}
      </h3>
      {failed ? (
        <p role="alert" className="mt-2 text-sm text-muted">{t('errorServer')}</p>
      ) : page == null ? (
        <p className="mt-2 text-sm text-muted">{t('loading')}</p>
      ) : rows.length === 0 ? (
        <p className="mt-2 text-sm text-muted">{t('noRecordsTitle')}</p>
      ) : (
        <>
          <div className="mt-2 overflow-x-auto rounded-lg border border-border">
            <table className="w-full text-sm">
              <thead>
                <tr>
                  {columns.map(column => (
                    <th key={column.key} scope="col" className="px-3 py-2 text-start text-[11px] font-semibold uppercase tracking-wider text-muted">
                      {column.label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((row, index) => (
                  <tr key={index} className="border-t border-border">
                    {columns.map(column => (
                      <td key={column.key} className="px-3 py-2 text-fg">{cellText(row, column)}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {total != null && total > rows.length && (
            <p className="mt-1.5 text-xs text-muted">{t('firstNOfM', { n: rows.length, m: total })}</p>
          )}
        </>
      )}
    </section>
  )
}
