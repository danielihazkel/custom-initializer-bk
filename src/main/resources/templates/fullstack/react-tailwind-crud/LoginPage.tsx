import { useState } from 'react'
import { LogIn, ShieldCheck } from 'lucide-react'
import { Alert, Field, inputClass } from '@shared/ui'
import { useAuth } from '@shared/auth'

/** Sample identities offered as quick-pick buttons in dev. Adjust to match your LDAP test users. */
const SAMPLE_USERS: { label: string; username: string; hint: string }[] = [
  { label: 'Administrator', username: 'admin', hint: 'full access · ADMIN' },
  { label: 'Read-only user', username: 'viewer', hint: 'read access · USER' },
]

const LOGIN_URL = (import.meta.env.VITE_LOGIN_URL as string | undefined)?.trim() || ''
const IS_DEV = import.meta.env.DEV

/**
 * Identity gate shown until the caller resolves to a recognized role.
 *
 * In dev there is no SSO gateway, so this screen lets you pick the `userinfo` identity sent to
 * the backend (which resolves it to LDAP roles via `/api/me`). In production the gateway injects
 * the identity automatically — this screen only appears if that identity carries no app role,
 * pointing the user at the configured login URL.
 */
export function LoginPage() {
  const { login } = useAuth()
  const [username, setUsername] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function signIn(name: string) {
    const trimmed = name.trim()
    if (!trimmed) {
      setError('Enter a username to continue.')
      return
    }
    setBusy(true)
    setError(null)
    const me = await login(trimmed)
    setBusy(false)
    // On success the auth gate swaps this screen for the app. If we're still here, the identity
    // resolved but carries no role (or could not be verified).
    if (!me) {
      setError('Could not verify that user. Check the username and that the backend is running.')
    } else if (me.roles.length === 0) {
      setError(`"${me.username}" has no application role assigned. Contact an administrator.`)
    }
  }

  return (
    <div className="flex min-h-full items-center justify-center bg-canvas p-6">
      <div className="w-full max-w-sm rounded-2xl border border-border bg-surface p-8 shadow-sm">
        <div className="mb-6 flex flex-col items-center text-center">
          <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-xl bg-brand/10 text-brand">
            <ShieldCheck className="h-6 w-6" />
          </div>
          <h1 className="text-lg font-semibold tracking-tight text-fg">Sign in</h1>
          <p className="mt-1 text-sm text-muted">
            {IS_DEV
              ? 'Choose an identity to use against the backend.'
              : 'Authenticating with your single sign-on session.'}
          </p>
        </div>

        {error && <Alert>{error}</Alert>}

        {IS_DEV ? (
          <form
            onSubmit={e => {
              e.preventDefault()
              void signIn(username)
            }}
            className="space-y-4"
          >
            <Field label="Username">
              <input
                className={inputClass}
                value={username}
                onChange={e => setUsername(e.target.value)}
                placeholder="e.g. admin"
                autoFocus
              />
            </Field>
            <button
              type="submit"
              disabled={busy}
              className="inline-flex w-full items-center justify-center gap-2 rounded-lg bg-brand px-4 py-2.5 text-sm font-medium text-white shadow-sm transition-colors hover:bg-brand-deep disabled:opacity-60"
            >
              <LogIn className="h-4 w-4" />
              {busy ? 'Signing in…' : 'Sign in'}
            </button>

            <div className="pt-2">
              <div className="mb-2 text-center text-[11px] uppercase tracking-wider text-muted">
                Quick sign-in
              </div>
              <div className="grid gap-2">
                {SAMPLE_USERS.map(u => (
                  <button
                    key={u.username}
                    type="button"
                    disabled={busy}
                    onClick={() => {
                      setUsername(u.username)
                      void signIn(u.username)
                    }}
                    className="flex items-center justify-between rounded-lg border border-border bg-surface px-3 py-2 text-left text-sm transition-colors hover:bg-surface-2 disabled:opacity-60"
                  >
                    <span className="font-medium text-fg">{u.label}</span>
                    <span className="text-[11px] uppercase tracking-wider text-muted">{u.hint}</span>
                  </button>
                ))}
              </div>
            </div>
          </form>
        ) : (
          <div className="space-y-4 text-center">
            {LOGIN_URL ? (
              <a
                href={LOGIN_URL}
                className="inline-flex w-full items-center justify-center gap-2 rounded-lg bg-brand px-4 py-2.5 text-sm font-medium text-white shadow-sm transition-colors hover:bg-brand-deep"
              >
                <LogIn className="h-4 w-4" />
                Continue to sign in
              </a>
            ) : (
              <p className="text-sm text-muted">
                Your session is missing an application role. Contact an administrator for access.
              </p>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
