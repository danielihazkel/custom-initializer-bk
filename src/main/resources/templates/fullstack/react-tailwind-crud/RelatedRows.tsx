import { useEffect, useRef, useState } from 'react'
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
  /** The rows themselves, where the record carries them (rows saved with it): shown as they are, nothing is fetched. */
  rows?: object[] | null
  /** Rows that are part of the record: all of them at once, not a page at a time. */
  whole?: boolean
}

/** How many rows are fetched at a time; "Load more" brings the next ones. */
const SHOWN = 10
/** The most rows a record saves with it. */
const WHOLE = 200

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
 * the first few with a way to load the rest, and how many there are in all. Rows saved with the
 * record are shown whole. They are edited on their own page, or in the record's form.
 */
export function RelatedRows({ title, path, param, id, columns, count, rows: given, whole }: Props) {
  const [loaded, setLoaded] = useState<Row[] | null>(null)
  const [found, setFound] = useState<number | null>(null)
  const [pages, setPages] = useState(0)
  const [failed, setFailed] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const own = given != null
  const size = whole ? WHOLE : SHOWN
  const query = `${path}?${param}=${encodeURIComponent(String(id))}&size=${size}`
  // The query the rows on screen answer: a later page of another record's rows is not theirs.
  const current = useRef(query)

  useEffect(() => {
    current.current = query
    if (id == null || own) return
    let active = true
    setLoaded(null)
    setFound(null)
    setPages(0)
    setFailed(false)
    api
      .get<Page>(query)
      .then(page => {
        if (!active) return
        setLoaded(page.content)
        setFound(page.totalElements)
        setPages(1)
      })
      .catch(() => { if (active) setFailed(true) })
    return () => { active = false }
  }, [query, id, own])

  async function loadMore() {
    const asked = query
    setLoadingMore(true)
    try {
      const page = await api.get<Page>(`${asked}&page=${pages}`)
      if (current.current !== asked) return
      setLoaded(before => [...(before ?? []), ...page.content])
      setFound(page.totalElements)
      setPages(n => n + 1)
    } catch {
      if (current.current === asked) setFailed(true)
    } finally {
      setLoadingMore(false)
    }
  }

  const rows: Row[] = own ? (given as Row[]) : loaded ?? []
  const total = own ? rows.length : found ?? count ?? null

  return (
    <section className="mt-6" data-related-rows={param}>
      <h3 className="text-[11px] font-semibold uppercase tracking-wider text-muted">
        {title}
        {total != null && <span className="ms-1.5 font-normal">({total})</span>}
      </h3>
      {failed ? (
        <p role="alert" className="mt-2 text-sm text-muted">{t('errorServer')}</p>
      ) : !own && loaded == null ? (
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
          {!own && total != null && total > rows.length && (
            <button
              type="button"
              onClick={() => { void loadMore() }}
              disabled={loadingMore}
              className="mt-1.5 text-xs font-medium text-brand underline-offset-2 hover:underline disabled:opacity-60"
              data-related-more
            >
              {loadingMore ? t('loading') : t('loadMoreN', { n: total - rows.length })}
            </button>
          )}
        </>
      )}
    </section>
  )
}
