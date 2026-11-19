import { useState, type KeyboardEvent, type ReactNode } from 'react'
import { Pencil } from 'lucide-react'
import { t } from '../i18n'
import { MultiRelationPicker } from './MultiRelationPicker'

type Id = string | number

interface Props {
  /** The column's heading, for the edit button's label. */
  label: string
  /** What the cell shows when it is not being edited (the column's own rendering). */
  display: ReactNode
  /** The list endpoint of the linked entity, its key and the field that names a row. */
  path: string
  valueKey: string
  labelKey?: string
  numeric?: boolean
  /** The row's linked ids, and their labels in the same order. */
  value: Id[]
  labels: string[]
  /** Saves the new set; a rejection keeps the editor open so the choice is not lost. */
  onSave: (ids: Id[]) => Promise<void>
}

/** A table cell holding a set of linked rows, edited in place: a pencil opens the picker, Save
 *  writes the set with the row, Escape or Cancel puts it back. */
export function InlineLinks({ label, display, path, valueKey, labelKey, numeric, value, labels, onSave }: Props) {
  const [editing, setEditing] = useState(false)
  const [ids, setIds] = useState<Id[]>([])
  const [names, setNames] = useState<string[]>([])
  const [saving, setSaving] = useState(false)

  function open() {
    setIds(value)
    setNames(labels)
    setEditing(true)
  }
  async function commit() {
    if (saving) return
    const same = ids.length === value.length && ids.every(id => value.some(v => String(v) === String(id)))
    if (same) {
      setEditing(false)
      return
    }
    setSaving(true)
    try {
      await onSave(ids)
      setEditing(false)
    } catch {
      // The row's own message was shown where the save failed; the picker stays open.
    } finally {
      setSaving(false)
    }
  }
  function onKeyDown(e: KeyboardEvent) {
    if (e.key === 'Escape') {
      // Only the cell: a drawer or dialog listening for Escape skips a handled event.
      e.preventDefault()
      e.stopPropagation()
      setEditing(false)
    }
  }

  if (!editing) {
    return (
      <span className="group/cell inline-flex items-center gap-1.5">
        <span>{display}</span>
        <button
          type="button"
          onClick={e => { e.stopPropagation(); open() }}
          className="rounded p-0.5 text-muted opacity-0 transition-opacity hover:text-fg focus:opacity-100 group-hover/cell:opacity-100"
          aria-label={t('editX', { x: label })}
          title={t('editX', { x: label })}
        >
          <Pencil className="h-3.5 w-3.5" />
        </button>
      </span>
    )
  }

  return (
    // The row opens on a click: one inside the editor is the editor's own.
    <div className="min-w-[14rem] space-y-2" onClick={e => e.stopPropagation()} onKeyDown={onKeyDown} role="group" aria-label={label}>
      <MultiRelationPicker
        path={path}
        valueKey={valueKey}
        labelKey={labelKey}
        numeric={numeric}
        value={ids}
        labels={names}
        disabled={saving}
        onChange={(next, nextLabels) => { setIds(next); setNames(nextLabels) }}
      />
      <div className="flex gap-2">
        <button
          type="button"
          onClick={() => { void commit() }}
          disabled={saving}
          className="rounded-md bg-primary px-2.5 py-1 text-xs font-medium text-on-primary hover:bg-primary-deep disabled:opacity-60"
        >
          {t('save')}
        </button>
        <button
          type="button"
          onClick={() => setEditing(false)}
          disabled={saving}
          className="rounded-md border border-border px-2.5 py-1 text-xs text-fg hover:bg-surface-2 disabled:opacity-60"
        >
          {t('cancel')}
        </button>
      </div>
    </div>
  )
}
