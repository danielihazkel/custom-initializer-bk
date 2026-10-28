import type { ReactNode } from 'react'
import { Chip } from '@shared/ui/menora'

type Tone = 'neutral' | 'brand' | 'success' | 'danger'

interface Props {
  children: ReactNode
  tone?: Tone
}

/**
 * Small status pill used for booleans, enums and counts. In the Menora set it is the design
 * system's Chip: lavender by default, outlined for the `brand` tone (the status tones have no
 * colour of their own here — the design never colours a row for status). Kept under the `Badge`
 * name so the borrowed per-entity Detail/Form templates keep compiling.
 */
export function Badge({ children, tone = 'neutral' }: Props) {
  const label = typeof children === 'string' || typeof children === 'number' ? String(children) : ''
  return <Chip label={label} compact tone={tone === 'brand' ? 'outlined' : 'lavender'} />
}
