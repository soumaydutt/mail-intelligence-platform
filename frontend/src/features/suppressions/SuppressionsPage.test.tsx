import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useSession } from '../../lib/auth/session'
import { useNotice } from '../../app/notifications'
import { ApiError } from '../../lib/api/client'
import SuppressionsPage from './SuppressionsPage'

vi.mock('../../lib/auth/session', () => ({ useSession: vi.fn() }))
vi.mock('../../app/notifications', () => ({ useNotice: vi.fn() }))

const id = '550e8400-e29b-41d4-a716-446655440000'
const otherId = '660e8400-e29b-41d4-a716-446655440000'
const record = {
  id, email: 'person@example.test', reason: 'MANUAL', effectiveAt: '2026-10-20T12:00:00Z',
  expiresAt: null, active: true, liftedAt: null, liftingReason: null, sourceEventId: null,
}
const api = { json: vi.fn(), empty: vi.fn(), text: vi.fn(), download: vi.fn() }
const notify = vi.fn()

function renderPage(connected = true) {
  vi.mocked(useSession).mockReturnValue({
    connected, api, connect: vi.fn(), disconnect: vi.fn(), trackValidation: vi.fn(),
    trackJob: vi.fn(), trackImport: vi.fn(), validations: [], jobs: [], imports: [],
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={client}><SuppressionsPage /></QueryClientProvider>)
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(useNotice).mockReturnValue(notify)
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.open = true } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.open = false } })
})

describe('SuppressionsPage', () => {
  it('keeps disconnected administrators from querying or mutating data', () => {
    renderPage(false)
    expect(screen.getByText(/Connect with an administrator key/)).toBeInTheDocument()
    expect(api.json).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'Create suppression' })).not.toBeInTheDocument()
  })

  it('loads UUID-keyset pages and displays active, lifted, and expired records', async () => {
    api.json.mockResolvedValueOnce(Array.from({ length: 25 }, (_, index) => ({ ...record, id: index === 24 ? otherId : `${index.toString(16).padStart(8, '0')}-e29b-41d4-a716-446655440000`, email: `person${index}@example.test` })))
      .mockResolvedValueOnce([{ ...record, id: otherId, active: false, liftedAt: '2026-10-20T13:00:00Z', liftingReason: 'OPERATOR_REVIEW' },
        { ...record, id, active: false, expiresAt: '2026-10-20T13:00:00Z', email: 'expired@example.test' }])
    const user = userEvent.setup()
    renderPage()
    expect(await screen.findByText('person0@example.test')).toBeInTheDocument()
    expect(api.json).toHaveBeenCalledWith('/api/suppressions?limit=25', expect.anything(), expect.objectContaining({ signal: expect.anything() }))
    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('expired@example.test')).toBeInTheDocument()
    expect(api.json).toHaveBeenCalledWith(`/api/suppressions?limit=25&after=${otherId}`, expect.anything(), expect.anything())
    expect(screen.getByText('LIFTED')).toBeInTheDocument()
    expect(screen.getByText('INACTIVE')).toBeInTheDocument()
    expect(screen.getByText('OPERATOR_REVIEW')).toBeInTheDocument()
  })

  it('creates a manual suppression in POST body, then refreshes records', async () => {
    api.json.mockResolvedValueOnce([]).mockResolvedValueOnce({ id }).mockResolvedValueOnce([record])
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('No suppression records on this page.')
    await user.type(screen.getByLabelText('Email address'), 'person@example.test')
    await user.type(screen.getByLabelText('Reason code'), 'OPERATOR_BLOCK')
    await user.click(screen.getByRole('button', { name: 'Create suppression' }))
    await waitFor(() => expect(api.json).toHaveBeenCalledWith('/api/suppressions', expect.anything(), {
      method: 'POST', body: { email: 'person@example.test', reasonCode: 'OPERATOR_BLOCK' },
    }))
    expect(await screen.findByText('person@example.test')).toBeInTheDocument()
    expect(api.json).toHaveBeenCalledTimes(3)
  })

  it('requires confirmation for lifting and sends reason in DELETE body', async () => {
    api.json.mockResolvedValueOnce([record]).mockResolvedValueOnce([{ ...record, active: false, liftedAt: '2026-10-21T12:00:00Z', liftingReason: 'OPERATOR_REVIEW' }])
    api.empty.mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('person@example.test')
    await user.click(screen.getByRole('button', { name: 'Lift' }))
    expect(api.empty).not.toHaveBeenCalled()
    await user.type(within(screen.getByRole('dialog')).getByLabelText('Lifting reason code'), 'OPERATOR_REVIEW')
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(api.empty).toHaveBeenCalledWith(`/api/suppressions/${id}`,
      { method: 'DELETE', body: { reasonCode: 'OPERATOR_REVIEW' } }))
    expect(await screen.findByText('LIFTED')).toBeInTheDocument()
    expect(notify).toHaveBeenCalledWith('Suppression lifted.')
  })

  it('shows a lift conflict without removing the record or retrying DELETE', async () => {
    api.json.mockResolvedValue([record])
    api.empty.mockRejectedValue(new ApiError('The record changed or this action conflicts with its current state. Refresh before retrying.', 409))
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('person@example.test')
    await user.click(screen.getByRole('button', { name: 'Lift' }))
    await user.type(within(screen.getByRole('dialog')).getByLabelText('Lifting reason code'), 'OPERATOR_REVIEW')
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm' }))
    expect(await within(screen.getByRole('dialog')).findByText(/conflicts with its current state/)).toBeInTheDocument()
    expect(screen.getByText('ACTIVE')).toBeInTheDocument()
    expect(api.empty).toHaveBeenCalledTimes(1)
  })

  it.each([401, 429, 503])('shows safe %i errors without retrying failed create', async status => {
    api.json.mockResolvedValueOnce([]).mockRejectedValueOnce(new ApiError(
      status === 401 ? 'Administrator key is missing or incorrect. Reconnect with a valid key.'
        : status === 429 ? 'Request limit reached. Try again in 9 seconds.'
          : 'Shared admission or the backend is unavailable. Try again later.', status,
    ))
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('No suppression records on this page.')
    await user.type(screen.getByLabelText('Email address'), 'person@example.test')
    await user.type(screen.getByLabelText('Reason code'), 'OPERATOR_BLOCK')
    await user.click(screen.getByRole('button', { name: 'Create suppression' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      status === 401 ? /Reconnect with a valid key/ : status === 429 ? /9 seconds/ : /admission or the backend is unavailable/,
    )
    expect(api.json).toHaveBeenCalledTimes(2)
  })
})
