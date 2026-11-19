import { useEffect, useState } from 'react'
import { api, ApiError } from '@shared/api'
import { LOCALE, t } from '@shared/i18n'

/** One entry of a row's change log (GET /api/<entity>/{id}/history). */
interface Change {
  id: number
  action: 'CREATE' | 'UPDATE' | 'DELETE' | 'RESTORE'
  at: string
  by: string
  fields: string[]
  before: Record<string, unknown> | null
  after: Record<string, unknown> | null
}

/** How a field reads in the history: its label, and an enum's labels by constant. */
export interface HistoryField {
  label: string
  values?: Record<string, string>
}

interface Props {
  /** The row's own address: `/api/<entity>/<id>`. */
  path: string
  fields: Record<string, HistoryField>
  /** Whether the caller may put the row back to an earlier version (the backend checks again). */
  canRestore: boolean
  /** Changes when the row has changed, so the list reads again. */
  version?: unknown
  /** The row as a restore left it. */
  onRestored?: (row: unknown) => void
}

const WHEN = new Intl.DateTimeFormat(LOCALE, { dateStyle: 'medium', timeStyle: 'short' })
const ISO_DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/

const ACTION: Record<Change['action'], () => string> = {
  CREATE: () => t('changeCreated'),
  UPDATE: () => t('changeUpdated'),
  DELETE: () => t('changeDeleted'),
  RESTORE: () => t('changeRestored'),
}

function show(field: HistoryField | undefined, value: unknown): string {
  if (value == null || value === '') return '—'
  if (typeof value === 'boolean') return value ? t('trueLabel') : t('falseLabel')
  if (typeof value === 'string') {
    if (field?.values?.[value]) return field.values[value]
    if (ISO_DATE_TIME.test(value)) {
      const date = new Date(value)
      if (!Number.isNaN(date.getTime())) return WHEN.format(date)
    }
    return value
  }
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

/**
 * A row's change history, newest first: who changed what, from what to what, and when — read
 * from the log the backend keeps with every change. An earlier version can be put back; if the
 * row changed again meanwhile, the restore asks first.
 */
export function ChangeHistory({ path, fields, canRestore, version, onRestored }: Props) {
  const [changes, setChanges] = useState<Change[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [busy, setBusy] = useState<number | null>(null)
  const [conflict, setConflict] = useState<{ id: number; who: string } | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    setFailed(false)
    api.get<Change[]>(`${path}/history`)
      .then(found => { if (active) setChanges(found) })
      .catch(() => { if (active) setFailed(true) })
    return () => { active = false }
  }, [path, version, attempt])

  async function restore(change: Change, force: boolean) {
    setBusy(change.id)
    setMessage(null)
    try {
      const answer = await api.post<{ row: unknown; skipped?: string[] }>(
        `${path}/history/${change.id}/restore`, { latest: changes?.[0]?.id, force })
      setConflict(null)
      // A field the old version had and the row no longer has (renamed or removed since) is named.
      const skipped = answer.skipped ?? []
      setMessage(skipped.length > 0 ? t('versionRestoredWithout', { fields: skipped.join(', ') }) : t('versionRestored'))
      setAttempt(a => a + 1)
      onRestored?.(answer.row)
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        // Someone changed the row after this list was read: show their change, then ask.
        const fresh = await api.get<Change[]>(`${path}/history`).catch(() => null)
        if (fresh) setChanges(fresh)
        setConflict({ id: change.id, who: fresh?.[0]?.by ?? '' })
      } else {
        setMessage(err instanceof Error ? err.message : String(err))
      }
    } finally {
      setBusy(null)
    }
  }

  return (
    <section className="mt-6 rounded-2xl border border-border bg-surface p-5 shadow-sm" aria-label={t('changeHistory')} data-change-history>
      <h2 className="text-sm font-semibold text-fg">{t('changeHistory')}</h2>
      {failed && (
        <p className="mt-3 text-sm text-muted">
          {t('couldNotLoad')}{' '}
          <button type="button" onClick={() => setAttempt(a => a + 1)} className="font-medium text-brand underline">{t('retry')}</button>
        </p>
      )}
      {!failed && changes == null && <p className="mt-3 text-sm text-muted">{t('loading')}</p>}
      {!failed && changes != null && changes.length === 0 && <p className="mt-3 text-sm text-muted">{t('noChanges')}</p>}
      {message && <p role="status" className="mt-3 text-sm text-muted">{message}</p>}
      {!failed && changes != null && changes.length > 0 && (
        <ol className="mt-3 space-y-4">
          {changes.map((change, i) => (
            <li key={change.id} className="flex gap-3 text-sm" data-change={change.action}>
              <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-brand" aria-hidden="true" />
              <div className="min-w-0 flex-1">
                <div className="text-fg">
                  <span className="font-medium">{ACTION[change.action]()}</span>
                  <span className="text-muted" dir="ltr"> · {change.by}</span>
                </div>
                <div className="text-xs text-muted">
                  <time dateTime={change.at}>{WHEN.format(new Date(change.at))}</time>
                </div>
                {change.fields.length > 0 && change.before && change.after && (
                  <dl className="mt-1.5 space-y-0.5 text-xs">
                    {change.fields.map(name => (
                      <div key={name} className="flex flex-wrap gap-x-1.5">
                        <dt className="text-muted">{fields[name]?.label ?? name}:</dt>
                        <dd className="text-fg">
                          <span className="line-through decoration-muted/60">{show(fields[name], change.before?.[name])}</span>
                          {' → '}
                          <span>{show(fields[name], change.after?.[name])}</span>
                        </dd>
                      </div>
                    ))}
                  </dl>
                )}
                {canRestore && i > 0 && change.after && (
                  conflict?.id === change.id ? (
                    <div role="alert" className="mt-2 rounded-lg border border-border bg-surface-2 p-2 text-xs text-fg">
                      {t('changedSince', { who: conflict.who })}{' '}
                      <button type="button" disabled={busy !== null} onClick={() => void restore(change, true)} className="font-medium text-brand underline">
                        {t('restoreAnyway')}
                      </button>{' '}
                      <button type="button" onClick={() => setConflict(null)} className="font-medium text-muted underline">{t('cancel')}</button>
                    </div>
                  ) : (
                    <button type="button" disabled={busy !== null} onClick={() => void restore(change, false)}
                            className="mt-1.5 text-xs font-medium text-brand hover:underline disabled:opacity-50">
                      {busy === change.id ? t('restoring') : t('restoreVersion')}
                    </button>
                  )
                )}
              </div>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
