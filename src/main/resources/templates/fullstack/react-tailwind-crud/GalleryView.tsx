import { ChevronLeft, ChevronRight, ImageOff, Search } from 'lucide-react'
import type { Column, PaginationProps } from './Table'
import { EmptyState } from './EmptyState'
import { Skeleton } from './Skeleton'
import { attachmentUrl } from './format'
import { t } from '../i18n'

interface Props<T extends object> {
  columns: Column<T>[]
  rows: T[]
  /** Stable identity for a row (primary key) — see Table. */
  rowKey: (row: T) => string | number
  /** The image field: each row's picture is the attachment its value names. */
  imageField: keyof T & string
  /** That field's column heading: its own column is the tile, so it is not repeated under it. */
  imageLabel: string
  /** Opens a row (its record page, or the quick look). */
  onView?: (row: T) => void
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

/**
 * The list as pictures: a tile per row — its image, its name (the first column) and one more
 * value under it — for an entity whose rows are best told apart by sight. Driven by the same
 * columns, search and pager as the table and the cards, so the page switches between them with
 * one piece of state. A tile opens the row; editing lives on the row's own page or the table.
 */
export function GalleryView<T extends object>({
  columns, rows, rowKey, imageField, imageLabel, onView, loading,
  search, onSearchChange, pagination, searchable = true, filtered = false, emptyAction,
}: Props<T>) {
  const { pageNumber, pageSize, totalPages, totalElements, onPageChange, onPageSizeChange } = pagination
  const startRow = totalElements === 0 ? 0 : pageNumber * pageSize + 1
  const endRow = Math.min(totalElements, (pageNumber + 1) * pageSize)
  const empty = !loading && rows.length === 0
  const [heading, second] = columns.filter(c => c.label !== imageLabel)
  const grid = 'grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4'

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

      {/* Skeleton only for the very first load; a refetch keeps the current tiles on screen. */}
      {loading && rows.length === 0 ? (
        <div className={grid}>
          {Array.from({ length: 8 }).map((_, i) => (
            <div key={i} className="overflow-hidden rounded-xl border border-border bg-surface shadow-sm">
              <Skeleton className="aspect-square w-full" />
              <div className="p-3"><Skeleton className="h-4 w-2/3" /></div>
            </div>
          ))}
        </div>
      ) : empty ? (
        <div className="overflow-hidden rounded-xl border border-border bg-surface shadow-sm">
          {filtered
            ? <EmptyState title={t('noMatchingRecords')} />
            : <EmptyState title={t('noRecordsTitle')} hint={t('noRecordsHint')} action={emptyAction} />}
        </div>
      ) : (
        <ul className={grid}>
          {rows.map(row => {
            const picture = row[imageField]
            const tile = (
              <>
                <div className="flex aspect-square items-center justify-center overflow-hidden bg-surface-2">
                  {picture == null || picture === ''
                    ? <ImageOff className="h-8 w-8 text-muted" aria-hidden="true" />
                    : <img src={attachmentUrl(String(picture))} alt="" loading="lazy" className="h-full w-full object-cover" />}
                </div>
                <div className="p-3">
                  {heading && <div className="truncate text-sm font-semibold text-fg">{heading.render(row)}</div>}
                  {second && <div className="mt-0.5 truncate text-xs text-muted">{second.render(row)}</div>}
                </div>
              </>
            )
            return (
              <li key={rowKey(row)}>
                {onView ? (
                  <button
                    type="button"
                    onClick={() => onView(row)}
                    className="block w-full overflow-hidden rounded-xl border border-border bg-surface text-start shadow-sm transition-colors hover:border-brand/40 focus:outline-none focus:ring-2 focus:ring-ring/40"
                  >
                    {tile}
                  </button>
                ) : (
                  <div className="overflow-hidden rounded-xl border border-border bg-surface shadow-sm">{tile}</div>
                )}
              </li>
            )
          })}
        </ul>
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
