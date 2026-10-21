import { useEffect, useState } from 'react'
import type { ChangeEvent, DragEvent, FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router'
import { z } from 'zod'
import { useNotice } from '../../app/notifications'
import { Button, Card, ConfirmDialog, EmptyState, ErrorState, Status } from '../../components/ui'
import { idSchema, importSchema, resultRowSchema } from '../../lib/api/contracts'
import type { ImportProgress } from '../../lib/api/contracts'
import { useSession } from '../../lib/auth/session'
import { displayTime, fileSize, saveDownload } from '../../lib/format'
import { ApiError } from '../../lib/api/client'

const limit = 16 * 1024 * 1024
const pageSize = 100
const terminal = (state: ImportProgress['state']) => state === 'COMPLETED' || state === 'FAILED' || state === 'CANCELLED'
function validFilename(name: string): boolean {
  if (!name.toLowerCase().endsWith('.csv') || name.length > 255) return false
  for (const char of name) {
    const code = char.charCodeAt(0)
    if (code < 32 || code === 127 || char === '/' || char === ':' || char === '\\') return false
  }
  return true
}
function useVisible() {
  const [visible, setVisible] = useState(() => document.visibilityState !== 'hidden')
  useEffect(() => {
    const update = () => setVisible(document.visibilityState !== 'hidden')
    document.addEventListener('visibilitychange', update)
    return () => document.removeEventListener('visibilitychange', update)
  }, [])
  return visible
}

function ImportDetail({ id }: { id: string }) {
  const { api, connected } = useSession()
  const notice = useNotice()
  const cache = useQueryClient()
  const visible = useVisible()
  const [after, setAfter] = useState(0)
  const [confirm, setConfirm] = useState(false)
  const [actionError, setActionError] = useState<unknown>(null)
  const [downloading, setDownloading] = useState(false)
  const path = `/api/imports/${id}`
  const detailKey = ['import', api, id]
  const resultsKey = ['import-results', api, id, after]
  const detail = useQuery({
    queryKey: detailKey, queryFn: ({ signal }) => api.json(path, importSchema, { signal }),
    enabled: connected, retry: false,
    refetchInterval: query => !visible || (query.state.data && terminal(query.state.data.state)) || (query.state.error instanceof ApiError && query.state.error.status === 401)
      ? false : Math.max(2000 * 2 ** Math.min(query.state.fetchFailureCount, 4),
        query.state.error instanceof ApiError ? (query.state.error.retryAfter ?? 0) * 1000 : 0),
  })
  const active = !!detail.data && !terminal(detail.data.state)
  const results = useQuery({
    queryKey: resultsKey,
    queryFn: ({ signal }) => api.json(`${path}/results?after=${after}&limit=${pageSize}`, z.array(resultRowSchema), { signal }),
    enabled: connected && !!detail.data,
    retry: false,
    refetchInterval: query => !visible || !active || (query.state.error instanceof ApiError && query.state.error.status === 401)
      ? false : Math.max(4000 * 2 ** Math.min(query.state.fetchFailureCount, 3),
        query.state.error instanceof ApiError ? (query.state.error.retryAfter ?? 0) * 1000 : 0),
  })
  const state = detail.data?.state
  useEffect(() => {
    if (state && terminal(state)) {
      void cache.invalidateQueries({ queryKey: ['import-results', api, id] })
    }
  }, [state, cache, api, id])
  const cancel = useMutation({
    mutationFn: () => api.json(path, importSchema, { method: 'DELETE' }),
    onSuccess: data => {
      cache.setQueryData(detailKey, data)
      setConfirm(false)
      setActionError(null)
      notice('Import cancelled.')
    },
    onError: error => {
      setActionError(error)
      setConfirm(false)
      void cache.invalidateQueries({ queryKey: detailKey })
    },
  })

  async function exportCsv() {
    if (downloading) return
    setDownloading(true)
    setActionError(null)
    try {
      const { blob, filename } = await api.download(`${path}/export`, { timeoutMs: 120000 })
      saveDownload(blob, filename)
    } catch (error) {
      setActionError(error)
    } finally {
      setDownloading(false)
    }
  }

  if (!connected) return <EmptyState title="Administrator access required">Connect an administrator key to view an import.</EmptyState>
  if (detail.isPending) return <Card><p role="status">Loading import…</p></Card>
  if (detail.isError && !detail.data) return <ErrorState error={detail.error} onRetry={() => { void detail.refetch() }} />
  const value = detail.data
  if (!value) return null
  const rows = results.data ?? []
  return <div className="space-y-5">
    <Link to="/bulk" className="underline">Back to imports</Link>
    <Card>
      <div className="flex flex-wrap items-center justify-between gap-3"><h2 className="text-xl font-semibold break-all">Import {id}</h2><Status value={value.state} /></div>
      <p className="muted">Created {displayTime(value.createdAt)} · Finished {displayTime(value.finishedAt)}</p>
      {value.failureCode && <p role="alert">Failure: {value.failureCode}</p>}
      <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
        {([['Input rows', value.inputRows], ['Accepted', value.acceptedRows], ['Duplicates', value.duplicateRows], ['Rejected', value.rejectedRows], ['Pending', value.pendingRows], ['Running', value.runningRows], ['Retry', value.retryRows], ['Completed', value.completedRows], ['Failed', value.failedRows], ['Cancelled', value.cancelledRows]] as const).map(([label, count]) => <div key={label}><dt className="muted">{label}</dt><dd className="font-semibold">{count}</dd></div>)}
      </dl>
      <div className="mt-4 flex flex-wrap gap-2">
        <Button variant="secondary" disabled={downloading} onClick={() => { void exportCsv() }}>{downloading ? 'Preparing CSV…' : 'Export CSV'}</Button>
        {!terminal(value.state) && <Button variant="danger" disabled={cancel.isPending} onClick={() => setConfirm(true)}>Cancel import</Button>}
      </div>
      {active && <p className="muted">Export may contain partial results while processing continues.</p>}
      {detail.isError && <ErrorState error={detail.error} onRetry={() => { void detail.refetch() }} />}
      {actionError !== null && <ErrorState error={actionError} />}
    </Card>
    <Card>
      <h2 className="text-xl font-semibold">Result rows</h2>
      {results.isError && <ErrorState error={results.error} onRetry={() => { void results.refetch() }} />}
      {results.isPending ? <p role="status">Loading results…</p> : rows.length === 0 ? <p className="muted">No rows available yet.</p> : <>
        <div className="overflow-x-auto"><table className="w-full text-left text-sm"><thead><tr><th scope="col">Row</th><th scope="col">Email</th><th scope="col">Row state</th><th scope="col">Job</th><th scope="col">Result</th><th scope="col">Details</th></tr></thead>
          <tbody>{rows.map(row => <tr key={row.ordinal} className="border-t align-top"><th scope="row">{row.ordinal}</th><td className="break-all">{row.email ?? '—'}</td><td><Status value={row.rowState} /></td><td>{row.jobState ?? '—'}</td><td>{row.resultStatus ?? '—'}</td><td>{[row.reason, row.failureCode, row.message, row.duplicateOf != null ? `Duplicate of row ${row.duplicateOf}` : null].filter(Boolean).join(' · ') || '—'}</td></tr>)}</tbody>
        </table></div>
        <div className="mt-3 flex items-center gap-3"><span className="muted">After row {after}</span>{after > 0 && <Button variant="secondary" onClick={() => setAfter(0)}>First page</Button>}{rows.length === pageSize && <Button variant="secondary" onClick={() => setAfter(rows[rows.length - 1]!.ordinal)}>Next page</Button>}</div>
      </>}
    </Card>
    <ConfirmDialog open={confirm} title="Cancel import?" busy={cancel.isPending} onConfirm={() => cancel.mutate()} onClose={() => setConfirm(false)}>Unfinished rows will be cancelled. Existing results remain available.</ConfirmDialog>
  </div>
}

export default function BulkPage() {
  const { id } = useParams()
  const { api, connected, imports, trackImport } = useSession()
  const navigate = useNavigate()
  const notice = useNotice()
  const [file, setFile] = useState<File | null>(null)
  const [fileError, setFileError] = useState('')
  const [submitError, setSubmitError] = useState<unknown>(null)
  const [lookup, setLookup] = useState('')
  const [lookupError, setLookupError] = useState('')
  const [dragging, setDragging] = useState(false)
  const upload = useMutation({
    mutationFn: (chosen: File) => {
      const form = new FormData()
      form.append('file', chosen.type === 'text/csv' ? chosen : new File([chosen], chosen.name, { type: 'text/csv', lastModified: chosen.lastModified }))
      return api.json('/api/imports', idSchema, { method: 'POST', body: form, timeoutMs: 120000 })
    },
    onSuccess: data => {
      trackImport(data.id)
      setSubmitError(null)
      setFile(null)
      notice('CSV import accepted.')
      void navigate(`/bulk/${data.id}`)
    },
    onError: setSubmitError,
  })

  function choose(chosen: File | null | undefined) {
    setFile(null)
    setFileError('')
    setSubmitError(null)
    if (!chosen) return
    if (!validFilename(chosen.name)) {
      setFileError('Choose a .csv file with a valid filename.')
    } else if (chosen.size > limit) {
      setFileError('The upload is too large. Choose a CSV under 16 MiB.')
    } else {
      setFile(chosen)
    }
  }
  function drop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setDragging(false)
    choose(event.dataTransfer.files[0])
  }
  function openId(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const cleaned = lookup.trim()
    if (!z.uuid().safeParse(cleaned).success) { setLookupError('Enter a valid import UUID.'); return }
    setLookupError('')
    trackImport(cleaned)
    void navigate(`/bulk/${cleaned}`)
  }
  if (id) return z.uuid().safeParse(id).success ? <ImportDetail key={id} id={id} /> : <ErrorState error={new ApiError('Enter a valid import UUID.', 400)} />
  if (!connected) return <EmptyState title="Administrator access required">Connect an administrator key to upload and view imports.</EmptyState>
  return <div className="space-y-5">
    <h1 className="text-2xl font-bold">Bulk CSV imports</h1>
    <Card><h2 className="text-xl font-semibold">Upload CSV</h2>
      <p className="muted">One .csv file, up to 16 MiB. Imports are processed asynchronously.</p>
      <div className={`mt-3 rounded border-2 border-dashed p-5 ${dragging ? 'border-blue-600' : ''}`} onDragOver={event => { event.preventDefault(); setDragging(true) }} onDragLeave={() => setDragging(false)} onDrop={drop}>
        <label htmlFor="import-file" className="label">Choose or drop a CSV file</label>
        <input id="import-file" className="input" type="file" accept=".csv,text/csv" disabled={upload.isPending} onChange={(event: ChangeEvent<HTMLInputElement>) => { choose(event.target.files?.[0]); event.target.value = '' }} />
      </div>
      {file && <p className="mt-2 break-all" role="status">{file.name} · {fileSize(file.size)}</p>}
      {fileError && <p role="alert">{fileError}</p>}
      {submitError !== null && <ErrorState error={submitError} />}
      <Button className="mt-3" disabled={!file || upload.isPending} onClick={() => { if (file) upload.mutate(file) }}>{upload.isPending ? 'Uploading…' : 'Upload import'}</Button>
    </Card>
    <Card><h2 className="text-xl font-semibold">Find import</h2>
      <p className="muted">Only IDs opened in this session appear below. There is no global import list.</p>
      <form onSubmit={openId} className="flex flex-wrap items-end gap-2"><div><label htmlFor="import-id" className="label">Import UUID</label><input id="import-id" className="input" value={lookup} onChange={event => setLookup(event.target.value)} aria-invalid={!!lookupError} aria-describedby={lookupError ? 'import-id-error' : undefined} /></div><Button type="submit">Open import</Button></form>
      {lookupError && <p id="import-id-error" role="alert">{lookupError}</p>}
      {imports.length > 0 && <ul className="mt-3 space-y-2">{imports.map(entry => <li key={entry}><Link className="underline break-all" to={`/bulk/${entry}`}>{entry}</Link></li>)}</ul>}
    </Card>
  </div>
}
