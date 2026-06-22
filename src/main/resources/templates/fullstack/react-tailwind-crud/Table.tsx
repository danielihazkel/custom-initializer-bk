import type { ReactNode } from 'react'
import { ChevronDown, ChevronLeft, ChevronRight, ChevronUp, ChevronsUpDown, Pencil, Search, Trash2 } from 'lucide-react'
import { EmptyState } from './EmptyState'
import { TableSkeleton } from './Skeleton'

export interface Column<T> {
  label: string
  render: (row: T) => ReactNode
  width?: string
  sortKey?: string
}

export interface SortSpec {
  field: string
  direction: 'asc' | 'desc'
}

export interface PaginationProps {
  pageNumber: number
  pageSize: number
  totalPages: number
  totalElements: number
  onPageChange: (page: number) => void
  onPageSizeChange: (size: number) => void
}

interface Props<T extends object> {
  columns: Column<T>[]
  rows: T[]
  /** Omit both handlers for a read-only table — the Actions column is then hidden. */
  onEdit?: (row: T) => void
  onDelete?: (row: T) => void
  loading: boolean
  sort: SortSpec | null
  onSortChange: (next: SortSpec | null) => void
  search: string
  onSearchChange: (next: string) => void
  /** When false, the search box is hidden (the backend only filters on string fields). */
  searchable?: boolean
  pagination: PaginationProps
}

const PAGE_SIZES = [10, 20, 50, 100]

function nextSort(current: SortSpec | null, field: string): SortSpec | null {
  if (!current || current.field !== field) return { field, direction: 'asc' }
  if (current.direction === 'asc') return { field, direction: 'desc' }
  return null
}

export function Table<T extends object>({
  columns, rows, onEdit, onDelete, loading,
  sort, onSortChange, search, onSearchChange, pagination, searchable = true,
}: Props<T>) {
  const { pageNumber, pageSize, totalPages, totalElements, onPageChange, onPageSizeChange } = pagination
  const startRow = totalElements === 0 ? 0 : pageNumber * pageSize + 1
  const endRow = Math.min(totalElements, (pageNumber + 1) * pageSize)
  const empty = !loading && rows.length === 0
  const hasActions = !!onEdit || !!onDelete

  return (
    <div className="space-y-3">
      {searchable && (
        <div className="relative max-w-sm">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
          <input
            type="text"
            value={search}
            onChange={e => onSearchChange(e.target.value)}
            placeholder="Search…"
            className="w-full rounded-lg border border-border bg-surface py-2 pl-9 pr-3 text-sm text-fg placeholder:text-muted shadow-sm focus:border-ring focus:outline-none focus:ring-2 focus:ring-ring/40"
          />
        </div>
      )}

      <div className="overflow-hidden rounded-xl border border-border bg-surface shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="sticky top-0 z-10">
              <tr className="border-b border-border bg-surface-2">
                {columns.map((col, i) => {
                  const sortable = !!col.sortKey
                  const active = sortable && sort?.field === col.sortKey
                  return (
                    <th
                      key={i}
                      className="px-5 py-3 text-left text-[11px] font-semibold uppercase tracking-wider text-muted"
                      style={col.width ? { width: col.width } : undefined}
                    >
                      {sortable ? (
                        <button
                          type="button"
                          onClick={() => onSortChange(nextSort(sort, col.sortKey!))}
                          className={`inline-flex items-center gap-1 transition-colors hover:text-fg ${active ? 'text-fg' : ''}`}
                        >
                          {col.label}
                          {active && sort?.direction === 'asc' && <ChevronUp className="h-3 w-3" />}
                          {active && sort?.direction === 'desc' && <ChevronDown className="h-3 w-3" />}
                          {!active && <ChevronsUpDown className="h-3 w-3 opacity-40" />}
                        </button>
                      ) : col.label}
                    </th>
                  )
                })}
                {hasActions && (
                  <th className="w-24 px-5 py-3 text-right text-[11px] font-semibold uppercase tracking-wider text-muted">
                    Actions
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <TableSkeleton cols={columns.length} />
              ) : empty ? (
                <tr>
                  <td colSpan={columns.length + (hasActions ? 1 : 0)}>
                    <EmptyState title="No records yet" hint="Create your first record to see it here." />
                  </td>
                </tr>
              ) : (
                rows.map((row, idx) => (
                  <tr
                    key={(row as { id?: number | string | null }).id ?? idx}
                    className="border-t border-border transition-colors hover:bg-surface-2/60"
                  >
                    {columns.map((col, i) => (
                      <td key={i} className="px-5 py-3 text-fg">
                        {col.render(row)}
                      </td>
                    ))}
                    {hasActions && (
                      <td className="px-5 py-3 text-right">
                        <div className="flex items-center justify-end gap-1">
                          {onEdit && (
                            <button
                              onClick={() => onEdit(row)}
                              className="rounded-lg p-1.5 text-muted transition-colors hover:bg-surface-2 hover:text-fg"
                              title="Edit"
                            >
                              <Pencil className="h-4 w-4" />
                            </button>
                          )}
                          {onDelete && (
                            <button
                              onClick={() => onDelete(row)}
                              className="rounded-lg p-1.5 text-muted transition-colors hover:bg-danger/10 hover:text-danger"
                              title="Delete"
                            >
                              <Trash2 className="h-4 w-4" />
                            </button>
                          )}
                        </div>
                      </td>
                    )}
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3 text-sm text-muted">
        <div>
          {totalElements === 0
            ? 'No results'
            : <>Showing <span className="font-medium text-fg">{startRow}–{endRow}</span> of <span className="font-medium text-fg">{totalElements}</span></>}
        </div>
        <div className="flex items-center gap-3">
          <label className="flex items-center gap-2">
            <span>Rows</span>
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
