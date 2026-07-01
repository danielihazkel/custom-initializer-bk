import { useState } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import type { Column } from './Table'
import { Skeleton } from './Skeleton'

interface Props<T extends object> {
  /** Same column model as Table — the first column is used as each event's label. */
  columns: Column<T>[]
  rows: T[]
  /** Field the records are placed by (a LOCAL_DATE / LOCAL_DATE_TIME value). */
  dateField: string
  loading: boolean
  onView?: (row: T) => void
}

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December']

/** Local yyyy-mm-dd key for a Date, for bucketing without timezone drift. */
function dayKey(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/**
 * Month-grid calendar: records are bucketed onto the day of their `dateField`. Prev/next step the
 * month; clicking a record opens its detail via `onView`. Pure client-side date math — no extra
 * endpoint, so only the currently-loaded page of records is shown.
 */
export function CalendarView<T extends object>({ columns, rows, dateField, loading, onView }: Props<T>) {
  const today = new Date()
  const [cursor, setCursor] = useState({ year: today.getFullYear(), month: today.getMonth() })
  const heading = columns[0]

  // Bucket rows by local day key.
  const byDay = new Map<string, T[]>()
  for (const row of rows) {
    const raw = (row as Record<string, unknown>)[dateField]
    if (raw == null) continue
    const d = new Date(String(raw))
    if (Number.isNaN(d.getTime())) continue
    const key = dayKey(d)
    const list = byDay.get(key)
    if (list) list.push(row); else byDay.set(key, [row])
  }

  const first = new Date(cursor.year, cursor.month, 1)
  const startOffset = first.getDay()
  const daysInMonth = new Date(cursor.year, cursor.month + 1, 0).getDate()
  // Pad to whole weeks so the grid is rectangular.
  const cells: Array<Date | null> = []
  for (let i = 0; i < startOffset; i++) cells.push(null)
  for (let d = 1; d <= daysInMonth; d++) cells.push(new Date(cursor.year, cursor.month, d))
  while (cells.length % 7 !== 0) cells.push(null)

  function step(delta: number) {
    setCursor(c => {
      const m = c.month + delta
      return { year: c.year + Math.floor(m / 12), month: ((m % 12) + 12) % 12 }
    })
  }

  if (loading) return <Skeleton className="h-96 w-full" />

  return (
    <div className="overflow-hidden rounded-xl border border-border bg-surface shadow-sm">
      <div className="flex items-center justify-between border-b border-border px-4 py-3">
        <h2 className="text-sm font-semibold text-fg">{MONTHS[cursor.month]} {cursor.year}</h2>
        <div className="flex items-center gap-1">
          <button onClick={() => step(-1)} className="rounded-lg border border-border bg-surface p-1.5 text-muted transition-colors hover:bg-surface-2 hover:text-fg" title="Previous month" aria-label="Previous month">
            <ChevronLeft className="h-4 w-4" />
          </button>
          <button onClick={() => setCursor({ year: today.getFullYear(), month: today.getMonth() })} className="rounded-lg border border-border bg-surface px-2.5 py-1.5 text-xs font-medium text-muted transition-colors hover:bg-surface-2 hover:text-fg">
            Today
          </button>
          <button onClick={() => step(1)} className="rounded-lg border border-border bg-surface p-1.5 text-muted transition-colors hover:bg-surface-2 hover:text-fg" title="Next month" aria-label="Next month">
            <ChevronRight className="h-4 w-4" />
          </button>
        </div>
      </div>
      <div className="grid grid-cols-7 border-b border-border bg-surface-2/40">
        {WEEKDAYS.map(d => (
          <div key={d} className="px-2 py-2 text-center text-[11px] font-semibold uppercase tracking-wider text-muted">{d}</div>
        ))}
      </div>
      <div className="grid grid-cols-7">
        {cells.map((date, i) => {
          const key = date ? dayKey(date) : `pad-${i}`
          const events = date ? (byDay.get(key) ?? []) : []
          const isToday = date != null && key === dayKey(today)
          return (
            <div key={key} className={`min-h-24 border-b border-e border-border p-1.5 ${date ? '' : 'bg-surface-2/20'}`}>
              {date && (
                <>
                  <div className={`mb-1 text-end text-xs ${isToday ? 'font-bold text-brand' : 'text-muted'}`}>{date.getDate()}</div>
                  <div className="space-y-1">
                    {events.slice(0, 4).map((row, j) => (
                      <button
                        key={(row as { id?: number | string | null }).id ?? j}
                        onClick={() => onView?.(row)}
                        className="block w-full truncate rounded bg-brand/10 px-1.5 py-0.5 text-start text-xs text-brand transition-colors hover:bg-brand/20"
                        title="View record"
                      >
                        {heading ? heading.render(row) : 'Record'}
                      </button>
                    ))}
                    {events.length > 4 && <div className="px-1.5 text-[11px] text-muted">+{events.length - 4} more</div>}
                  </div>
                </>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
