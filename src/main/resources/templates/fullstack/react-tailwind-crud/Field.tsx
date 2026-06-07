import type { ReactNode } from 'react'

interface Props {
  label: string
  hint?: string
  error?: string
  required?: boolean
  children: ReactNode
}

export function Field({ label, hint, error, required, children }: Props) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-xs font-medium uppercase tracking-wider text-muted">
        {label}{required && <span className="text-danger"> *</span>}
      </span>
      {children}
      {hint && !error && <span className="text-xs text-muted">{hint}</span>}
      {error && <span className="text-xs text-danger">{error}</span>}
    </label>
  )
}

export const inputClass =
  'w-full px-3 py-2 rounded-lg border border-border bg-surface text-sm text-fg placeholder:text-muted ' +
  'focus:outline-none focus:ring-2 focus:ring-ring/40 focus:border-ring transition-shadow'
