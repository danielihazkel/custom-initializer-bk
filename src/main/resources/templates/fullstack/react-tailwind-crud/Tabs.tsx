export interface TabItem {
  id: string
  label: string
  /** A number by the label (a tab's row count); left out while unknown. */
  count?: number
}

/**
 * A row of tabs over one panel each. Tab `x` is `#x-tab` and controls `#x-panel` — the caller
 * renders the open panel with that id.
 */
export function Tabs({ items, active, onChange, ariaLabel }: { items: TabItem[]; active: number; onChange: (index: number) => void; ariaLabel: string }) {
  return (
    <div
      role="tablist"
      aria-label={ariaLabel}
      className="mt-4 flex gap-1 overflow-x-auto border-b border-border bg-surface px-4 md:px-8"
    >
      {items.map((tab, i) => (
        <button
          key={tab.id}
          type="button"
          role="tab"
          id={`${tab.id}-tab`}
          aria-selected={active === i}
          aria-controls={`${tab.id}-panel`}
          onClick={() => onChange(i)}
          className={`-mb-px whitespace-nowrap border-b-2 px-4 py-3 text-sm font-medium transition-colors ${
            active === i ? 'border-brand text-fg' : 'border-transparent text-muted hover:text-fg'
          }`}
        >
          {tab.label}
          {tab.count != null && (
            <span className="ms-1.5 rounded-full bg-surface-2 px-1.5 py-0.5 text-[11px] tabular-nums text-muted">{tab.count}</span>
          )}
        </button>
      ))}
    </div>
  )
}
