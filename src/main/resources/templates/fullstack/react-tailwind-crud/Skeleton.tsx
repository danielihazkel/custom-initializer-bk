import { clsx } from 'clsx'

/** A single shimmering placeholder block. */
export function Skeleton({ className }: { className?: string }) {
  return <div className={clsx('animate-pulse rounded-md bg-surface-2', className)} />
}

/** Placeholder rows for a Table body while the first page loads. */
export function TableSkeleton({ rows = 6, cols }: { rows?: number; cols: number }) {
  return (
    <>
      {Array.from({ length: rows }).map((_, r) => (
        <tr key={r} className="border-t border-border">
          {Array.from({ length: cols }).map((_, c) => (
            <td key={c} className="px-5 py-3.5">
              <Skeleton className="h-3.5" />
            </td>
          ))}
          <td className="px-5 py-3.5">
            <Skeleton className="ms-auto h-3.5 w-12" />
          </td>
        </tr>
      ))}
    </>
  )
}
