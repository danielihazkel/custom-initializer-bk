import type { ReactNode } from 'react'
import { ChevronDown, ChevronUp, ChevronsUpDown, Pencil, Search, Trash2 } from 'lucide-react'

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

interface Props<T extends { id?: number | string | null }> {
  columns: Column<T>[]
  rows: T[]
  onEdit: (row: T) => void
  onDelete: (row: T) => void
  loading: boolean
  sort: SortSpec | null
  onSortChange: (next: SortSpec | null) => void
  search: string
  onSearchChange: (next: string) => void
  pagination: PaginationProps
}

const PAGE_SIZES = [10, 20, 50, 100]

function nextSort(current: SortSpec | null, field: string): SortSpec | null {
  if (!current || current.field !== field) return { field, direction: 'asc' }
  if (current.direction === 'asc') return { field, direction: 'desc' }
  return null
}

export function Table<T extends { id?: number | string | null }>({
  columns, rows, onEdit, onDelete, loading,
  sort, onSortChange, search, onSearchChange, pagination,
}: Props<T>) {
  const { pageNumber, pageSize, totalPages, totalElements, onPageChange, onPageSizeChange } = pagination
  const startRow = totalElements === 0 ? 0 : pageNumber * pageSize + 1
  const endRow = Math.min(totalElements, (pageNumber + 1) * pageSize)

  return (
    <div className="space-y-3">
      <div className="relative max-w-sm">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
        <input
          type="text"
          value={search}
          onChange={e => onSearchChange(e.target.value)}
          placeholder="Search…"
          className="w-full pl-9 pr-3 py-2 text-sm rounded-md border border-slate-300 bg-white shadow-sm focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500"
        />
      </div>

      <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-slate-50 border-b border-slate-200">
              {columns.map((col, i) => {
                const sortable = !!col.sortKey
                const active = sortable && sort?.field === col.sortKey
                return (
                  <th
                    key={i}
                    className="px-5 py-3 text-left text-[11px] font-semibold uppercase tracking-wider text-slate-500"
                    style={col.width ? { width: col.width } : undefined}
                  >
                    {sortable ? (
                      <button
                        type="button"
                        onClick={() => onSortChange(nextSort(sort, col.sortKey!))}
                        className="inline-flex items-center gap-1 hover:text-slate-900"
                      >
                        {col.label}
                        {active && sort?.direction === 'asc' && <ChevronUp className="w-3 h-3" />}
                        {active && sort?.direction === 'desc' && <ChevronDown className="w-3 h-3" />}
                        {!active && <ChevronsUpDown className="w-3 h-3 opacity-40" />}
                      </button>
                    ) : col.label}
                  </th>
                )
              })}
              <th className="px-5 py-3 text-right text-[11px] font-semibold uppercase tracking-wider text-slate-500 w-24">
                Actions
              </th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan={columns.length + 1} className="px-5 py-12 text-center text-slate-500">
                  Loading…
                </td>
              </tr>
            ) : rows.length === 0 ? (
              <tr>
                <td colSpan={columns.length + 1} className="px-5 py-12 text-center text-slate-500">
                  No records yet
                </td>
              </tr>
            ) : (
              rows.map((row, idx) => (
                <tr
                  key={row.id ?? idx}
                  className="border-t border-slate-100 hover:bg-slate-50 transition-colors"
                >
                  {columns.map((col, i) => (
                    <td key={i} className="px-5 py-3 text-slate-900">
                      {col.render(row)}
                    </td>
                  ))}
                  <td className="px-5 py-3 text-right">
                    <div className="flex items-center justify-end gap-1">
                      <button
                        onClick={() => onEdit(row)}
                        className="p-1.5 rounded-md text-slate-500 hover:text-slate-900 hover:bg-slate-200 transition-colors"
                        title="Edit"
                      >
                        <Pencil className="w-4 h-4" />
                      </button>
                      <button
                        onClick={() => onDelete(row)}
                        className="p-1.5 rounded-md text-slate-500 hover:text-red-600 hover:bg-red-50 transition-colors"
                        title="Delete"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-between text-sm text-slate-600">
        <div>
          Showing {startRow}–{endRow} of {totalElements}
        </div>
        <div className="flex items-center gap-3">
          <label className="flex items-center gap-2">
            <span>Rows per page</span>
            <select
              value={pageSize}
              onChange={e => onPageSizeChange(Number(e.target.value))}
              className="rounded-md border border-slate-300 bg-white px-2 py-1 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500"
            >
              {PAGE_SIZES.map(s => <option key={s} value={s}>{s}</option>)}
            </select>
          </label>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => onPageChange(pageNumber - 1)}
              disabled={pageNumber <= 0}
              className="px-3 py-1 rounded-md border border-slate-300 bg-white disabled:opacity-50 disabled:cursor-not-allowed hover:bg-slate-50"
            >
              Prev
            </button>
            <span>
              Page {totalPages === 0 ? 0 : pageNumber + 1} of {totalPages}
            </span>
            <button
              type="button"
              onClick={() => onPageChange(pageNumber + 1)}
              disabled={pageNumber + 1 >= totalPages}
              className="px-3 py-1 rounded-md border border-slate-300 bg-white disabled:opacity-50 disabled:cursor-not-allowed hover:bg-slate-50"
            >
              Next
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
