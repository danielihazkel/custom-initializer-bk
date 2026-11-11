import { LOCALE } from '@shared/i18n'

const BASE = ((import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '').replace(/\/+$/, '')

/** Where an uploaded file (a file or image field's value) is read from. */
export function attachmentUrl(id: string): string {
  return `${BASE}/api/attachments/${encodeURIComponent(id)}`
}

/** An amount in its currency, written for the app's locale: 1234.5 → "₪1,234.50". */
export function formatMoney(value: number | string, currency: string): string {
  const amount = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(amount)) return String(value)
  return new Intl.NumberFormat(LOCALE, { style: 'currency', currency }).format(amount)
}

/** A date ("2026-01-31") written for the app's locale. Read as local midnight: `new Date` of a
 *  bare date is UTC, which is the day before west of it. */
export function formatDate(value: string): string {
  const at = new Date(`${String(value).slice(0, 10)}T00:00:00`)
  return Number.isNaN(at.getTime()) ? String(value) : at.toLocaleDateString(LOCALE)
}

/** A local date and time ("2026-01-31T08:00:00") written for the app's locale. */
export function formatDateTime(value: string): string {
  const at = new Date(value)
  return Number.isNaN(at.getTime()) ? String(value) : at.toLocaleString(LOCALE)
}

/** A UTC instant ("2026-01-31T08:00:00Z") in the reader's own zone and the app's locale. */
export function formatInstant(value: string): string {
  const at = new Date(value)
  return Number.isNaN(at.getTime()) ? value : at.toLocaleString(LOCALE)
}

/** A UTC instant as the value of an `<input type="datetime-local">`, in the browser's zone. */
export function toLocalInput(value: string | null | undefined): string {
  if (!value) return ''
  const at = new Date(value)
  if (Number.isNaN(at.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}T${pad(at.getHours())}:${pad(at.getMinutes())}`
}

/** What was picked in an `<input type="datetime-local">`, as a UTC instant; null when cleared. */
export function fromLocalInput(value: string): string | null {
  if (!value) return null
  const at = new Date(value)
  return Number.isNaN(at.getTime()) ? null : at.toISOString()
}
