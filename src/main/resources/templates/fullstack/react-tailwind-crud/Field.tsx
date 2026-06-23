import type { ReactElement, ReactNode } from 'react'
import { cloneElement, isValidElement, useId } from 'react'

interface Props {
  label: string
  hint?: string
  error?: string
  required?: boolean
  children: ReactNode
}

export function Field({ label, hint, error, required, children }: Props) {
  const id = useId()
  const hintId = `${id}-hint`
  const errorId = `${id}-error`
  const describedBy = error ? errorId : hint ? hintId : undefined

  // Wire aria-invalid / aria-describedby onto the wrapped control so screen
  // readers announce the error and required state alongside the visible label.
  const control = isValidElement(children)
    ? cloneElement(children as ReactElement, {
        'aria-invalid': error ? true : undefined,
        'aria-describedby': describedBy,
        'aria-required': required || undefined,
      })
    : children

  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-xs font-medium uppercase tracking-wider text-muted">
        {label}{required && <span className="text-danger"> *</span>}
      </span>
      {control}
      {hint && !error && <span id={hintId} className="text-xs text-muted">{hint}</span>}
      {error && <span id={errorId} role="alert" className="text-xs text-danger">{error}</span>}
    </label>
  )
}

export const inputClass =
  'w-full px-3 py-2 rounded-lg border border-border bg-surface text-sm text-fg placeholder:text-muted ' +
  'focus:outline-none focus:ring-2 focus:ring-ring/40 focus:border-ring transition-shadow'
