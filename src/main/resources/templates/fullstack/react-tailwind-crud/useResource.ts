import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from './client'

interface HasId {
  id?: number | string | null
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

export function useResource<T extends HasId>(basePath: string, params: PageParams) {
  const [items, setItems] = useState<T[]>([])
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [pageNumber, setPageNumber] = useState(params.page)
  const [pageSize, setPageSize] = useState(params.size)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const query = useMemo(() => {
    const sp = new URLSearchParams()
    sp.set('page', String(params.page))
    sp.set('size', String(params.size))
    if (params.sort) sp.set('sort', `${params.sort.field},${params.sort.direction}`)
    if (params.q && params.q.trim() !== '') sp.set('q', params.q.trim())
    return sp.toString()
  }, [params.page, params.size, params.sort, params.q])

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

  const update = useCallback(async (id: number | string, payload: T) => {
    const updated = await api.put<T>(`${basePath}/${id}`, payload)
    await reload()
    return updated
  }, [basePath, reload])

  const remove = useCallback(async (id: number | string) => {
    await api.del(`${basePath}/${id}`)
    await reload()
  }, [basePath, reload])

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
  }
}
