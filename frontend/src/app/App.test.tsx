import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { SessionProvider } from '../lib/auth/session'
import App from './App'

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={client}><SessionProvider><MemoryRouter><App /></MemoryRouter></SessionProvider></QueryClientProvider>)
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async (path: string) => new Response(JSON.stringify(path.includes('/suppressions?') ? [] : { status: 'UP' }), { status: 200 })))
  HTMLDialogElement.prototype.showModal = function () { this.open = true }
  HTMLDialogElement.prototype.close = function () { this.open = false }
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  localStorage.clear()
  sessionStorage.clear()
  delete document.documentElement.dataset.theme
})

describe('theme preference without a credential store', () => {
  it('loads and persists normal theme preferences without storing the administrator key', async () => {
    localStorage.setItem('mailguard-theme', 'dark')
    const user = userEvent.setup()
    mount()
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    await user.click(screen.getByRole('button', { name: 'Switch to light theme' }))
    expect(localStorage.getItem('mailguard-theme')).toBe('light')
    await user.click(screen.getByRole('button', { name: /^Connect$/ }))
    const dialog = screen.getByRole('dialog')
    await user.type(within(dialog).getByLabelText('Administrative API key'), 'temporary-key')
    await user.click(within(dialog).getByRole('button', { name: /^Connect$/ }))
    await waitFor(() => expect(screen.getByRole('button', { name: /^Disconnect$/ })).toBeInTheDocument())
    expect([...Array(localStorage.length)].map((_, index) => localStorage.key(index))).toEqual(['mailguard-theme'])
    expect(localStorage.getItem('mailguard-theme')).toBe('light')
    expect(sessionStorage.length).toBe(0)
  })

  it('falls back to light and switches in memory when read and write throw SecurityError', async () => {
    const blocked = {
      getItem: vi.fn(() => { throw new DOMException('Blocked', 'SecurityError') }),
      setItem: vi.fn(() => { throw new DOMException('Blocked', 'SecurityError') }),
    }
    vi.stubGlobal('localStorage', blocked)
    const user = userEvent.setup()
    mount()
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
    await user.click(screen.getByRole('button', { name: 'Switch to dark theme' }))
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    expect(blocked.getItem).toHaveBeenCalledWith('mailguard-theme')
    expect(blocked.setItem).toHaveBeenCalledWith('mailguard-theme', 'dark')
    expect(screen.getByRole('heading', { name: 'Overview' })).toBeInTheDocument()
  })

  it('keeps a saved dark theme and still switches after writes throw', async () => {
    vi.stubGlobal('localStorage', {
      getItem: vi.fn(() => 'dark'),
      setItem: vi.fn(() => { throw new DOMException('Blocked', 'SecurityError') }),
    })
    const user = userEvent.setup()
    mount()
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    await user.click(screen.getByRole('button', { name: 'Switch to light theme' }))
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
  })

  it('renders and switches themes when storage access is unavailable', async () => {
    vi.stubGlobal('localStorage', undefined)
    const user = userEvent.setup()
    mount()
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
    await user.click(screen.getByRole('button', { name: 'Switch to dark theme' }))
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
  })
})
