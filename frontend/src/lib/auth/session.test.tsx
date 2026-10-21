import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SessionProvider, useSession } from './session'
import { validationSchema } from '../api/contracts'

function setup() {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={cache}><SessionProvider>{children}</SessionProvider></QueryClientProvider>
  return { cache, ...renderHook(useSession, { wrapper }) }
}
const id = (index: number) => `f21f5da5-7898-43e7-95c5-${String(index).padStart(12, '0')}`

afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear() })

describe('single-principal developer session', () => {
  it('verifies key through bounded admin read, never persists it, and wipes query data on disconnect', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response('[]', { status: 200 }))
    vi.stubGlobal('fetch', fetcher)
    const { result, cache } = setup()
    cache.setQueryData(['private-results'], { email: 'sensitive@fixture.test' })
    await act(async () => { await result.current.connect('temporary-key') })
    expect(result.current.connected).toBe(true)
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('X-Job-API-Key')).toBe('temporary-key')
    expect(cache.getQueryData(['private-results'])).toBeUndefined()
    expect(JSON.stringify(localStorage)).not.toContain('temporary-key')
    expect(JSON.stringify(sessionStorage)).not.toContain('temporary-key')
    cache.setQueryData(['private-results'], { email: 'sensitive@fixture.test' })
    act(() => result.current.disconnect())
    expect(result.current.connected).toBe(false)
    expect(cache.getQueryData(['private-results'])).toBeUndefined()
  })

  it('rejects wrong key without retaining a connected session', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('private', { status: 401 })))
    const { result } = setup()
    await expect(act(async () => result.current.connect('wrong-key'))).rejects.toMatchObject({ status: 401 })
    expect(result.current.connected).toBe(false)
  })

  it('does not connect after the credential check is cancelled while the response arrives', async () => {
    const pending = Promise.withResolvers<Response>()
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => pending.promise))
    const { result } = setup()
    const controller = new AbortController()
    let connection!: Promise<void>
    act(() => { connection = result.current.connect('temporary-key', controller.signal) })
    controller.abort()
    pending.resolve(new Response('[]', { status: 200 }))
    await act(async () => { await connection })
    expect(result.current.connected).toBe(false)
    expect(JSON.stringify(localStorage)).not.toContain('temporary-key')
  })
  it.each([1, 10, 25, 50, 51])('bounds and orders %i recent job and import IDs', count => {
    const { result } = setup()
    act(() => {
      for (let index = 1; index <= count; index++) {
        result.current.trackJob(id(index))
        result.current.trackImport(id(index))
      }
    })
    const newest = Array.from({ length: Math.min(count, 50) }, (_, index) => id(count - index))
    expect(result.current.jobs).toEqual(newest)
    expect(result.current.imports).toEqual(newest)
  })

  it('moves a duplicate ID to the front without increasing capacity', () => {
    const { result } = setup()
    act(() => { for (let index = 1; index <= 51; index++) result.current.trackJob(id(index)) })
    expect(result.current.jobs).not.toContain(id(1))
    act(() => result.current.trackJob(id(2)))
    expect(result.current.jobs).toHaveLength(50)
    expect(result.current.jobs[0]).toBe(id(2))
    expect(result.current.jobs.filter(value => value === id(2))).toHaveLength(1)
    expect(result.current.jobs.at(-1)).toBe(id(3))
    act(() => result.current.trackJob(id(52)))
    expect(result.current.jobs).not.toContain(id(3))
  })

  it('bounds imports independently of jobs and evicts the oldest import', () => {
    const { result } = setup()
    act(() => {
      for (let index = 1; index <= 25; index++) result.current.trackJob(id(index))
      for (let index = 1; index <= 51; index++) result.current.trackImport(id(index))
    })
    expect(result.current.jobs).toHaveLength(25)
    expect(result.current.imports).toEqual(Array.from({ length: 50 }, (_, index) => id(51 - index)))
    act(() => result.current.trackImport(id(2)))
    expect(result.current.imports[0]).toBe(id(2))
    expect(result.current.imports.filter(value => value === id(2))).toHaveLength(1)
    expect(result.current.imports.at(-1)).toBe(id(3))
    act(() => result.current.trackImport(id(52)))
    expect(result.current.imports).not.toContain(id(3))
    expect(result.current.jobs[0]).toBe(id(25))
  })

  it('clears address data and both ID collections on disconnect and remount', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('[]', { status: 200 })))
    const session = setup()
    await act(async () => session.result.current.connect('temporary-key'))
    act(() => {
      session.result.current.trackJob(id(1))
      session.result.current.trackImport(id(2))
      for (let index = 1; index <= 11; index++) session.result.current.trackValidation(validationSchema.parse({
        email: `synthetic${index}@fixture.test`, domain: 'fixture.test', formatValid: true,
        mxRecordFound: null, smtpVerified: null, status: 'UNVERIFIED', message: 'Not observed',
      }))
    })
    expect(session.result.current.validations).toHaveLength(10)
    expect(session.result.current.validations[0]?.email).toBe('synthetic11@fixture.test')
    expect(session.result.current.validations.at(-1)?.email).toBe('synthetic2@fixture.test')
    session.cache.setQueryData(['address'], 'synthetic@fixture.test')
    act(() => session.result.current.disconnect())
    expect(session.result.current.connected).toBe(false)
    expect(session.result.current.jobs).toEqual([])
    expect(session.result.current.imports).toEqual([])
    expect(session.result.current.validations).toEqual([])
    expect(session.cache.getQueryData(['address'])).toBeUndefined()
    session.unmount()
    const reopened = setup()
    expect(reopened.result.current.jobs).toEqual([])
    expect(reopened.result.current.imports).toEqual([])
    expect(reopened.result.current.validations).toEqual([])
    expect(reopened.result.current.connected).toBe(false)
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })
  it('does not restore nonempty session collections across a reload', () => {
    const session = setup()
    act(() => {
      session.result.current.trackJob(id(1))
      session.result.current.trackImport(id(2))
    })
    expect(session.result.current.jobs).toEqual([id(1)])
    expect(session.result.current.imports).toEqual([id(2)])
    session.unmount()
    const reopened = setup()
    expect(reopened.result.current.jobs).toEqual([])
    expect(reopened.result.current.imports).toEqual([])
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

})
