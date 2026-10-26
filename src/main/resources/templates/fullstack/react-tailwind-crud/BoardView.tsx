import { useState, type ReactNode } from 'react'
import { Skeleton } from './Skeleton'
import { t } from '../i18n'

/** One lane of a board page: its value of the lane field, the cards loaded so far and how many it holds. */
export interface BoardLane<T> {
  value: string
  label: string
  rows: T[]
  /** How many rows the lane really holds (the list endpoint's total). */
  total: number
  /** The most cards the lane should hold (a work-in-progress limit); absent: no limit. */
  limit?: number
  loading: boolean
  failed: boolean
}

interface Props<T extends object> {
  lanes: BoardLane<T>[]
  /** Stable identity for a card (its primary key). */
  rowKey: (row: T) => string | number
  /** What a card shows. */
  renderCard: (row: T) => ReactNode
  onOpen?: (row: T) => void
  /** Moves a card to another lane (drag and drop, or the card's "Move to" menu); absent: read-only. */
  onMove?: (row: T, from: string, to: string) => void
  /** Loads the next cards of a lane. */
  onLoadMore: (lane: string) => void
  /** Loads a lane again after it failed. */
  onRetry: (lane: string) => void
}

/**
 * A board page's lanes: one column per value of the lane field, each loading its own cards a page at
 * a time. Cards move by dragging or — on touch and from the keyboard — by their "Move to" menu.
 */
export function BoardView<T extends object>({ lanes, rowKey, renderCard, onOpen, onMove, onLoadMore, onRetry }: Props<T>) {
  const [drag, setDrag] = useState<{ key: string | number; from: string } | null>(null)
  const [over, setOver] = useState<string | null>(null)

  function drop(to: string) {
    setOver(null)
    if (!drag || !onMove || drag.from === to) { setDrag(null); return }
    const row = lanes.find(l => l.value === drag.from)?.rows.find(r => rowKey(r) === drag.key)
    setDrag(null)
    if (row) onMove(row, drag.from, to)
  }

  return (
    <div className="flex gap-4 overflow-x-auto pb-2">
      {lanes.map(lane => {
        const full = lane.limit != null && lane.total >= lane.limit
        const over_ = lane.limit != null && lane.total > lane.limit
        return (
          <section
            key={lane.value}
            aria-label={lane.label}
            onDragOver={e => { if (onMove && drag) { e.preventDefault(); setOver(lane.value) } }}
            onDragLeave={() => setOver(o => (o === lane.value ? null : o))}
            onDrop={e => { e.preventDefault(); drop(lane.value) }}
            className={`flex w-72 shrink-0 flex-col rounded-xl border bg-surface-2/40 p-3 transition-colors ${
              over === lane.value ? 'border-brand bg-brand/5' : 'border-border'}`}
          >
            <header className="mb-3 flex items-center justify-between gap-2">
              <h2 className="truncate text-sm font-semibold text-fg">{lane.label}</h2>
              <span
                className={`rounded-full px-2 py-0.5 text-xs tabular-nums ${over_ ? 'bg-danger/10 font-semibold text-danger' : full ? 'bg-amber-500/15 text-fg' : 'bg-surface text-muted'}`}
                title={lane.limit != null ? t('laneLimit', { n: lane.limit }) : undefined}
              >
                {lane.limit != null ? `${lane.total} / ${lane.limit}` : lane.total}
              </span>
            </header>
            <div className="flex-1 space-y-2">
              {lane.loading && lane.rows.length === 0 && (
                <>
                  <Skeleton className="h-20 w-full" />
                  <Skeleton className="h-20 w-full" />
                </>
              )}
              {lane.failed && (
                <div className="rounded-lg border border-danger/30 bg-danger/5 px-3 py-2 text-xs text-danger">
                  {t('couldNotLoad')}{' '}
                  <button type="button" onClick={() => onRetry(lane.value)} className="font-semibold underline underline-offset-2">{t('retry')}</button>
                </div>
              )}
              {!lane.loading && !lane.failed && lane.rows.length === 0 && (
                <div className="rounded-lg border border-dashed border-border px-3 py-6 text-center text-xs text-muted">{t('emptyLane')}</div>
              )}
              {lane.rows.map(row => (
                <article
                  key={rowKey(row)}
                  draggable={!!onMove}
                  onDragStart={() => setDrag({ key: rowKey(row), from: lane.value })}
                  onDragEnd={() => { setDrag(null); setOver(null) }}
                  className={`group rounded-lg border border-border bg-surface p-3 text-sm shadow-sm ${onMove ? 'cursor-grab' : ''} ${
                    drag?.key === rowKey(row) ? 'opacity-40' : ''}`}
                >
                  <button type="button" onClick={() => onOpen?.(row)} className="block w-full text-start" title={t('viewRecord')}>
                    {renderCard(row)}
                  </button>
                  {onMove && lanes.length > 1 && (
                    <select
                      value=""
                      onChange={e => { if (e.target.value) onMove(row, lane.value, e.target.value) }}
                      aria-label={t('moveTo')}
                      className="mt-2 w-full rounded-md border border-border bg-canvas px-2 py-1 text-xs text-muted opacity-0 transition-opacity focus:opacity-100 group-hover:opacity-100 group-focus-within:opacity-100 [@media(hover:none)]:opacity-100"
                    >
                      <option value="">{t('moveTo')}…</option>
                      {lanes.filter(l => l.value !== lane.value).map(l => (
                        <option key={l.value} value={l.value}>{l.label}</option>
                      ))}
                    </select>
                  )}
                </article>
              ))}
              {lane.rows.length < lane.total && !lane.failed && (
                <button
                  type="button"
                  onClick={() => onLoadMore(lane.value)}
                  disabled={lane.loading}
                  className="w-full rounded-lg px-3 py-2 text-xs font-medium text-muted transition-colors hover:bg-surface hover:text-fg disabled:opacity-50"
                >
                  {lane.loading ? t('loading') : t('loadMoreN', { n: lane.total - lane.rows.length })}
                </button>
              )}
            </div>
          </section>
        )
      })}
    </div>
  )
}
