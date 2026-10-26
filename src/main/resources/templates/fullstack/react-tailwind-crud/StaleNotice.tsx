import { RefreshCw } from 'lucide-react'
import { t } from '../i18n'

interface Props {
  busy?: boolean
  /** Throw the draft away and edit the record as it is now. */
  onReload: () => void
  /** Save the draft over the other change. */
  onOverwrite: () => void
}

/** Shown in a form when its save was refused because someone else saved the record first
 *  (optimistic locking): the user picks whose version wins instead of losing their edits. */
export function StaleNotice({ busy, onReload, onOverwrite }: Props) {
  return (
    <div role="alert" className="mb-4 rounded-lg border border-danger/20 bg-danger/5 px-4 py-3 text-sm text-fg">
      <p className="flex items-start gap-2 font-medium text-danger">
        <RefreshCw className="mt-0.5 h-4 w-4 shrink-0" />
        {t('staleTitle')}
      </p>
      <p className="mt-1 text-muted">{t('staleBlurb')}</p>
      <div className="mt-3 flex flex-wrap gap-2">
        <button
          type="button"
          onClick={onReload}
          disabled={busy}
          className="rounded-lg border border-border bg-surface px-3 py-1.5 text-sm font-medium text-fg transition-colors hover:bg-surface-2 disabled:opacity-50"
        >
          {t('loadTheirs')}
        </button>
        <button
          type="button"
          onClick={onOverwrite}
          disabled={busy}
          className="rounded-lg border border-danger/30 px-3 py-1.5 text-sm font-medium text-danger transition-colors hover:bg-danger/10 disabled:opacity-50"
        >
          {t('saveMineAnyway')}
        </button>
      </div>
    </div>
  )
}
