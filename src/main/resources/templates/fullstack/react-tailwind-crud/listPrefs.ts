import type { SortSpec } from './Table'

/** One browser's own settings for one entity's list, read back the next time it opens. Kept in
 *  localStorage, which may be unavailable (private mode, blocked storage) — then nothing is kept. */
export interface ListPrefs {
  size?: number
  sort?: SortSpec | null
  view?: string
  hidden?: string[]
}

const PAGE_SIZES = [10, 20, 50, 100]
const keyOf = (list: string) => `list-settings:${list}`

/** The saved settings, minus anything this list no longer offers (a renamed field, a dropped view):
 *  a stale sort would otherwise be refused by the API. */
export function readListPrefs(list: string, sortable: string[], views: string[]): ListPrefs {
  let saved: ListPrefs = {}
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(keyOf(list)) ?? 'null')
    if (parsed && typeof parsed === 'object') saved = parsed as ListPrefs
  } catch {
    return {}
  }
  const sort = saved.sort
  return {
    size: typeof saved.size === 'number' && PAGE_SIZES.includes(saved.size) ? saved.size : undefined,
    sort: sort === null || (sort && sortable.includes(sort.field) && (sort.direction === 'asc' || sort.direction === 'desc'))
      ? sort
      : undefined,
    view: typeof saved.view === 'string' && views.includes(saved.view) ? saved.view : undefined,
    hidden: Array.isArray(saved.hidden) ? saved.hidden.filter(k => typeof k === 'string') : undefined,
  }
}

/** Saves the settings that differ from the list's defaults; none left forgets the entry. */
export function writeListPrefs(list: string, prefs: ListPrefs) {
  try {
    const kept = Object.fromEntries(Object.entries(prefs).filter(([, v]) => v !== undefined))
    if (Object.keys(kept).length === 0) localStorage.removeItem(keyOf(list))
    else localStorage.setItem(keyOf(list), JSON.stringify(kept))
  } catch {
    // Storage unavailable: the list opens with its defaults next time.
  }
}
