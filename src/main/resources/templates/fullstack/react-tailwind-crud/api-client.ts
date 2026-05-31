const BASE = ''

/**
 * Error thrown for non-2xx responses. A 400 from the API carries a `{ field: message }`
 * map (the controller's bean-validation handler) which is exposed as `fieldErrors` so
 * forms can show the message next to the offending input.
 */
export class ApiError extends Error {
  readonly status: number
  readonly fieldErrors?: Record<string, string>

  constructor(status: number, message: string, fieldErrors?: Record<string, string>) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(BASE + path, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    let parsed: unknown = null
    try { parsed = text ? JSON.parse(text) : null } catch { /* not JSON */ }
    const isObject = parsed != null && typeof parsed === 'object' && !Array.isArray(parsed)
    // A 400 with an object body is the validation field-error map.
    if (res.status === 400 && isObject) {
      throw new ApiError(res.status, 'Validation failed', parsed as Record<string, string>)
    }
    const message = (isObject && ((parsed as Record<string, string>).error
      || (parsed as Record<string, string>).message)) || text
    throw new ApiError(res.status, `HTTP ${res.status} on ${method} ${path}: ${message}`)
  }
  if (res.status === 204) return undefined as unknown as T
  return res.json() as Promise<T>
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body: unknown) => request<T>('PUT', path, body),
  del: (path: string) => request<void>('DELETE', path),
}
