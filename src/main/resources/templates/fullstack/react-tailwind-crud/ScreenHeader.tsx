import type { ReactNode } from 'react'
import { Skeleton } from './Skeleton'

/**
 * A screen's heading at the top of its column: the title (with a small label over it on a record
 * page, and a placeholder while the record loads), lines under it, the description, and the
 * screen's own actions at the end of the row.
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
  const heading = (
    <div>
      {eyebrow && <div className="text-xs font-medium uppercase tracking-wider text-muted">{eyebrow}</div>}
      {loading
        ? <Skeleton className={eyebrow ? 'mt-1 h-8 w-56' : 'h-8 w-56'} />
        : <h1 className={eyebrow ? 'mt-1 text-2xl font-semibold tracking-tight text-fg' : 'text-2xl font-semibold tracking-tight text-fg'}>{title}</h1>}
      {children}
      {description && <p className="mt-1 max-w-2xl text-sm text-muted">{description}</p>}
    </div>
  )
  if (actions == null) return heading
  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      {heading}
      <div className="flex flex-wrap items-center gap-2">{actions}</div>
    </div>
  )
}
