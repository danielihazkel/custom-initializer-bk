import { useRef, useState } from 'react'
import { api, ApiError } from '@shared/api'
import { t } from '@shared/i18n'
import { attachmentUrl } from './format'

/** The largest file the picker sends; the server's own limit (app.attachments.max-size) is the same. */
const MAX_MB = 10

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
 * A record's file or image: shows the one it has and uploads another. The file is sent the moment
 * it is picked and the record only keeps its id, so saving the form stays an ordinary JSON save.
 */
export function AttachmentField({ value, name, image, disabled, onChange, ...aria }: Props) {
  const input = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function pick(file: File | undefined) {
    if (!file) return
    setError(null)
    if (file.size > MAX_MB * 1024 * 1024) {
      setError(t('fileTooLarge', { max: MAX_MB }))
      return
    }
    setBusy(true)
    try {
      const stored = await api.upload<Stored>('/api/attachments', file, image ? { kind: 'image' } : undefined)
      onChange(stored.id, stored.fileName)
    } catch (e) {
      const status = e instanceof ApiError ? e.status : 0
      setError(status === 413 ? t('fileTooLarge', { max: MAX_MB }) : status === 400 && image ? t('notAnImage') : t('uploadFailed'))
    } finally {
      setBusy(false)
      // The same file can be picked again after a failure or a removal.
      if (input.current) input.current.value = ''
    }
  }

  const buttonClass = 'rounded-lg border border-border bg-surface px-3 py-1.5 text-sm text-fg hover:bg-surface-2 '
    + 'focus:outline-none focus:ring-2 focus:ring-ring/40 disabled:cursor-not-allowed disabled:opacity-60'
  return (
    <div className="space-y-2">
      {value && (
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
          accept={image ? 'image/png,image/jpeg,image/gif,image/webp' : undefined}
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
      </div>
      {error && <p role="alert" className="text-xs text-danger">{error}</p>}
    </div>
  )
}
