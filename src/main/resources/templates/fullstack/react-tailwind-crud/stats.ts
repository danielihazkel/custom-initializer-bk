import { useEffect, useState } from 'react'
import { api } from '@shared/api'
import { LOCALE } from '../i18n'

// The client half of the generated backend's aggregation endpoint, GET /api/<entity>/stats.
// Kept out of widgets.tsx on purpose: that file may only export components, or the generated
// lint's react-refresh rule fails the build.

/** One group of the rollup — see the generated <Entity>Service.StatsBucket. */
export interface StatsBucket {
  /** The enum constant, 'true'/'false', a date bucket like '2026-09', or '' when ungrouped. */
  key: string
  value: number | null
}

/** The rollup response — see the generated <Entity>Controller.StatsResponse. */
export interface StatsResponse {
  buckets: StatsBucket[]
  total: number | null
}

/** Reads an entity's rollup. `query` is a ready query string, so a fresh object literal at the
 *  call site can never retrigger the fetch. */
export function useStats(path: string, query: string) {
  const [stats, setStats] = useState<StatsResponse | null>(null)
  const [failed, setFailed] = useState(false)
  // Bumped by retry() to run the same request again.
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let active = true
    setStats(null)
    setFailed(false)
    api.get<StatsResponse>(`${path}/stats${query ? `?${query}` : ''}`)
      .then(s => { if (active) setStats(s) })
      .catch(() => { if (active) setFailed(true) })
    return () => { active = false }
  }, [path, query, attempt])

  return { stats, failed, retry: () => setAttempt(a => a + 1) }
}

const NUMBER = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 2 })

/** A rollup number for display; an empty group (min/max/avg over no rows) reads as a dash. */
export function formatStat(value: number | null): string {
  return value == null ? '—' : NUMBER.format(value)
}

/** A bucket's label, through the enum display labels when the column has any. */
export function statLabel(bucket: StatsBucket, labels?: Record<string, string>): string {
  if (bucket.key === '') return '—'
  return labels?.[bucket.key] ?? bucket.key
}

/** The query half that says how to reduce, or '' for a plain record count. */
export function aggQuery(agg?: string, field?: string): string {
  if (!agg || agg === 'count') return ''
  return `agg=${agg}${field ? `&field=${field}` : ''}`
}

/** Joins the non-empty halves of a rollup query. */
export function statsQuery(...parts: string[]): string {
  return parts.filter(Boolean).join('&')
}

/** The periods a dashboard's picker offers, each ending today. */
export type Period = 'all' | '7d' | '30d' | '90d' | 'ytd' | '12m'
export const PERIODS: readonly Period[] = ['all', '7d', '30d', '90d', 'ytd', '12m']

const DAY_MS = 24 * 60 * 60 * 1000

function startOfDay(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate())
}

function addDays(d: Date, days: number): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + days)
}

/** The first and last day a period covers (both inclusive), or null for all time. `previous`
 *  gives the equal-length period just before it — for "this year", the same dates a year back. */
export function periodRange(period: Period, previous = false, today = new Date()): { from: Date; to: Date } | null {
  const end = startOfDay(today)
  let from: Date
  switch (period) {
    case 'all':
      return null
    case '7d':
      from = addDays(end, -6)
      break
    case '30d':
      from = addDays(end, -29)
      break
    case '90d':
      from = addDays(end, -89)
      break
    case 'ytd':
      from = new Date(end.getFullYear(), 0, 1)
      if (previous) {
        return { from: new Date(end.getFullYear() - 1, 0, 1), to: new Date(end.getFullYear() - 1, end.getMonth(), end.getDate()) }
      }
      break
    case '12m':
      from = addDays(new Date(end.getFullYear() - 1, end.getMonth(), end.getDate()), 1)
      break
  }
  if (!previous) return { from, to: end }
  const days = Math.round((end.getTime() - from.getTime()) / DAY_MS) + 1
  return { from: addDays(from, -days), to: addDays(from, -1) }
}

function isoDay(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** The list filter params that limit the date column `field` to a period ('' for all time). A
 *  date-time column gets whole days: midnight on the first to the last second of the last. */
export function rangeParams(field: string, dateTime: boolean, period: Period, previous = false): string {
  const range = periodRange(period, previous)
  if (!range) return ''
  const from = isoDay(range.from) + (dateTime ? 'T00:00:00' : '')
  const to = isoDay(range.to) + (dateTime ? 'T23:59:59' : '')
  return `${field}From=${from}&${field}To=${to}`
}

/** Params as a filter object — what a list page opens with when a widget drills into it. */
export function queryOf(params: string): Record<string, string> {
  return Object.fromEntries(new URLSearchParams(params))
}

/** The list filters that cover one bucket of a time series ('2026', '2026-09' or '2026-09-22') on
 *  the date column `field`; a date-time column gets whole days. */
export function bucketRange(field: string, key: string, dateTime: boolean): Record<string, string> {
  const [year, month, day] = key.split('-').map(Number)
  const from = new Date(year, month ? month - 1 : 0, day || 1)
  const to = day ? from : month ? new Date(year, month, 0) : new Date(year, 11, 31)
  return {
    [`${field}From`]: isoDay(from) + (dateTime ? 'T00:00:00' : ''),
    [`${field}To`]: isoDay(to) + (dateTime ? 'T23:59:59' : ''),
  }
}
