import { LOCALE } from '@shared/i18n'

/** An amount in its currency, written for the app's locale: 1234.5 → "₪1,234.50". */
export function formatMoney(value: number | string, currency: string): string {
  const amount = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(amount)) return String(value)
  return new Intl.NumberFormat(LOCALE, { style: 'currency', currency }).format(amount)
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
