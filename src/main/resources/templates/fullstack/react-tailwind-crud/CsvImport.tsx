import { useState } from 'react'
import { FileUp, Upload } from 'lucide-react'
import { api, ApiError } from '../api'
import { t } from '../i18n'
import { coerce, guessMapping, parseCsv, sampleCsv, type ImportField } from './csv'

/** The most rows one import takes (the generated endpoint refuses more). */
const MAX_ROWS = 1000
/** The problems listed before "and n more". */
const SHOWN_PROBLEMS = 50

interface Props {
  /** The entity's `IMPORT_FIELDS`. */
  fields: ImportField[]
  /** The entity's form validator, run on every row before anything is sent. */
  validate: (row: Record<string, unknown>) => Record<string, string>
  /** The entity's list endpoint (`/api/tasks`); rows go to `<path>/import`. */
  path: string
  /** The entity's plural label, for the headings. */
  label: string
  /** Called with the number of rows created. */
  onDone: (created: number) => void
}

/** One problem with one line of the file (lines counted as a spreadsheet shows them, header = 1). */
interface Problem {
  line: number
  field?: string
  message: string
}

/**
 * A CSV import in three steps: choose a file, match its columns to the entity's fields, review.
 * Every row is read and checked in the browser first; the server checks them again and saves them
 * all or none, so a corrected file can simply be imported again.
 */
export function CsvImport({ fields, validate, path, label, onDone }: Props) {
  const [fileName, setFileName] = useState<string | null>(null)
  const [headers, setHeaders] = useState<string[]>([])
  const [rows, setRows] = useState<string[][]>([])
  const [mapping, setMapping] = useState<(string | null)[]>([])
  const [step, setStep] = useState<'file' | 'map' | 'review'>('file')
  const [fileError, setFileError] = useState<string | null>(null)
  const [serverProblems, setServerProblems] = useState<Problem[]>([])
  const [busy, setBusy] = useState(false)

  const labelOf = (name: string | undefined) => fields.find(f => f.name === name)?.label ?? name ?? ''

  async function choose(file: File | undefined) {
    if (!file) return
    setFileError(null)
    setServerProblems([])
    const cells = parseCsv(await file.text())
    if (cells.length < 2) {
      setFileError(t('importEmpty'))
      return
    }
    if (cells.length - 1 > MAX_ROWS) {
      setFileError(t('importTooMany', { n: MAX_ROWS }))
      return
    }
    setFileName(file.name)
    setHeaders(cells[0])
    setRows(cells.slice(1))
    setMapping(guessMapping(cells[0], fields))
    setStep('map')
  }

  function downloadSample() {
    const url = URL.createObjectURL(new Blob([sampleCsv(fields)], { type: 'text/csv;charset=utf-8' }))
    const a = document.createElement('a')
    a.href = url
    a.download = 'import-sample.csv'
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  }

  const mapped = new Set(mapping.filter((m): m is string => m != null))
  const missing = fields.filter(f => f.required && !mapped.has(f.name))

  // Every row read through the mapping, with what is wrong with it.
  const records: Record<string, unknown>[] = []
  const problems: Problem[] = []
  if (step === 'review') {
    rows.forEach((cells, i) => {
      const line = i + 2
      const record: Record<string, unknown> = {}
      const own: Record<string, string> = {}
      mapping.forEach((name, column) => {
        const field = fields.find(f => f.name === name)
        if (!field) return
        const result = coerce(field, cells[column] ?? '')
        if ('error' in result) own[field.name] = result.error
        else if (result.value !== null) record[field.name] = result.value
      })
      for (const [name, message] of Object.entries({ ...validate(record), ...own })) {
        problems.push({ line, field: name, message })
      }
      records.push(record)
    })
  }
  const allProblems = [...problems, ...serverProblems]

  async function run() {
    setBusy(true)
    setServerProblems([])
    try {
      const result = await api.post<{ created: number }>(`${path}/import`, records)
      onDone(result.created)
    } catch (e) {
      // 422: the server's own check found problems (a key taken, a link to nothing), by row.
      const body = e instanceof ApiError ? e.body as { errors?: { row: number; field?: string | null; message: string }[] } | undefined : undefined
      if (body?.errors) {
        setServerProblems(body.errors.map(err => ({ line: err.row + 1, field: err.field ?? undefined, message: err.message })))
      } else {
        setServerProblems([{ line: 0, message: e instanceof Error ? e.message : String(e) }])
      }
    } finally {
      setBusy(false)
    }
  }

  const stepClass = (active: boolean) =>
    `rounded-full px-2.5 py-0.5 text-xs font-medium ${active ? 'bg-brand text-white' : 'bg-surface-2 text-muted'}`

  return (
    <div className="space-y-4">
      <ol className="flex flex-wrap items-center gap-2" aria-label={t('importSteps')}>
        <li className={stepClass(step === 'file')} aria-current={step === 'file' ? 'step' : undefined}>1 · {t('importChooseFile')}</li>
        <li className={stepClass(step === 'map')} aria-current={step === 'map' ? 'step' : undefined}>2 · {t('importMatchColumns')}</li>
        <li className={stepClass(step === 'review')} aria-current={step === 'review' ? 'step' : undefined}>3 · {t('importReview')}</li>
      </ol>

      {step === 'file' && (
        <div className="space-y-3">
          <label
            className="flex cursor-pointer flex-col items-center gap-2 rounded-xl border-2 border-dashed border-border bg-surface-2/40 px-6 py-10 text-center transition-colors hover:border-brand"
            onDragOver={e => e.preventDefault()}
            onDrop={e => { e.preventDefault(); void choose(e.dataTransfer.files[0]) }}
          >
            <FileUp className="h-8 w-8 text-muted" />
            <span className="text-sm font-medium text-fg">{t('importDropFile', { x: label })}</span>
            <span className="text-xs text-muted">{t('importFileHint', { n: MAX_ROWS })}</span>
            <input type="file" accept=".csv,text/csv" className="sr-only" onChange={e => { void choose(e.target.files?.[0]); e.target.value = '' }} />
          </label>
          {fileError && <p role="alert" className="text-sm text-danger">{fileError}</p>}
          <button type="button" onClick={downloadSample} className="text-xs font-medium text-brand hover:underline">{t('importSample')}</button>
        </div>
      )}

      {step === 'map' && (
        <div className="space-y-3">
          <p className="text-sm text-muted">{t('importFileRows', { file: fileName ?? '', n: rows.length })}</p>
          <div className="overflow-hidden rounded-xl border border-border">
            <table className="w-full text-sm">
              <thead className="bg-surface-2/60 text-start text-xs uppercase tracking-wider text-muted">
                <tr>
                  <th className="px-3 py-2 text-start font-semibold">{t('importFileColumn')}</th>
                  <th className="px-3 py-2 text-start font-semibold">{t('importFirstValue')}</th>
                  <th className="px-3 py-2 text-start font-semibold">{t('importFillsField')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {headers.map((header, column) => (
                  <tr key={column}>
                    <td className="px-3 py-2 font-medium text-fg">{header || `#${column + 1}`}</td>
                    <td className="max-w-[12rem] truncate px-3 py-2 text-muted">{rows[0]?.[column] ?? ''}</td>
                    <td className="px-3 py-2">
                      <select
                        value={mapping[column] ?? ''}
                        onChange={e => setMapping(m => m.map((v, j) => (j === column ? e.target.value || null : v)))}
                        aria-label={t('importFieldFor', { column: header || `#${column + 1}` })}
                        className="w-full rounded-lg border border-border bg-canvas px-2 py-1.5 text-sm text-fg"
                      >
                        <option value="">{t('importSkipColumn')}</option>
                        {fields.map(f => (
                          <option key={f.name} value={f.name} disabled={mapped.has(f.name) && mapping[column] !== f.name}>
                            {f.label}{f.required ? ' *' : ''}
                          </option>
                        ))}
                      </select>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {missing.length > 0 && (
            <p role="alert" className="text-sm text-danger">{t('importRequiredUnmatched', { fields: missing.map(f => f.label).join(', ') })}</p>
          )}
          <div className="flex justify-between gap-2">
            <button type="button" onClick={() => setStep('file')} className="rounded-lg border border-border px-3 py-2 text-sm text-fg hover:bg-surface-2">{t('importAnotherFile')}</button>
            <button
              type="button"
              onClick={() => setStep('review')}
              disabled={missing.length > 0}
              className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-on-primary hover:bg-primary-deep disabled:opacity-50"
            >
              {t('next')}
            </button>
          </div>
        </div>
      )}

      {step === 'review' && (
        <div className="space-y-3">
          <p className="text-sm text-fg">
            {allProblems.length === 0
              ? t('importReady', { n: records.length, x: label })
              : t('importProblems', { n: allProblems.length })}
          </p>
          {allProblems.length > 0 && (
            <ul className="max-h-72 divide-y divide-border overflow-auto rounded-xl border border-danger/30 bg-danger/5 text-sm">
              {allProblems.slice(0, SHOWN_PROBLEMS).map((p, i) => (
                <li key={i} className="px-3 py-2 text-fg">
                  {p.line > 0 && <span className="me-2 font-semibold tabular-nums">{t('importLine', { n: p.line })}</span>}
                  {p.field && <span className="me-1 text-muted">{labelOf(p.field)}:</span>}
                  {p.message}
                </li>
              ))}
              {allProblems.length > SHOWN_PROBLEMS && (
                <li className="px-3 py-2 text-muted">{t('nMore', { n: allProblems.length - SHOWN_PROBLEMS })}</li>
              )}
            </ul>
          )}
          <div className="flex justify-between gap-2">
            <button type="button" onClick={() => { setServerProblems([]); setStep('map') }} className="rounded-lg border border-border px-3 py-2 text-sm text-fg hover:bg-surface-2">{t('back')}</button>
            <button
              type="button"
              onClick={() => { void run() }}
              disabled={busy || allProblems.length > 0 || records.length === 0}
              className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-on-primary hover:bg-primary-deep disabled:opacity-50"
            >
              <Upload className="h-4 w-4" />
              {busy ? t('importing') : t('importN', { n: records.length })}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
