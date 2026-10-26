import { t } from '../i18n'

/** A field a CSV import can fill, as the entity's `IMPORT_FIELDS` describe it. */
export interface ImportField {
  /** The property the API takes (a relation: its `<field>Id`). */
  name: string
  label: string
  kind: 'string' | 'integer' | 'number' | 'boolean' | 'date' | 'datetime' | 'enum' | 'relation'
  required: boolean
  /** Other headers that name it — a relation's field name. */
  also?: string[]
  /** enum: its constants and their labels (a cell may hold either). */
  options?: { value: string; label: string }[]
  /** relation: whether the key it points at is a number. */
  numeric?: boolean
}

/** A header, reduced to letters and digits in lower case: "Due on", "due_on" and "dueOn" all match. */
export const normalizeHeader = (text: string) => text.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '')

/** The delimiter a file uses: the one of `,` `;` and tab that its first line holds most of, outside quotes. */
function delimiterOf(text: string): string {
  const counts: Record<string, number> = { ',': 0, ';': 0, '\t': 0 }
  let quoted = false
  for (const c of text) {
    if (c === '"') quoted = !quoted
    else if (!quoted && c === '\n') break
    else if (!quoted && c in counts) counts[c]++
  }
  const [best, n] = Object.entries(counts).sort((a, b) => b[1] - a[1])[0]
  return n > 0 ? best : ','
}

/**
 * The cells of a CSV file (RFC 4180): quoted cells may hold the delimiter, newlines and doubled
 * quotes. A byte-order mark is dropped, the delimiter is detected, and blank lines are skipped.
 */
export function parseCsv(input: string): string[][] {
  const text = input.replace(/^\uFEFF/, '')
  const delimiter = delimiterOf(text)
  const rows: string[][] = []
  let row: string[] = []
  let cell = ''
  let quoted = false
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') { cell += '"'; i++ }
      else if (c === '"') quoted = false
      else cell += c
    } else if (c === '"' && cell === '') {
      quoted = true
    } else if (c === delimiter) {
      row.push(cell)
      cell = ''
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++
      row.push(cell)
      if (row.some(v => v.trim() !== '')) rows.push(row)
      row = []
      cell = ''
    } else {
      cell += c
    }
  }
  row.push(cell)
  if (row.some(v => v.trim() !== '')) rows.push(row)
  return rows
}

/** Which field each header fills: its name, its label or another name it answers to; null for none. */
export function guessMapping(headers: string[], fields: ImportField[]): (string | null)[] {
  const taken = new Set<string>()
  return headers.map(header => {
    const key = normalizeHeader(header)
    const field = fields.find(f => !taken.has(f.name)
      && [f.name, f.label, ...(f.also ?? [])].some(name => normalizeHeader(name) === key))
    if (!field) return null
    taken.add(field.name)
    return field.name
  })
}

const TRUE_WORDS = ['true', 'yes', 'y', '1', 'כן']
const FALSE_WORDS = ['false', 'no', 'n', '0', 'לא']

/** A cell read as a field's value — or why it cannot be. An empty cell is no value (null). */
export function coerce(field: ImportField, raw: string): { value: unknown } | { error: string } {
  const text = raw.trim()
  if (text === '') return { value: null }
  switch (field.kind) {
    case 'integer': {
      const n = Number(text)
      return Number.isInteger(n) ? { value: n } : { error: t('importNotWhole') }
    }
    case 'number': {
      const n = Number(text)
      return Number.isFinite(n) ? { value: n } : { error: t('importNotNumber') }
    }
    case 'boolean': {
      const word = text.toLowerCase()
      if (TRUE_WORDS.includes(word) || word === t('trueLabel').toLowerCase()) return { value: true }
      if (FALSE_WORDS.includes(word) || word === t('falseLabel').toLowerCase()) return { value: false }
      return { error: t('importNotBoolean') }
    }
    case 'date':
      return /^\d{4}-\d{2}-\d{2}$/.test(text) ? { value: text } : { error: t('importNotDate') }
    case 'datetime': {
      const m = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})(:\d{2})?/.exec(text)
      return m ? { value: `${m[1]}T${m[2]}${m[3] ?? ':00'}` } : { error: t('importNotDateTime') }
    }
    case 'enum': {
      const option = field.options?.find(o => o.value.toLowerCase() === text.toLowerCase() || o.label.toLowerCase() === text.toLowerCase())
      return option ? { value: option.value } : { error: t('importNotOption') }
    }
    case 'relation': {
      if (!field.numeric) return { value: text }
      const n = Number(text)
      return Number.isInteger(n) ? { value: n } : { error: t('importNotWhole') }
    }
    default:
      return { value: text }
  }
}

/** A file with just the header row — what the import expects, to fill in. */
export function sampleCsv(fields: ImportField[]): string {
  const cell = (s: string) => (/[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s)
  return '\uFEFF' + fields.map(f => cell(f.name)).join(',') + '\n'
}
