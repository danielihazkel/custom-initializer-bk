import type { ReactNode } from 'react'
import { useEffect, useRef, useState } from 'react'
import { Pencil, X } from 'lucide-react'

interface Props {
  open: boolean
  title: string
  subtitle?: string
  onClose: () => void
  /** When provided, a footer "Edit" button appears (omit for read-only entities). */
  onEdit?: () => void
  children: ReactNode
}

/**
 * Read-only slide-over for viewing a single record in full. Mirrors FormDrawer's animation
 * and layout but has no form semantics — its footer is just Close (+ an optional Edit).
 */
export function DetailDrawer({ open, title, subtitle, onClose, onEdit, children }: Props) {
  // Keep the node mounted briefly after `open` flips to false so the slide-out can play.
  const [mounted, setMounted] = useState(open)
  const [shown, setShown] = useState(false)
  const panelRef = useRef<HTMLDivElement>(null)
  const titleId = 'detail-drawer-title'

  useEffect(() => {
    if (open) {
      setMounted(true)
      const t = requestAnimationFrame(() => setShown(true))
      return () => cancelAnimationFrame(t)
    }
    setShown(false)
    const t = setTimeout(() => setMounted(false), 200)
    return () => clearTimeout(t)
  }, [open])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!mounted) return null
  return (
    <div className="fixed inset-0 z-40 flex">
      <div
        className={`absolute inset-0 bg-black/40 backdrop-blur-sm transition-opacity duration-200 ${
          shown ? 'opacity-100' : 'opacity-0'
        }`}
        onClick={onClose}
      />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className={`ms-auto relative flex h-full w-full max-w-md flex-col border-s border-border bg-surface shadow-2xl transition-transform duration-200 ease-out ${
          shown ? 'translate-x-0' : 'translate-x-full rtl:-translate-x-full'
        }`}
      >
        <div className="flex items-start justify-between border-b border-border px-5 py-4">
          <div>
            <h2 id={titleId} className="text-base font-semibold text-fg">{title}</h2>
            {subtitle && <p className="mt-0.5 text-xs text-muted">{subtitle}</p>}
          </div>
          <button
            onClick={onClose}
            className="-me-1 rounded-lg p-1.5 text-muted transition-colors hover:bg-surface-2 hover:text-fg"
            aria-label="Close"
          >
            <X className="h-5 w-5" />
          </button>
        </div>
        <div className="flex flex-1 flex-col overflow-y-auto px-5 py-5">
          {children}
        </div>
        <div className="flex items-center justify-end gap-2 border-t border-border bg-surface-2 px-5 py-3">
          <button
            onClick={onClose}
            className="rounded-lg px-4 py-2 text-sm font-medium text-fg transition-colors hover:bg-surface"
          >
            Close
          </button>
          {onEdit && (
            <button
              onClick={onEdit}
              className="inline-flex items-center gap-2 rounded-lg bg-brand px-4 py-2 text-sm font-medium text-white shadow-sm transition-colors hover:bg-brand-deep"
            >
              <Pencil className="h-4 w-4" />
              Edit
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
