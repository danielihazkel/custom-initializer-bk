import type { ReactNode } from 'react'
import { ChevronLeft, ChevronRight, Plus } from 'lucide-react'
import { Skeleton } from './Skeleton'
import { t, LOCALE } from '../i18n'
import { addDays, parseDay, step, todayKey, weekStart, windowOf, type ScheduleMode } from './schedule'

interface Props<T extends object> {
  rows: T[]
  /** Stable identity for a row (its primary key). */
  rowKey: (row: T) => string | number
  /** What an event shows: its heading. */
  label: (row: T) => ReactNode
  /** The day (yyyy-mm-dd) a row starts on, or null when it has none. */
  start: (row: T) => string | null
  /** The day it ends on, when the rows run over days (a missing or earlier end: the start day). */
  end?: (row: T) => string | null
  /** A time to put before the label (a date-time's hh:mm), when rows have one. */
  time?: (row: T) => string | null
  mode: ScheduleMode
  /** The views to switch between; one or none: no switcher. */
  modes: ScheduleMode[]
  onModeChange: (mode: ScheduleMode) => void
  /** Any day (yyyy-mm-dd) inside the period on screen. */
  cursor: string
  onCursorChange: (day: string) => void
  loading: boolean
  /** How many rows the period really holds, when that may be more than `rows`. */
  total?: number
  onOpen?: (row: T) => void
  /** Creates a row on a day; absent: the days are not clickable. */
  onCreate?: (day: string) => void
}

const weekdayFormat = new Intl.DateTimeFormat(LOCALE, { weekday: 'short' })
const monthFormat = new Intl.DateTimeFormat(LOCALE, { month: 'long', year: 'numeric' })
const dayFormat = new Intl.DateTimeFormat(LOCALE, { weekday: 'long', day: 'numeric', month: 'long' })
const shortDayFormat = new Intl.DateTimeFormat(LOCALE, { day: 'numeric', month: 'short' })
// 2023-01-01 is a Sunday, so day i of that week is weekday i.
const WEEKDAYS = Array.from({ length: 7 }, (_, i) => weekdayFormat.format(new Date(2023, 0, 1 + i)))
const MODE_LABEL: Record<ScheduleMode, 'calendarMonth' | 'calendarWeek' | 'calendarAgenda' | 'calendarTimeline'> = {
  month: 'calendarMonth', week: 'calendarWeek', agenda: 'calendarAgenda', timeline: 'calendarTimeline',
}

/**
 * A calendar page's body: the rows placed on a month grid, a week, a 30-day agenda or a four-week
 * timeline, by the day they start (and, with `end`, every day they run to). The page fetches the
 * period `windowOf` names, so this only lays out what it is handed.
 */
export function ScheduleView<T extends object>({
  rows, rowKey, label, start, end, time, mode, modes, onModeChange, cursor, onCursorChange, loading, total, onOpen, onCreate,
}: Props<T>) {
  const { from, to } = windowOf(mode, cursor)
  const today = todayKey()

  // Every day a row covers inside the window, capped to it.
  const byDay = new Map<string, T[]>()
  for (const row of rows) {
    const first = start(row)
    if (!first) continue
    const lastRaw = end?.(row)
    const last = lastRaw && lastRaw > first ? lastRaw : first
    let day = first < from ? from : first
    const stop = last > to ? to : last
    for (let guard = 0; day <= stop && guard < 62; guard++) {
      const list = byDay.get(day)
      if (list) list.push(row); else byDay.set(day, [row])
      day = addDays(day, 1)
    }
  }

  const heading = mode === 'month'
    ? monthFormat.format(parseDay(cursor))
    : `${shortDayFormat.format(parseDay(from))} – ${shortDayFormat.format(parseDay(to))}`

  const event = (row: T) => (
    <button
      key={rowKey(row)}
      type="button"
      onClick={e => { e.stopPropagation(); onOpen?.(row) }}
      className="block w-full truncate rounded bg-brand/10 px-1.5 py-0.5 text-start text-xs text-brand transition-colors hover:bg-brand/20"
      title={t('viewRecord')}
    >
      {time?.(row) && <span className="me-1 tabular-nums opacity-70">{time(row)}</span>}
      {label(row)}
    </button>
  )

  const dayCell = (day: string, inMonth: boolean, minHeight: string, limit: number) => {
    const events = byDay.get(day) ?? []
    return (
      <div
        key={day}
        className={`group relative border-b border-e border-border p-1.5 ${minHeight} ${inMonth ? '' : 'bg-surface-2/30'}`}
      >
        <div className="mb-1 flex items-center justify-between">
          {onCreate ? (
            <button
              type="button"
              onClick={() => onCreate(day)}
              className="rounded p-0.5 text-muted opacity-0 transition-opacity hover:bg-surface-2 hover:text-fg focus:opacity-100 group-hover:opacity-100"
              title={t('newOnDay', { day: shortDayFormat.format(parseDay(day)) })}
              aria-label={t('newOnDay', { day: shortDayFormat.format(parseDay(day)) })}
            >
              <Plus className="h-3.5 w-3.5" />
            </button>
          ) : <span />}
          <span className={`text-xs ${day === today ? 'font-bold text-brand' : inMonth ? 'text-muted' : 'text-muted/60'}`}>{parseDay(day).getDate()}</span>
        </div>
        <div className="space-y-1">
          {events.slice(0, limit).map(event)}
          {events.length > limit && <div className="px-1.5 text-[11px] text-muted">{t('nMore', { n: events.length - limit })}</div>}
        </div>
      </div>
    )
  }

  let body: ReactNode
  if (loading && rows.length === 0) {
    body = <Skeleton className="m-4 h-80" />
  } else if (mode === 'month' || mode === 'week') {
    const first = weekStart(from)
    const days: string[] = []
    for (let day = first; day <= to || days.length % 7 !== 0; day = addDays(day, 1)) days.push(day)
    const month = parseDay(cursor).getMonth()
    body = (
      <>
        <div className="grid grid-cols-7 border-b border-border bg-surface-2/40">
          {WEEKDAYS.map(d => (
            <div key={d} className="px-2 py-2 text-center text-[11px] font-semibold uppercase tracking-wider text-muted">{d}</div>
          ))}
        </div>
        <div className="grid grid-cols-7">
          {days.map(day => dayCell(day, mode === 'week' || parseDay(day).getMonth() === month,
            mode === 'week' ? 'min-h-64' : 'min-h-24', mode === 'week' ? 12 : 4))}
        </div>
      </>
    )
  } else if (mode === 'agenda') {
    const days = [...byDay.keys()].sort()
    body = days.length === 0
      ? <p className="px-4 py-10 text-center text-sm text-muted">{t('nothingScheduled')}</p>
      : (
        <ul className="divide-y divide-border">
          {days.map(day => (
            <li key={day} className="grid gap-2 px-4 py-3 sm:grid-cols-[12rem_minmax(0,1fr)]">
              <div className={`text-sm ${day === today ? 'font-semibold text-brand' : 'text-fg'}`}>{dayFormat.format(parseDay(day))}</div>
              <div className="space-y-1">{(byDay.get(day) ?? []).map(event)}</div>
            </li>
          ))}
        </ul>
      )
  } else {
    // Timeline: one bar per row across the four weeks, from its start to its end.
    const days: string[] = []
    for (let day = from; day <= to; day = addDays(day, 1)) days.push(day)
    const placed = rows.filter(r => start(r) != null)
    body = (
      <div className="overflow-x-auto">
        <div className="min-w-[56rem]">
          <div className="grid border-b border-border bg-surface-2/40" style={{ gridTemplateColumns: `14rem repeat(${days.length}, minmax(0, 1fr))` }}>
            <div />
            {days.map(day => (
              <div key={day} className={`py-2 text-center text-[10px] ${day === today ? 'font-bold text-brand' : 'text-muted'}`}>{parseDay(day).getDate()}</div>
            ))}
          </div>
          {placed.length === 0 && <p className="px-4 py-10 text-center text-sm text-muted">{t('nothingScheduled')}</p>}
          {placed.map(row => {
            const first = start(row) as string
            const lastRaw = end?.(row)
            const last = lastRaw && lastRaw > first ? lastRaw : first
            const a = Math.max(0, days.indexOf(first < from ? from : first))
            const b = days.indexOf(last > to ? to : last)
            return (
              <div key={rowKey(row)} className="grid items-center border-b border-border" style={{ gridTemplateColumns: `14rem repeat(${days.length}, minmax(0, 1fr))` }}>
                <div className="truncate px-3 py-2 text-sm text-fg">{label(row)}</div>
                <button
                  type="button"
                  onClick={() => onOpen?.(row)}
                  className="my-1.5 h-6 rounded-md bg-brand/70 text-start text-[11px] text-white transition-colors hover:bg-brand"
                  style={{ gridColumn: `${a + 2} / ${(b < 0 ? days.length - 1 : b) + 3}` }}
                  title={t('viewRecord')}
                  aria-label={t('viewRecord')}
                />
              </div>
            )
          })}
        </div>
      </div>
    )
  }

  return (
    <div className="overflow-hidden rounded-xl border border-border bg-surface shadow-sm" aria-busy={loading}>
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-4 py-3">
        <div>
          <h2 className="text-sm font-semibold text-fg">{heading}</h2>
          {total != null && total > rows.length && (
            <p className="mt-0.5 text-xs text-muted">{t('firstNOfM', { n: rows.length, m: total })}</p>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {modes.length > 1 && (
            <div role="group" aria-label={t('calendarViews')} className="inline-flex rounded-lg border border-border bg-surface p-0.5">
              {modes.map(m => (
                <button
                  key={m}
                  type="button"
                  onClick={() => onModeChange(m)}
                  aria-pressed={mode === m}
                  className={`rounded-md px-2.5 py-1 text-xs font-medium transition-colors ${mode === m ? 'bg-brand text-white' : 'text-muted hover:text-fg'}`}
                >
                  {t(MODE_LABEL[m])}
                </button>
              ))}
            </div>
          )}
          <div className="flex items-center gap-1">
            <button type="button" onClick={() => onCursorChange(step(mode, cursor, -1))} className="rounded-lg border border-border bg-surface p-1.5 text-muted transition-colors hover:bg-surface-2 hover:text-fg" title={t('previousPeriod')} aria-label={t('previousPeriod')}>
              <ChevronLeft className="h-4 w-4 rtl:rotate-180" />
            </button>
            <button type="button" onClick={() => onCursorChange(today)} className="rounded-lg border border-border bg-surface px-2.5 py-1.5 text-xs font-medium text-muted transition-colors hover:bg-surface-2 hover:text-fg">
              {t('today')}
            </button>
            <button type="button" onClick={() => onCursorChange(step(mode, cursor, 1))} className="rounded-lg border border-border bg-surface p-1.5 text-muted transition-colors hover:bg-surface-2 hover:text-fg" title={t('nextPeriod')} aria-label={t('nextPeriod')}>
              <ChevronRight className="h-4 w-4 rtl:rotate-180" />
            </button>
          </div>
        </div>
      </div>
      {body}
    </div>
  )
}
