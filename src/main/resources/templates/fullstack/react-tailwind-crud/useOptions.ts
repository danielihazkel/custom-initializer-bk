import { useEffect, useState } from 'react'
import { api } from './client'

interface Page<T> {
  content: T[]
}

/**
 * Fetches up to `size` records once, for use as <select> options (e.g. a foreign-key picker).
 * Not paginated — intended for small reference tables. Returns `{ options, loading }`.
 */
export function useOptions<T = Record<string, unknown>>(basePath: string, size = 1000) {
  const [options, setOptions] = useState<T[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let active = true
    setLoading(true)
    api
      .get<Page<T>>(`${basePath}?size=${size}`)
      .then(page => { if (active) setOptions(page.content ?? []) })
      .catch(() => { if (active) setOptions([]) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [basePath, size])

  return { options, loading }
}
