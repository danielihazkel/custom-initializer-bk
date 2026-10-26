// Day arithmetic for the calendar pages, on local yyyy-mm-dd keys (no timezone drift). Kept out of
// ScheduleView.tsx: a component file may export only components (the generated lint's react-refresh rule).

/** The views a calendar page can offer. */
export type ScheduleMode = 'month' | 'week' | 'agenda' | 'timeline'

const pad = (n: number) => String(n).padStart(2, '0')

/** Local yyyy-mm-dd of a Date, for bucketing without timezone drift. */
export function dayKey(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** Today, as a day key. */
export const todayKey = () => dayKey(new Date())

/** A day key back to a local Date (noon, so a DST change never moves it to another day). */
export function parseDay(key: string): Date {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(key)
  if (!m) return new Date()
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), 12)
}

/** Whether a string is a day key the calendar can open on. */
export const isDayKey = (value: string | undefined): value is string => value != null && /^\d{4}-\d{2}-\d{2}$/.test(value)

export function addDays(key: string, n: number): string {
  const d = parseDay(key)
  d.setDate(d.getDate() + n)
  return dayKey(d)
}

/** The Sunday a day's week starts on (the grid always starts on Sunday). */
export function weekStart(key: string): string {
  return addDays(key, -parseDay(key).getDay())
}

/** The days a mode shows around `cursor`, first and last included — what the page fetches. */
export function windowOf(mode: ScheduleMode, cursor: string): { from: string; to: string } {
  const d = parseDay(cursor)
  if (mode === 'month') {
    return { from: dayKey(new Date(d.getFullYear(), d.getMonth(), 1, 12)), to: dayKey(new Date(d.getFullYear(), d.getMonth() + 1, 0, 12)) }
  }
  if (mode === 'week') {
    const from = weekStart(cursor)
    return { from, to: addDays(from, 6) }
  }
  if (mode === 'timeline') {
    const from = weekStart(cursor)
    return { from, to: addDays(from, 27) }
  }
  return { from: cursor, to: addDays(cursor, 29) }
}

/** Where Previous / Next move the cursor. */
export function step(mode: ScheduleMode, cursor: string, delta: number): string {
  if (mode === 'month') {
    const d = parseDay(cursor)
    return dayKey(new Date(d.getFullYear(), d.getMonth() + delta, 1, 12))
  }
  return addDays(cursor, delta * (mode === 'week' ? 7 : mode === 'timeline' ? 28 : 30))
}
