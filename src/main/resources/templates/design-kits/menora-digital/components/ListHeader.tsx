import type { ReactNode } from 'react'
import { t } from '@shared/i18n'
import { SectionHeader, Chip, Tabs, Button } from '@shared/ui/menora'

/** The column a list page sits in. */
export function ListFrame({ children }: { children: ReactNode }) {
  return <div className="mx-auto w-full max-w-[1283px] px-4 py-5 md:px-6 md:py-8">{children}</div>
}

interface Props {
  /** The list's heading; left out when the list sits inside another page. */
  title?: string
  description?: string
  /** How many records the list holds. */
  count: number
  /** The list is a read-only view. */
  readOnlyView?: boolean
  /** The views to switch between (two or more), the open one, and the switch. */
  views?: { id: string; label: string }[]
  view?: string
  onViewChange?: (id: string) => void
  /** Each action is shown only when given. */
  onExport?: () => void
  exporting?: boolean
  onImport?: () => void
  onCreate?: () => void
  createLabel?: string
}

/**
 * A list page's heading and toolbar: the SectionHeader, then the view switcher (bare Tabs) and
 * record count Chip on the start side, outlined Export/Import and the view's one yellow New pill
 * on the end side.
 */
export function ListHeader({ title, description, count, readOnlyView, views, view, onViewChange, onExport, exporting, onImport, onCreate, createLabel }: Props) {
  return (
    <>
      <div className="mb-4">
        {title && <SectionHeader title={title} align="start" />}
        {description && <p className="mt-2 max-w-2xl text-sm text-muted">{description}</p>}
      </div>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-3">
          {views && views.length > 1 && (
            <Tabs
              bare
              ariaLabel={t('viewMode')}
              items={views}
              activeIndex={views.findIndex(v => v.id === view)}
              onChange={i => onViewChange?.(views[i].id)}
            />
          )}
          <Chip label={`${count} ${t(count === 1 ? 'record' : 'records')}`} />
          {readOnlyView && <Chip tone="outlined" label={t('readOnlyView')} />}
        </div>
        <div className="flex items-center gap-2">
          {onExport && <Button variant="outlined" className="mn-btn--compact" label={exporting ? t('exporting') : t('export')} onClick={onExport} />}
          {onImport && <Button variant="outlined" className="mn-btn--compact" label={t('import')} onClick={onImport} />}
          {onCreate && <Button variant="primary" className="mn-btn--compact" label={createLabel ?? ''} onClick={onCreate} />}
        </div>
      </div>
    </>
  )
}
