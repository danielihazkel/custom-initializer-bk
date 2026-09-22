import { useEffect, useState } from 'react'
import { ArrowRight } from 'lucide-react'
import { api } from '@shared/api'
import { t } from '../i18n'

// Dashboard widgets for the generated screens (src/app/screens). Each reads the entity's regular
// list endpoint — no extra API: a count from the page metadata, a breakdown grouped client-side
// from a sample page, the latest rows sorted by key.

const card = 'rounded-2xl border border-border bg-surface p-5 shadow-sm'

/** Loading / failed / empty states shared by the list-shaped widgets. */
function Status({ failed, loading, empty }: { failed: boolean; loading: boolean; empty: boolean }) {
  if (failed) return <div className="text-sm text-muted">{t('couldNotLoad')}</div>
  if (loading) return <div className="text-sm text-muted">…</div>
  if (empty) return <div className="text-sm text-muted">{t('noRecordsYetDot')}</div>
  return null
}

function WidgetHeader({ title, onOpen }: { title: string; onOpen?: () => void }) {
  return (
    <div className="mb-4 flex items-center justify-between gap-3">
      <div className="text-sm font-semibold text-fg">{title}</div>
      {onOpen && (
        <button
          type="button"
          onClick={onOpen}
          className="inline-flex items-center gap-1 text-xs font-medium text-brand hover:underline"
        >
          {t('viewAll')}
          <ArrowRight className="h-3.5 w-3.5 rtl:rotate-180" />
        </button>
      )}
    </div>
  )
}

/** Total record count of `path`, read from the page metadata (size=1). */
export function KpiTile({ title, path, onOpen }: { title: string; path: string; onOpen?: () => void }) {
  const [count, setCount] = useState<number | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let active = true
    api.get<{ totalElements: number }>(`${path}?size=1`)
      .then(p => { if (active) setCount(p.totalElements) })
      .catch(() => { if (active) setFailed(true) })
    return () => { active = false }
  }, [path])

  const body = (
    <>
      <div className="text-sm text-muted">{title}</div>
      <div className="mt-2 text-3xl font-semibold tabular-nums tracking-tight text-fg">
        {failed ? '—' : count == null ? <span className="text-muted">…</span> : count}
      </div>
    </>
  )
  return onOpen ? (
    <button
      type="button"
      onClick={onOpen}
      className={`${card} group w-full text-start transition-all hover:-translate-y-0.5 hover:border-brand/40 hover:shadow-md`}
    >
      {body}
    </button>
  ) : (
    <div className={card}>{body}</div>
  )
}

const BREAKDOWN_SAMPLE = 200

/** Record count per value of `field` (an enum or boolean column), from the first
 *  BREAKDOWN_SAMPLE records — the card says so when the table is larger. */
export function BreakdownCard({ title, path, field, labels, onOpen }: {
  title: string
  path: string
  field: string
  /** Enum breakdowns: display label per constant. */
  labels?: Record<string, string>
  onOpen?: () => void
}) {
  const [data, setData] = useState<{ label: string; count: number }[] | null>(null)
  const [sampled, setSampled] = useState<{ shown: number; total: number } | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let active = true
    api.get<{ content: Record<string, unknown>[]; totalElements: number }>(`${path}?size=${BREAKDOWN_SAMPLE}`)
      .then(p => {
        if (!active) return
        const rows = p.content ?? []
        setSampled({ shown: rows.length, total: p.totalElements ?? rows.length })
        const counts = new Map<string, number>()
        for (const row of rows) {
          const raw = row[field] == null ? '—' : String(row[field])
          const key = labels?.[raw] ?? raw
          counts.set(key, (counts.get(key) ?? 0) + 1)
        }
        setData(Array.from(counts, ([label, count]) => ({ label, count })).sort((a, b) => b.count - a.count))
      })
      .catch(() => { if (active) setFailed(true) })
    return () => { active = false }
    // `labels` is a module-level constant in the generated screens, so it never changes identity.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, field])

  const max = (data ?? []).reduce((m, d) => Math.max(m, d.count), 0) || 1
  return (
    <div className={card}>
      <WidgetHeader title={title} onOpen={onOpen} />
      <Status failed={failed} loading={data == null} empty={data?.length === 0} />
      {data && data.length > 0 && !failed && (
        <>
          <div className="space-y-2">
            {data.map(d => (
              <div key={d.label} className="flex items-center gap-3 text-sm">
                <div className="w-28 shrink-0 truncate text-muted" title={d.label}>{d.label}</div>
                <div className="h-2.5 flex-1 overflow-hidden rounded-full bg-surface-2">
                  <div className="h-full rounded-full bg-brand" style={{ width: `${(d.count / max) * 100}%` }} />
                </div>
                <div className="w-8 shrink-0 text-end tabular-nums text-fg">{d.count}</div>
              </div>
            ))}
          </div>
          {sampled && sampled.total > sampled.shown && (
            <div className="mt-3 text-xs text-muted">
              {t('basedOnSample', { shown: sampled.shown, total: sampled.total })}
            </div>
          )}
        </>
      )}
    </div>
  )
}

/** The latest `limit` rows of `path`, newest key first, labelled by `displayField`. */
export function RecentList({ title, path, sortField, displayField, limit, onOpen }: {
  title: string
  path: string
  sortField: string
  displayField: string
  limit: number
  onOpen?: () => void
}) {
  const [rows, setRows] = useState<Record<string, unknown>[] | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let active = true
    api.get<{ content: Record<string, unknown>[] }>(`${path}?size=${limit}&sort=${sortField},desc`)
      .then(p => { if (active) setRows(p.content ?? []) })
      .catch(() => { if (active) setFailed(true) })
    return () => { active = false }
  }, [path, sortField, limit])

  return (
    <div className={card}>
      <WidgetHeader title={title} onOpen={onOpen} />
      <Status failed={failed} loading={rows == null} empty={rows?.length === 0} />
      {rows && rows.length > 0 && !failed && (
        <ul className="divide-y divide-border">
          {rows.map((row, i) => (
            <li key={i} className="flex items-center justify-between gap-3 py-2 text-sm">
              <span className="truncate text-fg">{row[displayField] == null ? '—' : String(row[displayField])}</span>
              {displayField !== sortField && row[sortField] != null && (
                <span className="shrink-0 tabular-nums text-muted">#{String(row[sortField])}</span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
