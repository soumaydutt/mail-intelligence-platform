import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useSession } from '../../lib/auth/session'
import { useNotice } from '../../app/notifications'
import { ApiError } from '../../lib/api/client'
import EvidencePage from './EvidencePage'

vi.mock('../../lib/auth/session', () => ({ useSession: vi.fn() }))
vi.mock('../../app/notifications', () => ({ useNotice: vi.fn() }))

const id = '550e8400-e29b-41d4-a716-446655440000'
const snapshot = {
  available: true, eventId: id, eventType: 'HARD_BOUNCE', interpretation: 'UNCERTAIN_BOUNCE',
  occurredAt: '2026-10-20T12:00:00Z', sourceType: 'ADMIN_JSON', sourceIdentifier: 'trusted-provider',
  freshness: 'FRESH', suppressed: false, suppressionReason: null,
  observedAt: '2026-10-20T12:01:00Z', limitation: 'Historical observations do not prove current deliverability.',
}
const api = { json: vi.fn(), empty: vi.fn(), text: vi.fn(), download: vi.fn() }
const notify = vi.fn()

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={client}><EvidencePage /></QueryClientProvider>)
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(useSession).mockReturnValue({
    connected: true, api, connect: vi.fn(), disconnect: vi.fn(), trackValidation: vi.fn(),
    trackJob: vi.fn(), trackImport: vi.fn(), validations: [], jobs: [], imports: [],
  })
  vi.mocked(useNotice).mockReturnValue(notify)
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.open = true } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.open = false } })
})

describe('EvidencePage', () => {
  it('only looks up submitted email in POST body and distinguishes uncertain bounce from suppression', async () => {
    api.json.mockResolvedValueOnce(snapshot)
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByLabelText('Email address'), 'person@example.test')
    expect(api.json).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Look up evidence' }))
    expect(await screen.findByText('Snapshot for person@example.test')).toBeInTheDocument()
    expect(api.json).toHaveBeenCalledWith('/api/delivery-evidence/lookup', expect.anything(),
      expect.objectContaining({ method: 'POST', body: { email: 'person@example.test' }, signal: expect.anything() }))
    expect(screen.getByText(/does not automatically suppress this address/)).toBeInTheDocument()
    expect(screen.getByText('Suppression: None reported')).toBeInTheDocument()
    expect(screen.getByText(/Historical observations do not prove current deliverability/)).toBeInTheDocument()
  })

  it('shows NONE and UNAVAILABLE without treating either as current deliverability', async () => {
    api.json.mockResolvedValueOnce({ ...snapshot, available: false, eventId: null, freshness: 'NONE', interpretation: null })
      .mockResolvedValueOnce({ ...snapshot, available: false, eventId: null, freshness: 'UNAVAILABLE', suppressed: null })
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByLabelText('Email address'), 'person@example.test')
    await user.click(screen.getByRole('button', { name: 'Look up evidence' }))
    expect(await screen.findByText('No delivery event observed for this address.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Look up evidence' }))
    expect(await screen.findByText(/Absence of data cannot be inferred/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Revoke displayed event' })).not.toBeInTheDocument()
  })

  it('labels stale observations without implying current status', async () => {
    api.json.mockResolvedValueOnce({ ...snapshot, freshness: 'STALE' })
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByLabelText('Email address'), 'person@example.test')
    await user.click(screen.getByRole('button', { name: 'Look up evidence' }))
    expect(await screen.findByText('STALE')).toBeInTheDocument()
    expect(screen.getByText(/does not establish current status/)).toBeInTheDocument()
  })

  it('ingests a trusted event once with UTC timestamp, then refreshes matching evidence', async () => {
    api.json.mockResolvedValueOnce(snapshot).mockResolvedValueOnce({ id, reused: true }).mockResolvedValueOnce(snapshot)
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByLabelText('Email address'), 'person@example.test')
    await user.click(screen.getByRole('button', { name: 'Look up evidence' }))
    await screen.findByText('Snapshot for person@example.test')
    await user.type(screen.getByLabelText('Event email'), 'person@example.test')
    await user.type(screen.getByLabelText('Trusted external event ID'), 'provider-77')
    fireEvent.change(screen.getByLabelText(/Occurred at/), { target: { value: '2026-10-20T12:00' } })
    await user.type(screen.getByLabelText(/Enhanced status code/), '5.1.1')
    await user.click(screen.getByRole('button', { name: 'Ingest event' }))
    await waitFor(() => expect(api.json).toHaveBeenCalledWith('/api/delivery-events', expect.anything(), {
      method: 'POST', body: {
        email: 'person@example.test', eventType: 'HARD_BOUNCE', externalEventId: 'provider-77',
        occurredAt: expect.stringMatching(/Z$/), enhancedStatusCode: '5.1.1',
      },
    }))
    expect(await screen.findByText(/Existing event reused\./)).toBeInTheDocument()
    await waitFor(() => expect(api.json).toHaveBeenCalledTimes(3))
    expect(notify).toHaveBeenCalledWith(expect.stringContaining('no new event created'))
  })

  it('confirms revocation and safely reports a conflict without hiding the event', async () => {
    api.json.mockResolvedValue(snapshot)
    api.empty.mockRejectedValue(new ApiError('The record changed or this action conflicts with its current state. Refresh before retrying.', 409))
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByLabelText('Email address'), 'person@example.test')
    await user.click(screen.getByRole('button', { name: 'Look up evidence' }))
    await screen.findByRole('button', { name: 'Revoke displayed event' })
    await user.click(screen.getByRole('button', { name: 'Revoke displayed event' }))
    expect(api.empty).not.toHaveBeenCalled()
    await user.type(screen.getByLabelText('Reason code'), 'BAD_EVENT')
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(api.empty).toHaveBeenCalledWith(`/api/delivery-events/${id}/revoke`,
      { method: 'POST', body: { reasonCode: 'BAD_EVENT' } }))
    expect(await within(screen.getByRole('dialog')).findByText(/conflicts with its current state/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Revoke displayed event' })).toBeInTheDocument()
    expect(api.empty).toHaveBeenCalledTimes(1)
  })
  it('refreshes evidence after a successful confirmed revocation', async () => {
    api.json.mockResolvedValueOnce(snapshot).mockResolvedValueOnce({
      ...snapshot, eventId: null, eventType: null, interpretation: null, freshness: 'NONE',
      suppressed: false, limitation: 'No delivery event observed.',
    })
    api.empty.mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByLabelText('Email address'), 'person@example.test')
    await user.click(screen.getByRole('button', { name: 'Look up evidence' }))
    await user.click(await screen.findByRole('button', { name: 'Revoke displayed event' }))
    await user.type(screen.getByLabelText('Reason code'), 'BAD_EVENT')
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm' }))
    expect(await screen.findByText('No delivery event observed for this address.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Revoke displayed event' })).not.toBeInTheDocument()
    expect(api.empty).toHaveBeenCalledTimes(1)
  })

  it.each([401, 429, 503])('shows safe %i errors without retrying failed ingest', async status => {
    api.json.mockRejectedValueOnce(new ApiError(
      status === 401 ? 'Administrator key is missing or incorrect. Reconnect with a valid key.'
        : status === 429 ? 'Request limit reached. Try again in 9 seconds.'
          : 'Shared admission or the backend is unavailable. Try again later.', status,
    ))
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByLabelText('Event email'), 'person@example.test')
    await user.type(screen.getByLabelText('Trusted external event ID'), 'provider-77')
    fireEvent.change(screen.getByLabelText(/Occurred at/), { target: { value: '2026-10-20T12:00' } })
    await user.click(screen.getByRole('button', { name: 'Ingest event' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      status === 401 ? /Reconnect with a valid key/ : status === 429 ? /9 seconds/ : /admission or the backend is unavailable/,
    )
    expect(api.json).toHaveBeenCalledTimes(1)
  })
})
