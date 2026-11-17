import { useEffect, useState, type ReactNode } from 'react'
import { RefreshTick } from './useResource'

// The API's origin, as the API client has it (empty: same origin, through the dev proxy or nginx).
const BASE = ((import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '').replace(/\/+$/, '')
/** Changes this close together are one refresh. */
const SETTLE_MS = 800
/** How long to wait before connecting again, by failures in a row. */
const RETRY_MS = [1_000, 2_000, 5_000, 10_000, 30_000]

/** Whether a server-sent event block (its lines) is a `change`. */
function isChange(block: string): boolean {
  return block.split('\n').some(line => line.replace(/\s+$/, '') === 'event:change' || line.replace(/\s+$/, '') === 'event: change')
}

/**
 * Keeps the app in step with the data: it listens to the backend's change stream
 * (`GET /api/events/stream`) and, when a row changes — here or in another user's browser — bumps
 * the reload count every list, chart and the bell read (`RefreshTick`), so they fetch again. Changes
 * that come close together are one refresh, and a hidden tab waits until it is shown. The stream
 * only says that something changed; each list reads its rows through the API as before. When the
 * connection drops it opens a new one, waiting longer each time.
 */
export function LiveUpdates({ children }: { children: ReactNode }) {
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let stopped = false
    let failures = 0
    let pending = false
    let settle: ReturnType<typeof setTimeout> | undefined
    let retry: ReturnType<typeof setTimeout> | undefined
    const controller = new AbortController()

    const refresh = () => {
      if (document.visibilityState === 'visible') setTick(n => n + 1)
      else pending = true
    }
    const changed = () => {
      if (settle !== undefined) return
      settle = setTimeout(() => {
        settle = undefined
        refresh()
      }, SETTLE_MS)
    }
    const shown = () => {
      if (pending && document.visibilityState === 'visible') {
        pending = false
        setTick(n => n + 1)
      }
    }

    const connect = async () => {
      const headers: Record<string, string> = { Accept: 'text/event-stream' }
      // Dev only: the SSO gateway names the user in production; the API client does the same.
      if (import.meta.env.DEV) headers['userinfo'] = import.meta.env.VITE_DEV_USERINFO ?? 'dev-user'
      try {
        const res = await fetch(`${BASE}/api/events/stream`, { headers, signal: controller.signal })
        if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`)
        failures = 0
        const reader = res.body.pipeThrough(new TextDecoderStream()).getReader()
        let buffer = ''
        for (;;) {
          const { value, done } = await reader.read()
          if (done) break
          buffer += value.replace(/\r/g, '')
          let end = buffer.indexOf('\n\n')
          while (end >= 0) {
            if (isChange(buffer.slice(0, end))) changed()
            buffer = buffer.slice(end + 2)
            end = buffer.indexOf('\n\n')
          }
        }
      } catch {
        if (stopped) return
        failures++
      }
      if (!stopped) retry = setTimeout(() => { void connect() }, RETRY_MS[Math.min(failures, RETRY_MS.length - 1)])
    }

    void connect()
    document.addEventListener('visibilitychange', shown)
    return () => {
      stopped = true
      controller.abort()
      clearTimeout(settle)
      clearTimeout(retry)
      document.removeEventListener('visibilitychange', shown)
    }
  }, [])

  return <RefreshTick.Provider value={tick}>{children}</RefreshTick.Provider>
}
