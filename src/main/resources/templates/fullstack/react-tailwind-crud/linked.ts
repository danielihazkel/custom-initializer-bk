import { useEffect, useState } from 'react'
import { api } from './client'

/**
 * The rows a form's rules read through a link (`costCenter.budget`), fetched by id when the link is
 * picked. A rule reads them with `value`; one not fetched yet — or not readable by this user — is
 * unknown, so the rule holds here and the server, which reads the row itself, decides.
 */
const ROWS = new Map<string, Record<string, unknown>>()

const urlOf = (path: string, id: unknown): string | null =>
  id == null || id === '' ? null : `${path}/${encodeURIComponent(String(id))}`

/** A field of the linked row `id` at `path`, or undefined while it is not known. */
export function value(path: string, id: unknown, field: string): unknown {
  const url = urlOf(path, id)
  return url == null ? undefined : ROWS.get(url)?.[field]
}

/** Fetches the linked rows `[path, id]` once each, and renders again when one arrives. */
export function useLinkedRows(links: [string, unknown][]): void {
  const [, setFetched] = useState(0)
  const key = links.map(([path, id]) => urlOf(path, id)).filter(url => url != null).join(' ')
  useEffect(() => {
    let live = true
    for (const url of key.split(' ')) {
      if (url === '' || ROWS.has(url)) continue
      api
        .get<Record<string, unknown>>(url)
        .then(row => {
          ROWS.set(url, row)
          if (live) setFetched(n => n + 1)
        })
        .catch(() => undefined)
    }
    return () => {
      live = false
    }
  }, [key])
}
