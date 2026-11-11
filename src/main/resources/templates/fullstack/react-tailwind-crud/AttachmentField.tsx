import { useEffect, useRef, useState } from 'react'
import type { DragEvent } from 'react'
import { api, ApiError } from '@shared/api'
import { t } from '@shared/i18n'
import { attachmentUrl } from './format'

/** The largest file the picker sends; the server's own limit (app.attachments.max-size) is the same. */
const MAX_MB = 10

/** The pictures the server shows as pictures. */
const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp']

interface Stored {
  id: string
  fileName: string
}

interface Props {
  /** The attachment's id, as the record holds it. */
  value: string | null | undefined
  /** Its file name, when known — the API sends it beside the id. */
  name?: string | null
  /** An image field: only pictures can be picked, and the current one is shown. */
  image?: boolean
  disabled?: boolean
  /** A new file's id and name, or nulls when the file is removed. */
  onChange: (id: string | null, name: string | null) => void
  'aria-invalid'?: boolean
  'aria-describedby'?: string
  'aria-required'?: boolean
}

/**
 * A record's file or image: shows the one it has and uploads another — picked, or dropped onto
 * the field. The file is sent the moment it is chosen and the record only keeps its id, so saving
 * the form stays an ordinary JSON save.
 */
export function AttachmentField({ value, name, image, disabled, onChange, ...aria }: Props) {
  const input = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [over, setOver] = useState(false)
  // The picture being sent, shown from the browser's own copy until the server has it.
  const [sending, setSending] = useState<{ url: string | null; name: string } | null>(null)
  const sendingUrl = sending?.url
  useEffect(() => () => { if (sendingUrl) URL.revokeObjectURL(sendingUrl) }, [sendingUrl])

  async function pick(file: File | undefined) {
    if (!file) return
    setError(null)
    if (image && !IMAGE_TYPES.includes(file.type)) {
      setError(t('notAnImage'))
      return
    }
    if (file.size > MAX_MB * 1024 * 1024) {
      setError(t('fileTooLarge', { max: MAX_MB }))
      return
    }
    setBusy(true)
    setSending({ url: image ? URL.createObjectURL(file) : null, name: file.name })
    try {
      const stored = await api.upload<Stored>('/api/attachments', file, image ? { kind: 'image' } : undefined)
      onChange(stored.id, stored.fileName)
    } catch (e) {
      const status = e instanceof ApiError ? e.status : 0
      setError(status === 413 ? t('fileTooLarge', { max: MAX_MB }) : status === 400 && image ? t('notAnImage') : t('uploadFailed'))
    } finally {
      setBusy(false)
      setSending(null)
      // The same file can be picked again after a failure or a removal.
      if (input.current) input.current.value = ''
    }
  }

  const open = !disabled && !busy
  function onDrag(e: DragEvent<HTMLDivElement>) {
    // Only a file is taken; anything else dragged over the form is left to the browser.
    if (!open || !Array.from(e.dataTransfer.types).includes('Files')) return
    e.preventDefault()
    setOver(true)
  }
  function onDrop(e: DragEvent<HTMLDivElement>) {
    if (!open || !Array.from(e.dataTransfer.types).includes('Files')) return
    e.preventDefault()
    setOver(false)
    void pick(e.dataTransfer.files[0])
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
      {sending ? (
        sending.url
          ? <img src={sending.url} alt={sending.name} className="max-h-32 rounded-lg border border-border opacity-60" />
          : <span className="block truncate text-sm text-muted">{sending.name}</span>
      ) : value && (
        <a href={attachmentUrl(value)} target="_blank" rel="noopener noreferrer" className="block w-fit max-w-full">
          {image
            ? <img src={attachmentUrl(value)} alt={name ?? ''} className="max-h-32 rounded-lg border border-border" />
            : <span className="block truncate text-sm font-medium text-brand hover:underline">{name ?? value}</span>}
        </a>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <input
          ref={input}
          type="file"
          accept={image ? IMAGE_TYPES.join(',') : undefined}
          className="sr-only"
          tabIndex={-1}
          aria-hidden
          disabled={disabled || busy}
          onChange={e => void pick(e.target.files?.[0])}
        />
        <button type="button" className={buttonClass} disabled={disabled || busy} onClick={() => input.current?.click()} {...aria}>
          {busy ? t('uploading') : value ? t('replaceFile') : image ? t('chooseImage') : t('chooseFile')}
        </button>
        {value && !disabled && !busy && (
          <button type="button" className={buttonClass} onClick={() => onChange(null, null)}>{t('removeFile')}</button>
        )}
        {open && <span className="text-xs text-muted">{t('dropHere')}</span>}
      </div>
      {error && <p role="alert" className="text-xs text-danger">{error}</p>}
    </div>
  )
}
