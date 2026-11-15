import { useContext, useEffect, useState, type ReactNode } from 'react'
import { ArrowRight } from 'lucide-react'
import { api } from '@shared/api'
import { LOCALE, t } from '../i18n'
import { Skeleton } from './Skeleton'
import { RefreshTick, aggQuery, formatStat, statsQuery, useStats } from './stats'

// The dashboard widgets that read a process rather than a table: a funnel over an ordered set of
// stages, a gauge against a full-scale value, and how long rows stay in each workflow state.
// A file of their own, so the widgets every dashboard ships keep their bytes.

const card = 'rounded-2xl border border-border bg-surface p-5 shadow-sm'

function Retry({ onRetry }: { onRetry: () => void }) {
  return (
    <p className="mt-3 text-sm text-muted">
      {t('couldNotLoad')}{' '}
      <button type="button" onClick={onRetry} className="font-medium text-brand underline">{t('retry')}</button>
    </p>
  )
}

function Card({ title, className, onOpen, children }: { title: string; className: string; onOpen?: () => void; children: ReactNode }) {
  return (
    <div className={`${card} ${className}`}>
      <div className="flex items-center justify-between gap-3">
        <h3 className="text-sm font-semibold text-fg">{title}</h3>
        {onOpen && (
          <button type="button" onClick={onOpen} className="inline-flex items-center gap-1 text-xs font-medium text-brand hover:underline">
            {t('viewAll')}
            <ArrowRight className="h-3.5 w-3.5 rtl:rotate-180" />
          </button>
        )}
      </div>
      {children}
    </div>
  )
}

/**
 * A funnel over an enum's values, in their own order — a pipeline's stages. A stage's bar is
 * everything that has got at least that far: the rows in it now and in every later stage, with
 * its share of the first. (Read off where the rows are today: it takes the stages to be passed in
 * order, and a row that left the pipeline is counted where it stands.)
 */
export function FunnelCard({ title, path, field, labels, stages: wanted, agg, valueField, currency, params = '', onSelect, onOpen, className = '' }: {
  title: string
  path: string
  field: string
  /** The enum's labels by constant, in the enum's order: the stages. */
  labels: Record<string, string>
  /** The stages drawn, when not every value is one (a state off the main line is left out). */
  stages?: readonly string[]
  /** sum over `valueField`; omitted counts records. */
  agg?: string
  valueField?: string
  currency?: string
  /** List filter params the funnel is limited to (a preset, the dashboard period and filters). */
  params?: string
  /** A click on a stage, with its constant (the list filter value). */
  onSelect?: (key: string) => void
  /** "View all": the entity's list. */
  onOpen?: () => void
  className?: string
}) {
  const { stats, failed, retry } = useStats(path, statsQuery(`groupBy=${field}`, aggQuery(agg, valueField), params))
  const stages = wanted ?? Object.keys(labels)
  const now = stages.map(key => stats?.buckets.find(b => b.key === key)?.value ?? 0)
  // Reached: this stage and every one after it.
  const reached = now.map((_, i) => now.slice(i).reduce((sum, n) => sum + n, 0))
  const first = reached[0] ?? 0

  return (
    <Card title={title} className={className} onOpen={onOpen}>
      {failed && <Retry onRetry={retry} />}
      {!failed && stats == null && <Skeleton className="mt-4 h-32 w-full" />}
      {!failed && stats != null && first === 0 && <p className="mt-3 text-sm text-muted">{t('noRecordsYetDot')}</p>}
      {!failed && stats != null && first > 0 && (
        <ol className="mt-4 space-y-2">
          {stages.map((key, i) => {
            const share = (reached[i] ?? 0) / first
            const row = (
              <>
                <div className="flex items-baseline justify-between gap-3 text-sm">
                  <span className="truncate text-fg">{labels[key] ?? key}</span>
                  <span className="shrink-0 tabular-nums text-muted">
                    {formatStat(reached[i] ?? 0, currency)} · {Math.round(share * 100)}%
                  </span>
                </div>
                <div className="mt-1 flex justify-center">
                  <div className="h-3 rounded-full bg-brand transition-[width]" style={{ width: `${Math.max(share * 100, 2)}%`, opacity: 1 - i * (0.5 / Math.max(stages.length - 1, 1)) }} />
                </div>
              </>
            )
            return (
              <li key={key}>
                {onSelect
                  ? <button type="button" onClick={() => onSelect(key)} className="block w-full rounded-lg p-1 text-start transition-colors hover:bg-surface-2">{row}</button>
                  : <div className="p-1">{row}</div>}
              </li>
            )
          })}
        </ol>
      )}
    </Card>
  )
}

/** One number of an entity: its row count (exact from the list's page metadata) or an aggregate
 *  of one column (from its rollup), limited by `params`. Read again on a dashboard refresh. */
function useAmount(path: string, agg: string | undefined, field: string | undefined, params: string) {
  const [value, setValue] = useState<number | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const tick = useContext(RefreshTick)

  useEffect(() => {
    let active = true
    setFailed(false)
    const pending = agg && agg !== 'count'
      ? api.get<{ total: number | null }>(`${path}/stats?${statsQuery(aggQuery(agg, field), params)}`).then(s => s.total ?? 0)
      : api.get<{ totalElements: number }>(`${path}?${statsQuery('size=1', params)}`).then(p => p.totalElements)
    pending.then(v => { if (active) setValue(v) }).catch(() => { if (active) setFailed(true) })
    return () => { active = false }
  }, [path, agg, field, params, attempt, tick])

  return { value, failed, retry: () => setAttempt(a => a + 1) }
}

/** One number against a full-scale value, as a half dial: where it stands, at a glance. */
export function GaugeTile({ title, path, agg, field, currency, target, params = '', onOpen, className = '' }: {
  title: string
  path: string
  agg?: string
  field?: string
  currency?: string
  /** The value at which the dial is full. */
  target: number
  params?: string
  onOpen?: () => void
  className?: string
}) {
  const { value, failed, retry } = useAmount(path, agg, field, params)
  const share = value == null ? 0 : Math.min(Math.max(value / target, 0), 1)
  // A half circle of radius 40 around (50, 50): its length is π·40.
  const length = Math.PI * 40
  const body = (
    <>
      <div className="text-sm text-muted">{title}</div>
      <svg viewBox="0 0 100 56" className="mx-auto mt-2 w-full max-w-[180px]" role="img" aria-label={`${Math.round(share * 100)}%`}>
        <path d="M 10 50 A 40 40 0 0 1 90 50" fill="none" strokeWidth="9" strokeLinecap="round" className="stroke-surface-2" />
        <path
          d="M 10 50 A 40 40 0 0 1 90 50"
          fill="none"
          strokeWidth="9"
          strokeLinecap="round"
          className="stroke-brand"
          strokeDasharray={`${share * length} ${length}`}
        />
      </svg>
      <div className="-mt-5 text-center">
        <div className="text-2xl font-semibold tabular-nums tracking-tight text-fg">
          {failed ? '—' : value == null ? <Skeleton className="inline-block h-7 w-20 align-middle" /> : formatStat(value, currency)}
        </div>
        <div className="text-xs tabular-nums text-muted">/ {formatStat(target, currency)}</div>
      </div>
      {failed && <Retry onRetry={retry} />}
    </>
  )
  return onOpen ? (
    <button type="button" onClick={onOpen} className={`${card} w-full text-start transition-all hover:-translate-y-0.5 hover:border-brand/40 hover:shadow-md ${className}`}>
      {body}
    </button>
  ) : (
    <div className={`${card} ${className}`}>{body}</div>
  )
}

/** One state's average stay (GET /api/<entity>/moves/time-in-state). */
interface StateTime {
  state: string
  seconds: number
  moves: number
}

/** A duration in the unit that reads best: minutes under an hour, hours under two days, else days. */
function duration(seconds: number): string {
  const unit = seconds < 3600 ? 'minute' : seconds < 172800 ? 'hour' : 'day'
  const amount = seconds / (unit === 'minute' ? 60 : unit === 'hour' ? 3600 : 86400)
  return new Intl.NumberFormat(LOCALE, { style: 'unit', unit, unitDisplay: 'short', maximumFractionDigits: 1 }).format(amount)
}

/**
 * How long rows stay in each workflow state before they move on: the average, from the workflow's
 * move log — so a state nothing has left yet has no bar. Over every row ever moved: the
 * dashboard's period and filters do not limit it.
 */
export function TimeInStateCard({ title, path, labels, className = '' }: {
  title: string
  path: string
  /** The status field's labels by constant, in the enum's order. */
  labels: Record<string, string>
  className?: string
}) {
  const [times, setTimes] = useState<StateTime[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const tick = useContext(RefreshTick)

  useEffect(() => {
    let active = true
    setFailed(false)
    api.get<StateTime[]>(`${path}/moves/time-in-state`)
      .then(found => { if (active) setTimes(found) })
      .catch(() => { if (active) setFailed(true) })
    return () => { active = false }
  }, [path, attempt, tick])

  const rows = Object.keys(labels).flatMap(key => {
    const found = times?.find(s => s.state === key)
    return found ? [{ key, label: labels[key] ?? key, seconds: found.seconds }] : []
  })
  const longest = Math.max(...rows.map(r => r.seconds), 1)

  return (
    <Card title={title} className={className}>
      {failed && <Retry onRetry={() => setAttempt(a => a + 1)} />}
      {!failed && times == null && <Skeleton className="mt-4 h-24 w-full" />}
      {!failed && times != null && rows.length === 0 && <p className="mt-3 text-sm text-muted">{t('noRecordsYetDot')}</p>}
      {!failed && rows.length > 0 && (
        <ul className="mt-4 space-y-3">
          {rows.map(row => (
            <li key={row.key}>
              <div className="flex items-baseline justify-between gap-3 text-sm">
                <span className="truncate text-fg">{row.label}</span>
                <span className="shrink-0 tabular-nums text-muted">{duration(row.seconds)}</span>
              </div>
              <div className="mt-1 h-2.5 overflow-hidden rounded-full bg-surface-2">
                <div className="h-full rounded-full bg-brand" style={{ width: `${Math.max((row.seconds / longest) * 100, 2)}%` }} />
              </div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}
