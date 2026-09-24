import type { LucideIcon } from 'lucide-react'

// A dashboard's launcher: one tile per page of the app, opening it. The screen passes the pages'
// nav labels and icons, so a tile reads exactly like the nav entry it stands for.

const card = 'rounded-2xl border border-border bg-surface p-5 shadow-sm'

export function LinksCard({ title, items, onOpen, className = '' }: {
  title?: string
  items: { id: string; label: string; icon: LucideIcon }[]
  onOpen: (id: string) => void
  className?: string
}) {
  return (
    <div className={`${card} ${className}`}>
      {title && <div className="mb-4 text-sm font-semibold text-fg">{title}</div>}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
        {items.map(({ id, label, icon: Icon }) => (
          <button
            key={id}
            type="button"
            onClick={() => onOpen(id)}
            className="flex flex-col items-center gap-2 rounded-xl border border-border bg-surface px-3 py-4 text-sm font-medium text-fg transition-colors hover:border-brand hover:bg-brand/5"
          >
            <Icon className="h-6 w-6 text-brand" />
            <span className="text-center">{label}</span>
          </button>
        ))}
      </div>
    </div>
  )
}
