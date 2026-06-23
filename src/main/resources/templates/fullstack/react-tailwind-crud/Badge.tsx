import type { ReactNode } from 'react'
import { clsx } from 'clsx'

type Tone = 'neutral' | 'brand' | 'success' | 'danger'

const TONES: Record<Tone, string> = {
  neutral: 'bg-surface-2 text-muted ring-border',
  brand: 'bg-brand/10 text-brand ring-brand/20',
  success: 'bg-success/10 text-success ring-success/20',
  danger: 'bg-danger/10 text-danger ring-danger/20',
}

interface Props {
  children: ReactNode
  tone?: Tone
}

/** Small status pill used for booleans, enums and counts in table cells. */
export function Badge({ children, tone = 'neutral' }: Props) {
  return (
    <span
      className={clsx(
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
        TONES[tone],
      )}
    >
      {children}
    </span>
  )
}
