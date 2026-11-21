import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import { useSession } from '../../lib/auth/session'
import { historySchema } from '../../lib/api/contracts'
import { displayTime } from '../../lib/format'
import { Button, Card, EmptyState, ErrorState, Status } from '../../components/ui'

const pageSize = 50

export default function HistoryPage() {
  const { api, connected, validations } = useSession()
  const [after, setAfter] = useState<number | null>(null)
  const [previous, setPrevious] = useState<(number | null)[]>([])
  const history = useQuery({
    queryKey: ['history', api, after],
    queryFn: ({ signal }) => api.json(`/api/email/history?limit=${pageSize}${after === null ? '' : `&after=${after}`}`,
      z.array(historySchema), { signal }),
    enabled: connected, retry: false,
  })
  const rows = history.data ?? []
  return <div className="stack">
    <header><p className="eyebrow">Verification / History</p><h2 className="page-title">Validation history</h2>
      <p className="lead">PostgreSQL history is shared by every holder of the administrator key. No owner-scoped access, search or total count is available.</p></header>
    {!connected ? <EmptyState title="Administrator access required">Connect an administrator key to view persisted history.</EmptyState> : <Card>
      <h2>Persisted validations</h2>
      {history.isPending && <p role="status">Loading history…</p>}
      {history.isError && <ErrorState error={history.error} onRetry={() => void history.refetch()} />}
      {history.isSuccess && (rows.length === 0 ? <p>No history records on this page.</p> : <div className="table-scroll"><table>
        <thead><tr><th scope="col">ID</th><th scope="col">Checked</th><th scope="col">Address</th><th scope="col">Status</th><th scope="col">Message</th><th scope="col">Format valid</th><th scope="col">Domain</th><th scope="col">MX</th><th scope="col">SMTP</th></tr></thead>
        <tbody>{rows.map(row => <tr key={row.id}><th scope="row">{row.id}</th><td>{displayTime(row.createdAt)}</td>
          <td className="mono">{row.email ?? 'Not recorded'}</td><td><Status value={row.status} /></td><td>{row.message ?? 'Not recorded'}</td>
          <td>{row.formatValid === null ? 'Unknown' : row.formatValid ? 'Yes' : 'No'}</td><td>{row.domain ?? 'Unknown'}</td>
          <td>{row.mxRecordFound === null ? 'Unknown' : row.mxRecordFound ? 'Observed' : 'Not observed'}</td>
          <td>{row.smtpVerified === null ? 'Not checked' : row.smtpVerified ? 'Yes' : 'No'}</td></tr>)}</tbody>
      </table></div>)}
      <div className="row-actions">
        <Button variant="secondary" disabled={!previous.length || history.isFetching} onClick={() => {
          setAfter(previous.at(-1) ?? null)
          setPrevious(items => items.slice(0, -1))
        }}>Previous page</Button>
        <Button variant="secondary" disabled={!history.isSuccess || rows.length < pageSize || history.isFetching} onClick={() => {
          setPrevious(items => [...items, after])
          setAfter(rows.at(-1)!.id)
        }}>Next page</Button>
      </div>
    </Card>}
    <Card><h2>Checks from this browser session</h2><p className="muted">These recent results are held in memory and disappear on disconnect or reload.</p>
      {validations.length === 0 ? <p>No session validations.</p> : <div className="table-scroll"><table>
        <thead><tr><th scope="col">Address</th><th scope="col">Status</th><th scope="col">Domain</th><th scope="col">DNS</th></tr></thead>
        <tbody>{validations.map((item, index) => <tr key={`${index}-${item.email}`}><td className="mono">{item.email}</td><td><Status value={item.status} /></td><td>{item.domain ?? '—'}</td><td>{item.mxRecordFound === true ? 'MX observed' : item.mxRecordFound === false ? 'No MX observed' : 'Unknown'}</td></tr>)}</tbody>
      </table></div>}
    </Card>
  </div>
}
