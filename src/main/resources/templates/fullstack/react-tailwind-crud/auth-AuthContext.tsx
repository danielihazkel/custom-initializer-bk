import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from 'react'
import { api, ApiError } from '@shared/api'
import { clearDevIdentity, setDevIdentity } from './devIdentity'

/** The authenticated caller, as returned by the backend `GET /api/me`. */
export interface CurrentUser {
  username: string
  /** Application roles ({@code ADMIN}/{@code USER}) derived from the user's LDAP groups. */
  roles: string[]
  groups: string[]
}

interface AuthState {
  user: CurrentUser | null
  loading: boolean
  /** True once the caller holds at least one recognized role (reads require USER; ADMIN implies access). */
  isAuthenticated: boolean
  isAdmin: boolean
  hasRole: (role: string) => boolean
  /** Dev-only: set the active identity and re-resolve. In production the gateway owns identity. */
  login: (username: string) => Promise<CurrentUser | null>
  logout: () => void
  reload: () => Promise<CurrentUser | null>
}

const LOGOUT_URL = (import.meta.env.VITE_LOGOUT_URL as string | undefined)?.trim() || ''

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null)
  const [loading, setLoading] = useState(true)

  const reload = useCallback(async (): Promise<CurrentUser | null> => {
    setLoading(true)
    try {
      const me = await api.get<CurrentUser>('/api/me')
      setUser(me)
      return me
    } catch (err) {
      // 401 (no/unknown identity) or 403 → unauthenticated. Anything else also leaves us
      // signed-out; the login screen surfaces the failure.
      if (!(err instanceof ApiError)) throw err
      setUser(null)
      return null
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void reload()
  }, [reload])

  // The api layer fires this when a request 401s mid-session — drop back to the login gate.
  useEffect(() => {
    const onUnauthorized = () => setUser(null)
    window.addEventListener('auth:unauthorized', onUnauthorized)
    return () => window.removeEventListener('auth:unauthorized', onUnauthorized)
  }, [])

  const login = useCallback(
    async (username: string) => {
      setDevIdentity(username)
      return reload()
    },
    [reload],
  )

  const logout = useCallback(() => {
    clearDevIdentity()
    setUser(null)
    // In production, hand off to the gateway's logout URL if one is configured.
    if (LOGOUT_URL) window.location.href = LOGOUT_URL
  }, [])

  const roles = user?.roles ?? []
  const value: AuthState = {
    user,
    loading,
    isAuthenticated: roles.length > 0,
    isAdmin: roles.includes('ADMIN'),
    hasRole: role => roles.includes(role),
    login,
    logout,
    reload,
  }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within <AuthProvider>')
  return ctx
}
