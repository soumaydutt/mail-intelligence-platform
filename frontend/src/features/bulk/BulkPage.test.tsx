import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router'
import { useSession } from '../../lib/auth/session'
import { saveDownload } from '../../lib/format'
import { ApiError } from '../../lib/api/client'
import { createApi } from '../../lib/api/client'
import BulkPage from './BulkPage'

vi.mock('../../lib/auth/session', () => ({ useSession: vi.fn() }))
vi.mock('../../app/notifications', () => ({ useNotice: () => vi.fn() }))
vi.mock('../../lib/format', () => ({ displayTime: (value: string | null) => value ?? 'Not recorded', fileSize: (bytes: number) => `${bytes} B`, saveDownload: vi.fn() }))

const id = 'f21f5da5-7898-43e7-95c5-241a99ad63db'
const progress = { id, state: 'COMPLETED', inputRows: 1, acceptedRows: 1, duplicateRows: 0, rejectedRows: 0, pendingRows: 0, runningRows: 0, retryRows: 0, completedRows: 1, failedRows: 0, cancelledRows: 0, createdAt: '2026-01-01T00:00:00Z', finishedAt: '2026-01-01T00:00:01Z', failureCode: null }
const json = vi.fn()
const download = vi.fn()
const trackImport = vi.fn()

function mount(route = '/bulk') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[route]}><Routes><Route path="/bulk" element={<BulkPage />} /><Route path="/bulk/:id" element={<BulkPage />} /></Routes></MemoryRouter></QueryClientProvider>)
}

beforeEach(() => {
  if (!HTMLDialogElement.prototype.showModal) HTMLDialogElement.prototype.showModal = function () { this.open = true }
  if (!HTMLDialogElement.prototype.close) HTMLDialogElement.prototype.close = function () { this.open = false }
  json.mockReset()
  download.mockReset()
  trackImport.mockReset()
  vi.mocked(useSession).mockReturnValue({ api: { json, download, text: vi.fn(), empty: vi.fn() }, connected: true, imports: [], trackImport, jobs: [], validations: [], trackJob: vi.fn(), trackValidation: vi.fn(), connect: vi.fn(), disconnect: vi.fn() })
})
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals() })

describe('bulk import', () => {
  it('rejects oversize and non-CSV files before upload', async () => {
    const user = userEvent.setup()
    mount()
    const large = new File(['csv'], 'large.csv', { type: 'text/csv' })
    Object.defineProperty(large, 'size', { value: 16 * 1024 * 1024 + 1 })
    await user.upload(screen.getByLabelText('Choose or drop a CSV file'), large)
    expect(screen.getByRole('alert')).toHaveTextContent('too large')
    expect(screen.getByRole('button', { name: 'Upload import' })).toBeDisabled()
    fireEvent.drop(screen.getByLabelText('Choose or drop a CSV file').parentElement!, { dataTransfer: { files: [new File(['x'], 'bad.txt')] } })
    expect(screen.getByRole('alert')).toHaveTextContent('.csv')
    expect(json).not.toHaveBeenCalled()
  })

  it('uploads one CSV part with text/csv even when OS supplies no MIME type', async () => {
    const user = userEvent.setup()
    json.mockResolvedValueOnce({ id })
    json.mockImplementation(async (path: string) => path.endsWith('/results?after=0&limit=100') ? [] : progress)
    mount()
    await user.upload(screen.getByLabelText('Choose or drop a CSV file'), new File(['email\nuser@example.test'], 'users.csv'))
    expect(screen.getByText(/users.csv/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Upload import' }))
    await waitFor(() => expect(trackImport).toHaveBeenCalledWith(id))
    const [path, , options] = json.mock.calls[0]!
    expect(path).toBe('/api/imports')
    expect(options.method).toBe('POST')
    const parts = [...(options.body as FormData).entries()]
    expect(parts).toHaveLength(1)
    expect(parts[0]![0]).toBe('file')
    expect((parts[0]![1] as File).type).toBe('text/csv')
    expect((parts[0]![1] as File).name).toBe('users.csv')
  })

  it('reads exact results cursor and downloads backend CSV name', async () => {
    const user = userEvent.setup()
    const rows = Array.from({ length: 100 }, (_, index) => ({ ordinal: index + 1, email: null, rowState: 'REJECTED', duplicateOf: null, reason: 'EMPTY', jobState: null, resultStatus: null, failureCode: null, formatValid: null, mxRecordFound: null, smtpVerified: null, domain: null, message: null }))
    json.mockImplementation(async (path: string) => path.includes('/results?') ? path.includes('after=0') ? rows : [] : progress)
    const blob = new Blob(['ordinal,email\n'])
    download.mockResolvedValue({ blob, filename: 'import-results.csv' })
    mount(`/bulk/${id}`)
    await user.click(await screen.findByRole('button', { name: 'Next page' }))
    await waitFor(() => expect(json).toHaveBeenCalledWith(`/api/imports/${id}/results?after=100&limit=100`, expect.anything(), expect.objectContaining({ signal: expect.any(AbortSignal) })))
    await user.click(screen.getByRole('button', { name: 'Export CSV' }))
    await waitFor(() => expect(saveDownload).toHaveBeenCalledWith(blob, 'import-results.csv'))
    expect(download).toHaveBeenCalledWith(`/api/imports/${id}/export`, expect.objectContaining({ timeoutMs: 120000 }))
  })

  it('stops polling terminal imports and confirms cancellation on active imports', async () => {
    json.mockImplementation(async (path: string, _schema: unknown, options?: { method?: string }) => options?.method === 'DELETE' ? { ...progress, state: 'CANCELLED' } : path.includes('/results?') ? [] : { ...progress, state: 'READY', finishedAt: null })
    const user = userEvent.setup()
    mount(`/bulk/${id}`)
    await screen.findByText('No rows available yet.')
    await user.click(await screen.findByRole('button', { name: 'Cancel import' }))
    expect(json.mock.calls.filter(call => call[2]?.method === 'DELETE')).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(json.mock.calls.filter(call => call[2]?.method === 'DELETE')).toHaveLength(1))
    await screen.findByText('CANCELLED')
    await waitFor(() => expect(json.mock.calls.filter(call => call[0].includes('/results?'))).toHaveLength(2))
    expect(screen.queryByRole('button', { name: 'Cancel import' })).not.toBeInTheDocument()
    const calls = json.mock.calls.length
    vi.useFakeTimers()
    await act(async () => { await vi.advanceTimersByTimeAsync(33000) })
    expect(json.mock.calls.length).toBe(calls)
  })
  it.each(['COMPLETED', 'FAILED', 'CANCELLED'] as const)(
    'parses representative import responses and stops polling at %s', async finalState => {
      const states = ['INGESTING', 'READY', 'PROCESSING', finalState] as const
      let reads = 0
      const fetcher = vi.fn(async (path: string) => new Response(JSON.stringify(path.includes('/results?')
        ? []
        : { ...progress, state: states[Math.min(reads++, states.length - 1)], finishedAt: reads === states.length ? progress.finishedAt : null }), { status: 200 }))
      vi.stubGlobal('fetch', fetcher)
      vi.mocked(useSession).mockReturnValue({ ...vi.mocked(useSession)(), api: createApi('key') })
      mount(`/bulk/${id}`)
      await screen.findByText('INGESTING')
      for (const state of states.slice(1)) {
        expect(await screen.findByText(state, {}, { timeout: 5000 })).toBeInTheDocument()
        expect(screen.queryByText('The backend returned an unexpected response.')).not.toBeInTheDocument()
      }
      expect(reads).toBe(4)
      expect(screen.queryByRole('button', { name: 'Cancel import' })).not.toBeInTheDocument()
      await new Promise(resolve => setTimeout(resolve, 2300))
      expect(reads).toBe(4)
    }, 16000,
  )

  it('reports backend upload limit errors without repeating the mutation', async () => {
    const user = userEvent.setup()
    json.mockRejectedValue(new ApiError('The upload is too large. Choose a CSV under 16 MiB.', 413))
    mount()
    await user.upload(screen.getByLabelText('Choose or drop a CSV file'), new File(['email'], 'users.csv', { type: 'text/csv' }))
    await user.click(screen.getByRole('button', { name: 'Upload import' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('too large')
    expect(json).toHaveBeenCalledTimes(1)
  })

})
