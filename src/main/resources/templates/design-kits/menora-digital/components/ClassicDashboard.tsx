import type { ReactNode } from 'react'
import { t } from '@shared/i18n'
import { Hero, ActionPanel, SectionHeader } from '@shared/ui/menora'

// The 22px line icon every action disc holds (stroke currentColor, so it inherits on-yellow).
const tableGlyph = (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
    <rect x="4" y="5" width="16" height="14" rx="2" />
    <path d="M4 10h16M10 10v9" />
  </svg>
)

/**
 * The classic dashboard's page. Menora hero: the display headline whose full stop is the yellow
 * action colour, and a light lead line. No CTA there — the ActionPanel directly under it is the
 * shortcut bar, one yellow disc per collection.
 */
export function DashboardFrame({ title, lead, shortcuts, children }: { title: string; lead: string; shortcuts: { label: string; onOpen: () => void }[]; children: ReactNode }) {
  return (
    <div className="mx-auto w-full max-w-[1283px] px-4 py-5 md:px-6 md:py-8">
      <div className="mb-6">
        <Hero title={`${title}.`} lead={lead} />
      </div>
      {shortcuts.length > 0 && (
        <div className="mb-10">
          <ActionPanel
            ariaLabel={t('collections')}
            items={shortcuts.map(s => ({ label: s.label, icon: tableGlyph, href: '#', onClick: s.onOpen }))}
          />
        </div>
      )}
      {children}
    </div>
  )
}

/** A titled grid: the record counts (`stats`) or the breakdown charts (`charts`). */
export function DashboardSection({ title, kind, children }: { title: string; kind: 'stats' | 'charts'; children: ReactNode }) {
  return kind === 'stats' ? (
    <>
      <div className="mb-4"><SectionHeader title={title} align="start" /></div>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">{children}</div>
    </>
  ) : (
    <>
      <div className="mb-4 mt-10"><SectionHeader title={title} align="start" /></div>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">{children}</div>
    </>
  )
}

/** A white surface card with the live record count in ink tabular figures and the collection label in micro. */
export function StatCard({ label, count, failed, onOpen }: { label: string; count: number | null; failed: boolean; onOpen: () => void }) {
  return (
    <button
      onClick={onOpen}
      className="mn-panel mn-panel--lined flex flex-col p-6 text-start transition-transform hover:-translate-y-0.5"
    >
      <div className="text-[28px] font-semibold leading-[30px] tabular-nums text-fg">
        {failed ? '—' : count == null ? <span className="text-muted">…</span> : count}
      </div>
      <div className="mt-2 text-sm text-muted">{label}</div>
    </button>
  )
}

function BarChart({ data }: { data: { label: string; count: number }[] }) {
  const max = data.reduce((m, d) => Math.max(m, d.count), 0) || 1
  return (
    <div className="space-y-2">
      {data.map(d => (
        <div key={d.label} className="flex items-center gap-3 text-sm">
          <div className="w-28 shrink-0 truncate text-muted" title={d.label}>{d.label}</div>
          <div className="h-2.5 flex-1 overflow-hidden rounded-full bg-surface-2">
            <div className="h-full rounded-full bg-brand" style={{ width: `${(d.count / max) * 100}%` }} />
          </div>
          <div className="w-8 shrink-0 text-end tabular-nums text-fg">{d.count}</div>
        </div>
      ))}
    </div>
  )
}

/** A breakdown of one collection by a field, on a lined panel: one bar per value, largest first. */
export function ChartCard({ label, data, failed }: { label: string; data: { label: string; count: number }[] | null; failed: boolean }) {
  return (
    <div className="mn-panel mn-panel--lined p-6">
      <div className="mb-4 text-base font-semibold text-fg">{label}</div>
      {failed ? (
        <div className="text-sm text-muted">{t('couldNotLoad')}</div>
      ) : data == null ? (
        <div className="text-sm text-muted">…</div>
      ) : data.length === 0 ? (
        <div className="text-sm text-muted">{t('noRecordsYetDot')}</div>
      ) : (
        <BarChart data={data} />
      )}
    </div>
  )
}
