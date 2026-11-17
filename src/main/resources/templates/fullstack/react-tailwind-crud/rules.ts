/**
 * What the entities' rules are written in, as the form runs them (each validate<Entity>). It
 * means what the backend's rules/Expr.java means: numbers are numbers, a date is 'YYYY-MM-DD', a
 * date and time is milliseconds. An empty value (null) makes an operation empty and a comparison
 * unknown, `and`/`or` follow three-valued logic, and a rule that comes out unknown holds — asking
 * for a value is a required field's job, or isEmpty's.
 */
type Value = number | string | boolean | null
type Maybe = boolean | null

/** A rule's condition applies only when it is true. */
export const applies = (when: Maybe): boolean => when === true
/** A rule's check fails only when it is false. */
export const fails = (check: Maybe): boolean => check === false

export function num(v: unknown): number | null {
  if (v == null || v === '') return null
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : null
}

export const text = (v: unknown): string | null => (v == null ? null : String(v))

export function bool(v: unknown): boolean | null {
  if (typeof v === 'boolean') return v
  return v === 'true' ? true : v === 'false' ? false : null
}

export const date = (v: unknown): string | null => (v == null || v === '' ? null : String(v).slice(0, 10))

export function moment(v: unknown): number | null {
  if (v == null || v === '') return null
  const at = new Date(String(v)).getTime()
  return Number.isNaN(at) ? null : at
}

export function time(v: unknown): string | null {
  if (v == null || v === '') return null
  const s = String(v)
  return s.length === 5 ? `${s}:00` : s.slice(0, 8)
}

export function and(a: Maybe, b: Maybe): Maybe {
  if (a === false || b === false) return false
  return a == null || b == null ? null : true
}

export function or(a: Maybe, b: Maybe): Maybe {
  if (a === true || b === true) return true
  return a == null || b == null ? null : false
}

export const not = (a: Maybe): Maybe => (a == null ? null : !a)

const known = (a: Value, b: Value): boolean => a != null && b != null
export const eq = (a: Value, b: Value): Maybe => (known(a, b) ? a === b : null)
export const ne = (a: Value, b: Value): Maybe => (known(a, b) ? a !== b : null)
export const lt = (a: Value, b: Value): Maybe => (known(a, b) ? (a as number | string) < (b as number | string) : null)
export const le = (a: Value, b: Value): Maybe => (known(a, b) ? (a as number | string) <= (b as number | string) : null)
export const gt = (a: Value, b: Value): Maybe => (known(a, b) ? (a as number | string) > (b as number | string) : null)
export const ge = (a: Value, b: Value): Maybe => (known(a, b) ? (a as number | string) >= (b as number | string) : null)

export const add = (a: number | null, b: number | null): number | null => (a == null || b == null ? null : a + b)
export const sub = (a: number | null, b: number | null): number | null => (a == null || b == null ? null : a - b)
export const mul = (a: number | null, b: number | null): number | null => (a == null || b == null ? null : a * b)
/** Division by zero is empty, like a missing value. */
export const div = (a: number | null, b: number | null): number | null => (a == null || b == null || b === 0 ? null : a / b)
export const neg = (a: number | null): number | null => (a == null ? null : -a)
export const concat = (a: string | null, b: string | null): string | null => (a == null || b == null ? null : a + b)

const DAY = 86_400_000
const utc = (d: string): number => {
  const [y, m, day] = d.split('-').map(Number)
  return Date.UTC(y, m - 1, day)
}

export function plusDays(d: string | null, days: number | null): string | null {
  if (d == null || days == null) return null
  return new Date(utc(d) + Math.trunc(days) * DAY).toISOString().slice(0, 10)
}

export const isEmpty = (v: unknown): boolean => v == null || (typeof v === 'string' && v.trim() === '')

export function today(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export const now = (): number => Date.now()

/** Whole days from a to b, two dates (negative when b is earlier). */
export const daysBetweenDates = (a: string | null, b: string | null): number | null =>
  (a == null || b == null ? null : Math.round((utc(b) - utc(a)) / DAY))

/** Whole days from a to b, two dates and times. */
export const daysBetween = (a: number | null, b: number | null): number | null =>
  (a == null || b == null ? null : Math.trunc((b - a) / DAY))

export const length = (s: string | null): number | null => (s == null ? null : s.length)
export const abs = (a: number | null): number | null => (a == null ? null : Math.abs(a))

const pad = (n: number): string => String(n).padStart(2, '0')

/** A computed date and time as a date-time field holds it (local, to the second). */
export function localMoment(at: number | null): string | null {
  if (at == null) return null
  const d = new Date(at)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** A computed instant as an instant field holds it. */
export const isoInstant = (at: number | null): string | null => (at == null ? null : new Date(at).toISOString())

/** A computed value as the form shows it: empty for nothing, a number without float noise. */
export function show(v: unknown): string {
  if (v == null) return ''
  if (typeof v === 'number') return String(Math.round(v * 1e6) / 1e6)
  return String(v)
}

/** Half away from zero, like the backend's HALF_UP. */
export function round(a: number | null, places: number): number | null {
  if (a == null) return null
  const f = 10 ** places
  return (Math.sign(a) * Math.round(Math.abs(a) * f)) / f
}
