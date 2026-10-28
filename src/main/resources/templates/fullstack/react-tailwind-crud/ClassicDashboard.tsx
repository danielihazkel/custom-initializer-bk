import type { ReactNode } from 'react'
import { ArrowRight, LayoutGrid, Table2 } from 'lucide-react'
import { t } from '@shared/i18n'

/** The classic dashboard's page: the welcome banner over its sections. `shortcuts` (one per collection) are not drawn here — the stat cards already open them. */
export function DashboardFrame({ title, lead, children }: { title: string; lead: string; shortcuts: { label: string; onOpen: () => void }[]; children: ReactNode }) {
  return (
    <div className="mx-auto max-w-6xl p-4 md:p-8">
      <div className="mb-8 overflow-hidden rounded-2xl border border-border bg-gradient-to-br from-ink to-ink-soft p-6 text-white shadow-sm md:p-8">
        <div className="flex items-center gap-2 text-gold">
          <LayoutGrid className="h-5 w-5" />
          <span className="text-xs font-semibold uppercase tracking-wider">{t('overview')}</span>
        </div>
        <h1 className="mt-3 text-2xl font-semibold tracking-tight">{title}</h1>
        <p className="mt-1 max-w-xl text-sm text-slate-300">
          {lead}
        </p>
      </div>
      {children}
    </div>
  )
}

/** A titled grid: the record counts (`stats`) or the breakdown charts (`charts`). */
export function DashboardSection({ title, kind, children }: { title: string; kind: 'stats' | 'charts'; children: ReactNode }) {
  return kind === 'stats' ? (
    <>
      <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-muted">{title}</h2>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">{children}</div>
    </>
  ) : (
    <>
      <h2 className="mb-3 mt-8 text-xs font-semibold uppercase tracking-wider text-muted">{title}</h2>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">{children}</div>
    </>
  )
}

/** A collection's live record count; opens the collection. */
export function StatCard({ label, count, failed, onOpen }: { label: string; count: number | null; failed: boolean; onOpen: () => void }) {
  return (
    <button
      onClick={onOpen}
      className="group flex flex-col rounded-2xl border border-border bg-surface p-5 text-start shadow-sm transition-all hover:-translate-y-0.5 hover:border-brand/40 hover:shadow-md"
    >
      <div className="flex items-center justify-between">
        <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-brand/10 text-brand">
          <Table2 className="h-5 w-5" />
        </div>
        <ArrowRight className="h-4 w-4 text-muted transition-transform group-hover:translate-x-0.5 group-hover:text-brand" />
      </div>
      <div className="mt-4 text-3xl font-semibold tabular-nums tracking-tight text-fg">
        {failed ? '—' : count == null ? <span className="text-muted">…</span> : count}
      </div>
      <div className="mt-1 text-sm text-muted">{label}</div>
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

/** A breakdown of one collection by a field: one bar per value, largest first. */
export function ChartCard({ label, data, failed }: { label: string; data: { label: string; count: number }[] | null; failed: boolean }) {
  return (
    <div className="rounded-2xl border border-border bg-surface p-5 shadow-sm">
      <div className="mb-4 text-sm font-semibold text-fg">{label}</div>
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
