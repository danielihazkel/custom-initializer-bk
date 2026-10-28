import type { ReactNode } from 'react'
import { Skeleton } from './Skeleton'

/**
 * A screen's heading in the SectionHeader style (600 36/40, ink): centred like a list page's when
 * it stands alone, at the start with the screen's actions at the end when it has any. A record's
 * page label sits over it in brand purple; its status chips and the description under it.
 */
export function ScreenHeader({ title, description, eyebrow, loading, actions, children }: {
  title: string
  description?: string
  /** A small label over the title: the page a record is shown on. */
  eyebrow?: string
  /** The title is not known yet (a record still loading). */
  loading?: boolean
  actions?: ReactNode
  /** Under the title: a record's status chips. */
  children?: ReactNode
}) {
  const alone = actions == null
  const heading = (
    <div className={alone ? 'flex flex-col items-center text-center' : 'min-w-0'}>
      {eyebrow && <div className="mb-1 text-sm font-semibold text-brand">{eyebrow}</div>}
      {loading
        ? <Skeleton className="h-10 w-64" />
        : <h1 className="mn mn-section__title">{title}</h1>}
      {children}
      {description && <p className="mt-2 max-w-2xl text-sm text-muted">{description}</p>}
    </div>
  )
  if (alone) return heading
  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      {heading}
      <div className="flex flex-wrap items-center gap-2">{actions}</div>
    </div>
  )
}
