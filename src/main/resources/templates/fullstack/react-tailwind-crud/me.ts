import { useEffect, useState } from 'react'
import { api } from './client'

/** The signed-in user as the backend's workflow sees them (GET /api/me). */
export interface Me {
  /** The user id from the `userinfo` header, or null when there is none. */
  id: string | null
  /** The application roles the user holds (ADMIN, USER). */
  roles: string[]
}

const NOBODY: Me = { id: null, roles: [] }
let asked: Promise<Me> | null = null

/** The signed-in user — asked once per page load, shared by every caller. */
export function useMe(): Me {
  const [me, setMe] = useState<Me>(NOBODY)
  useEffect(() => {
    let active = true
    asked ??= api.get<Me>('/api/me').catch(() => NOBODY)
    void asked.then(m => { if (active) setMe(m) })
    return () => { active = false }
  }, [])
  return me
}
