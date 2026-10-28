/** A page's heading: its title and an optional line under it. */
export function PageHeader({ title, description }: { title: string; description?: string }) {
  return (
    <div className="px-4 pt-4 md:px-8 md:pt-8">
      <h1 className="text-2xl font-semibold tracking-tight text-fg">{title}</h1>
      {description && <p className="mt-1 max-w-2xl text-sm text-muted">{description}</p>}
    </div>
  )
}
