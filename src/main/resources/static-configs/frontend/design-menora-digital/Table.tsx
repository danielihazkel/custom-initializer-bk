import type { ReactNode } from 'react';

export interface TableColumn<T> {
  /** Indexes the row; also the value handed to `onSort`. */
  key: string;
  label: string;
  /** `direction: ltr`, logical-end alignment and tabular figures. Use it for dates and codes too, not only amounts. */
  numeric?: boolean;
  /** Wraps the head in a `<button>` and manages `aria-sort`. Sorting stays the caller's job. */
  sortable?: boolean;
  /** The column the row is about — rendered in `ink` at weight 600. */
  keyCol?: boolean;
  width?: string;
  render: (row: T) => ReactNode;
}

export interface TableSort {
  key: string;
  dir: 'asc' | 'desc';
}

export interface TableEmpty {
  title: string;
  hint?: string;
  action?: ReactNode;
}

export interface TableProps<T> {
  /** Rendered as a visually hidden `<caption>`. Required — a table with no caption is unreadable by screen reader. */
  caption: string;
  columns: TableColumn<T>[];
  /** Empty renders the empty state, never an empty grid. */
  rows: T[];
  rowKey: (row: T) => string | number;
  /** Controlled. Only one column carries a caret at a time. */
  sort?: TableSort | null;
  onSort?: (key: string) => void;
  /** 13px or 7px cell padding. Compact drops body to 17px and is for long lists only. */
  density?: 'comfortable' | 'compact';
  empty: TableEmpty;
  /** Optional leading column (e.g. a selection checkbox). */
  leadingHead?: ReactNode;
  leading?: (row: T) => ReactNode;
  /** Optional trailing column (row actions — a text Button or a DropdownMenu, never a primary Button). */
  trailingHead?: ReactNode;
  trailing?: (row: T) => ReactNode;
  /** Keeps the current rows on screen, dimmed, while they are being refetched. */
  dimmed?: boolean;
  /** Replaces the body (e.g. skeleton rows for the very first load). */
  body?: ReactNode;
  className?: string;
}

/**
 * Rows on hairlines, column heads on `surface-subtle`, status carried by a Chip — a real `<table>`
 * with `<caption>`, `<th scope="col">` and `aria-sort`. Hairlines, not stripes; never colour a row to mean
 * approved or rejected; numbers sit in LTR cells with tabular figures so digits line up under each other.
 */
export function Table<T>({
  caption, columns, rows, rowKey, sort, onSort, density = 'comfortable', empty,
  leadingHead, leading, trailingHead, trailing, dimmed = false, body, className,
}: TableProps<T>) {
  const hasLeading = !!leading;
  const hasTrailing = !!trailing;
  const showEmpty = !body && rows.length === 0;
  const tableCls = ['mn-table', density === 'compact' ? 'compact' : '', dimmed ? 'is-dimmed' : ''].filter(Boolean).join(' ');

  return (
    <div className={['mn', 'mn-table__frame', className ?? ''].filter(Boolean).join(' ')}>
      {showEmpty ? (
        <div className="mn-table__empty" role="status">
          <span className="mn-table__empty-title">{empty.title}</span>
          {empty.hint && <span className="mn-table__empty-hint">{empty.hint}</span>}
          {empty.action && <div className="mn-table__empty-action">{empty.action}</div>}
        </div>
      ) : (
        <div className="mn-table__scroll">
          <table className={tableCls}>
            <caption>{caption}</caption>
            <thead>
              <tr>
                {hasLeading && <th scope="col"><span>{leadingHead}</span></th>}
                {columns.map(col => {
                  const active = !!sort && sort.key === col.key;
                  const ariaSort = col.sortable ? (active ? (sort!.dir === 'asc' ? 'ascending' : 'descending') : 'none') : undefined;
                  return (
                    <th
                      key={col.key}
                      scope="col"
                      className={col.numeric ? 'num' : undefined}
                      aria-sort={ariaSort}
                      style={col.width ? { width: col.width } : undefined}
                    >
                      {col.sortable && onSort ? (
                        <button type="button" onClick={() => onSort(col.key)}>
                          {col.label}
                          {active && <span className={`mn-table__caret${sort!.dir === 'asc' ? ' mn-table__caret--asc' : ''}`} aria-hidden="true" />}
                        </button>
                      ) : (
                        <span>{col.label}</span>
                      )}
                    </th>
                  );
                })}
                {hasTrailing && <th scope="col"><span>{trailingHead}</span></th>}
              </tr>
            </thead>
            <tbody>
              {body ??
                rows.map(row => (
                  <tr key={rowKey(row)}>
                    {hasLeading && <td>{leading!(row)}</td>}
                    {columns.map(col => (
                      <td key={col.key} className={[col.numeric ? 'num' : '', col.keyCol ? 'key' : ''].filter(Boolean).join(' ') || undefined}>
                        {col.render(row)}
                      </td>
                    ))}
                    {hasTrailing && <td>{trailing!(row)}</td>}
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
