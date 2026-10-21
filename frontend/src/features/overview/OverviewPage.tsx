import { Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { useSession } from '../../lib/auth/session'
import { healthSchema } from '../../lib/api/contracts'
import { Card, ErrorState, Status } from '../../components/ui'

export default function Overview() {
  const { validations, imports, jobs, api, connected } = useSession()
  const health = useQuery({ queryKey: ['health'], queryFn: ({ signal }) => api.json('/actuator/health', healthSchema, { signal, auth: false, timeoutMs: 5000 }), refetchInterval: () => document.hidden ? false : 30000 })
  return <div className="stack">
    <header><p className="eyebrow">Operations / Overview</p><h2 className="page-title">Clarity for every address.</h2><p className="lead">Live connectivity and work from this browser session. No global counts are inferred from limited API responses.</p></header>
    <div className="grid">
      <Card><div className="caption">Backend connection</div><h2>{health.isPending ? 'Checking…' : health.isError ? 'Unavailable' : health.data.status === 'UP' ? 'Connected' : health.data.status}</h2><p className="muted small">Health only; administrative admission may fail independently.</p>{health.isError && <ErrorState error={health.error} onRetry={() => void health.refetch()} />}</Card>
      <Card><div className="caption">Administrator</div><h2>{connected ? 'Connected' : 'Disconnected'}</h2><p className="muted small">{connected ? 'Single shared development key. Disconnect when finished.' : 'Connect to access imports, jobs, delivery evidence and suppressions.'}</p></Card>
      <Card><div className="caption">Evidence model</div><h2>Conservative by design</h2><p className="muted small">An MX record is routing evidence, not proof a mailbox exists. SMTP is disabled on the API.</p></Card>
    </div>
    <div className="split">
      <Card><h2>Recent validations in this session</h2>{validations.length === 0 ? <p className="muted">No checks in this browser session yet. <Link className="text-teal-700 underline dark:text-teal-300" to="/validate">Validate an address</Link>.</p> : <ul className="list">{validations.map((item, index) => <li key={`${index}-${item.email}`}><div className="row-actions" style={{ justifyContent: 'space-between', padding: '.7rem 0' }}><span className="mono small">{item.email}</span><Status value={item.status} /></div></li>)}</ul>}</Card>
      <Card><h2>Session work</h2>{jobs.length === 0 && imports.length === 0 ? <p className="muted">No jobs or imports opened this session. The backend does not expose a global list.</p> : <ul className="list">{imports.map(id => <li key={id}><Link to={`/bulk/${id}`}><span>Import <span className="mono small">{id.slice(0, 8)}</span></span><span>View →</span></Link></li>)}{jobs.map(id => <li key={id}><Link to={`/jobs/${id}`}><span>Job <span className="mono small">{id.slice(0, 8)}</span></span><span>View →</span></Link></li>)}</ul>}</Card>
    </div>
  </div>
}
