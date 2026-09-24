import type { LucideIcon } from 'lucide-react'
import { ActionPanel } from '@shared/ui/menora'

// A dashboard's launcher on the Menora set: the site's ActionPanel, one yellow disc per page — the
// dashboard's one yellow fill, so the tiles are the page's shortcut bar, not a second CTA.

export function LinksCard({ title, items, onOpen, className = '' }: {
  title?: string
  items: { id: string; label: string; icon: LucideIcon }[]
  onOpen: (id: string) => void
  className?: string
}) {
  return (
    <div className={className}>
      {title && <div className="mb-3 text-sm font-semibold text-fg">{title}</div>}
      <ActionPanel
        ariaLabel={title}
        items={items.map(({ id, label, icon: Icon }) => ({ label, icon: <Icon className="h-[22px] w-[22px]" />, href: '#', onClick: () => onOpen(id) }))}
      />
    </div>
  )
}
