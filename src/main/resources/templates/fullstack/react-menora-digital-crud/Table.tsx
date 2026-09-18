import type { ReactNode } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Chip, DropdownMenu, SearchField, Table as MenoraTable } from '@shared/ui/menora'
import { TableSkeleton } from './Skeleton'
import { t } from '../i18n'

export interface Column<T> {
  label: string
  render: (row: T) => ReactNode
  width?: string
  sortKey?: string
  /** LTR cell with tabular figures, end-aligned — numbers, dates, ids and codes. */
  numeric?: boolean
  /** A short status/category value rendered as a lavender Chip (enum, boolean). */
  chip?: boolean
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
  /** Visually hidden `<caption>` of the underlying table (the collection's name). */
  caption: string
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
}

const PAGE_SIZES = [10, 20, 50, 100]

function nextSort(current: SortSpec | null, field: string): SortSpec | null {
  if (!current || current.field !== field) return { field, direction: 'asc' }
  if (current.direction === 'asc') return { field, direction: 'desc' }
  return null
}

/** The `⋯` glyph of the row-action menu trigger (18px, three currentColor dots). */
function Dots() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <circle cx="5" cy="12" r="2" />
      <circle cx="12" cy="12" r="2" />
      <circle cx="19" cy="12" r="2" />
    </svg>
  )
}

/**
 * The Menora Digital table: rows on hairlines in a 30px-radius shadow card, heads on
 * `surface-subtle`, a caret only on the sorted column, LTR tabular numeric cells and lavender
 * Chips for statuses. Same props as the default set's Table (plus `caption`), so the entity
 * pages drive it with the same `Column<T>[]` model.
 */
export function Table<T extends object>({
  caption, columns, rows, rowKey, onView, onEdit, onDelete, loading,
  sort, onSortChange, search, onSearchChange, pagination, searchable = true,
  selectable = false, isRowSelected, onToggleRow, allOnPageSelected, onToggleAllOnPage,
}: Props<T>) {
  const { pageNumber, pageSize, totalPages, totalElements, onPageChange, onPageSizeChange } = pagination
  const startRow = totalElements === 0 ? 0 : pageNumber * pageSize + 1
  const endRow = Math.min(totalElements, (pageNumber + 1) * pageSize)
  const hasActions = !!onView || !!onEdit || !!onDelete
  const leadCols = selectable ? 1 : 0

  const menoraColumns = columns.map((col, i) => ({
    key: col.sortKey ?? `c${i}`,
    label: col.label,
    numeric: col.numeric,
    sortable: !!col.sortKey,
    width: col.width,
    render: (row: T) => {
      const out = col.render(row)
      // `render` yields a ReactNode, so a chip column can only be wrapped when the cell is a plain
      // value; the '—' placeholder for null stays plain text.
      if (col.chip && (typeof out === 'string' || typeof out === 'number') && out !== '—') {
        return <Chip label={String(out)} compact />
      }
      return out
    },
  }))

  const menuItems = (row: T) => [
    ...(onView ? [{ label: t('view'), onSelect: () => onView(row) }] : []),
    ...(onEdit ? [{ label: t('edit'), onSelect: () => onEdit(row) }] : []),
    ...(onDelete ? [{ label: t('delete'), onSelect: () => onDelete(row) }] : []),
  ]

  // Skeleton only for the very first load; a refetch (page/sort/search change) keeps the current
  // rows on screen, dimmed, instead of flashing an empty table.
  const firstLoad = loading && rows.length === 0

  return (
    <div className="space-y-3">
      {searchable && (
        <div className="max-w-sm">
          <SearchField
            value={search}
            onInput={onSearchChange}
            label={t('search')}
            placeholder={t('search')}
            onClear={() => onSearchChange('')}
            clearLabel={t('clearSearch')}
          />
        </div>
      )}

      <MenoraTable<T>
        caption={caption}
        columns={menoraColumns}
        rows={rows}
        rowKey={rowKey}
        sort={sort ? { key: sort.field, dir: sort.direction } : null}
        onSort={key => onSortChange(nextSort(sort, key))}
        empty={{ title: t('noRecordsTitle'), hint: t('noRecordsHint') }}
        leadingHead={selectable ? (
          <input
            type="checkbox"
            checked={!!allOnPageSelected}
            onChange={onToggleAllOnPage}
            aria-label={t('selectAllOnPage')}
            className="h-4 w-4 rounded border-border accent-brand"
          />
        ) : undefined}
        leading={selectable ? row => (
          <input
            type="checkbox"
            checked={isRowSelected ? isRowSelected(row) : false}
            onChange={() => onToggleRow?.(row)}
            aria-label={t('selectRow')}
            className="h-4 w-4 rounded border-border accent-brand"
          />
        ) : undefined}
        trailingHead={hasActions ? t('actions') : undefined}
        trailing={hasActions ? row => (
          <DropdownMenu
            width="auto"
            ariaLabel={t('actions')}
            groups={[{ items: menuItems(row) }]}
            trigger={({ props }) => (
              <button
                type="button"
                {...props}
                className={`mn mn-btn mn-btn--text mn-btn--icon ${props.className}`}
                aria-label={t('actions')}
                title={t('actions')}
              >
                <Dots />
              </button>
            )}
          />
        ) : undefined}
        dimmed={loading && rows.length > 0}
        body={firstLoad ? <TableSkeleton cols={columns.length + leadCols} /> : undefined}
      />

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
              className="mn-field"
              style={{ minHeight: 36, padding: '2px 10px' }}
            >
              {PAGE_SIZES.map(s => <option key={s} value={s}>{s}</option>)}
            </select>
          </label>
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={() => onPageChange(pageNumber - 1)}
              disabled={pageNumber <= 0}
              className="mn mn-btn mn-btn--text"
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
              className="mn mn-btn mn-btn--text"
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
