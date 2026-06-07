import type { ReactNode } from 'react'
import { AlertTriangle, Info } from 'lucide-react'
import { clsx } from 'clsx'

interface Props {
  tone?: 'error' | 'info'
  children: ReactNode
}

/** Inline banner for page-level errors or hints. */
export function Alert({ tone = 'error', children }: Props) {
  const Icon = tone === 'error' ? AlertTriangle : Info
  return (
    <div
      className={clsx(
        'mb-4 flex items-start gap-2.5 rounded-lg border px-4 py-3 text-sm',
        tone === 'error'
          ? 'border-danger/20 bg-danger/5 text-danger'
          : 'border-border bg-surface-2 text-fg',
      )}
    >
      <Icon className="mt-0.5 h-4 w-4 shrink-0" />
      <span>{children}</span>
    </div>
  )
}
