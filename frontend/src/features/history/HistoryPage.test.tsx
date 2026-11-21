import { beforeEach, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useSession } from '../../lib/auth/session'
import HistoryPage from './HistoryPage'

vi.mock('../../lib/auth/session', () => ({ useSession: vi.fn() }))

const json = vi.fn()
const api = { json, empty: vi.fn(), text: vi.fn(), download: vi.fn() }
const row = { id: 50, email: 'record@example.invalid', domain: null, formatValid: false,
  mxRecordFound: null, smtpVerified: null, status: 'INVALID', message: 'Invalid email format', createdAt: '2026-01-01T00:00:00Z' }

function mount(connected: boolean) {
  vi.mocked(useSession).mockReturnValue({ api, connected, validations: [], imports: [], jobs: [],
    connect: vi.fn(), disconnect: vi.fn(), trackValidation: vi.fn(), trackImport: vi.fn(), trackJob: vi.fn() })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><HistoryPage /></QueryClientProvider>)
}

beforeEach(() => json.mockReset())

it('does not fetch administrator history before authentication', () => {
  mount(false)
  expect(screen.getByText('Administrator access required')).toBeInTheDocument()
  expect(json).not.toHaveBeenCalled()
})

it('shows real history fields with keyset pagination and explicit empty result', async () => {
  json.mockResolvedValueOnce(Array.from({ length: 50 }, (_, index) => ({ ...row, id: 100 - index })))
    .mockResolvedValueOnce([])
  mount(true)
  const table = await screen.findByRole('table')
  expect(within(table).getAllByRole('row', { name: /record@example.invalid/ })[0]).toHaveTextContent('Invalid email format')
  expect(within(table).getAllByRole('row', { name: /record@example.invalid/ })[0]).toHaveTextContent('Not checked')
  expect(json).toHaveBeenCalledWith('/api/email/history?limit=50', expect.anything(), expect.objectContaining({ signal: expect.any(AbortSignal) }))
  await userEvent.setup().click(screen.getByRole('button', { name: 'Next page' }))
  expect(await screen.findByText('No history records on this page.')).toBeInTheDocument()
  await waitFor(() => expect(json).toHaveBeenCalledWith('/api/email/history?limit=50&after=51', expect.anything(), expect.objectContaining({ signal: expect.any(AbortSignal) })))
})
