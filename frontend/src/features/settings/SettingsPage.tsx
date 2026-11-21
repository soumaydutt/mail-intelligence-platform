import { useQuery } from '@tanstack/react-query'
import { useSession } from '../../lib/auth/session'
import { healthSchema } from '../../lib/api/contracts'
import { displayTime } from '../../lib/format'
import { Button, Card } from '../../components/ui'

export default function SettingsPage({ theme, toggle }: { theme: 'light' | 'dark'; toggle(): void }) {
  const { connected, disconnect, api } = useSession()
  const health = useQuery({ queryKey: ['health'], queryFn: ({ signal }) => api.json('/actuator/health', healthSchema, { signal, auth: false, timeoutMs: 5000 }) })
  return <div className="stack"><header><p className="eyebrow">Workspace / Settings</p><h2 className="page-title">Connection & privacy</h2><p className="lead">This shared-key console has no per-user identity or owner-scoped access.</p></header><div className="grid"><Card><h2>API connection</h2><p>{health.isPending ? 'Checking backend…' : health.isError ? 'Unavailable' : health.data.status}</p><p className="muted small">Same-origin API via Vite proxy or trusted reverse proxy. No wildcard credential CORS.</p><Button variant="secondary" onClick={() => void health.refetch()}>Refresh health</Button></Card><Card><h2>Administrator session</h2><p>{connected ? 'Key held only in memory.' : 'Disconnected.'}</p><p className="muted small">Never placed in URLs, local storage or frontend build configuration. Closing the tab clears access.</p>{connected && <Button variant="danger" onClick={disconnect}>Disconnect & clear session</Button>}</Card><Card><h2>Appearance</h2><p className="muted">Theme preference is saved locally. Credential and browser-session results are not saved in browser storage.</p><Button variant="secondary" onClick={toggle}>Switch to {theme === 'dark' ? 'light' : 'dark'} mode</Button></Card></div>
    <Card><h2>Data boundaries</h2><p className="muted">PostgreSQL owns durable jobs, events, suppressions and history. Valkey stores disposable DNS and quota state. Administrative history pages expose all recorded validations to key holders. No global jobs, imports or delivery events listing is available.</p><p className="muted">Broader exposure needs per-user identity, owner-scoped access, approved retention and audited exports. This deployment uses TLS only on the local proxy.</p><p className="small muted">Local time: {displayTime(new Date().toISOString())}</p></Card>
  </div>
}
