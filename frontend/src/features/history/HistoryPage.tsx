import { useSession } from '../../lib/auth/session'
import { Card, EmptyState, Status } from '../../components/ui'

export default function HistoryPage() {
  const { validations } = useSession()
  return <div className="stack"><header><p className="eyebrow">Verification / History</p><h2 className="page-title">Checks from this session</h2><p className="lead">Historical database search is not exposed by the backend. These results exist only in browser memory and disappear on disconnect or reload.</p></header>
    {validations.length === 0 ? <EmptyState title="No session validations">Run an individual validation to view its result here.</EmptyState> : <Card><div className="table-scroll"><table><thead><tr><th scope="col">Address</th><th scope="col">Status</th><th scope="col">Domain</th><th scope="col">DNS</th></tr></thead><tbody>{validations.map((item, index) => <tr key={`${index}-${item.email}`}><td className="mono">{item.email}</td><td><Status value={item.status} /></td><td>{item.domain ?? '—'}</td><td>{item.mxRecordFound === true ? 'MX observed' : item.mxRecordFound === false ? 'No MX observed' : 'Unknown'}</td></tr>)}</tbody></table></div></Card>}
  </div>
}
