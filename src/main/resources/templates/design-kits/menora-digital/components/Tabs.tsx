import { Tabs as MenoraTabs } from '@shared/ui/menora'

export interface TabItem {
  id: string
  label: string
  /** A number by the label (a tab's row count); left out while unknown. */
  count?: number
}

/**
 * The Menora Tabs strip (bare: each embedded page brings its own panel). Tab `x` controls
 * `#x-panel` — the caller renders the open panel with that id. The port takes a plain string
 * label, so a count rides inside it.
 */
export function Tabs({ items, active, onChange, ariaLabel }: { items: TabItem[]; active: number; onChange: (index: number) => void; ariaLabel: string }) {
  return (
    <div className="mx-auto max-w-[1283px] px-4 md:px-6">
      <div className="mt-4">
        <MenoraTabs
          items={items.map(x => ({ id: x.id, label: x.count == null ? x.label : `${x.label} (${x.count})`, panelId: `${x.id}-panel` }))}
          activeIndex={active}
          onChange={onChange}
          ariaLabel={ariaLabel}
          bare
        />
      </div>
    </div>
  )
}
