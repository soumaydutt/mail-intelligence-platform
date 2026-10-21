import { Card, Status } from '../../components/ui'
import { displayTime } from '../../lib/format'
import type { Validation } from '../../lib/api/contracts'

function verdict(value: boolean | null): string {
  return value === true ? 'Yes' : value === false ? 'No' : 'Unknown / not checked'
}

export default function ValidationResult({ result }: { result: Validation }) {
  const evidence = result.historicalEvidence
  return <Card>
    <h2>Validation result</h2>
    <p className="break-all">{result.email}</p>
    <p><Status value={result.status} /> {result.message}</p>
    <dl className="grid gap-3 sm:grid-cols-2">
      <div><dt>Format valid</dt><dd>{verdict(result.formatValid)}</dd></div>
      <div><dt>Domain</dt><dd className="break-all">{result.domain ?? 'Unknown'}</dd></div>
      <div><dt>MX record found</dt><dd>{verdict(result.mxRecordFound)}</dd></div>
      <div><dt>SMTP verified</dt><dd>{verdict(result.smtpVerified)}</dd></div>
    </dl>
    {result.status === 'UNVERIFIED' && <p className="muted">UNVERIFIED means mailbox ownership and deliverability were not established. MX alone is not proof.</p>}
    {result.status === 'INVALID' && <p className="muted">INVALID means the backend rejected the address format.</p>}
    {evidence && <section aria-label="Historical delivery evidence" className="mt-4 border-t pt-4">
      <h3>Historical delivery evidence</h3>
      <p>Freshness: <Status value={evidence.freshness} /></p>
      {evidence.available ? <dl className="grid gap-3 sm:grid-cols-2">
        <div><dt>Event</dt><dd>{evidence.eventType ?? 'Unknown'}</dd></div>
        <div><dt>Interpretation</dt><dd>{evidence.interpretation ?? 'Unknown'}</dd></div>
        <div><dt>Occurred</dt><dd>{displayTime(evidence.occurredAt)}</dd></div>
        <div><dt>Observed</dt><dd>{displayTime(evidence.observedAt)}</dd></div>
        <div><dt>Source</dt><dd>{evidence.sourceType ?? 'Unknown'}</dd></div>
        <div><dt>Suppressed</dt><dd>{verdict(evidence.suppressed)}</dd></div>
        {evidence.suppressionReason && <div><dt>Suppression reason</dt><dd>{evidence.suppressionReason}</dd></div>}
      </dl> : <p>Historical evidence not available.</p>}
      {evidence.limitation && <p className="muted">{evidence.limitation}</p>}
      <p className="muted">Historical events do not establish current mailbox reachability.</p>
    </section>}
  </Card>
}
