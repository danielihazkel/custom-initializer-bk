import { useEffect, useState } from 'react'
import { api } from './client'

/** What an entity's endpoints ask of the caller — the backend's security.Constants. */
export type AccessLevel = 'SIGNED_IN' | 'USER' | 'ADMIN'

let known: readonly string[] | undefined
let asked: Promise<readonly string[]> | undefined

/** The caller's roles (GET /api/me/roles), asked once for the session. A failed call is no roles. */
function roles(): Promise<readonly string[]> {
  asked ??= api
    .get<string[]>('/api/me/roles')
    .catch(() => [] as string[])
    .then(found => {
      known = found
      return found
    })
  return asked
}

/**
 * Whether the caller holds what `level` asks for. An admin is a user too — the backend's answer
 * already says so. This decides what a page offers, nothing more: the backend checks the call
 * itself. False until the roles are known, so an action appears rather than disappears.
 */
export function useCallerMay(level: AccessLevel): boolean {
  const [held, setHeld] = useState(known)
  useEffect(() => {
    let active = true
    void roles().then(found => {
      if (active) setHeld(found)
    })
    return () => {
      active = false
    }
  }, [])
  return level === 'SIGNED_IN' || (held?.includes(level) ?? false)
}
