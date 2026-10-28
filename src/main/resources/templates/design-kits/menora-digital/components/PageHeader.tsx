import { SectionHeader } from '@shared/ui/menora'

/** A page's heading: the Menora SectionHeader and an optional line under it. */
export function PageHeader({ title, description }: { title: string; description?: string }) {
  return (
    <div className="mx-auto max-w-[1283px] px-4 pt-6 md:px-6">
      <SectionHeader title={title} align="start" />
      {description && <p className="mt-2 max-w-2xl text-sm text-muted">{description}</p>}
    </div>
  )
}
