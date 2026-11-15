import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '@shared/api'
import type { SortSpec } from './Table'

/** What a view remembers of a list: everything its toolbar sets. */
export interface ViewState {
  q?: string
  sort?: SortSpec | null
  size?: number
  view?: string
  hidden?: string[]
  filters?: Record<string, string>
}

/** A named view of a list, kept on the server (GET /api/views). */
export interface SavedView {
  id: number
  name: string
  state: ViewState
  /** Offered to every user of the list. */
  shared: boolean
  /** The view this user's list opens with. */
  defaultView: boolean
  /** Saved by this user, who may change and delete it; a shared view of someone else's is not. */
  mine: boolean
}

const PAGE_SIZES = [10, 20, 50, 100]

/**
 * A stored state as the list can take it today: a sort the list no longer offers, a view it no
 * longer has or a page size the pager never had is dropped rather than applied — the model may
 * have changed since the view was saved, and a stale sort would be refused by the API.
 */
export function fitViewState(raw: unknown, sortable: string[], views: string[]): ViewState {
  const saved = (raw && typeof raw === 'object' ? raw : {}) as ViewState
  const sort = saved.sort
  const filters = saved.filters && typeof saved.filters === 'object'
    ? Object.fromEntries(Object.entries(saved.filters).filter(([, v]) => typeof v === 'string' && v !== ''))
    : undefined
  return {
    q: typeof saved.q === 'string' && saved.q !== '' ? saved.q : undefined,
    sort: sort === null || (sort && sortable.includes(sort.field) && (sort.direction === 'asc' || sort.direction === 'desc'))
      ? sort
      : undefined,
    size: typeof saved.size === 'number' && PAGE_SIZES.includes(saved.size) ? saved.size : undefined,
    view: typeof saved.view === 'string' && views.includes(saved.view) ? saved.view : undefined,
    hidden: Array.isArray(saved.hidden) ? saved.hidden.filter(k => typeof k === 'string') : undefined,
    filters,
  }
}

/** What a change to a view sends: only what changes. */
export interface ViewPatch {
  name?: string
  state?: ViewState
  shared?: boolean
  defaultView?: boolean
}

/**
 * One list's saved views — this user's and the shared ones — and the calls that change them.
 * Every change reads the list again, so what is shown is what the server holds.
 */
export function useSavedViews(list: string) {
  const [views, setViews] = useState<SavedView[]>([])
  const [loaded, setLoaded] = useState(false)
  const asked = useRef(0)

  const reload = useCallback(() => {
    const turn = ++asked.current
    return api.get<SavedView[]>(`/api/views?list=${encodeURIComponent(list)}`)
      .then(found => { if (turn === asked.current) setViews(found) })
      // Views are a convenience: a list without them still works.
      .catch(() => undefined)
      .finally(() => { if (turn === asked.current) setLoaded(true) })
  }, [list])
  useEffect(() => { void reload() }, [reload])

  const save = useCallback((name: string, state: ViewState) =>
    api.post<SavedView>('/api/views', { list, name, state }).finally(reload), [list, reload])
  const change = useCallback((id: number, patch: ViewPatch) =>
    api.put<SavedView>(`/api/views/${id}`, patch).finally(reload), [reload])
  const remove = useCallback((id: number) => api.del(`/api/views/${id}`).finally(reload), [reload])

  return { views, loaded, save, change, remove }
}
