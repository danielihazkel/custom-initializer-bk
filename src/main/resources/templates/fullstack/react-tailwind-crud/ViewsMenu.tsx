import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Bookmark, MoreHorizontal, Star, Users } from 'lucide-react'
import { t } from '@shared/i18n'
import { fitViewState, useSavedViews } from './savedViews'
import type { SavedView, ViewState } from './savedViews'

interface Props {
  /** The list the views are of: the entity's name as its pages spell it. */
  list: string
  /** What the list shows now — what "Save" and "Update" keep. */
  current: ViewState
  /** What the list can sort by and the views it has: a saved state is fitted to them. */
  sortable: string[]
  views: string[]
  onApply: (state: ViewState) => void
  /** The list opened with nothing asked of it (no route state): the user's default view applies. */
  opensPlain: boolean
}

/**
 * The list's saved views: apply one, save what is on screen under a name, and — for a view of
 * one's own — update, rename, share, make it the default or delete it. A shared view of someone
 * else's can be applied, and saved again under one's own name.
 */
export function ViewsMenu({ list, current, sortable, views: viewModes, onApply, opensPlain }: Props) {
  const { views, loaded, save, change, remove } = useSavedViews(list)
  const box = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  /** The view whose actions are open, the one being renamed and the one asked to be deleted. */
  const [actionsFor, setActionsFor] = useState<number | null>(null)
  const [renaming, setRenaming] = useState<{ id: number; name: string } | null>(null)
  const [deleting, setDeleting] = useState<number | null>(null)

  // The user's default view, once, when the list opened with nothing asked of it.
  const defaulted = useRef(false)
  useEffect(() => {
    if (!loaded || defaulted.current) return
    defaulted.current = true
    const first = opensPlain ? views.find(v => v.mine && v.defaultView) : undefined
    if (first) onApply(fitViewState(first.state, sortable, viewModes))
  }, [loaded, views, opensPlain, onApply, sortable, viewModes])

  // A click outside or Escape closes the menu, and the menu alone.
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || e.defaultPrevented) return
      e.preventDefault()
      setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey, true)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey, true)
    }
  }, [open])

  /** Runs a change and shows why it was refused (a name already taken, say). */
  function run(action: () => Promise<unknown>, after?: () => void) {
    setBusy(true)
    setError(null)
    action()
      .then(() => after?.())
      .catch((e: unknown) => setError(e instanceof Error ? e.message : t('viewFailed')))
      .finally(() => setBusy(false))
  }

  function apply(view: SavedView) {
    onApply(fitViewState(view.state, sortable, viewModes))
    setOpen(false)
  }

  function saveNew(e: FormEvent) {
    e.preventDefault()
    const wanted = name.trim()
    if (wanted === '') return
    run(() => save(wanted, current), () => setName(''))
  }

  const action = 'rounded-md px-2 py-1 text-xs text-fg transition-colors hover:bg-surface-2 disabled:opacity-50'
  return (
    <div ref={box} className="relative">
      <button
        type="button"
        onClick={() => { setOpen(o => !o); setError(null); setActionsFor(null); setRenaming(null); setDeleting(null) }}
        className="inline-flex items-center gap-2 rounded-lg border border-border bg-surface px-3 py-2 text-sm font-medium text-fg shadow-sm transition-colors hover:bg-surface-2"
        aria-haspopup="true"
        aria-expanded={open}
      >
        <Bookmark className="h-4 w-4" />
        {t('views')}
      </button>
      {open && (
        <div className="absolute end-0 top-full z-30 mt-2 w-80 rounded-xl border border-border bg-surface p-2 text-fg shadow-xl" role="group" aria-label={t('views')}>
          {views.length === 0 && <p className="px-2 py-3 text-sm text-muted">{loaded ? t('noViews') : t('loading')}</p>}
          <ul className="max-h-72 overflow-y-auto">
            {views.map(view => (
              <li key={view.id}>
                <div className="flex items-center gap-1">
                  {renaming?.id === view.id ? (
                    <form
                      className="flex min-w-0 flex-1 items-center gap-1 p-1"
                      onSubmit={e => {
                        e.preventDefault()
                        const wanted = renaming.name.trim()
                        if (wanted !== '') run(() => change(view.id, { name: wanted }), () => setRenaming(null))
                      }}
                    >
                      <input
                        autoFocus
                        value={renaming.name}
                        maxLength={80}
                        onChange={e => setRenaming({ id: view.id, name: e.target.value })}
                        aria-label={t('viewRename')}
                        className="min-w-0 flex-1 rounded-md border border-border bg-canvas px-2 py-1 text-sm text-fg focus:border-brand focus:outline-none"
                      />
                      <button type="submit" disabled={busy} className={action}>{t('save')}</button>
                    </form>
                  ) : (
                    <button
                      type="button"
                      onClick={() => apply(view)}
                      className="flex min-w-0 flex-1 items-center gap-2 rounded-lg px-2 py-2 text-start text-sm text-fg transition-colors hover:bg-surface-2"
                    >
                      <span className="min-w-0 flex-1 truncate">{view.name}</span>
                      {view.defaultView && <Star className="h-3.5 w-3.5 shrink-0 text-brand" aria-label={t('viewDefault')} />}
                      {view.shared && <Users className="h-3.5 w-3.5 shrink-0 text-muted" aria-label={t('viewShared')} />}
                    </button>
                  )}
                  {view.mine && renaming?.id !== view.id && (
                    <button
                      type="button"
                      onClick={() => { setActionsFor(actionsFor === view.id ? null : view.id); setDeleting(null) }}
                      className="rounded-md p-1.5 text-muted transition-colors hover:bg-surface-2 hover:text-fg"
                      aria-label={t('viewActions', { name: view.name })}
                      aria-expanded={actionsFor === view.id}
                    >
                      <MoreHorizontal className="h-4 w-4" />
                    </button>
                  )}
                </div>
                {view.mine && actionsFor === view.id && renaming?.id !== view.id && (
                  <div className="mb-1 flex flex-wrap gap-1 px-1">
                    <button type="button" disabled={busy} className={action} onClick={() => run(() => change(view.id, { state: current }))}>
                      {t('viewUpdate')}
                    </button>
                    <button type="button" disabled={busy} className={action} onClick={() => setRenaming({ id: view.id, name: view.name })}>
                      {t('viewRename')}
                    </button>
                    <button type="button" disabled={busy} className={action} onClick={() => run(() => change(view.id, { shared: !view.shared }))}>
                      {view.shared ? t('viewUnshare') : t('viewShare')}
                    </button>
                    <button type="button" disabled={busy} className={action} onClick={() => run(() => change(view.id, { defaultView: !view.defaultView }))}>
                      {view.defaultView ? t('viewClearDefault') : t('viewMakeDefault')}
                    </button>
                    <button
                      type="button"
                      disabled={busy}
                      className={`${action} text-danger`}
                      onClick={() => (deleting === view.id ? run(() => remove(view.id), () => { setDeleting(null); setActionsFor(null) }) : setDeleting(view.id))}
                    >
                      {deleting === view.id ? t('viewDeleteSure') : t('delete')}
                    </button>
                  </div>
                )}
              </li>
            ))}
          </ul>
          <form onSubmit={saveNew} className="mt-1 flex items-center gap-1 border-t border-border pt-2">
            <input
              value={name}
              maxLength={80}
              onChange={e => setName(e.target.value)}
              placeholder={t('viewName')}
              aria-label={t('viewName')}
              className="min-w-0 flex-1 rounded-md border border-border bg-canvas px-2 py-1.5 text-sm text-fg placeholder:text-muted focus:border-brand focus:outline-none"
            />
            <button
              type="submit"
              disabled={busy || name.trim() === ''}
              className="rounded-md border border-border bg-surface px-3 py-1.5 text-sm font-medium text-fg transition-colors hover:bg-surface-2 disabled:opacity-50"
            >
              {t('save')}
            </button>
          </form>
          {error && <p role="alert" className="px-1 pt-2 text-xs text-danger">{error}</p>}
        </div>
      )}
    </div>
  )
}
