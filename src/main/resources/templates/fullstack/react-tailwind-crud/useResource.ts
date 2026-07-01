import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from './client'

/** A resource key: a single value, or — for composite primary keys — the key parts in
 *  declared order (the backend addresses them as ordered path segments, e.g. /api/x/{a}/{b}). */
export type ResourceId = number | string | Array<number | string>

function toPath(id: ResourceId): string {
  return Array.isArray(id) ? id.map(v => encodeURIComponent(String(v))).join('/') : encodeURIComponent(String(id))
}

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
  const query = useMemo(() => {
    const sp = new URLSearchParams()
    sp.set('page', String(params.page))
    sp.set('size', String(params.size))
    if (params.sort) sp.set('sort', `${params.sort.field},${params.sort.direction}`)
    if (params.q && params.q.trim() !== '') sp.set('q', params.q.trim())
    for (const [k, v] of Object.entries(params.filters ?? {})) {
      if (v !== '' && v != null) sp.set(k, v)
    }
    return sp.toString()
  }, [params.page, params.size, params.sort, params.q, filterKey])

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const page = await api.get<Page<T>>(`${basePath}?${query}`)
      setItems(page.content)
      setTotalElements(page.totalElements)
      setTotalPages(page.totalPages)
      setPageNumber(page.number)
      setPageSize(page.size)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [basePath, query])

  useEffect(() => {
    reload()
  }, [reload])

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
    removeMany,
    updateMany,
    exportCsv,
  }
}
