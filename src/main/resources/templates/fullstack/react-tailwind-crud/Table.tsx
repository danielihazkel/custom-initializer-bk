import type { ReactNode } from 'react'
import { ChevronDown, ChevronLeft, ChevronRight, ChevronUp, ChevronsUpDown, Eye, Pencil, Search, Trash2 } from 'lucide-react'
import { EmptyState } from './EmptyState'
import { TableSkeleton } from './Skeleton'
import { t } from '../i18n'

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

/** Where a list is — what a list page keeps in its route. Each part absent is the list's opening
 *  value; `sort: null` is "no sort" chosen over an opening sort. */
export interface ListState {
  q?: string
  sort?: SortSpec | null
  page?: number
  size?: number
  view?: string
  filters?: Record<string, string>
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
  /** Stable identity for a row (its primary key, joined for composite keys). Drives React keys so
   *  rows keep their DOM state across sort/page changes; never falls back to the array index. */
  rowKey: (row: T) => string | number
  /** Omit all handlers for a read-only table — the Actions column is then hidden. */
  onView?: (row: T) => void
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
  /** Bulk-selection (opt-in). When true, a leading checkbox column is rendered and the caller owns
   *  the selected set via the predicates/handlers below. */
  selectable?: boolean
  isRowSelected?: (row: T) => boolean
  onToggleRow?: (row: T) => void
  /** Header checkbox state + handler for "select all rows on this page". */
  allOnPageSelected?: boolean
  onToggleAllOnPage?: () => void
  /** A search or filter is on: an empty page says nothing matches rather than inviting a first record. */
  filtered?: boolean
  /** Offered on an empty, unfiltered list — "New <entity>". */
  emptyAction?: { label: string; onClick: () => void }
}

const PAGE_SIZES = [10, 20, 50, 100]

function nextSort(current: SortSpec | null, field: string): SortSpec | null {
  if (!current || current.field !== field) return { field, direction: 'asc' }
  if (current.direction === 'asc') return { field, direction: 'desc' }
  return null
}

export function Table<T extends object>({
  columns, rows, rowKey, onView, onEdit, onDelete, loading,
  sort, onSortChange, search, onSearchChange, pagination, searchable = true,
  selectable = false, isRowSelected, onToggleRow, allOnPageSelected, onToggleAllOnPage,
  filtered = false, emptyAction,
}: Props<T>) {
  const { pageNumber, pageSize, totalPages, totalElements, onPageChange, onPageSizeChange } = pagination
  const startRow = totalElements === 0 ? 0 : pageNumber * pageSize + 1
  const endRow = Math.min(totalElements, (pageNumber + 1) * pageSize)
  const empty = !loading && rows.length === 0
  const hasActions = !!onView || !!onEdit || !!onDelete
  const leadCols = selectable ? 1 : 0

  return (
    <div className="space-y-3">
      {searchable && (
        <div className="relative max-w-sm">
          <Search className="pointer-events-none absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
          <input
            type="text"
            value={search}
            onChange={e => onSearchChange(e.target.value)}
            placeholder={t('search')}
            className="w-full rounded-lg border border-border bg-surface py-2 ps-9 pe-3 text-sm text-fg placeholder:text-muted shadow-sm focus:border-ring focus:outline-none focus:ring-2 focus:ring-ring/40"
          />
        </div>
      )}

      <div className="overflow-hidden rounded-xl border border-border bg-surface shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="sticky top-0 z-10">
              <tr className="border-b border-border bg-surface-2">
                {selectable && (
                  <th className="w-10 px-5 py-3 text-start">
                    <input
                      type="checkbox"
                      checked={!!allOnPageSelected}
                      onChange={onToggleAllOnPage}
                      aria-label={t('selectAllOnPage')}
                      className="h-4 w-4 rounded border-border accent-brand"
                    />
                  </th>
                )}
                {columns.map((col, i) => {
                  const sortable = !!col.sortKey
                  const active = sortable && sort?.field === col.sortKey
                  return (
                    <th
                      key={i}
                      className="px-5 py-3 text-start text-[11px] font-semibold uppercase tracking-wider text-muted"
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
                  <th className="sticky end-0 z-20 w-24 border-s border-border bg-surface-2 px-5 py-3 text-end text-[11px] font-semibold uppercase tracking-wider text-muted">
                    {t('actions')}
                  </th>
                )}
              </tr>
            </thead>
            {/* Skeleton only for the very first load; a refetch (page/sort/search change) keeps the
                current rows on screen, dimmed, instead of flashing an empty table. */}
            <tbody className={loading && rows.length > 0 ? 'opacity-60 transition-opacity' : 'transition-opacity'}>
              {loading && rows.length === 0 ? (
                <TableSkeleton cols={columns.length + leadCols} />
              ) : empty ? (
                <tr>
                  <td colSpan={columns.length + leadCols + (hasActions ? 1 : 0)}>
                    {filtered
                      ? <EmptyState title={t('noMatchingRecords')} />
                      : <EmptyState title={t('noRecordsTitle')} hint={t('noRecordsHint')} action={emptyAction} />}
                  </td>
                </tr>
              ) : (
                rows.map(row => (
                  <tr
                    key={rowKey(row)}
                    className="group border-t border-border transition-colors hover:bg-surface-2/60"
                  >
                    {selectable && (
                      <td className="px-5 py-3">
                        <input
                          type="checkbox"
                          checked={isRowSelected ? isRowSelected(row) : false}
                          onChange={() => onToggleRow?.(row)}
                          aria-label={t('selectRow')}
                          className="h-4 w-4 rounded border-border accent-brand"
                        />
                      </td>
                    )}
                    {columns.map((col, i) => (
                      <td key={i} className="px-5 py-3 text-fg">
                        {col.render(row)}
                      </td>
                    ))}
                    {hasActions && (
                      <td className="sticky end-0 z-10 border-s border-border bg-surface px-5 py-3 text-end transition-colors group-hover:bg-surface-2">
                        <div className="flex items-center justify-end gap-1">
                          {onView && (
                            <button
                              onClick={() => onView(row)}
                              className="rounded-lg p-1.5 text-muted transition-colors hover:bg-surface-2 hover:text-fg"
                              title={t('view')}
                              aria-label={t('view')}
                            >
                              <Eye className="h-4 w-4" />
                            </button>
                          )}
                          {onEdit && (
                            <button
                              onClick={() => onEdit(row)}
                              className="rounded-lg p-1.5 text-muted transition-colors hover:bg-surface-2 hover:text-fg"
                              title={t('edit')}
                              aria-label={t('edit')}
                            >
                              <Pencil className="h-4 w-4" />
                            </button>
                          )}
                          {onDelete && (
                            <button
                              onClick={() => onDelete(row)}
                              className="rounded-lg p-1.5 text-muted transition-colors hover:bg-danger/10 hover:text-danger"
                              title={t('delete')}
                              aria-label={t('delete')}
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
            ? t('noResults')
            : <>{t('showing')} <span className="font-medium text-fg">{startRow}–{endRow}</span> {t('of')} <span className="font-medium text-fg">{totalElements}</span></>}
        </div>
        <div className="flex items-center gap-3">
          <label className="flex items-center gap-2">
            <span>{t('rows')}</span>
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
              <ChevronLeft className="h-4 w-4 rtl:rotate-180" />
              {t('prev')}
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
              {t('next')}
              <ChevronRight className="h-4 w-4 rtl:rotate-180" />
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
