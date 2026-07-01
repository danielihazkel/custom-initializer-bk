import type { ReactNode } from 'react'
import { useEffect, useRef, useState } from 'react'
import { Loader2, X } from 'lucide-react'

interface Props {
  open: boolean
  title: string
  subtitle?: string
  onClose: () => void
  onSave: () => void
  saving?: boolean
  /** When true, dismissing the drawer asks the user to confirm discarding edits. */
  dirty?: boolean
  children: ReactNode
}

export function FormDrawer({ open, title, subtitle, onClose, onSave, saving, dirty, children }: Props) {
  // Keep the node mounted briefly after `open` flips to false so the slide-out can play.
  const [mounted, setMounted] = useState(open)
  const [shown, setShown] = useState(false)
  const panelRef = useRef<HTMLDivElement>(null)
  const titleId = 'form-drawer-title'

  // Guard dismissal when there are unsaved edits (skip while a save is in flight).
  function requestClose() {
    if (saving) return
    if (dirty && !window.confirm('Discard unsaved changes?')) return
    onClose()
  }
  const requestCloseRef = useRef(requestClose)
  requestCloseRef.current = requestClose

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
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') requestCloseRef.current() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])

  // Move focus into the drawer when it opens so keyboard/AT users land inside it.
  useEffect(() => {
    if (!shown) return
    const first = panelRef.current?.querySelector<HTMLElement>(
      'input:not([disabled]), select:not([disabled]), textarea:not([disabled])',
    )
    first?.focus()
  }, [shown])

  if (!mounted) return null
  return (
    <div className="fixed inset-0 z-40 flex">
      <div
        className={`absolute inset-0 bg-black/40 backdrop-blur-sm transition-opacity duration-200 ${
          shown ? 'opacity-100' : 'opacity-0'
        }`}
        onClick={requestClose}
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
            onClick={requestClose}
            className="-me-1 rounded-lg p-1.5 text-muted transition-colors hover:bg-surface-2 hover:text-fg"
            aria-label="Close"
          >
            <X className="h-5 w-5" />
          </button>
        </div>
        <div className="flex flex-1 flex-col gap-4 overflow-y-auto px-5 py-5">
          {children}
        </div>
        <div className="flex items-center justify-end gap-2 border-t border-border bg-surface-2 px-5 py-3">
          <button
            onClick={requestClose}
            className="rounded-lg px-4 py-2 text-sm font-medium text-fg transition-colors hover:bg-surface"
          >
            Cancel
          </button>
          <button
            onClick={onSave}
            disabled={saving}
            className="inline-flex items-center gap-2 rounded-lg bg-brand px-4 py-2 text-sm font-medium text-white shadow-sm transition-colors hover:bg-brand-deep disabled:opacity-50"
          >
            {saving && <Loader2 className="h-4 w-4 animate-spin" />}
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  )
}
