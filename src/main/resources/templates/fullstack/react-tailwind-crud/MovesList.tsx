import { useEffect, useState } from 'react'
import { api } from '@shared/api'
import { LOCALE, t } from '@shared/i18n'

/** One step of a row through its workflow (GET /api/<entity>/{id}/moves). */
interface Move {
  from: string | null
  to: string
  transition: string | null
  actor: string | null
  at: string
}

interface Props {
  /** The row's own address: `/api/<entity>/<id>`. */
  path: string
  /** The status field's labels, by constant. */
  labels: Record<string, string>
  /** The transitions' labels, by name; one without a label reads as its name. */
  transitions?: Record<string, string>
  /** Changes when the row has moved, so the list reads again. */
  version?: unknown
}

const WHEN = new Intl.DateTimeFormat(LOCALE, { dateStyle: 'medium', timeStyle: 'short' })

/**
 * A row's history: where it started and every move since — which transition, by whom, when —
 * newest first. Read from the workflow's move log, which the backend writes with each move.
 */
export function MovesList({ path, labels, transitions, version }: Props) {
  const [moves, setMoves] = useState<Move[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let active = true
    setFailed(false)
    api.get<Move[]>(`${path}/moves`)
      .then(found => { if (active) setMoves(found) })
      .catch(() => { if (active) setFailed(true) })
    return () => { active = false }
  }, [path, version, attempt])

  const state = (key: string) => labels[key] ?? key
  return (
    <section className="mt-6 rounded-2xl border border-border bg-surface p-5 shadow-sm" aria-label={t('history')}>
      <h2 className="text-sm font-semibold text-fg">{t('history')}</h2>
      {failed && (
        <p className="mt-3 text-sm text-muted">
          {t('couldNotLoad')}{' '}
          <button type="button" onClick={() => setAttempt(a => a + 1)} className="font-medium text-brand underline">{t('retry')}</button>
        </p>
      )}
      {!failed && moves == null && <p className="mt-3 text-sm text-muted">{t('loading')}</p>}
      {!failed && moves != null && moves.length === 0 && <p className="mt-3 text-sm text-muted">{t('historyEmpty')}</p>}
      {!failed && moves != null && moves.length > 0 && (
        <ol className="mt-3 space-y-3">
          {[...moves].reverse().map((move, i) => (
            <li key={i} className="flex gap-3 text-sm">
              <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-brand" aria-hidden="true" />
              <div className="min-w-0">
                <div className="text-fg">
                  {move.from == null
                    ? t('historyCreated', { state: state(move.to) })
                    : t('historyMoved', { from: state(move.from), to: state(move.to) })}
                  {move.transition && <span className="text-muted"> · {transitions?.[move.transition] ?? move.transition}</span>}
                </div>
                <div className="text-xs text-muted">
                  <time dateTime={move.at}>{WHEN.format(new Date(move.at))}</time>
                  {move.actor && <span dir="ltr"> · {move.actor}</span>}
                </div>
              </div>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
