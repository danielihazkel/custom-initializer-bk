/**
 * Dev-only identity store. In production the SSO gateway injects the `userinfo` header and
 * this module is never consulted (the api client only reads it under `import.meta.env.DEV`).
 * In `npm run dev` there is no gateway, so the login screen writes the chosen username here and
 * the api client sends it as the `userinfo` header — letting you switch between users/roles
 * locally. Seeded from VITE_DEV_USERINFO.
 */
const STORAGE_KEY = 'dev-userinfo'

const DEFAULT_IDENTITY =
  (import.meta.env.VITE_DEV_USERINFO as string | undefined)?.trim() || 'dev-user'

export function getDevIdentity(): string {
  if (typeof window === 'undefined') return DEFAULT_IDENTITY
  return localStorage.getItem(STORAGE_KEY) ?? DEFAULT_IDENTITY
}

export function setDevIdentity(username: string): void {
  if (typeof window !== 'undefined') localStorage.setItem(STORAGE_KEY, username)
}

export function clearDevIdentity(): void {
  if (typeof window !== 'undefined') localStorage.removeItem(STORAGE_KEY)
}
