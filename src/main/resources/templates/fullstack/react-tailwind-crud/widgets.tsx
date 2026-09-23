import { useEffect, useState, type ReactNode } from 'react'
import { ArrowRight } from 'lucide-react'
import { api } from '@shared/api'
import { LOCALE, t, type StringKey } from '../i18n'
import { Skeleton } from './Skeleton'
import { PERIODS, aggQuery, formatStat, statLabel, statsQuery, useStats, type Period, type StatsResponse } from './stats'

// Dashboard widgets for the generated screens (src/app/screens). Counts come from the list
// endpoint's page metadata; every breakdown, trend and aggregate comes from the entity's
// /stats rollup, so a chart is exact rather than a sample of the first page.

const card = 'rounded-2xl border border-border bg-surface p-5 shadow-sm'

const PERIOD_LABELS: Record<Period, StringKey> = {
  all: 'periodAll',
  '7d': 'period7d',
  '30d': 'period30d',
  '90d': 'period90d',
  ytd: 'periodYtd',
  '12m': 'period12m',
}

/** The dashboard's period picker: every widget with a date column follows it. */
export function PeriodSelect({ value, onChange }: { value: Period; onChange: (period: Period) => void }) {
  return (
    <div role="radiogroup" aria-label={t('period')} className="inline-flex flex-wrap gap-1 rounded-xl border border-border bg-surface p-1">
      {PERIODS.map(p => (
        <button
          key={p}
          type="button"
          role="radio"
          aria-checked={value === p}
          onClick={() => onChange(p)}
          className={`rounded-lg px-3 py-1.5 text-xs font-medium transition-colors ${
            value === p ? 'bg-brand text-white' : 'text-muted hover:bg-surface-2 hover:text-fg'
          }`}
        >
          {t(PERIOD_LABELS[p])}
        </button>
      ))}
    </div>
  )
}

/** A failed fetch: what happened, and a way to run it again. */
function Failed({ onRetry }: { onRetry?: () => void }) {
  return (
    <div className="flex flex-wrap items-center gap-2 text-sm text-muted" role="alert">
      <span>{t('couldNotLoad')}</span>
      {onRetry && (
        <button type="button" onClick={onRetry} className="font-medium text-brand hover:underline">
          {t('retry')}
        </button>
      )}
    </div>
  )
}

/** The small "try again" under a tile whose number failed to load. */
function RetryLink({ onRetry }: { onRetry: () => void }) {
  return (
    <span
      role="button"
      tabIndex={0}
      onClick={e => { e.stopPropagation(); onRetry() }}
      onKeyDown={e => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          e.stopPropagation()
          onRetry()
        }
      }}
      className="mt-1 inline-block cursor-pointer text-xs font-medium text-brand hover:underline"
    >
      {t('couldNotLoad')} {t('retry')}
    </span>
  )
}

/** Loading / failed / empty states shared by the list-shaped widgets. */
function Status({ failed, loading, empty, onRetry, emptyText }: {
  failed: boolean
  loading: boolean
  empty: boolean
  onRetry?: () => void
  emptyText?: string
}) {
  if (failed) return <Failed onRetry={onRetry} />
  if (loading) {
    return (
      <div className="space-y-2.5" aria-busy="true">
        <Skeleton className="h-3.5 w-11/12" />
        <Skeleton className="h-3.5 w-3/4" />
        <Skeleton className="h-3.5 w-5/6" />
      </div>
    )
  }
  if (empty) return <div className="text-sm text-muted">{emptyText ?? t('noRecordsYetDot')}</div>
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
 *  screen, which draw the same chart from the same rollup. With `onSelect` each bar is a button
 *  (a drill-down into the list). */
export function BarRows({ data, onSelect }: { data: { label: string; value: number }[]; onSelect?: (index: number) => void }) {
  const max = data.reduce((m, d) => Math.max(m, d.value), 0) || 1
  return (
    <div className="space-y-2">
      {data.map((d, i) => {
        const row = (
          <>
            <div className="w-28 shrink-0 truncate text-muted" title={d.label}>{d.label}</div>
            <div className="h-2.5 flex-1 overflow-hidden rounded-full bg-surface-2">
              <div className="h-full rounded-full bg-brand" style={{ width: `${(d.value / max) * 100}%` }} />
            </div>
            <div className="w-16 shrink-0 text-end tabular-nums text-fg">{formatStat(d.value)}</div>
          </>
        )
        return onSelect ? (
          <button
            key={d.label}
            type="button"
            onClick={() => onSelect(i)}
            className="-mx-1 flex w-[calc(100%+0.5rem)] items-center gap-3 rounded-lg px-1 text-start text-sm transition-colors hover:bg-surface-2"
          >
            {row}
          </button>
        ) : (
          <div key={d.label} className="flex items-center gap-3 text-sm">{row}</div>
        )
      })}
    </div>
  )
}

const CHART_W = 320
const CHART_H = 110
const CHART_PAD = 8

/** A time series as plain SVG — the generated app ships no charting library. The viewBox scales
 *  with the container, so the stroke and the dots keep their proportions at any width. With
 *  `onSelect` each point is a button (a drill-down into its day/month/year). */
export function LineChart({ data, onSelect }: { data: { label: string; value: number }[]; onSelect?: (index: number) => void }) {
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
        {onSelect && points.map(([x, y], i) => (
          <circle
            key={`hit-${data[i].label}`}
            cx={x}
            cy={y}
            r={9}
            className="cursor-pointer fill-transparent hover:fill-brand/20 focus:fill-brand/20 focus:outline-none"
            role="button"
            tabIndex={0}
            aria-label={`${data[i].label}: ${formatStat(data[i].value)}`}
            onClick={() => onSelect(i)}
            onKeyDown={e => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                onSelect(i)
              }
            }}
          />
        ))}
      </svg>
      <div className="mt-1 flex justify-between text-[11px] tabular-nums text-muted">
        <span>{data[0].label}</span>
        {data.length > 1 && <span>{data[data.length - 1].label}</span>}
      </div>
    </div>
  )
}

/** One number from the backend: the record count (from the list's page metadata, which is
 *  exact), or an aggregate of one numeric column. `params` null skips the fetch. */
function useNumber(path: string, agg: string | undefined, field: string | undefined, params: string | null) {
  const [value, setValue] = useState<number | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (params == null) return
    let active = true
    // A plain count is already exact from the page metadata, and one row crosses the wire.
    const pending = agg && agg !== 'count'
      ? api.get<StatsResponse>(`${path}/stats?${statsQuery(aggQuery(agg, field), params)}`).then(s => s.total)
      : api.get<{ totalElements: number }>(`${path}?${statsQuery('size=1', params)}`).then(p => p.totalElements)
    setValue(null)
    setFailed(false)
    pending.then(v => { if (active) setValue(v) }).catch(() => { if (active) setFailed(true) })
    return () => { active = false }
  }, [path, agg, field, params, attempt])

  return { value, failed, retry: () => setAttempt(a => a + 1) }
}

/** A tile's frame: a button when it opens the list, a plain card otherwise. */
function Tile({ onOpen, className, children }: { onOpen?: () => void; className: string; children: ReactNode }) {
  return onOpen ? (
    <button
      type="button"
      onClick={onOpen}
      className={`${card} group w-full text-start transition-all hover:-translate-y-0.5 hover:border-brand/40 hover:shadow-md ${className}`}
    >
      {children}
    </button>
  ) : (
    <div className={`${card} ${className}`}>{children}</div>
  )
}

/** A single number: the record count, or an aggregate of one numeric column — optionally with the
 *  change against the previous period. */
export function KpiTile({ title, path, agg, field, params = '', compareParams, onOpen, className = '' }: {
  title: string
  path: string
  /** sum/avg/min/max over `field`; omitted counts records. */
  agg?: string
  field?: string
  /** List filter params the number is limited to (a preset, the dashboard period). */
  params?: string
  /** The same params over the previous period; given, the tile shows the change against it. */
  compareParams?: string
  onOpen?: () => void
  /** Grid placement (the widget's span). */
  className?: string
}) {
  const { value, failed, retry } = useNumber(path, agg, field, params)
  const previous = useNumber(path, agg, field, compareParams ?? null)
  const change = compareParams != null && value != null && previous.value != null && previous.value !== 0
    ? ((value - previous.value) / Math.abs(previous.value)) * 100
    : null

  return (
    <Tile onOpen={onOpen} className={className}>
      <div className="text-sm text-muted">{title}</div>
      <div className="mt-2 text-3xl font-semibold tabular-nums tracking-tight text-fg">
        {failed ? '—' : value == null ? <Skeleton className="h-9 w-24" /> : formatStat(value)}
      </div>
      {failed && <RetryLink onRetry={retry} />}
      {change != null && (
        <div className={`mt-1 text-xs font-medium tabular-nums ${change > 0 ? 'text-success' : change < 0 ? 'text-danger' : 'text-muted'}`}>
          {change > 0 ? '▲' : change < 0 ? '▼' : '–'} {formatStat(Math.abs(change))}%{' '}
          <span className="font-normal text-muted">{t('vsPrevious')}</span>
        </div>
      )}
    </Tile>
  )
}

/** A single number against a target, as a filling bar. */
export function ProgressTile({ title, path, agg, field, target, params = '', onOpen, className = '' }: {
  title: string
  path: string
  agg?: string
  field?: string
  target: number
  params?: string
  onOpen?: () => void
  className?: string
}) {
  const { value, failed, retry } = useNumber(path, agg, field, params)
  const percent = value == null ? 0 : Math.max(0, (value / target) * 100)

  return (
    <Tile onOpen={onOpen} className={className}>
      <div className="text-sm text-muted">{title}</div>
      <div className="mt-2 flex items-baseline gap-2">
        <span className="text-3xl font-semibold tabular-nums tracking-tight text-fg">
          {failed ? '—' : value == null ? <Skeleton className="inline-block h-9 w-24 align-middle" /> : formatStat(value)}
        </span>
        <span className="text-sm tabular-nums text-muted">/ {formatStat(target)}</span>
      </div>
      <div
        className="mt-3 h-2.5 overflow-hidden rounded-full bg-surface-2"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(percent)}
      >
        <div className="h-full rounded-full bg-brand" style={{ width: `${Math.min(percent, 100)}%` }} />
      </div>
      {failed
        ? <RetryLink onRetry={retry} />
        : <div className="mt-1 text-xs tabular-nums text-muted">{t('percentOfTarget', { x: formatStat(Math.round(percent)) })}</div>}
    </Tile>
  )
}

/** Records grouped by `field` (an enum or boolean column), rolled up by the backend. */
export function BreakdownCard({ title, path, field, agg, valueField, labels, params = '', onSelect, onOpen, className = '' }: {
  title: string
  path: string
  field: string
  /** sum/avg/min/max over `valueField`; omitted counts records. */
  agg?: string
  valueField?: string
  /** Enum breakdowns: display label per constant. */
  labels?: Record<string, string>
  /** List filter params the chart is limited to (a preset, the dashboard period). */
  params?: string
  /** A click on a bar, with its group's key (the list filter value). */
  onSelect?: (key: string) => void
  onOpen?: () => void
  /** Grid placement (the widget's span). */
  className?: string
}) {
  const { stats, failed, retry } = useStats(path, statsQuery(`groupBy=${field}`, aggQuery(agg, valueField), params))
  // Largest first: a breakdown is read by size, not by key order.
  const data = (stats?.buckets ?? [])
    .map(b => ({ key: b.key, label: statLabel(b, labels), value: b.value ?? 0 }))
    .sort((a, b) => b.value - a.value)

  return (
    <div className={`${card} ${className}`}>
      <WidgetHeader title={title} onOpen={onOpen} />
      <Status failed={failed} loading={stats == null} empty={data.length === 0} onRetry={retry} />
      {data.length > 0 && !failed && (
        <BarRows data={data} onSelect={onSelect && (i => { if (data[i].key !== '') onSelect(data[i].key) })} />
      )}
    </div>
  )
}

/** A time series of `agg` over the temporal column `on`, bucketed by day/month/year. */
export function TrendCard({ title, path, on, bucket, agg, field, params = '', onSelect, onOpen, className = '' }: {
  title: string
  path: string
  on: string
  bucket: string
  agg?: string
  field?: string
  /** List filter params the series is limited to (a preset, the dashboard period). */
  params?: string
  /** A click on a point, with its bucket's key ('2026-09'). */
  onSelect?: (key: string) => void
  onOpen?: () => void
  /** Grid placement (the widget's span). */
  className?: string
}) {
  const { stats, failed, retry } = useStats(path, statsQuery(`groupBy=${on}`, `bucket=${bucket}`, aggQuery(agg, field), params))
  const data = (stats?.buckets ?? []).map(b => ({ key: b.key, label: b.key === '' ? '—' : b.key, value: b.value ?? 0 }))

  return (
    <div className={`${card} ${className}`}>
      <WidgetHeader title={title} onOpen={onOpen} />
      <Status failed={failed} loading={stats == null} empty={data.length === 0} onRetry={retry} />
      {data.length > 0 && !failed && (
        <LineChart data={data} onSelect={onSelect && (i => { if (data[i].key !== '') onSelect(data[i].key) })} />
      )}
    </div>
  )
}

/** The largest `limit` groups of `by` — an enum/boolean column, or a relation whose ids are named
 *  from its target's list (`optionsPath`) — ranked by the backend. */
export function TopList({ title, path, by, agg, valueField, limit, labels, optionsPath, optionValue = 'id', optionLabel, params = '', onSelect, onOpen, className = '' }: {
  title: string
  path: string
  by: string
  agg?: string
  valueField?: string
  limit: number
  /** Enum ranks: display label per constant. */
  labels?: Record<string, string>
  /** Relation ranks: the target's list endpoint, its key and its label column. */
  optionsPath?: string
  optionValue?: string
  optionLabel?: string
  params?: string
  /** A click on a row, with its group's key (the list filter value). */
  onSelect?: (key: string) => void
  onOpen?: () => void
  className?: string
}) {
  const { stats, failed, retry } = useStats(path, statsQuery(`groupBy=${by}`, aggQuery(agg, valueField), `top=${limit}`, params))
  const [names, setNames] = useState<Record<string, string>>({})

  useEffect(() => {
    if (!optionsPath) return
    let active = true
    api.get<{ content: Record<string, unknown>[] }>(`${optionsPath}?size=1000`)
      .then(page => {
        if (!active) return
        const next: Record<string, string> = {}
        for (const row of page.content ?? []) {
          const id = String(row[optionValue])
          next[id] = optionLabel && row[optionLabel] != null ? String(row[optionLabel]) : `#${id}`
        }
        setNames(next)
      })
      .catch(() => { if (active) setNames({}) })
    return () => { active = false }
  }, [optionsPath, optionValue, optionLabel])

  const rows = (stats?.buckets ?? []).map(b => ({
    key: b.key,
    label: optionsPath ? (b.key === '' ? '—' : names[b.key] ?? `#${b.key}`) : statLabel(b, labels),
    value: b.value ?? 0,
  }))

  return (
    <div className={`${card} ${className}`}>
      <WidgetHeader title={title} onOpen={onOpen} />
      <Status failed={failed} loading={stats == null} empty={rows.length === 0} onRetry={retry} />
      {rows.length > 0 && !failed && (
        <ol className="divide-y divide-border">
          {rows.map((row, i) => {
            const body = (
              <>
                <span className="w-5 shrink-0 tabular-nums text-muted">{i + 1}</span>
                <span className={`min-w-0 flex-1 truncate ${onSelect && row.key !== '' ? 'font-medium text-brand' : 'text-fg'}`}>{row.label}</span>
                <span className="shrink-0 tabular-nums font-medium text-fg">{formatStat(row.value)}</span>
              </>
            )
            return (
              <li key={row.key}>
                {onSelect && row.key !== '' ? (
                  <button type="button" onClick={() => onSelect(row.key)} className="flex w-full items-center gap-3 py-2 text-start text-sm hover:underline">
                    {body}
                  </button>
                ) : (
                  <div className="flex items-center gap-3 py-2 text-sm">{body}</div>
                )}
              </li>
            )
          })}
        </ol>
      )}
    </div>
  )
}

/** The latest `limit` rows of `path` — highest `sortField` first — labelled by `displayField`. */
export function RecentList({ title, path, sortField, keyField, displayField, limit, params = '', onOpen, onOpenRow, className = '' }: {
  title: string
  path: string
  sortField: string
  /** The primary key, when the list is sorted by another column (default: `sortField`). */
  keyField?: string
  displayField: string
  limit: number
  /** List filter params the rows are limited to (a preset, the dashboard period). */
  params?: string
  onOpen?: () => void
  /** Opens one row (its record page); without it the rows are plain text. */
  onOpenRow?: (row: Record<string, unknown>) => void
  /** Grid placement (the widget's span). */
  className?: string
}) {
  const [rows, setRows] = useState<Record<string, unknown>[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let active = true
    setRows(null)
    setFailed(false)
    api.get<{ content: Record<string, unknown>[] }>(`${path}?${statsQuery(`size=${limit}`, `sort=${sortField},desc`, params)}`)
      .then(p => { if (active) setRows(p.content ?? []) })
      .catch(() => { if (active) setFailed(true) })
    return () => { active = false }
  }, [path, sortField, limit, params, attempt])

  // The key reads as "#12"; any other sort column shows its value (a date in the app's locale).
  const sortValue = (row: Record<string, unknown>) => {
    const v = row[sortField]
    if (v == null) return null
    if ((keyField ?? sortField) === sortField) return `#${String(v)}`
    // A date (or the day of a date-time), read as a local day so no timezone shifts it.
    const day = typeof v === 'string' ? /^(\d{4})-(\d{2})-(\d{2})/.exec(v) : null
    if (day) return new Date(Number(day[1]), Number(day[2]) - 1, Number(day[3])).toLocaleDateString(LOCALE)
    return String(v)
  }

  return (
    <div className={`${card} ${className}`}>
      <WidgetHeader title={title} onOpen={onOpen} />
      <Status failed={failed} loading={rows == null} empty={rows?.length === 0} onRetry={() => setAttempt(a => a + 1)} />
      {rows && rows.length > 0 && !failed && (
        <ul className="divide-y divide-border">
          {rows.map((row, i) => (
            <li key={String(row[keyField ?? sortField] ?? i)} className="flex items-center justify-between gap-3 py-2 text-sm">
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
              {displayField !== sortField && sortValue(row) != null && (
                <span className="shrink-0 tabular-nums text-muted">{sortValue(row)}</span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** One chart of a report page: bars (an enum/boolean group) or a line (a date, bucketed) from the
 *  rollup of the filtered rows, and — for the page's first chart — the totals table beneath it. */
export function ReportChart({ title, path, rollup, search, line = false, labels, groupLabel, valueLabel, table = false, onSelect }: {
  /** Shown when the page has more than one chart. */
  title?: string
  path: string
  /** The fixed half of the /stats query. */
  rollup: string
  /** The filter bar's values, as list params. */
  search: string
  line?: boolean
  labels?: Record<string, string>
  groupLabel: string
  valueLabel: string
  table?: boolean
  /** A click on a bar or a point, with its group's key. */
  onSelect?: (key: string) => void
}) {
  const { stats, failed, retry } = useStats(path, statsQuery(rollup, search))
  const rows = (stats?.buckets ?? []).map(b => ({
    key: b.key,
    label: line ? (b.key === '' ? '—' : b.key) : statLabel(b, labels),
    value: b.value ?? 0,
  }))
  const select = onSelect && ((i: number) => { if (rows[i].key !== '') onSelect(rows[i].key) })

  return (
    <div className={card}>
      {title && <div className="mb-4 text-sm font-semibold text-fg">{title}</div>}
      {failed || stats == null || rows.length === 0 ? (
        <Status failed={failed} loading={stats == null} empty={rows.length === 0} onRetry={retry} emptyText={t('noMatchingRecords')} />
      ) : (
        <>
          {line ? <LineChart data={rows} onSelect={select} /> : <BarRows data={rows} onSelect={select} />}
          {table && (
            <table className="mt-5 w-full text-sm">
              <thead>
                <tr className="border-b border-border text-[11px] uppercase tracking-wider text-muted">
                  <th className="py-2 text-start font-semibold">{groupLabel}</th>
                  <th className="py-2 text-end font-semibold">{valueLabel}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {rows.map(row => (
                  <tr key={row.label}>
                    <td className="py-2 text-fg">{row.label}</td>
                    <td className="py-2 text-end tabular-nums text-fg">{formatStat(row.value)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="border-t-2 border-border font-semibold text-fg">
                  <td className="py-2">{t('total')}</td>
                  <td className="py-2 text-end tabular-nums">{formatStat(stats.total)}</td>
                </tr>
              </tfoot>
            </table>
          )}
        </>
      )}
    </div>
  )
}
