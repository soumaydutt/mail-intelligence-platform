import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router'
import { z } from 'zod'
import { useNotice } from '../../app/notifications'
import { Button, Card, ConfirmDialog, EmptyState, ErrorState, Status } from '../../components/ui'
import { jobSchema, submissionSchema } from '../../lib/api/contracts'
import type { Job } from '../../lib/api/contracts'
import { ApiError } from '../../lib/api/client'
import { useSession } from '../../lib/auth/session'
import { displayTime } from '../../lib/format'

const terminal = (state: Job['state']) => state === 'COMPLETED' || state === 'FAILED' || state === 'CANCELLED'

function JobDetail({ id }: { id: string }) {
  const { api, connected } = useSession()
  const notice = useNotice()
  const cache = useQueryClient()
  const [visible, setVisible] = useState(() => document.visibilityState !== 'hidden')
  const [confirm, setConfirm] = useState(false)
  const [actionError, setActionError] = useState<unknown>(null)
  useEffect(() => {
    const update = () => setVisible(document.visibilityState !== 'hidden')
    document.addEventListener('visibilitychange', update)
    return () => document.removeEventListener('visibilitychange', update)
  }, [])
  const path = `/api/jobs/${id}`
  const key = ['job', api, id]
  const detail = useQuery({
    queryKey: key, queryFn: ({ signal }) => api.json(path, jobSchema, { signal }),
    enabled: connected, retry: false,
    refetchInterval: query => !visible || (query.state.data && terminal(query.state.data.state)) || (query.state.error instanceof ApiError && query.state.error.status === 401)
      ? false : Math.max(2000 * 2 ** Math.min(query.state.fetchFailureCount, 4),
        query.state.error instanceof ApiError ? (query.state.error.retryAfter ?? 0) * 1000 : 0),
  })
  const cancel = useMutation({
    mutationFn: () => api.json(path, jobSchema, { method: 'DELETE' }),
    onSuccess: value => {
      cache.setQueryData(key, value)
      setConfirm(false)
      setActionError(null)
      notice('Job cancelled.')
    },
    onError: error => {
      setConfirm(false)
      setActionError(error)
      void cache.invalidateQueries({ queryKey: key })
    },
  })
  if (!connected) return <EmptyState title="Administrator access required">Connect an administrator key to view this job.</EmptyState>
  if (detail.isPending) return <Card><p role="status">Loading job…</p></Card>
  if (detail.isError && !detail.data) return <ErrorState error={detail.error} onRetry={() => { void detail.refetch() }} />
  const value = detail.data
  if (!value) return null
  const result = value.state === 'COMPLETED' ? value.result : null
  return <div className="space-y-5">
    <Link to="/jobs" className="underline">Back to jobs</Link>
    <Card>
      <div className="flex flex-wrap items-center justify-between gap-3"><h1 className="text-xl font-semibold break-all">Job {id}</h1><Status value={value.state} /></div>
      <dl className="mt-3 grid gap-3 sm:grid-cols-2"><div><dt className="muted">Attempts</dt><dd>{value.attempts} of {value.maxAttempts}</dd></div><div><dt className="muted">Next due</dt><dd>{displayTime(value.dueAt)}</dd></div><div><dt className="muted">Created</dt><dd>{displayTime(value.createdAt)}</dd></div><div><dt className="muted">Finished</dt><dd>{displayTime(value.finishedAt)}</dd></div></dl>
      {value.failureCode && <p role="alert">Failure: {value.failureCode}</p>}
      {!terminal(value.state) && <Button className="mt-3" variant="danger" disabled={cancel.isPending} onClick={() => setConfirm(true)}>Cancel job</Button>}
      {detail.isError && <ErrorState error={detail.error} onRetry={() => { void detail.refetch() }} />}
      {actionError !== null && <ErrorState error={actionError} />}
    </Card>
    {result && <Card><h2 className="text-xl font-semibold">Validation result</h2>
      <p className="break-all">{result.email} · <Status value={result.status} /></p><p>{result.message}</p>
      <dl className="grid gap-3 sm:grid-cols-2"><div><dt className="muted">Domain</dt><dd>{result.domain ?? 'Unknown'}</dd></div><div><dt className="muted">Format valid</dt><dd>{result.formatValid == null ? 'Unknown' : result.formatValid ? 'Yes' : 'No'}</dd></div><div><dt className="muted">MX record found</dt><dd>{result.mxRecordFound == null ? 'Unknown' : result.mxRecordFound ? 'Yes' : 'No'}</dd></div><div><dt className="muted">SMTP verified</dt><dd>{result.smtpVerified == null ? 'Unknown' : result.smtpVerified ? 'Yes' : 'No'}</dd></div></dl>
      {result.historicalEvidence && <p className="muted">Historical evidence: {result.historicalEvidence.freshness} · {result.historicalEvidence.interpretation ?? 'No interpretation'}</p>}
    </Card>}
    <ConfirmDialog open={confirm} title="Cancel job?" busy={cancel.isPending} onConfirm={() => cancel.mutate()} onClose={() => setConfirm(false)}>Outstanding work will be cancelled. This does not delete the job.</ConfirmDialog>
  </div>
}

export default function JobsPage() {
  const { id } = useParams()
  const { api, connected, jobs, trackJob } = useSession()
  const navigate = useNavigate()
  const notice = useNotice()
  const [email, setEmail] = useState('')
  const [lookup, setLookup] = useState('')
  const [lookupError, setLookupError] = useState('')
  const [submitError, setSubmitError] = useState<unknown>(null)
  const submit = useMutation({
    mutationFn: (address: string) => api.json('/api/jobs', submissionSchema, { method: 'POST', body: { email: address } }),
    onSuccess: value => {
      setSubmitError(null)
      setEmail('')
      trackJob(value.id)
      notice(value.reused ? 'Existing job opened.' : 'Job submitted.')
      void navigate(`/jobs/${value.id}`)
    },
    onError: setSubmitError,
  })
  function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!email.trim() || email.length > 255) { setSubmitError(new ApiError('Enter an email address up to 255 characters.', 400)); return }
    setSubmitError(null)
    submit.mutate(email)
  }
  function openId(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const cleaned = lookup.trim()
    if (!z.uuid().safeParse(cleaned).success) { setLookupError('Enter a valid job UUID.'); return }
    setLookupError('')
    trackJob(cleaned)
    void navigate(`/jobs/${cleaned}`)
  }
  if (id) return z.uuid().safeParse(id).success ? <JobDetail key={id} id={id} /> : <ErrorState error={new ApiError('Enter a valid job UUID.', 400)} />
  if (!connected) return <EmptyState title="Administrator access required">Connect an administrator key to submit and view jobs.</EmptyState>
  return <div className="space-y-5">
    <h1 className="text-2xl font-bold">Validation jobs</h1>
    <Card><h2 className="text-xl font-semibold">Submit a job</h2>
      <p className="muted">Submit one address for asynchronous validation. The job status shows the real result.</p>
      <form onSubmit={create} className="flex flex-wrap items-end gap-2"><div><label htmlFor="job-email" className="label">Email address</label><input id="job-email" className="input" type="email" autoComplete="off" maxLength={255} required value={email} onChange={event => setEmail(event.target.value)} /></div><Button type="submit" disabled={submit.isPending}>{submit.isPending ? 'Submitting…' : 'Submit job'}</Button></form>
      {submitError !== null && <ErrorState error={submitError} />}
    </Card>
    <Card><h2 className="text-xl font-semibold">Find job</h2>
      <p className="muted">Only IDs opened in this session appear below. There is no global jobs list.</p>
      <form onSubmit={openId} className="flex flex-wrap items-end gap-2"><div><label htmlFor="job-id" className="label">Job UUID</label><input id="job-id" className="input" value={lookup} onChange={event => setLookup(event.target.value)} aria-invalid={!!lookupError} aria-describedby={lookupError ? 'job-id-error' : undefined} /></div><Button type="submit">Open job</Button></form>
      {lookupError && <p id="job-id-error" role="alert">{lookupError}</p>}
      {jobs.length > 0 && <ul className="mt-3 space-y-2">{jobs.map(entry => <li key={entry}><Link className="underline break-all" to={`/jobs/${entry}`}>{entry}</Link></li>)}</ul>}
    </Card>
  </div>
}
