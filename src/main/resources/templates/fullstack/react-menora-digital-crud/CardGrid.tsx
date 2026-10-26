import { ChevronLeft, ChevronRight } from 'lucide-react'
import { DropdownMenu, SearchField } from '@shared/ui/menora'
import type { Column, PaginationProps } from './Table'
import { EmptyState } from './EmptyState'
import { Skeleton } from './Skeleton'
import { t } from '../i18n'

interface Props<T extends object> {
  columns: Column<T>[]
  rows: T[]
  /** Stable identity for a row (primary key) — see Table. */
  rowKey: (row: T) => string | number
  /** Optional row actions, offered from a `⋯` menu on the card; omit all three for a
   *  read-only grid (no action menu). Mirrors Table so the two are drop-in interchangeable. */
  onView?: (row: T) => void
  onEdit?: (row: T) => void
  onDelete?: (row: T) => void
  loading: boolean
  search: string
  onSearchChange: (next: string) => void
  /** When false, the search box is hidden (the backend only filters on string fields). */
  searchable?: boolean
  pagination: PaginationProps
  /** See Table: a filtered empty page says nothing matches; an unfiltered one offers `emptyAction`. */
  filtered?: boolean
  emptyAction?: { label: string; onClick: () => void }
}

const PAGE_SIZES = [10, 20, 50, 100]

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
 * Card/grid alternative to {@link Table}, driven by the same `Column<T>[]` model so an
 * EntityPage can switch between them with one piece of state. Each card is a lined `.mn-panel`;
 * the first column is the card heading, the rest render as label/value rows. Sorting lives on
 * the table view only.
 */
export function CardGrid<T extends object>({
  columns, rows, rowKey, onView, onEdit, onDelete, loading,
  search, onSearchChange, pagination, searchable = true, filtered = false, emptyAction,
}: Props<T>) {
  const { pageNumber, pageSize, totalPages, totalElements, onPageChange, onPageSizeChange } = pagination
  const startRow = totalElements === 0 ? 0 : pageNumber * pageSize + 1
  const endRow = Math.min(totalElements, (pageNumber + 1) * pageSize)
  const empty = !loading && rows.length === 0
  const hasActions = !!onView || !!onEdit || !!onDelete
  const [heading, ...rest] = columns

  const menuItems = (row: T) => [
    ...(onView ? [{ label: t('view'), onSelect: () => onView(row) }] : []),
    ...(onEdit ? [{ label: t('edit'), onSelect: () => onEdit(row) }] : []),
    ...(onDelete ? [{ label: t('delete'), onSelect: () => onDelete(row) }] : []),
  ]

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

      {/* Skeleton only for the very first load; a refetch keeps the current cards on screen. */}
      {loading && rows.length === 0 ? (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="mn-panel mn-panel--lined p-4">
              <Skeleton className="h-4 w-2/3" />
              <Skeleton className="mt-3 h-3 w-full" />
              <Skeleton className="mt-2 h-3 w-5/6" />
              <Skeleton className="mt-2 h-3 w-1/2" />
            </div>
          ))}
        </div>
      ) : empty ? (
        <div className="mn-panel">
          {filtered
            ? <EmptyState title={t('noMatchingRecords')} />
            : <EmptyState title={t('noRecordsTitle')} hint={t('noRecordsHint')} action={emptyAction} />}
        </div>
      ) : (
        <div className={`grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 transition-opacity ${loading ? 'opacity-60' : ''}`}>
          {rows.map(row => (
            <div key={rowKey(row)} className="mn-panel mn-panel--lined flex flex-col p-5">
              {heading && (
                <div className="mb-3 flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="text-[11px] font-semibold uppercase tracking-wider text-muted">{heading.label}</div>
                    <div className="mt-0.5 truncate text-lg font-semibold text-fg">{heading.render(row)}</div>
                  </div>
                  {hasActions && (
                    <DropdownMenu
                      width="auto"
                      ariaLabel={t('actions')}
                      groups={[{ items: menuItems(row) }]}
                      trigger={({ props }) => (
                        <button
                          type="button"
                          {...props}
                          className={`mn mn-btn mn-btn--text mn-btn--icon shrink-0 ${props.className}`}
                          aria-label={t('actions')}
                          title={t('actions')}
                        >
                          <Dots />
                        </button>
                      )}
                    />
                  )}
                </div>
              )}
              <dl className="space-y-1.5">
                {rest.map((col, i) => (
                  <div key={i} className="flex items-baseline justify-between gap-3 text-[15px]">
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
            ? t('noResults')
            : <>{t('showing')} <span className="font-medium text-fg">{startRow}–{endRow}</span> {t('of')} <span className="font-medium text-fg">{totalElements}</span></>}
        </div>
        <div className="flex items-center gap-3">
          <label className="flex items-center gap-2">
            <span>{t('perPage')}</span>
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
