import { useEffect, useState } from 'react'
import { ArrowRight } from 'lucide-react'
import { api } from '@shared/api'
import { t } from '../i18n'
import { aggQuery, formatStat, statLabel, statsQuery, useStats, type StatsResponse } from './stats'

// Dashboard widgets for the generated screens (src/app/screens). Counts come from the list
// endpoint's page metadata; every breakdown, trend and aggregate comes from the entity's
// /stats rollup, so a chart is exact rather than a sample of the first page.

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

/** Horizontal bars scaled to the largest value. Shared by the breakdown widget and the report
 *  screen, which draw the same chart from the same rollup. */
export function BarRows({ data }: { data: { label: string; value: number }[] }) {
  const max = data.reduce((m, d) => Math.max(m, d.value), 0) || 1
  return (
    <div className="space-y-2">
      {data.map(d => (
        <div key={d.label} className="flex items-center gap-3 text-sm">
          <div className="w-28 shrink-0 truncate text-muted" title={d.label}>{d.label}</div>
          <div className="h-2.5 flex-1 overflow-hidden rounded-full bg-surface-2">
            <div className="h-full rounded-full bg-brand" style={{ width: `${(d.value / max) * 100}%` }} />
          </div>
          <div className="w-16 shrink-0 text-end tabular-nums text-fg">{formatStat(d.value)}</div>
        </div>
      ))}
    </div>
  )
}

const CHART_W = 320
const CHART_H = 110
const CHART_PAD = 8

/** A time series as plain SVG — the generated app ships no charting library. The viewBox scales
 *  with the container, so the stroke and the dots keep their proportions at any width. */
export function LineChart({ data }: { data: { label: string; value: number }[] }) {
  if (data.length === 0) return null
  const values = data.map(d => d.value)
  const max = Math.max(...values, 0)
  const min = Math.min(...values, 0)
  const span = max - min || 1
  const step = data.length > 1 ? (CHART_W - CHART_PAD * 2) / (data.length - 1) : 0
  const points = data.map((d, i) => [
    CHART_PAD + i * step,
    CHART_H - CHART_PAD - ((d.value - min) / span) * (CHART_H - CHART_PAD * 2),
  ] as const)
  const line = points.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)} ${y.toFixed(1)}`).join(' ')
  const last = points[points.length - 1]
  const area = `${line} L${last[0].toFixed(1)} ${CHART_H - CHART_PAD} L${points[0][0].toFixed(1)} ${CHART_H - CHART_PAD} Z`

  return (
    <div>
      <svg
        viewBox={`0 0 ${CHART_W} ${CHART_H}`}
        className="h-auto w-full"
        role="img"
        aria-label={data.map(d => `${d.label}: ${formatStat(d.value)}`).join(', ')}
      >
        {data.length > 1 && <path d={area} className="fill-brand/10" />}
        <path
          d={line}
          className="stroke-brand"
          fill="none"
          strokeWidth={2}
          strokeLinejoin="round"
          strokeLinecap="round"
        />
        {points.map(([x, y], i) => (
          <circle key={data[i].label} cx={x} cy={y} r={2.5} className="fill-brand">
            <title>{`${data[i].label}: ${formatStat(data[i].value)}`}</title>
          </circle>
        ))}
      </svg>
      <div className="mt-1 flex justify-between text-[11px] tabular-nums text-muted">
        <span>{data[0].label}</span>
        {data.length > 1 && <span>{data[data.length - 1].label}</span>}
      </div>
    </div>
  )
}

/** A single number: the record count, or an aggregate of one numeric column. */
export function KpiTile({ title, path, agg, field, onOpen }: {
  title: string
  path: string
  /** sum/avg/min/max over `field`; omitted counts records. */
  agg?: string
  field?: string
  onOpen?: () => void
}) {
  const [value, setValue] = useState<number | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let active = true
    // A plain count is already exact from the page metadata, and one row crosses the wire.
    const pending = agg && agg !== 'count'
      ? api.get<StatsResponse>(`${path}/stats?${aggQuery(agg, field)}`).then(s => s.total)
      : api.get<{ totalElements: number }>(`${path}?size=1`).then(p => p.totalElements)
    pending.then(v => { if (active) setValue(v) }).catch(() => { if (active) setFailed(true) })
    return () => { active = false }
  }, [path, agg, field])

  const body = (
    <>
      <div className="text-sm text-muted">{title}</div>
      <div className="mt-2 text-3xl font-semibold tabular-nums tracking-tight text-fg">
        {failed ? '—' : value == null ? <span className="text-muted">…</span> : formatStat(value)}
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

/** Records grouped by `field` (an enum or boolean column), rolled up by the backend. */
export function BreakdownCard({ title, path, field, agg, valueField, labels, onOpen }: {
  title: string
  path: string
  field: string
  /** sum/avg/min/max over `valueField`; omitted counts records. */
  agg?: string
  valueField?: string
  /** Enum breakdowns: display label per constant. */
  labels?: Record<string, string>
  onOpen?: () => void
}) {
  const { stats, failed } = useStats(path, statsQuery(`groupBy=${field}`, aggQuery(agg, valueField)))
  // Largest first: a breakdown is read by size, not by key order.
  const data = (stats?.buckets ?? [])
    .map(b => ({ label: statLabel(b, labels), value: b.value ?? 0 }))
    .sort((a, b) => b.value - a.value)

  return (
    <div className={card}>
      <WidgetHeader title={title} onOpen={onOpen} />
      <Status failed={failed} loading={stats == null} empty={data.length === 0} />
      {data.length > 0 && !failed && <BarRows data={data} />}
    </div>
  )
}

/** A time series of `agg` over the temporal column `on`, bucketed by day/month/year. */
export function TrendCard({ title, path, on, bucket, agg, field, onOpen }: {
  title: string
  path: string
  on: string
  bucket: string
  agg?: string
  field?: string
  onOpen?: () => void
}) {
  const { stats, failed } = useStats(path, statsQuery(`groupBy=${on}`, `bucket=${bucket}`, aggQuery(agg, field)))
  const data = (stats?.buckets ?? []).map(b => ({ label: b.key === '' ? '—' : b.key, value: b.value ?? 0 }))

  return (
    <div className={card}>
      <WidgetHeader title={title} onOpen={onOpen} />
      <Status failed={failed} loading={stats == null} empty={data.length === 0} />
      {data.length > 0 && !failed && <LineChart data={data} />}
    </div>
  )
}

/** The latest `limit` rows of `path`, newest key first, labelled by `displayField`. */
export function RecentList({ title, path, sortField, displayField, limit, onOpen, onOpenRow }: {
  title: string
  path: string
  sortField: string
  displayField: string
  limit: number
  onOpen?: () => void
  /** Opens one row (its record page); without it the rows are plain text. */
  onOpenRow?: (row: Record<string, unknown>) => void
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
              {onOpenRow ? (
                <button
                  type="button"
                  onClick={() => onOpenRow(row)}
                  className="truncate text-start font-medium text-brand hover:underline"
                >
                  {row[displayField] == null ? '—' : String(row[displayField])}
                </button>
              ) : (
                <span className="truncate text-fg">{row[displayField] == null ? '—' : String(row[displayField])}</span>
              )}
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
