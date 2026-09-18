import type { LucideIcon } from 'lucide-react'
import { Inbox } from 'lucide-react'
import { Button } from '@shared/ui/menora'

interface Props {
  icon?: LucideIcon
  title: string
  hint?: string
  action?: { label: string; onClick: () => void }
}

/**
 * Friendly placeholder shown when a list has no records yet — the design system's table empty
 * state (`.mn-table__empty`), so a list card and a grid say "nothing here" the same way.
 */
export function EmptyState({ icon: Icon = Inbox, title, hint, action }: Props) {
  return (
    <div className="mn-table__empty">
      <Icon className="mx-auto mb-3 block h-10 w-10 text-muted" aria-hidden="true" />
      <span className="mn-table__empty-title">{title}</span>
      {hint && <span className="mn-table__empty-hint">{hint}</span>}
      {action && (
        <div className="mn-table__empty-action">
          <Button variant="primary" className="mn-btn--compact" label={action.label} onClick={action.onClick} />
        </div>
      )}
    </div>
  )
}
