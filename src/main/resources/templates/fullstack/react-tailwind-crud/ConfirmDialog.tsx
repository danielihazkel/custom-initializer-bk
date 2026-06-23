import { useEffect, useRef } from 'react'
import { AlertTriangle } from 'lucide-react'

interface Props {
  open: boolean
  title: string
  message: string
  confirmLabel?: string
  busy?: boolean
  onConfirm: () => void
  onCancel: () => void
}

/** Centered confirmation modal for destructive actions (delete). */
export function ConfirmDialog({
  open, title, message, confirmLabel = 'Delete', busy, onConfirm, onCancel,
}: Props) {
  const cancelRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onCancel() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onCancel])

  // Focus the non-destructive action first so an accidental Enter doesn't delete.
  useEffect(() => {
    if (open) cancelRef.current?.focus()
  }, [open])

  if (!open) return null
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/40 backdrop-blur-sm" onClick={onCancel} />
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
        className="relative w-full max-w-sm rounded-2xl border border-border bg-surface p-6 shadow-2xl"
      >
        <div className="flex items-start gap-3">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-danger/10 text-danger">
            <AlertTriangle className="h-5 w-5" />
          </div>
          <div>
            <h3 id="confirm-dialog-title" className="text-base font-semibold text-fg">{title}</h3>
            <p className="mt-1 text-sm text-muted">{message}</p>
          </div>
        </div>
        <div className="mt-6 flex items-center justify-end gap-2">
          <button
            ref={cancelRef}
            onClick={onCancel}
            className="rounded-lg px-4 py-2 text-sm font-medium text-fg transition-colors hover:bg-surface-2"
          >
            Cancel
          </button>
          <button
            onClick={onConfirm}
            disabled={busy}
            className="rounded-lg bg-danger px-4 py-2 text-sm font-medium text-white shadow-sm transition-colors hover:opacity-90 disabled:opacity-50"
          >
            {busy ? 'Deleting…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
