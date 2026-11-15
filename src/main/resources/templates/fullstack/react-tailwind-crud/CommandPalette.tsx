import { useEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { CornerDownLeft, FileText, History, Search } from 'lucide-react'
import { api } from '@shared/api'
import { t } from '@shared/i18n'

/** A page of the app the palette can open. */
export interface PalettePage {
  id: string
  label: string
}

/** An entity whose rows the palette searches by name. */
export interface PaletteEntity {
  /** The entity's name in the plural — the heading over its matches. */
  label: string
  /** Its list endpoint, which takes `?q=`. */
  path: string
  keyOf: (row: Record<string, unknown>) => string
  labelOf: (row: Record<string, unknown>) => string
  /** The page a match opens on with its key; without one, `listPage` opens with the words searched. */
  recordPage?: string
  listPage?: string
}

interface Props {
  pages: readonly PalettePage[]
  entities: readonly PaletteEntity[]
  onNavigate: (page: string, arg?: string, query?: Record<string, string>) => void
}

/** One line of the list: where it goes and how it reads. */
interface Item {
  key: string
  kind: 'page' | 'recent' | 'match'
  label: string
  /** The group a match belongs to (its entity), shown beside it. */
  hint?: string
  page: string
  arg?: string
  query?: Record<string, string>
}

interface Recent {
  page: string
  arg: string
  label: string
  hint: string
}

/** Words shorter than this search no rows (every row would match). */
const MIN_LENGTH = 2
/** Matches listed per entity. */
const PER_ENTITY = 5
/** Records the palette remembers having opened, per browser. */
const RECENT_KEY = 'palette-recent'
const MAX_RECENT = 6

function readRecent(): Recent[] {
  try {
    const stored: unknown = JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]')
    if (!Array.isArray(stored)) return []
    return stored.filter((r): r is Recent =>
      r != null && typeof r.page === 'string' && typeof r.arg === 'string' && typeof r.label === 'string' && typeof r.hint === 'string')
  } catch {
    return []
  }
}

function remember(item: Item) {
  if (item.kind === 'page' || item.arg == null) return
  const next: Recent = { page: item.page, arg: item.arg, label: item.label, hint: item.hint ?? '' }
  const rest = readRecent().filter(r => !(r.page === next.page && r.arg === next.arg))
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify([next, ...rest].slice(0, MAX_RECENT)))
  } catch {
    // Storage is full or off: the palette works without its memory.
  }
}

/**
 * "Go to anything": a page by its name, or a record by its own — every searchable entity is asked
 * at once. Opens from its button and with Ctrl+K (⌘K); arrows move, Enter opens, Escape closes.
 */
export function CommandPalette({ pages, entities, onNavigate }: Props) {
  const [open, setOpen] = useState(false)
  const [words, setWords] = useState('')
  const [matches, setMatches] = useState<Item[]>([])
  const [searching, setSearching] = useState(false)
  const [active, setActive] = useState(0)
  const [recent, setRecent] = useState<Recent[]>([])
  const input = useRef<HTMLInputElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const asked = useRef(0)

  // Ctrl+K anywhere — also while a field has the focus.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setOpen(o => !o)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  // Every opening starts empty, with what was opened last.
  useEffect(() => {
    if (!open) return
    setWords('')
    setMatches([])
    setActive(0)
    setRecent(readRecent())
    input.current?.focus()
    // Closed, the focus goes back to where the palette was opened from.
    const button = trigger.current
    return () => button?.focus()
  }, [open])

  // The rows that match, a moment after the typing stops; an older answer never overwrites a newer one.
  const q = words.trim()
  useEffect(() => {
    const turn = ++asked.current
    if (!open || q.length < MIN_LENGTH || entities.length === 0) {
      setMatches([])
      setSearching(false)
      return
    }
    setSearching(true)
    const timer = setTimeout(() => {
      void Promise.all(entities.map(entity =>
        api.get<{ content: Record<string, unknown>[] }>(`${entity.path}?q=${encodeURIComponent(q)}&size=${PER_ENTITY}`)
          .then(page => page.content.map((row): Item | null => {
            const label = entity.labelOf(row)
            if (entity.recordPage) return { key: `${entity.path}/${entity.keyOf(row)}`, kind: 'match', label, hint: entity.label, page: entity.recordPage, arg: entity.keyOf(row) }
            if (entity.listPage) return { key: `${entity.path}/${entity.keyOf(row)}`, kind: 'match', label, hint: entity.label, page: entity.listPage, query: { _q: q } }
            return null
          }))
          // An entity that cannot be asked right now leaves the others' matches standing.
          .catch(() => [] as (Item | null)[]),
      )).then(groups => {
        if (turn !== asked.current) return
        setMatches(groups.flat().filter((item): item is Item => item != null))
        setSearching(false)
      })
    }, 200)
    return () => clearTimeout(timer)
  }, [open, q, entities])

  const items = useMemo(() => {
    const lower = q.toLowerCase()
    const found: Item[] = pages
      .filter(p => lower === '' || p.label.toLowerCase().includes(lower))
      .map(p => ({ key: `page/${p.id}`, kind: 'page', label: p.label, page: p.id }))
    const recents: Item[] = q === ''
      ? recent.map(r => ({ key: `recent/${r.page}/${r.arg}`, kind: 'recent', label: r.label, hint: r.hint, page: r.page, arg: r.arg }))
      : []
    return [...recents, ...found, ...matches]
  }, [pages, q, recent, matches])
  const at = Math.min(active, Math.max(items.length - 1, 0))

  function go(item: Item) {
    remember(item)
    setOpen(false)
    onNavigate(item.page, item.arg, item.query)
  }

  function onKey(e: ReactKeyboardEvent) {
    if (e.key === 'Escape') {
      // The palette alone closes: a drawer under it stays open.
      e.preventDefault()
      e.stopPropagation()
      setOpen(false)
    } else if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActive(items.length === 0 ? 0 : (at + 1) % items.length)
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive(items.length === 0 ? 0 : (at - 1 + items.length) % items.length)
    } else if (e.key === 'Enter') {
      e.preventDefault()
      if (items[at]) go(items[at])
    }
  }

  const heading = (kind: Item['kind']) => (kind === 'page' ? t('palettePages') : kind === 'recent' ? t('paletteRecent') : t('paletteMatches'))
  return (
    <>
      <button
        ref={trigger}
        type="button"
        onClick={() => setOpen(true)}
        className="inline-flex items-center gap-2 rounded-lg border border-border bg-surface px-2.5 py-2 text-sm text-muted transition-colors hover:bg-surface-2 hover:text-fg"
        title={t('paletteOpen')}
        aria-label={t('paletteOpen')}
        aria-haspopup="dialog"
      >
        <Search className="h-4 w-4" />
        <kbd className="hidden rounded border border-border px-1 text-[10px] font-medium sm:inline" dir="ltr">Ctrl K</kbd>
      </button>
      {open && (
        <div className="fixed inset-0 z-50 flex items-start justify-center bg-black/40 p-4 pt-[12vh]" onMouseDown={() => setOpen(false)}>
          <div
            role="dialog"
            aria-modal="true"
            aria-label={t('paletteOpen')}
            className="w-full max-w-xl overflow-hidden rounded-xl border border-border bg-surface text-fg shadow-2xl"
            onMouseDown={e => e.stopPropagation()}
            onKeyDown={onKey}
          >
            <div className="flex items-center gap-2 border-b border-border px-4">
              <Search className="h-4 w-4 shrink-0 text-muted" />
              <input
                ref={input}
                value={words}
                onChange={e => { setWords(e.target.value); setActive(0) }}
                placeholder={t('palettePlaceholder')}
                aria-label={t('palettePlaceholder')}
                role="combobox"
                aria-expanded="true"
                aria-controls="palette-list"
                aria-activedescendant={items[at] ? `palette-${at}` : undefined}
                autoComplete="off"
                className="w-full bg-transparent py-3 text-sm text-fg placeholder:text-muted focus:outline-none"
              />
            </div>
            <ul id="palette-list" role="listbox" aria-label={t('paletteOpen')} className="max-h-[50vh] overflow-y-auto p-1.5">
              {items.map((item, i) => (
                <li key={item.key} role="presentation">
                  {(i === 0 || items[i - 1]?.kind !== item.kind) && (
                    <div className="px-2.5 pb-1 pt-2 text-[11px] font-semibold uppercase tracking-wider text-muted">{heading(item.kind)}</div>
                  )}
                  <div
                    id={`palette-${i}`}
                    role="option"
                    aria-selected={i === at}
                    onMouseEnter={() => setActive(i)}
                    onClick={() => go(item)}
                    className={`flex cursor-pointer items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm ${i === at ? 'bg-brand/10 text-fg' : 'text-fg'}`}
                  >
                    {item.kind === 'page' ? <FileText className="h-4 w-4 shrink-0 text-muted" />
                      : item.kind === 'recent' ? <History className="h-4 w-4 shrink-0 text-muted" />
                        : <Search className="h-4 w-4 shrink-0 text-muted" />}
                    <span className="min-w-0 flex-1 truncate">{item.label}</span>
                    {item.hint && <span className="shrink-0 text-xs text-muted">{item.hint}</span>}
                    {i === at && <CornerDownLeft className="h-3.5 w-3.5 shrink-0 text-muted rtl:-scale-x-100" />}
                  </div>
                </li>
              ))}
              {items.length === 0 && (
                <li role="presentation" className="px-2.5 py-6 text-center text-sm text-muted">
                  {searching ? t('paletteSearching') : t('paletteNothing')}
                </li>
              )}
            </ul>
          </div>
        </div>
      )}
    </>
  )
}
