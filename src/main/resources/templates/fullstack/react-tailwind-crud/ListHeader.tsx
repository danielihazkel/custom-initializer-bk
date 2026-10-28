import type { ReactNode } from 'react'
import { Calendar, Columns3, Download, LayoutGrid, Plus, Table2, Upload, type LucideIcon } from 'lucide-react'
import { t } from '@shared/i18n'

/** The column a list page sits in. */
export function ListFrame({ children }: { children: ReactNode }) {
  return <div className="mx-auto max-w-6xl p-4 md:p-8">{children}</div>
}

/** The icon of each list view in the switcher. */
const VIEW_ICONS: Record<string, LucideIcon> = { table: Table2, cards: LayoutGrid, kanban: Columns3, calendar: Calendar }

interface Props {
  /** The list's heading; left out when the list sits inside another page. */
  title?: string
  description?: string
  /** How many records the list holds. */
  count: number
  /** The list is a read-only view. */
  readOnlyView?: boolean
  /** The views to switch between (two or more), the open one, and the switch. */
  views?: { id: string; label: string }[]
  view?: string
  onViewChange?: (id: string) => void
  /** Each action is shown only when given. */
  onExport?: () => void
  exporting?: boolean
  onImport?: () => void
  onCreate?: () => void
  createLabel?: string
}

/** A list page's heading and toolbar: the title and record count, the view switch, Export, Import and New. */
export function ListHeader({ title, description, count, readOnlyView, views, view, onViewChange, onExport, exporting, onImport, onCreate, createLabel }: Props) {
  return (
    <div className="mb-6 flex items-end justify-between">
      <div>
        {title && <h1 className="text-2xl font-semibold tracking-tight text-fg">{title}</h1>}
        {description && <p className="mt-1 max-w-2xl text-sm text-muted">{description}</p>}
        <p className="mt-1 text-sm text-muted">
          {count} {t(count === 1 ? 'record' : 'records')}{readOnlyView && <> · {t('readOnlyView')}</>}
        </p>
      </div>
      <div className="flex items-center gap-2">
        {views && views.length > 1 && (
          <div className="inline-flex rounded-lg border border-border bg-surface p-0.5">
            {views.map(v => {
              const Icon = VIEW_ICONS[v.id] ?? Table2
              return (
                <button
                  key={v.id}
                  onClick={() => onViewChange?.(v.id)}
                  aria-pressed={view === v.id}
                  className={`rounded-md p-1.5 transition-colors ${view === v.id ? 'bg-brand text-white' : 'text-muted hover:text-fg'}`}
                  title={v.label}
                  aria-label={v.label}
                >
                  <Icon className="h-4 w-4" />
                </button>
              )
            })}
          </div>
        )}
        {onExport && (
          <button
            onClick={onExport}
            disabled={exporting}
            aria-busy={exporting}
            className="inline-flex items-center gap-2 rounded-lg border border-border bg-surface px-3 py-2 text-sm font-medium text-fg shadow-sm transition-colors hover:bg-surface-2"
            title={t('exportCsv')}
          >
            <Download className="h-4 w-4" />
            {exporting ? t('exporting') : t('export')}
          </button>
        )}
        {onImport && (
          <button
            onClick={onImport}
            className="inline-flex items-center gap-2 rounded-lg border border-border bg-surface px-3 py-2 text-sm font-medium text-fg shadow-sm transition-colors hover:bg-surface-2"
            title={t('importCsv')}
          >
            <Upload className="h-4 w-4" />
            {t('import')}
          </button>
        )}
        {onCreate && (
          <button
            onClick={onCreate}
            className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary-deep"
          >
            <Plus className="h-4 w-4" />
            {createLabel}
          </button>
        )}
      </div>
    </div>
  )
}
