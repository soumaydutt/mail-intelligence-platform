import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import { useSession } from '../../lib/auth/session'
import { ApiError } from '../../lib/api/client'
import JobsPage from './JobsPage'

vi.mock('../../lib/auth/session', () => ({ useSession: vi.fn() }))
vi.mock('../../app/notifications', () => ({ useNotice: () => vi.fn() }))

const id = 'f21f5da5-7898-43e7-95c5-241a99ad63db'
const job = { id, state: 'COMPLETED', attempts: 1, maxAttempts: 3, dueAt: null, createdAt: '2026-01-01T00:00:00Z', finishedAt: '2026-01-01T00:00:01Z', failureCode: null, result: { email: 'sample@example.test', domain: 'example.test', formatValid: true, mxRecordFound: false, smtpVerified: null, status: 'UNVERIFIED', message: 'No MX record' } }
const json = vi.fn()
const trackJob = vi.fn()

function mount(route = '/jobs') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[route]}><Routes><Route path="/jobs" element={<JobsPage />} /><Route path="/jobs/:id" element={<JobsPage />} /></Routes></MemoryRouter></QueryClientProvider>)
}

beforeEach(() => {
  if (!HTMLDialogElement.prototype.showModal) HTMLDialogElement.prototype.showModal = function () { this.open = true }
  if (!HTMLDialogElement.prototype.close) HTMLDialogElement.prototype.close = function () { this.open = false }
  json.mockReset()
  trackJob.mockReset()
  vi.mocked(useSession).mockReturnValue({ api: { json, download: vi.fn(), text: vi.fn(), empty: vi.fn() }, connected: true, imports: [], trackImport: vi.fn(), jobs: [], validations: [], trackJob, trackValidation: vi.fn(), connect: vi.fn(), disconnect: vi.fn() })
})
afterEach(() => cleanup())

describe('jobs', () => {
  it('submits an address once and opens its actual job ID', async () => {
    const user = userEvent.setup()
    json.mockResolvedValueOnce({ id, reused: false }).mockResolvedValue(job)
    mount()
    await user.type(screen.getByLabelText('Email address'), 'sample@example.test')
    await user.click(screen.getByRole('button', { name: 'Submit job' }))
    await waitFor(() => expect(trackJob).toHaveBeenCalledWith(id))
    expect(json.mock.calls.filter(call => call[2]?.method === 'POST')).toHaveLength(1)
    expect(json).toHaveBeenCalledWith('/api/jobs', expect.anything(), { method: 'POST', body: { email: 'sample@example.test' } })
    expect(await screen.findByText('No MX record')).toBeInTheDocument()
  })

  it('shows true terminal result, no cancel button, and fetches with AbortSignal', async () => {
    json.mockResolvedValue(job)
    mount(`/jobs/${id}`)
    expect(await screen.findByText('No MX record')).toBeInTheDocument()
    expect(screen.getByText('UNVERIFIED')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel job' })).not.toBeInTheDocument()
    expect(json).toHaveBeenCalledWith(`/api/jobs/${id}`, expect.anything(), expect.objectContaining({ signal: expect.any(AbortSignal) }))
  })

  it('confirms cancellation and stops displaying cancellation after backend accepts', async () => {
    json.mockImplementation(async (_path: string, _schema: unknown, options?: { method?: string }) => options?.method === 'DELETE' ? { ...job, state: 'CANCELLED', result: null } : { ...job, state: 'RUNNING', finishedAt: null, result: null })
    const user = userEvent.setup()
    mount(`/jobs/${id}`)
    await user.click(await screen.findByRole('button', { name: 'Cancel job' }))
    expect(json.mock.calls.filter(call => call[2]?.method === 'DELETE')).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(json.mock.calls.filter(call => call[2]?.method === 'DELETE')).toHaveLength(1))
    expect(screen.queryByRole('button', { name: 'Cancel job' })).not.toBeInTheDocument()
    expect(screen.queryByText('No MX record')).not.toBeInTheDocument()
  })
  it('shows cancellation conflicts and refreshes the job state', async () => {
    let completed = false
    json.mockImplementation(async (_path: string, _schema: unknown, options?: { method?: string }) => {
      if (options?.method === 'DELETE') {
        completed = true
        throw new ApiError('The record changed or this action conflicts with its current state. Refresh before retrying.', 409)
      }
      return completed ? job : { ...job, state: 'PENDING', finishedAt: null, result: null }
    })
    const user = userEvent.setup()
    mount(`/jobs/${id}`)
    await user.click(await screen.findByRole('button', { name: 'Cancel job' }))
    await user.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('conflicts')
    expect(await screen.findByText('No MX record')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel job' })).not.toBeInTheDocument()
  })
})
