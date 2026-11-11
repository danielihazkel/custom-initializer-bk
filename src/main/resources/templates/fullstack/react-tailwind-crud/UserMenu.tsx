import { useCallback, useRef, useState } from 'react'
import { User } from 'lucide-react'
import { useMe } from '@shared/api/me'
import { t } from '@shared/i18n'
import { useDismiss } from './dismiss'

interface Props {
  /** The app has the Ctrl+K palette: its shortcut is listed with the others. */
  palette?: boolean
}

/** Two letters to stand for a user id: `dana.levi` → DL, `u12345` → U1. */
function initials(id: string): string {
  const [first = id, second] = id.split(/[^\p{L}\p{N}]+/u).filter(Boolean)
  const letters = second ? first.slice(0, 1) + second.slice(0, 1) : first.slice(0, 2)
  return letters.toUpperCase()
}

/**
 * Who is signed in — the user id the backend sees and the roles it grants (GET /api/me) — and the
 * keyboard shortcuts every list has. Signing in and out are the gateway's, so there is no such
 * button here.
 */
export function UserMenu({ palette }: Props) {
  const me = useMe()
  const box = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
  const close = useCallback(() => setOpen(false), [])
  useDismiss(box, open, close)

  const shortcut = (keys: string, what: string) => (
    <li className="flex items-center justify-between gap-3 py-1 text-sm text-fg">
      <span>{what}</span>
      <kbd className="rounded border border-border px-1.5 text-[11px] font-medium text-muted" dir="ltr">{keys}</kbd>
    </li>
  )

  return (
    <div ref={box} className="relative">
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        className="flex h-[34px] w-[34px] items-center justify-center rounded-full border border-border bg-surface text-xs font-semibold text-fg transition-colors hover:bg-surface-2"
        title={t('userMenu')}
        aria-label={t('userMenu')}
        aria-haspopup="true"
        aria-expanded={open}
      >
        {me.id ? initials(me.id) : <User className="h-4 w-4 text-muted" />}
      </button>
      {open && (
        <div className="absolute end-0 top-full z-40 mt-2 w-64 rounded-xl border border-border bg-surface p-3 text-fg shadow-xl" role="group" aria-label={t('userMenu')}>
          <div className="text-[11px] font-semibold uppercase tracking-wider text-muted">{t('signedInAs')}</div>
          <div className="mt-0.5 break-all text-sm font-semibold" dir="ltr">{me.id ?? t('notSignedIn')}</div>
          {me.roles.length > 0 && (
            <div className="mt-2 flex flex-wrap gap-1.5">
              {me.roles.map(role => (
                <span key={role} className="rounded-full bg-brand/10 px-2 py-0.5 text-xs font-medium text-brand">{role}</span>
              ))}
            </div>
          )}
          <div className="mt-3 border-t border-border pt-2 text-[11px] font-semibold uppercase tracking-wider text-muted">{t('shortcuts')}</div>
          <ul className="mt-1">
            {palette && shortcut('Ctrl K', t('shortcutPalette'))}
            {shortcut('/', t('shortcutSearch'))}
            {shortcut('N', t('shortcutNew'))}
          </ul>
        </div>
      )}
    </div>
  )
}
