import { z } from 'zod'

export class ApiError extends Error {
  constructor(message: string, readonly status: number, readonly retryAfter: number | null = null) {
    super(message)
    this.name = 'ApiError'
  }
}

export function retryAfterSeconds(header: string | null, now = Date.now()): number | null {
  if (!header) return null
  const seconds = /^\d+$/.test(header.trim()) ? Number(header.trim()) : Math.ceil((Date.parse(header) - now) / 1000)
  return Number.isFinite(seconds) ? Math.max(0, Math.min(3600, seconds)) : null
}

export function errorMessage(status: number, retry: number | null): string {
  switch (status) {
    case 400: return 'The request was rejected. Check its format and try again.'
    case 401: return 'Administrator key is missing or incorrect. Reconnect with a valid key.'
    case 403: return 'You do not have permission to perform this action.'
    case 404: return 'This record was not found. Check its identifier.'
    case 409: return 'The record changed or this action conflicts with its current state. Refresh before retrying.'
    case 413: return 'The upload is too large. Choose a CSV under 16 MiB.'
    case 429: return `Request limit reached.${retry === null ? ' Try again later.' : ` Try again in ${retry} seconds.`}`
    case 503: return 'Shared admission or the backend is unavailable. Try again later.'
    default: return status >= 500 ? 'The backend could not complete the request.' : 'The request could not be completed.'
  }
}

export type Options = { method?: 'GET' | 'POST' | 'DELETE'; body?: object | FormData; signal?: AbortSignal; timeoutMs?: number; auth?: boolean }
export interface Api {
  json<T>(path: string, schema: z.ZodType<T>, options?: Options): Promise<T>
  text(path: string, options?: Options): Promise<string>
  empty(path: string, options?: Options): Promise<void>
  download(path: string, options?: Options): Promise<{ blob: Blob; filename: string }>
}


export function createApi(key: string | null): Api {
  async function send<T>(path: string, options: Options, consume: (response: Response) => Promise<T>): Promise<T> {
    const prefix = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '') || ''
    if (!path.startsWith('/') || path.startsWith('//') || (prefix && (prefix.startsWith('//') || new URL(prefix, window.location.origin).origin !== window.location.origin))) {
      throw new ApiError('API base URL must be same-origin.', 0)
    }
    const controller = new AbortController()
    const abort = () => controller.abort()
    options.signal?.addEventListener('abort', abort, { once: true })
    if (options.signal?.aborted) abort()
    const timer = window.setTimeout(abort, options.timeoutMs ?? 15000)
    try {
      const headers = new Headers()
      if (options.auth !== false && key) headers.set('X-Job-API-Key', key)
      if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
      const response = await fetch(prefix + path, {
        method: options.method || 'GET', headers, body: options.body instanceof FormData ? options.body : options.body ? JSON.stringify(options.body) : undefined,
        signal: controller.signal, credentials: 'omit', cache: 'no-store', redirect: 'error',
      })
      if (!response.ok) {
        const retry = retryAfterSeconds(response.headers.get('Retry-After'))
        throw new ApiError(errorMessage(response.status, retry), response.status, retry)
      }
      return await consume(response)
    } catch (error) {
      if (error instanceof ApiError || options.signal?.aborted) throw error
      if (controller.signal.aborted) throw new ApiError('Request timed out. Check backend status and retry.', 0)
      throw new ApiError('Cannot reach the backend. Check the connection and retry.', 0)
    } finally {
      window.clearTimeout(timer)
      options.signal?.removeEventListener('abort', abort)
    }
  }

  return {
    json<T>(path: string, schema: z.ZodType<T>, options: Options = {}): Promise<T> {
      return send(path, options, async response => {
        try {
          const parsed = schema.safeParse(await response.json())
          if (!parsed.success) throw new Error('Unexpected response')
          return parsed.data
        } catch {
          throw new ApiError('The backend returned an unexpected response.', response.status)
        }
      })
    },
    text(path: string, options: Options = {}): Promise<string> { return send(path, options, response => response.text()) },
    empty(path: string, options: Options = {}): Promise<void> { return send(path, options, async () => undefined) },
    download(path: string, options: Options = {}): Promise<{ blob: Blob; filename: string }> {
      return send(path, options, async response => ({ blob: await response.blob(), filename: 'import-results.csv' }))
    },
  }
}

