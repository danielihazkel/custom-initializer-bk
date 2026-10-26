import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { toast } from 'sonner'
import { api } from './client'
import { t } from '../i18n'

/** A resource key: a single value, or — for composite primary keys — the key parts in
 *  declared order (the backend addresses them as ordered path segments, e.g. /api/x/{a}/{b}). */
export type ResourceId = number | string | Array<number | string>

function toPath(id: ResourceId): string {
  return Array.isArray(id) ? id.map(v => encodeURIComponent(String(v))).join('/') : encodeURIComponent(String(id))
}

/** Runs a download (a CSV export) with a busy flag, and tells the user when it fails — a failed
 *  download otherwise ends silently, with no file and no message. */
export function useDownload(): { busy: boolean; run: (start: () => Promise<void>) => void } {
  const [busy, setBusy] = useState(false)
  // A second click while one is running is ignored rather than starting a second download.
  const running = useRef(false)
  const run = useCallback((start: () => Promise<void>) => {
    if (running.current) return
    running.current = true
    setBusy(true)
    start()
      .catch(e => { toast.error(t('exportFailed', { message: e instanceof Error ? e.message : String(e) })) })
      .finally(() => { running.current = false; setBusy(false) })
  }, [])
  return { busy, run }
}

/** Bumped by a dashboard's Refresh (or its timer): every list and data widget under it reloads,
 *  keeping what it shows until the new rows arrive. */
export const RefreshTick = createContext(0)

export interface SortSpec {
  field: string
  direction: 'asc' | 'desc'
}

export interface PageParams {
  page: number
  size: number
  sort: SortSpec | null
  q: string
  /** Extra type-aware filter params, keyed exactly as the backend reads them
   *  (e.g. `status`, `priceMin`, `createdAtFrom`). Empty values are skipped. */
  filters?: Record<string, string>
}

interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
  first: boolean
  last: boolean
}

export function useResource<T extends object>(basePath: string, params: PageParams) {
  const [items, setItems] = useState<T[]>([])
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [pageNumber, setPageNumber] = useState(params.page)
  const [pageSize, setPageSize] = useState(params.size)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const filterKey = JSON.stringify(params.filters ?? {})
  // The search and filters alone — what the stats rollup takes.
  const where = useMemo(() => {
    const sp = new URLSearchParams()
    if (params.q && params.q.trim() !== '') sp.set('q', params.q.trim())
    for (const [k, v] of Object.entries(params.filters ?? {})) {
      if (v !== '' && v != null) sp.set(k, v)
    }
    return sp.toString()
    // filterKey is the serialized form of params.filters: a fresh object with equal contents must
    // not rebuild the query (and refetch), so the object itself is deliberately not a dependency.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params.q, filterKey])
  const query = useMemo(() => {
    const sp = new URLSearchParams()
    sp.set('page', String(params.page))
    sp.set('size', String(params.size))
    if (params.sort) sp.set('sort', `${params.sort.field},${params.sort.direction}`)
    return where ? `${sp}&${where}` : sp.toString()
  }, [params.page, params.size, params.sort, where])
  const tick = useContext(RefreshTick)

  // Monotonic request counter: a response is applied only if no newer request was issued
  // meanwhile, so a slow page-1 response can never overwrite a faster page-2 one.
  const requestRef = useRef(0)
  const reload = useCallback(async () => {
    const requestId = ++requestRef.current
    setLoading(true)
    setError(null)
    try {
      const page = await api.get<Page<T>>(`${basePath}?${query}`)
      if (requestId !== requestRef.current) return
      setItems(page.content)
      setTotalElements(page.totalElements)
      setTotalPages(page.totalPages)
      setPageNumber(page.number)
      setPageSize(page.size)
    } catch (e) {
      if (requestId !== requestRef.current) return
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      if (requestId === requestRef.current) setLoading(false)
    }
  }, [basePath, query])

  // tick: a dashboard Refresh reloads the same page.
  useEffect(() => {
    reload()
  }, [reload, tick])

  const create = useCallback(async (payload: Omit<T, 'id'>) => {
    const created = await api.post<T>(basePath, payload)
    await reload()
    return created
  }, [basePath, reload])

  const update = useCallback(async (id: ResourceId, payload: T) => {
    const updated = await api.put<T>(`${basePath}/${toPath(id)}`, payload)
    await reload()
    return updated
  }, [basePath, reload])

  const remove = useCallback(async (id: ResourceId) => {
    await api.del(`${basePath}/${toPath(id)}`)
    await reload()
  }, [basePath, reload])

  // Un-deletes a soft-deleted record (only routed when the backend was generated with the
  // softDelete opt; the page wires it to the Undo action of the delete toast).
  const restore = useCallback(async (id: ResourceId) => {
    const restored = await api.post<T>(`${basePath}/${toPath(id)}/restore`, undefined)
    await reload()
    return restored
  }, [basePath, reload])

  // Bulk delete by a list of (single-column) primary keys.
  const removeMany = useCallback(async (ids: Array<number | string>) => {
    await api.del(`${basePath}/bulk`, ids)
    await reload()
  }, [basePath, reload])

  // Bulk-update a single field to `value` across a list of (single-column) primary keys.
  const updateMany = useCallback(async (ids: Array<number | string>, field: string, value: unknown) => {
    await api.patch(`${basePath}/bulk`, { ids, field, value })
    await reload()
  }, [basePath, reload])

  // How many matching records each value of `field` (an enum/boolean column) has, from the
  // entity's stats rollup (GET <base>/stats?groupBy=). A grouped column always has one.
  const countBy = useCallback(async (field: string) => {
    const stats = await api.get<{ buckets: { key: string | null; value: number | null }[] }>(
      `${basePath}/stats?groupBy=${encodeURIComponent(field)}${where ? `&${where}` : ''}`)
    const totals: Record<string, number> = {}
    for (const b of stats.buckets) if (b.key != null && b.key !== '') totals[b.key] = b.value ?? 0
    return totals
  }, [basePath, where])

  // Download the current result set (honoring search/filters/sort) as a CSV file. The backend
  // export endpoint ignores page/size and streams every matching row.
  const exportCsv = useCallback((filename = 'export.csv') => {
    return api.download(`${basePath}/export.csv?${query}`, filename)
  }, [basePath, query])

  return {
    items,
    totalElements,
    totalPages,
    pageNumber,
    pageSize,
    loading,
    error,
    reload,
    create,
    update,
    remove,
    restore,
    removeMany,
    updateMany,
    exportCsv,
    countBy,
  }
}
