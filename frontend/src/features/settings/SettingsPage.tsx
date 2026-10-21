import { useQuery } from '@tanstack/react-query'
import { useSession } from '../../lib/auth/session'
import { healthSchema } from '../../lib/api/contracts'
import { displayTime } from '../../lib/format'
import { Button, Card } from '../../components/ui'

export default function SettingsPage({ theme, toggle }: { theme: 'light' | 'dark'; toggle(): void }) {
  const { connected, disconnect, api } = useSession()
  const health = useQuery({ queryKey: ['health'], queryFn: ({ signal }) => api.json('/actuator/health', healthSchema, { signal, auth: false, timeoutMs: 5000 }) })
  return <div className="stack"><header><p className="eyebrow">Workspace / Settings</p><h2 className="page-title">Connection & privacy</h2><p className="lead">This single-principal developer dashboard is not a production user-identity system.</p></header><div className="grid"><Card><h2>API connection</h2><p>{health.isPending ? 'Checking backend…' : health.isError ? 'Unavailable' : health.data.status}</p><p className="muted small">Same-origin API via Vite proxy or trusted reverse proxy. No wildcard credential CORS.</p><Button variant="secondary" onClick={() => void health.refetch()}>Refresh health</Button></Card><Card><h2>Administrator session</h2><p>{connected ? 'Key held only in memory.' : 'Disconnected.'}</p><p className="muted small">Never placed in URLs, local storage or frontend build configuration. Closing the tab clears access.</p>{connected && <Button variant="danger" onClick={disconnect}>Disconnect & clear session</Button>}</Card><Card><h2>Appearance</h2><p className="muted">Theme preference is saved locally. Credential and results are not.</p><Button variant="secondary" onClick={toggle}>Switch to {theme === 'dark' ? 'light' : 'dark'} mode</Button></Card></div>
    <Card><h2>Data boundaries</h2><p className="muted">PostgreSQL owns durable jobs, events, suppressions and history. Valkey stores disposable DNS and quota state. The API has no global jobs, imports, delivery events or history listing endpoint.</p><p className="muted">Production needs per-user identity, owner-scoped access, retention, TLS and audited exports before broader exposure.</p><p className="small muted">Local time: {displayTime(new Date().toISOString())}</p></Card>
  </div>
}
