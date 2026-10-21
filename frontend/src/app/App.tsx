import { lazy, Suspense, useEffect, useState } from 'react'
import { NavLink, Link, Route, Routes, useLocation } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { LayoutDashboard, MailCheck, Upload, BriefcaseBusiness, History, ScanSearch, ShieldCheck, Settings, PanelLeft, Moon, Sun, Activity } from 'lucide-react'
import { useSession } from '../lib/auth/session'
import { healthSchema } from '../lib/api/contracts'
import { Button, EmptyState } from '../components/ui'
import CredentialDialog from '../components/layout/CredentialDialog'
import ErrorBoundary from './ErrorBoundary'
import Overview from '../features/overview/OverviewPage'
import HistoryPage from '../features/history/HistoryPage'
import SettingsPage from '../features/settings/SettingsPage'

const ValidationPage = lazy(() => import('../features/validation/ValidationPage'))
const BulkPage = lazy(() => import('../features/bulk/BulkPage'))
const JobsPage = lazy(() => import('../features/jobs/JobsPage'))
const EvidencePage = lazy(() => import('../features/evidence/EvidencePage'))
const SuppressionsPage = lazy(() => import('../features/suppressions/SuppressionsPage'))

const links = [
  { to: '/', text: 'Overview', icon: LayoutDashboard }, { to: '/validate', text: 'Validate email', icon: MailCheck },
  { to: '/bulk', text: 'Bulk validation', icon: Upload }, { to: '/jobs', text: 'Jobs', icon: BriefcaseBusiness },
  { to: '/history', text: 'Session history', icon: History }, { to: '/evidence', text: 'Delivery evidence', icon: ScanSearch },
  { to: '/suppressions', text: 'Suppressions', icon: ShieldCheck }, { to: '/settings', text: 'Settings', icon: Settings },
] as const


export default function App() {
  const location = useLocation()
  const [navOpen, setNavOpen] = useState(false)
  const [authOpen, setAuthOpen] = useState(false)
  const [theme, setTheme] = useState<'light' | 'dark'>(() => {
    try { return localStorage.getItem('mailguard-theme') === 'dark' ? 'dark' : 'light' }
    catch { return 'light' }
  })
  const { connected, disconnect, api } = useSession()
  const health = useQuery({ queryKey: ['health'], queryFn: ({ signal }) => api.json('/actuator/health', healthSchema, { signal, auth: false, timeoutMs: 5000 }), refetchInterval: () => document.hidden ? false : 30000 })
  useEffect(() => {
    document.documentElement.dataset.theme = theme
    try { localStorage.setItem('mailguard-theme', theme) }
    catch { /* Theme still works in memory when browser storage is blocked. */ }
  }, [theme])
  useEffect(() => {
    if (!navOpen) return
    document.querySelector<HTMLElement>('#primary-navigation .nav-link')?.focus()
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setNavOpen(false)
        requestAnimationFrame(() => document.querySelector<HTMLElement>('.menu-button')?.focus())
      }
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [navOpen])
  const page = links.find(link => link.to === location.pathname || link.to !== '/' && location.pathname.startsWith(link.to + '/'))?.text || 'Not found'
  return <><a className="skip-link" href="#main">Skip to content</a><div className="app"><button className={`mobile-scrim ${navOpen ? 'open' : ''}`} type="button" aria-label="Close navigation" tabIndex={navOpen ? 0 : -1} onClick={() => { setNavOpen(false); requestAnimationFrame(() => document.querySelector<HTMLElement>('.menu-button')?.focus()) }} />
    <aside id="primary-navigation" className={`sidebar ${navOpen ? 'open' : ''}`} aria-label="Primary navigation"><Link to="/" className="brand"><span className="brand-mark" aria-hidden="true">M</span><span>MailGuard<span className="brand-sub">Operations console</span></span></Link>
      <nav aria-label="Main"><p className="nav-group">Workspace</p><div className="nav-list">{links.map(({ to, text, icon: Icon }) => <NavLink key={to} to={to} end={to === '/'} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`} onClick={() => { setNavOpen(false); requestAnimationFrame(() => document.getElementById('main')?.focus()) }}><Icon size={17} aria-hidden="true" /><span>{text}</span></NavLink>)}</div></nav>
      <div className="sidebar-foot"><Activity size={15} aria-hidden="true" /> Evidence, not guarantees<br />PostgreSQL durable · Valkey disposable</div></aside>
    <div className="workspace" inert={navOpen}><header className="topbar"><div className="row-actions"><Button variant="secondary" className="menu-button" aria-label="Open navigation" aria-expanded={navOpen} aria-controls="primary-navigation" onClick={() => setNavOpen(true)}><PanelLeft size={18} /></Button><h1>{page}</h1></div><div className="top-actions"><span className="health" role="status"><span className={`health-dot ${health.data?.status === 'UP' ? '' : 'off'}`} aria-hidden="true" /><span className="health-label">{health.isPending ? 'Checking API' : health.data?.status === 'UP' ? 'API online' : 'API unavailable'}</span></span><Button variant="secondary" aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`} onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}>{theme === 'dark' ? <Sun size={17} /> : <Moon size={17} />}</Button>{connected ? <Button variant="secondary" onClick={disconnect}>Disconnect</Button> : <Button onClick={() => setAuthOpen(true)}>Connect</Button>}</div></header>
      <main id="main" className="content" tabIndex={-1}><ErrorBoundary key={location.pathname}><Suspense fallback={<div role="status" className="panel">Loading workspace…</div>}><Routes><Route path="/" element={<Overview />} /><Route path="/validate" element={<ValidationPage />} /><Route path="/bulk/:id?" element={<BulkPage />} /><Route path="/jobs/:id?" element={<JobsPage />} /><Route path="/history" element={<HistoryPage />} /><Route path="/evidence" element={<EvidencePage />} /><Route path="/suppressions" element={<SuppressionsPage />} /><Route path="/settings" element={<SettingsPage theme={theme} toggle={() => setTheme(theme === 'dark' ? 'light' : 'dark')} />} /><Route path="*" element={<EmptyState title="Page not found"><Link to="/">Return to overview</Link>.</EmptyState>} /></Routes></Suspense></ErrorBoundary></main>
    </div></div><CredentialDialog open={authOpen} close={() => setAuthOpen(false)} /></>
}
