/** A whole-page message with one way out: an unknown page, or one this user may not open. */
export function PageNotice({ title, hint, action, onAction }: { title: string; hint: string; action: string; onAction: () => void }) {
  return (
    <div className="mx-auto flex max-w-md flex-col items-center gap-3 p-10 text-center" role="alert">
      <h2 className="text-lg font-semibold text-fg">{title}</h2>
      <p className="text-sm text-muted">{hint}</p>
      <button
        type="button"
        onClick={onAction}
        className="rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-on-primary shadow-sm transition-colors hover:bg-primary-deep"
      >
        {action}
      </button>
    </div>
  )
}
