import { Component, type ReactNode } from 'react'
import { Button, Card } from '../components/ui'

export default class ErrorBoundary extends Component<{ children: ReactNode }, { broken: boolean }> {
  state = { broken: false }
  static getDerivedStateFromError() { return { broken: true } }
  render() {
    if (this.state.broken) return <Card><h2>The view could not load</h2><p className="muted">No operation was confirmed. Reload the page to try again.</p><Button onClick={() => window.location.reload()}>Reload dashboard</Button></Card>
    return this.props.children
  }
}
