import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSession } from '../../lib/auth/session'
import { evidenceSchema, ingestedSchema } from '../../lib/api/contracts'
import { useNotice } from '../../app/notifications'
import { Button, Card, ConfirmDialog, EmptyState, ErrorState, Status } from '../../components/ui'
import { displayTime } from '../../lib/format'

const reasonPattern = /^[A-Z][A-Z0-9_]{0,63}$/
const meaning: Record<string, string> = {
  RECIPIENT_HARD_BOUNCE: 'A trusted event reported a recipient-specific hard bounce.',
  UNCERTAIN_BOUNCE: 'The bounce does not establish a recipient-specific failure. It does not automatically suppress this address.',
  TRANSIENT_BOUNCE: 'A temporary delivery problem was reported; this is not a permanent recipient failure.',
  HISTORICAL_DELIVERY: 'A past delivery was reported; it does not prove current deliverability.',
  COMPLAINT: 'A complaint was reported.',
  UNSUBSCRIBED: 'An unsubscribe was reported.',
}

export default function EvidencePage() {
  const { connected, api } = useSession()
  const notice = useNotice()
  const cache = useQueryClient()
  const [email, setEmail] = useState('')
  const [lookedUpEmail, setLookedUpEmail] = useState<string | null>(null)
  const [eventEmail, setEventEmail] = useState('')
  const [eventType, setEventType] = useState('HARD_BOUNCE')
  const [externalEventId, setExternalEventId] = useState('')
  const [occurredAt, setOccurredAt] = useState('')
  const [enhancedStatusCode, setEnhancedStatusCode] = useState('')
  const [ingested, setIngested] = useState<{ id: string; reused: boolean } | null>(null)
  const [revokeId, setRevokeId] = useState<string | null>(null)
  const [revokeReason, setRevokeReason] = useState('')
  const [reasonError, setReasonError] = useState(false)

  const lookup = useQuery({
    queryKey: ['evidence', lookedUpEmail],
    enabled: connected && lookedUpEmail !== null,
    queryFn: ({ signal }) => api.json('/api/delivery-evidence/lookup', evidenceSchema, {
      method: 'POST', body: { email: lookedUpEmail }, signal,
    }),
    retry: false,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  })
  const ingest = useMutation({
    mutationFn: (input: { email: string; eventType: string; externalEventId: string; occurredAt: string; enhancedStatusCode?: string }) =>
      api.json('/api/delivery-events', ingestedSchema, { method: 'POST', body: input }),
    retry: false,
    onSuccess: async (result, input) => {
      setIngested(result)
      notice(result.reused ? 'Existing event reused; no new event created.' : 'Delivery event ingested.')
      await cache.invalidateQueries({ queryKey: ['evidence', input.email] })
    },
  })
  const revoke = useMutation({
    mutationFn: ({ id, reasonCode }: { id: string; reasonCode: string }) =>
      api.empty(`/api/delivery-events/${encodeURIComponent(id)}/revoke`, { method: 'POST', body: { reasonCode } }),
    retry: false,
    onSuccess: async () => {
      setRevokeId(null)
      setRevokeReason('')
      notice('Event revoked. Any suppression caused by this event was also lifted.')
      await cache.invalidateQueries({ queryKey: ['evidence', lookedUpEmail] })
    },
  })

  if (!connected) return <EmptyState title="Administrator connection required">Connect with an administrator key to inspect or change delivery evidence.</EmptyState>

  function submitLookup(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const address = email.trim()
    if (address === lookedUpEmail) void lookup.refetch()
    else setLookedUpEmail(address)
  }

  function submitEvent(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const timestamp = new Date(occurredAt)
    if (Number.isNaN(timestamp.getTime())) return
    setIngested(null)
    ingest.mutate({ email: eventEmail.trim(), eventType, externalEventId: externalEventId.trim(),
      occurredAt: timestamp.toISOString(), ...(enhancedStatusCode ? { enhancedStatusCode: enhancedStatusCode.trim() } : {}) })
  }

  function confirmRevoke() {
    const reasonCode = revokeReason.trim()
    if (!reasonPattern.test(reasonCode)) { setReasonError(true); return }
    if (revokeId) revoke.mutate({ id: revokeId, reasonCode })
  }

  const snapshot = lookup.data
  return <div className="space-y-6">
    <h1>Delivery evidence</h1>
    <p className="muted">Only trusted, observed delivery events belong here. Historical events never prove current deliverability. No global event search is available.</p>
    <Card>
      <h2>Exact-email lookup</h2>
      <form onSubmit={submitLookup} className="space-y-3">
        <label className="label" htmlFor="evidence-email">Email address</label>
        <input className="input" id="evidence-email" type="email" autoComplete="off" required maxLength={255} value={email} onChange={event => setEmail(event.target.value)} />
        <Button type="submit" disabled={lookup.isFetching}>{lookup.isFetching ? 'Looking up…' : 'Look up evidence'}</Button>
      </form>
      {lookup.isError && <ErrorState error={lookup.error} onRetry={() => void lookup.refetch()} />}
      {snapshot && !lookup.isError && lookedUpEmail && <div className="space-y-3" aria-live="polite">
        <h3>Snapshot for {lookedUpEmail}</h3>
        <p>Freshness: <Status value={snapshot.freshness} /></p>
        {snapshot.freshness === 'UNAVAILABLE' ? <p>Historical evidence is unavailable. Absence of data cannot be inferred.</p>
          : snapshot.freshness === 'NONE' ? <p>No delivery event observed for this address.</p>
            : <p>{snapshot.freshness === 'STALE' ? 'This observation is stale; it does not establish current status.' : 'This observation is recent, not proof of current deliverability.'}</p>}
        {snapshot.eventId && <dl>
          <dt>Event type</dt><dd>{snapshot.eventType ?? 'Unknown'}</dd>
          <dt>Interpretation</dt><dd>{snapshot.interpretation ?? 'Unknown'} — {meaning[snapshot.interpretation ?? ''] ?? 'Interpretation not provided.'}</dd>
          <dt>Occurred</dt><dd>{displayTime(snapshot.occurredAt)}</dd>
          <dt>Observed</dt><dd>{displayTime(snapshot.observedAt)}</dd>
          <dt>Source</dt><dd>{snapshot.sourceType ?? 'Unknown'} / {snapshot.sourceIdentifier ?? 'Unknown'}</dd>
          <dt>Event ID</dt><dd>{snapshot.eventId}</dd>
        </dl>}
        <p>Suppression: {snapshot.suppressed === null ? 'Unknown' : snapshot.suppressed ? `Active${snapshot.suppressionReason ? ` (${snapshot.suppressionReason})` : ''}` : 'None reported'}</p>
        {snapshot.limitation && <p className="muted">{snapshot.limitation}</p>}
        {snapshot.eventId && <Button variant="danger" onClick={() => { setRevokeId(snapshot.eventId); setRevokeReason(''); setReasonError(false) }}>Revoke displayed event</Button>}
      </div>}
    </Card>
    <Card>
      <h2>Ingest trusted event</h2>
      <p className="muted">Use an actual provider event and its stable external ID. Do not fabricate delivery evidence.</p>
      <form onSubmit={submitEvent} className="space-y-3">
        <label className="label" htmlFor="event-email">Event email</label>
        <input className="input" id="event-email" type="email" autoComplete="off" required maxLength={255} value={eventEmail} onChange={event => setEventEmail(event.target.value)} />
        <label className="label" htmlFor="event-type">Event type</label>
        <select className="input" id="event-type" value={eventType} onChange={event => setEventType(event.target.value)}>
          <option value="HARD_BOUNCE">Hard bounce</option><option value="SOFT_BOUNCE">Soft bounce</option>
          <option value="DELIVERED">Delivered</option><option value="COMPLAINT">Complaint</option><option value="UNSUBSCRIBED">Unsubscribed</option>
        </select>
        <label className="label" htmlFor="external-event-id">Trusted external event ID</label>
        <input className="input" id="external-event-id" required maxLength={255} pattern="[A-Za-z0-9][A-Za-z0-9._:-]*" value={externalEventId} onChange={event => setExternalEventId(event.target.value)} />
        <label className="label" htmlFor="event-occurred-at">Occurred at (local time; sent as UTC)</label>
        <input className="input" id="event-occurred-at" type="datetime-local" required value={occurredAt} onChange={event => setOccurredAt(event.target.value)} />
        <label className="label" htmlFor="enhanced-code">Enhanced status code (optional)</label>
        <input className="input" id="enhanced-code" maxLength={32} pattern="[245][.][0-9]{1,3}[.][0-9]{1,3}" placeholder="5.1.1" value={enhancedStatusCode} onChange={event => setEnhancedStatusCode(event.target.value)} />
        <p className="muted">Only a recipient-specific hard bounce (5.1.1) causes bounce suppression. Other bounces may be uncertain.</p>
        <Button type="submit" disabled={ingest.isPending}>{ingest.isPending ? 'Ingesting…' : 'Ingest event'}</Button>
      </form>
      {ingest.isError && <ErrorState error={ingest.error} />}
      {ingested && <p role="status">Event ID: {ingested.id}. {ingested.reused ? 'Existing event reused.' : 'New event created.'}</p>}
    </Card>
    <ConfirmDialog open={revokeId !== null} title="Revoke delivery event?" busy={revoke.isPending}
      onClose={() => { setRevokeId(null); setReasonError(false); revoke.reset() }} onConfirm={confirmRevoke}>
      <p>Revocation is audited and also lifts suppressions caused by this event. It cannot be undone.</p>
      <label className="label" htmlFor="revoke-reason">Reason code</label>
      <input className="input" id="revoke-reason" required maxLength={64} pattern="[A-Z][A-Z0-9_]*" placeholder="INCORRECT_EVENT" value={revokeReason} onChange={event => { setRevokeReason(event.target.value); setReasonError(false) }} />
      {reasonError && <p role="alert">Use uppercase letters, digits or underscores; start with a letter.</p>}
      {revoke.isError && <ErrorState error={revoke.error} />}
    </ConfirmDialog>
  </div>
}
