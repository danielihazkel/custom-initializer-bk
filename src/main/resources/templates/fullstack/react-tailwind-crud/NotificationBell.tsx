import { useCallback, {{#hasEvents}}useContext, {{/hasEvents}}useEffect, useRef, useState } from 'react'
import { Bell } from 'lucide-react'
import { api{{#hasEvents}}, RefreshTick{{/hasEvents}} } from '@shared/api'
import { t } from '@shared/i18n'
import { useDismiss } from './dismiss'

/** An entity with a workflow whose tasks are assigned: what the bell counts. */
export interface BellSource {
  /** The entity's name in the plural. */
  label: string
  /** Its list endpoint, which takes `?inbox=`. */
  path: string
  /** The workflow's due date as a list filter — `<field>To` — and whether it holds a time too. */
  due?: { param: string; dateTime: boolean }
  /** Where its tasks are worked on: its inbox page, else its list. */
  page?: string
  /** The queues that page offers as tabs (an inbox page only): a count opens on its own queue. */
  queues?: readonly string[]
}

interface Props {
  sources: readonly BellSource[]
  onNavigate: (page: string, arg?: string, query?: Record<string, string>) => void
}

interface Counts {
  mine: number
  overdue: number
  unassigned: number
}

/** The largest "assigned to you" total this browser has been shown: more than that is news. */
const SEEN_KEY = 'bell-seen'
/** How often the counts are read again while the tab is in view. */
const EVERY_MS = 60_000

function readSeen(): number {
  try {
    const seen = Number(localStorage.getItem(SEEN_KEY))
    return Number.isFinite(seen) && seen > 0 ? seen : 0
  } catch {
    return 0
  }
}

function writeSeen(seen: number) {
  try {
    localStorage.setItem(SEEN_KEY, String(seen))
  } catch {
    // Storage is off: every count is news, which is the safe side.
  }
}

/** Yesterday as the last moment that is overdue: a date, or the end of that day. */
function overdueUntil(dateTime: boolean): string {
  const d = new Date()
  d.setDate(d.getDate() - 1)
  const day = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return dateTime ? `${day}T23:59:59` : day
}

const total = (path: string, params: string) =>
  api.get<{ totalElements: number }>(`${path}?${params}&size=1`).then(page => page.totalElements)

/**
 * What is waiting for the signed-in user, from the workflows' own queues — nothing is stored for
 * it: per entity, the tasks assigned to them, those of them past their due date, and the tasks
 * nobody has taken. The number on the bell is "assigned to you"; it stands out while it is higher
 * than this browser has seen.
 */
export function NotificationBell({ sources, onNavigate }: Props) {
  const box = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
  const [counts, setCounts] = useState<Counts[] | null>(null)
  const [seen, setSeen] = useState(readSeen)
  const asked = useRef(0)
  const close = useCallback(() => setOpen(false), [])
  useDismiss(box, open, close)

  const load = useCallback(() => {
    const turn = ++asked.current
    void Promise.all(sources.map(async (s): Promise<Counts> => {
      const [mine, overdue, unassigned] = await Promise.all([
        total(s.path, 'inbox=mine'),
        s.due ? total(s.path, `inbox=mine&${s.due.param}=${overdueUntil(s.due.dateTime)}`) : Promise.resolve(0),
        total(s.path, 'inbox=unassigned'),
      ])
      return { mine, overdue, unassigned }
    }))
      .then(next => { if (turn === asked.current) setCounts(next) })
      // A failed read keeps the last counts: the bell is a hint, not a page.
      .catch(() => undefined)
  }, [sources])

  useEffect(() => {
    load()
    const tick = () => { if (document.visibilityState === 'visible') load() }
    const timer = setInterval(tick, EVERY_MS)
    window.addEventListener('focus', tick)
    return () => {
      clearInterval(timer)
      window.removeEventListener('focus', tick)
    }
  }, [load])
{{#hasEvents}}
  // A change the server pushed (LiveUpdates): read the counts again now.
  const live = useContext(RefreshTick)
  useEffect(() => {
    if (live > 0) load()
  }, [live, load])
{{/hasEvents}}

  const mine = counts?.reduce((sum, c) => sum + c.mine, 0) ?? 0
  // Fewer than were seen: the mark comes down with them, so the next new task is news again.
  useEffect(() => {
    if (counts != null && mine < seen) {
      setSeen(mine)
      writeSeen(mine)
    }
  }, [counts, mine, seen])
  const fresh = mine > seen

  function toggle() {
    if (!open) {
      load()
      setSeen(mine)
      writeSeen(mine)
    }
    setOpen(o => !o)
  }

  function go(source: BellSource, queue: string) {
    if (!source.page) return
    setOpen(false)
    onNavigate(source.page, undefined, source.queues?.includes(queue) ? { _queue: queue } : {})
  }

  const row = (source: BellSource, key: string, label: string, n: number, queue: string, danger = false) => n > 0 && (
    <li key={key}>
      <button
        type="button"
        disabled={!source.page}
        onClick={() => go(source, queue)}
        className="flex w-full items-center justify-between gap-3 rounded-lg px-2.5 py-2 text-start text-sm text-fg transition-colors enabled:hover:bg-surface-2"
      >
        <span>{label}</span>
        <span className={`rounded-full px-2 py-0.5 text-xs font-semibold tabular-nums ${danger ? 'bg-danger/10 text-danger' : 'bg-surface-2 text-muted'}`}>{n}</span>
      </button>
    </li>
  )
  const waiting = counts?.some(c => c.mine > 0 || c.unassigned > 0) ?? false

  return (
    <div ref={box} className="relative">
      <button
        type="button"
        onClick={toggle}
        className="relative rounded-lg border border-border p-2 text-muted transition-colors hover:bg-surface-2 hover:text-fg"
        title={t('notifications')}
        aria-label={mine > 0 ? t('notificationsN', { n: mine }) : t('notifications')}
        aria-haspopup="true"
        aria-expanded={open}
      >
        <Bell className="h-4 w-4" />
        {mine > 0 && (
          <span
            className={`absolute -end-1.5 -top-1.5 min-w-[18px] rounded-full px-1 text-center text-[10px] font-semibold leading-[18px] tabular-nums ${
              fresh ? 'bg-danger text-white' : 'border border-border bg-surface text-muted'
            }`}
            aria-hidden="true"
          >
            {mine > 99 ? '99+' : mine}
          </span>
        )}
      </button>
      {open && (
        <div className="absolute end-0 top-full z-40 mt-2 w-72 rounded-xl border border-border bg-surface p-2 text-fg shadow-xl" role="group" aria-label={t('notifications')}>
          <div className="px-2.5 pb-1 pt-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted">{t('notifications')}</div>
          {counts == null && <p className="px-2.5 py-3 text-sm text-muted">{t('loading')}</p>}
          {counts != null && !waiting && <p className="px-2.5 py-3 text-sm text-muted">{t('bellNothing')}</p>}
          {counts != null && waiting && sources.map((source, i) => {
            const c = counts[i]
            if (!c || (c.mine === 0 && c.unassigned === 0)) return null
            return (
              <div key={source.path}>
                {sources.length > 1 && <div className="px-2.5 pt-2 text-xs font-medium text-muted">{source.label}</div>}
                <ul>
                  {row(source, 'mine', t('bellMine'), c.mine, 'mine')}
                  {row(source, 'overdue', t('bellOverdue'), c.overdue, 'mine', true)}
                  {row(source, 'unassigned', t('bellUnassigned'), c.unassigned, 'unassigned')}
                </ul>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
