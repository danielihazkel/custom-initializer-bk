import { useRef, useState } from 'react'
import type { DragEvent } from 'react'
import { api, ApiError } from '@shared/api'
import { t } from '@shared/i18n'
import { attachmentUrl } from './format'

/** The largest file the picker sends; the server's own limit (app.attachments.max-size) is the same. */
const MAX_MB = 10

/** The pictures the server shows as pictures. */
const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp']

/** One file of the list, as the record holds it: its id, and its name once the server has it. */
interface AttachedFile {
  id: string
  name?: string | null
}

interface Stored {
  id: string
  fileName: string
}

interface Props {
  value: AttachedFile[]
  /** At most this many files. */
  max: number
  /** An image field: only pictures can be picked, and they are shown as pictures. */
  image?: boolean
  disabled?: boolean
  onChange: (files: AttachedFile[]) => void
  'aria-invalid'?: boolean
  'aria-describedby'?: string
  'aria-required'?: boolean
}

/**
 * A record's files, several in one field: the ones it has, each removable, and more uploaded —
 * picked several at a time, or dropped onto the field. Each file is sent the moment it is chosen
 * and the record keeps their ids, in order, so saving the form stays an ordinary JSON save.
 */
export function AttachmentsField({ value, max, image, disabled, onChange, ...aria }: Props) {
  const input = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [over, setOver] = useState(false)
  const room = max - value.length

  async function pick(list: FileList | File[] | null | undefined) {
    const files = Array.from(list ?? [])
    if (files.length === 0) return
    setError(null)
    if (files.length > room) {
      setError(t('maxFiles', { n: max }))
      return
    }
    for (const file of files) {
      if (image && !IMAGE_TYPES.includes(file.type)) {
        setError(t('notAnImage'))
        return
      }
      if (file.size > MAX_MB * 1024 * 1024) {
        setError(t('fileTooLarge', { max: MAX_MB }))
        return
      }
    }
    setBusy(true)
    const added: AttachedFile[] = []
    try {
      for (const file of files) {
        const stored = await api.upload<Stored>('/api/attachments', file, image ? { kind: 'image' } : undefined)
        added.push({ id: stored.id, name: stored.fileName })
      }
    } catch (e) {
      const status = e instanceof ApiError ? e.status : 0
      setError(status === 413 ? t('fileTooLarge', { max: MAX_MB }) : status === 400 && image ? t('notAnImage') : t('uploadFailed'))
    } finally {
      // What was sent before a failure is kept: those files are on the server.
      if (added.length > 0) onChange([...value, ...added])
      setBusy(false)
      // The same files can be picked again after a failure or a removal.
      if (input.current) input.current.value = ''
    }
  }

  const open = !disabled && !busy && room > 0
  function onDrag(e: DragEvent<HTMLDivElement>) {
    if (!open || !Array.from(e.dataTransfer.types).includes('Files')) return
    e.preventDefault()
    setOver(true)
  }
  function onDrop(e: DragEvent<HTMLDivElement>) {
    if (!open || !Array.from(e.dataTransfer.types).includes('Files')) return
    e.preventDefault()
    setOver(false)
    void pick(e.dataTransfer.files)
  }
  function remove(id: string) {
    onChange(value.filter(file => file.id !== id))
  }

  const buttonClass = 'rounded-lg border border-border bg-surface px-3 py-1.5 text-sm text-fg hover:bg-surface-2 '
    + 'focus:outline-none focus:ring-2 focus:ring-ring/40 disabled:cursor-not-allowed disabled:opacity-60'
  return (
    <div
      className={`space-y-2 rounded-lg outline-2 outline-offset-4 outline-dashed ${over ? 'outline-brand' : 'outline-transparent'}`}
      onDragEnter={onDrag}
      onDragOver={onDrag}
      onDragLeave={e => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setOver(false) }}
      onDrop={onDrop}
    >
      {value.length > 0 && (
        <ul className={image ? 'flex flex-wrap gap-2' : 'space-y-1'}>
          {value.map(file => (
            <li key={file.id} className={image ? 'relative' : 'flex items-center gap-2'}>
              <a href={attachmentUrl(file.id)} target="_blank" rel="noopener noreferrer" className="block w-fit max-w-full">
                {image
                  ? <img src={attachmentUrl(file.id, true)} alt={file.name ?? ''} className="h-20 w-20 rounded-lg border border-border object-cover" />
                  : <span className="block truncate text-sm font-medium text-brand hover:underline">{file.name ?? file.id}</span>}
              </a>
              {!disabled && !busy && (
                <button
                  type="button"
                  className={image ? 'absolute -end-2 -top-2 rounded-full border border-border bg-surface px-1.5 text-xs' : 'text-xs text-muted hover:text-danger'}
                  aria-label={`${t('removeFile')} ${file.name ?? ''}`}
                  onClick={() => remove(file.id)}
                >
                  {image ? '×' : t('removeFile')}
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <input
          ref={input}
          type="file"
          multiple
          accept={image ? IMAGE_TYPES.join(',') : undefined}
          className="sr-only"
          tabIndex={-1}
          aria-hidden
          disabled={!open}
          onChange={e => void pick(e.target.files)}
        />
        <button type="button" className={buttonClass} disabled={!open} onClick={() => input.current?.click()} {...aria}>
          {busy ? t('uploading') : image ? t('chooseImage') : t('chooseFile')}
        </button>
        {open && <span className="text-xs text-muted">{t('dropHere')}</span>}
        <span className="text-xs text-muted">{value.length} / {max}</span>
      </div>
      {error && <p role="alert" className="text-xs text-danger">{error}</p>}
    </div>
  )
}
