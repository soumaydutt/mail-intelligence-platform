import { afterEach, describe, expect, it, vi } from 'vitest'
import { z } from 'zod'
import { createApi, retryAfterSeconds } from './client'

afterEach(() => vi.unstubAllGlobals())

describe('backend transport', () => {
  it('parses Retry-After delta and HTTP dates without unbounded waits', () => {
    expect(retryAfterSeconds('7')).toBe(7)
    expect(retryAfterSeconds('Wed, 21 Oct 2026 16:00:10 GMT', Date.parse('2026-10-21T16:00:00Z'))).toBe(10)
    expect(retryAfterSeconds('999999')).toBe(3600)
    expect(retryAfterSeconds('nonsense')).toBeNull()
  })

  it('sends a key only for opted-in same-origin admin calls and validates JSON', async () => {
    const fetcher = vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ status: 'UP' }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
    vi.stubGlobal('fetch', fetcher)
    const api = createApi('private-key')
    expect(await api.json('/actuator/health', z.object({ status: z.string() }), { auth: false })).toEqual({ status: 'UP' })
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).has('X-Job-API-Key')).toBe(false)
    await api.json('/api/suppressions?limit=1', z.object({ status: z.string() }))
    expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get('X-Job-API-Key')).toBe('private-key')
    expect(fetcher.mock.calls[1]?.[0]).not.toContain('private-key')
  })

  it('normalizes 429 with bounded delay and never echoes raw body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('raw-address@example.test', { status: 429, headers: { 'Retry-After': '3' } })))
    await expect(createApi(null).json('/api/email/test', z.string())).rejects.toMatchObject({ status: 429, retryAfter: 3, message: 'Request limit reached. Try again in 3 seconds.' })
  })

  it('rejects invalid server payload instead of assuming a response shape', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 200 })))
    await expect(createApi(null).json('/actuator/health', z.object({ status: z.string() }))).rejects.toMatchObject({ status: 200 })
  })

  it('honors navigation abort without retrying the request', async () => {
    const controller = new AbortController()
    const pending = Promise.withResolvers<Response>()
    const fetcher = vi.fn((_url: string, init: RequestInit) => {
      init.signal?.addEventListener('abort', () => pending.reject(new DOMException('Aborted', 'AbortError')))
      return pending.promise
    })
    vi.stubGlobal('fetch', fetcher)
    const request = createApi(null).json('/api/email/test', z.string(), { signal: controller.signal })
    controller.abort()
    await expect(request).rejects.toBeInstanceOf(DOMException)
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('accepts only a safe filename from Content-Disposition', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('ordinal,email\r\n', { status: 200, headers: { 'Content-Disposition': 'attachment; filename=../../secret.csv' } })))
    const file = await createApi('private-key').download('/api/imports/123/export')
    expect(file.filename).toBe('import-results.csv')
    expect(await file.blob.text()).toBe('ordinal,email\r\n')
  })
})
