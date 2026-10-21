import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../lib/api/client'
import type { Validation } from '../../lib/api/contracts'
import ValidationPage from './ValidationPage'

const { json, trackValidation, session } = vi.hoisted(() => {
  const json = vi.fn()
  const trackValidation = vi.fn()
  return { json, trackValidation, session: { api: { json }, connected: false, trackValidation } }
})
vi.mock('../../lib/auth/session', () => ({ useSession: () => session }))

const unverified: Validation = {
  email: 'alice@example.com', domain: 'example.com', formatValid: true,
  mxRecordFound: null, smtpVerified: null, status: 'UNVERIFIED',
  message: 'DNS lookup unavailable; mailbox not verified.',
}

function enterAndSubmit(email: string) {
  fireEvent.change(screen.getByRole('textbox', { name: 'Email address' }), { target: { value: email } })
  fireEvent.click(screen.getByRole('button', { name: 'Validate email' }))
}

beforeEach(() => {
  json.mockReset()
  trackValidation.mockReset()
  session.connected = false
})

describe('email validation', () => {
  it('rejects malformed and oversized email without contacting backend', async () => {
    render(<ValidationPage />)
    enterAndSubmit('not-an-address')
    expect(await screen.findByRole('alert')).toHaveTextContent('Enter a valid email address.')
    expect(json).not.toHaveBeenCalled()
    enterAndSubmit(`${'a'.repeat(250)}@example.com`)
    expect(json).not.toHaveBeenCalled()
  })

  it('posts public validation and displays unknown checks without treating MX as mailbox proof', async () => {
    json.mockResolvedValue(unverified)
    render(<ValidationPage />)
    enterAndSubmit('  alice@example.com  ')
    expect(await screen.findByText('DNS lookup unavailable; mailbox not verified.')).toBeInTheDocument()
    expect(json).toHaveBeenCalledWith('/api/email/validate', expect.anything(), expect.objectContaining({
      method: 'POST', body: { email: 'alice@example.com' }, auth: false, signal: expect.any(AbortSignal),
    }))
    expect(screen.getByText('UNVERIFIED means mailbox ownership and deliverability were not established. MX alone is not proof.')).toBeInTheDocument()
    expect(screen.getAllByText('Unknown / not checked')).toHaveLength(2)
    expect(trackValidation).toHaveBeenCalledOnce()
  })

  it('displays invalid backend result and historical evidence only when returned', async () => {
    session.connected = true
    json.mockResolvedValueOnce({ ...unverified, domain: null, formatValid: false, mxRecordFound: null, status: 'INVALID', message: 'Invalid email format' })
      .mockResolvedValueOnce({ ...unverified, historicalEvidence: {
        available: true, eventId: '3a452eee-5c19-42ae-a16e-3e8f25c337d8', eventType: 'HARD_BOUNCE',
        interpretation: 'Hard bounce recorded', occurredAt: '2026-10-01T12:00:00Z', sourceType: 'DELIVERY_EVENT',
        sourceIdentifier: null, freshness: 'STALE', suppressed: true, suppressionReason: 'HARD_BOUNCE',
        observedAt: '2026-10-01T12:00:00Z', limitation: 'Historical evidence only.',
      } })
    render(<ValidationPage />)
    enterAndSubmit('alice@example.com')
    expect(await screen.findByText('INVALID means the backend rejected the address format.')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Historical delivery evidence' })).not.toBeInTheDocument()
    enterAndSubmit('alice@example.com')
    expect(await screen.findByRole('region', { name: 'Historical delivery evidence' })).toHaveTextContent('STALE')
    expect(screen.getByRole('region', { name: 'Historical delivery evidence' })).toHaveTextContent('Historical evidence only.')
    expect(json).toHaveBeenLastCalledWith('/api/email/validate', expect.anything(), expect.objectContaining({ auth: true }))
  })

  it('cancels stale results on another submission', async () => {
    const first = Promise.withResolvers<Validation>()
    const second = Promise.withResolvers<Validation>()
    json.mockImplementationOnce(() => first.promise).mockImplementationOnce(() => second.promise)
    const view = render(<ValidationPage />)
    enterAndSubmit('first@example.com')
    await waitFor(() => expect(json).toHaveBeenCalledTimes(1))
    const firstSignal = json.mock.calls[0]?.[2].signal as AbortSignal
    fireEvent.change(screen.getByRole('textbox', { name: 'Email address' }), { target: { value: 'second@example.com' } })
    expect(firstSignal.aborted).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Validate email' }))
    await waitFor(() => expect(json).toHaveBeenCalledTimes(2))
    expect(json.mock.calls[1]?.[2].signal).toBeInstanceOf(AbortSignal)
    first.resolve({ ...unverified, email: 'first@example.com' })
    await waitFor(() => expect(trackValidation).not.toHaveBeenCalled())
    second.resolve({ ...unverified, email: 'second@example.com' })
    expect(await screen.findByText('second@example.com')).toBeInTheDocument()
    expect(screen.queryByText('first@example.com')).not.toBeInTheDocument()
    view.unmount()
    expect(trackValidation).toHaveBeenCalledOnce()
  })

  it('aborts pending requests on navigation without tracking late responses', async () => {
    const pending = Promise.withResolvers<Validation>()
    json.mockImplementation(() => pending.promise)
    const view = render(<ValidationPage />)
    enterAndSubmit('alice@example.com')
    await waitFor(() => expect(json).toHaveBeenCalledOnce())
    const signal = json.mock.calls[0]?.[2].signal as AbortSignal
    view.unmount()
    expect(signal.aborted).toBe(true)
    pending.resolve(unverified)
    await waitFor(() => expect(trackValidation).not.toHaveBeenCalled())
  })

  it.each([
    [401, 'Administrator key is missing or incorrect. Reconnect with a valid key.'],
    [429, 'Request limit reached. Try again in 5 seconds.'],
    [503, 'Shared admission or the backend is unavailable. Try again later.'],
  ])('shows safe HTTP %i error without tracking validation', async (status, message) => {
    json.mockRejectedValue(new ApiError(message, status))
    render(<ValidationPage />)
    enterAndSubmit('alice@example.com')
    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(trackValidation).not.toHaveBeenCalled()
  })
})
