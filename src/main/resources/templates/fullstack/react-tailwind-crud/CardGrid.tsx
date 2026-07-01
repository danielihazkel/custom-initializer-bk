import { ChevronLeft, ChevronRight, Eye, Pencil, Search, Trash2 } from 'lucide-react'
import type { Column, PaginationProps } from './Table'
import { EmptyState } from './EmptyState'
import { Skeleton } from './Skeleton'

interface Props<T extends object> {
  columns: Column<T>[]
  rows: T[]
  /** Optional row actions. Each renders as an icon button on the card; omit all three for a
   *  read-only grid (no action row). Mirrors Table so the two are drop-in interchangeable. */
  onView?: (row: T) => void
  onEdit?: (row: T) => void
  onDelete?: (row: T) => void
  loading: boolean
  search: string
  onSearchChange: (next: string) => void
  /** When false, the search box is hidden (the backend only filters on string fields). */
  searchable?: boolean
  pagination: PaginationProps
}

const PAGE_SIZES = [10, 20, 50, 100]

/**
 * Card/grid alternative to {@link Table}, driven by the same `Column<T>[]` model so an
 * EntityPage can switch between them with one piece of state. The first column is the card
 * heading; the rest render as label/value rows. Sorting lives on the table view only.
 */
export function CardGrid<T extends object>({
  columns, rows, onView, onEdit, onDelete, loading,
  search, onSearchChange, pagination, searchable = true,
}: Props<T>) {
  const { pageNumber, pageSize, totalPages, totalElements, onPageChange, onPageSizeChange } = pagination
  const startRow = totalElements === 0 ? 0 : pageNumber * pageSize + 1
  const endRow = Math.min(totalElements, (pageNumber + 1) * pageSize)
  const empty = !loading && rows.length === 0
  const hasActions = !!onView || !!onEdit || !!onDelete
  const [heading, ...rest] = columns

  return (
    <div className="space-y-3">
      {searchable && (
        <div className="relative max-w-sm">
          <Search className="pointer-events-none absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
          <input
            type="text"
            value={search}
            onChange={e => onSearchChange(e.target.value)}
            placeholder="Search…"
            className="w-full rounded-lg border border-border bg-surface py-2 ps-9 pe-3 text-sm text-fg placeholder:text-muted shadow-sm focus:border-ring focus:outline-none focus:ring-2 focus:ring-ring/40"
          />
        </div>
      )}

      {loading ? (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="rounded-xl border border-border bg-surface p-4 shadow-sm">
              <Skeleton className="h-4 w-2/3" />
              <Skeleton className="mt-3 h-3 w-full" />
              <Skeleton className="mt-2 h-3 w-5/6" />
              <Skeleton className="mt-2 h-3 w-1/2" />
            </div>
          ))}
        </div>
      ) : empty ? (
        <div className="overflow-hidden rounded-xl border border-border bg-surface shadow-sm">
          <EmptyState title="No records yet" hint="Create your first record to see it here." />
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {rows.map((row, idx) => (
            <div
              key={(row as { id?: number | string | null }).id ?? idx}
              className="group flex flex-col rounded-xl border border-border bg-surface p-4 shadow-sm transition-colors hover:border-brand/40"
            >
              {heading && (
                <div className="mb-3 flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="text-[11px] font-semibold uppercase tracking-wider text-muted">{heading.label}</div>
                    <div className="mt-0.5 truncate text-base font-semibold text-fg">{heading.render(row)}</div>
                  </div>
                  {hasActions && (
                    <div className="flex shrink-0 items-center gap-1">
                      {onView && (
                        <button
                          onClick={() => onView(row)}
                          className="rounded-lg p-1.5 text-muted transition-colors hover:bg-surface-2 hover:text-fg"
                          title="View"
                          aria-label="View"
                        >
                          <Eye className="h-4 w-4" />
                        </button>
                      )}
                      {onEdit && (
                        <button
                          onClick={() => onEdit(row)}
                          className="rounded-lg p-1.5 text-muted transition-colors hover:bg-surface-2 hover:text-fg"
                          title="Edit"
                          aria-label="Edit"
                        >
                          <Pencil className="h-4 w-4" />
                        </button>
                      )}
                      {onDelete && (
                        <button
                          onClick={() => onDelete(row)}
                          className="rounded-lg p-1.5 text-muted transition-colors hover:bg-danger/10 hover:text-danger"
                          title="Delete"
                          aria-label="Delete"
                        >
                          <Trash2 className="h-4 w-4" />
                        </button>
                      )}
                    </div>
                  )}
                </div>
              )}
              <dl className="space-y-1.5">
                {rest.map((col, i) => (
                  <div key={i} className="flex items-baseline justify-between gap-3 text-sm">
                    <dt className="shrink-0 text-muted">{col.label}</dt>
                    <dd className="min-w-0 truncate text-end text-fg">{col.render(row)}</dd>
                  </div>
                ))}
              </dl>
            </div>
          ))}
        </div>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3 text-sm text-muted">
        <div>
          {totalElements === 0
            ? 'No results'
            : <>Showing <span className="font-medium text-fg">{startRow}–{endRow}</span> of <span className="font-medium text-fg">{totalElements}</span></>}
        </div>
        <div className="flex items-center gap-3">
          <label className="flex items-center gap-2">
            <span>Per page</span>
            <select
              value={pageSize}
              onChange={e => onPageSizeChange(Number(e.target.value))}
              className="rounded-lg border border-border bg-surface px-2 py-1 text-sm text-fg focus:outline-none focus:ring-2 focus:ring-ring/40"
            >
              {PAGE_SIZES.map(s => <option key={s} value={s}>{s}</option>)}
            </select>
          </label>
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={() => onPageChange(pageNumber - 1)}
              disabled={pageNumber <= 0}
              className="inline-flex items-center gap-1 rounded-lg border border-border bg-surface px-2.5 py-1.5 transition-colors hover:bg-surface-2 disabled:cursor-not-allowed disabled:opacity-40"
            >
              <ChevronLeft className="h-4 w-4" />
              Prev
            </button>
            <span className="px-2 tabular-nums">
              {totalPages === 0 ? 0 : pageNumber + 1} / {totalPages}
            </span>
            <button
              type="button"
              onClick={() => onPageChange(pageNumber + 1)}
              disabled={pageNumber + 1 >= totalPages}
              className="inline-flex items-center gap-1 rounded-lg border border-border bg-surface px-2.5 py-1.5 transition-colors hover:bg-surface-2 disabled:cursor-not-allowed disabled:opacity-40"
            >
              Next
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
