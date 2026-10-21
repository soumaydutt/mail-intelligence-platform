import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'
import { useSession } from '../../lib/auth/session'
import { idSchema, suppressionSchema, type Suppression } from '../../lib/api/contracts'
import { useNotice } from '../../app/notifications'
import { Button, Card, ConfirmDialog, EmptyState, ErrorState, Status } from '../../components/ui'
import { displayTime } from '../../lib/format'

const pageSize = 25
const reasonPattern = /^[A-Z][A-Z0-9_]{0,63}$/

export default function SuppressionsPage() {
  const { connected, api } = useSession()
  const notice = useNotice()
  const cache = useQueryClient()
  const [after, setAfter] = useState<string | null>(null)
  const [previous, setPrevious] = useState<(string | null)[]>([])
  const [email, setEmail] = useState('')
  const [createReason, setCreateReason] = useState('')
  const [liftTarget, setLiftTarget] = useState<Suppression | null>(null)
  const [liftReason, setLiftReason] = useState('')
  const [reasonError, setReasonError] = useState(false)

  const listing = useQuery({
    queryKey: ['suppressions', after],
    enabled: connected,
    queryFn: ({ signal }) => api.json(`/api/suppressions?limit=${pageSize}${after ? `&after=${encodeURIComponent(after)}` : ''}`,
      z.array(suppressionSchema), { signal }),
    retry: false,
  })
  const create = useMutation({
    mutationFn: (input: { email: string; reasonCode: string }) =>
      api.json('/api/suppressions', idSchema, { method: 'POST', body: input }),
    retry: false,
    onSuccess: async ({ id }) => {
      notice(`Manual suppression created: ${id}`)
      setEmail('')
      setCreateReason('')
      setAfter(null)
      setPrevious([])
      await cache.invalidateQueries({ queryKey: ['suppressions'] })
    },
  })
  const lift = useMutation({
    mutationFn: ({ id, reasonCode }: { id: string; reasonCode: string }) =>
      api.empty(`/api/suppressions/${encodeURIComponent(id)}`, { method: 'DELETE', body: { reasonCode } }),
    retry: false,
    onSuccess: async () => {
      setLiftTarget(null)
      setLiftReason('')
      notice('Suppression lifted.')
      await cache.invalidateQueries({ queryKey: ['suppressions'] })
    },
  })

  if (!connected) return <EmptyState title="Administrator connection required">Connect with an administrator key to inspect or change suppressions.</EmptyState>

  function submitCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!reasonPattern.test(createReason.trim())) return
    create.mutate({ email: email.trim(), reasonCode: createReason.trim() })
  }

  function confirmLift() {
    const reasonCode = liftReason.trim()
    if (!reasonPattern.test(reasonCode)) { setReasonError(true); return }
    if (liftTarget) lift.mutate({ id: liftTarget.id, reasonCode })
  }

  const rows = listing.data ?? []
  return <div className="space-y-6">
    <h1>Suppressions</h1>
    <p className="muted">UUID-keyset pages list recorded suppressions, including lifted and expired entries. This is not an email search.</p>
    <Card>
      <h2>Manual suppression</h2>
      <form onSubmit={submitCreate} className="space-y-3">
        <label className="label" htmlFor="suppression-email">Email address</label>
        <input className="input" id="suppression-email" type="email" autoComplete="off" required maxLength={255} value={email} onChange={event => setEmail(event.target.value)} />
        <label className="label" htmlFor="create-reason">Reason code</label>
        <input className="input" id="create-reason" required maxLength={64} pattern="[A-Z][A-Z0-9_]*" placeholder="OPERATOR_BLOCK" value={createReason} onChange={event => setCreateReason(event.target.value)} />
        <Button type="submit" disabled={create.isPending}>{create.isPending ? 'Creating…' : 'Create suppression'}</Button>
      </form>
      {create.isError && <ErrorState error={create.error} />}
    </Card>
    <Card>
      <h2>Suppression records</h2>
      {listing.isPending && <p role="status">Loading suppressions…</p>}
      {listing.isError && <ErrorState error={listing.error} onRetry={() => void listing.refetch()} />}
      {listing.isSuccess && (rows.length ? <div className="overflow-x-auto">
        <table>
          <caption className="sr-only">Suppression records ordered by UUID</caption>
          <thead><tr><th scope="col">Email</th><th scope="col">Status</th><th scope="col">Reason</th><th scope="col">Effective</th><th scope="col">Expires</th><th scope="col">Lifted</th><th scope="col">Lifting reason</th><th scope="col">Source event</th><th scope="col">ID / action</th></tr></thead>
          <tbody>{rows.map(row => <tr key={row.id}>
            <td>{row.email}</td><td><Status value={row.active ? 'ACTIVE' : row.liftedAt ? 'LIFTED' : 'INACTIVE'} /></td>
            <td>{row.reason}</td><td>{displayTime(row.effectiveAt)}</td><td>{row.expiresAt ? displayTime(row.expiresAt) : 'No expiry'}</td>
            <td>{row.liftedAt ? displayTime(row.liftedAt) : 'Not lifted'}</td><td>{row.liftingReason ?? '—'}</td>
            <td>{row.sourceEventId ?? 'Manual'}</td><td>{row.id}{row.active && <Button variant="danger" onClick={() => { setLiftTarget(row); setLiftReason(''); setReasonError(false) }}>Lift</Button>}</td>
          </tr>)}</tbody>
        </table>
      </div> : <p>No suppression records on this page.</p>)}
      <div className="flex flex-wrap gap-2 mt-4">
        <Button variant="secondary" disabled={!previous.length || listing.isFetching} onClick={() => {
          setAfter(previous.at(-1) ?? null)
          setPrevious(previous.slice(0, -1))
        }}>Previous</Button>
        <Button variant="secondary" disabled={rows.length < pageSize || listing.isFetching || !listing.isSuccess} onClick={() => {
          const last = rows.at(-1)
          if (!last) return
          setPrevious(items => [...items, after])
          setAfter(last.id)
        }}>Next</Button>
      </div>
    </Card>
    <ConfirmDialog open={liftTarget !== null} title="Lift suppression?" busy={lift.isPending}
      onClose={() => { setLiftTarget(null); setReasonError(false); lift.reset() }} onConfirm={confirmLift}>
      <p>This lifts the suppression for {liftTarget?.email}. The audited record remains. Confirm only if authorized.</p>
      <label className="label" htmlFor="lift-reason">Lifting reason code</label>
      <input className="input" id="lift-reason" required maxLength={64} pattern="[A-Z][A-Z0-9_]*" placeholder="OPERATOR_REVIEW" value={liftReason} onChange={event => { setLiftReason(event.target.value); setReasonError(false) }} />
      {reasonError && <p role="alert">Use uppercase letters, digits or underscores; start with a letter.</p>}
      {lift.isError && <ErrorState error={lift.error} />}
    </ConfirmDialog>
  </div>
}
