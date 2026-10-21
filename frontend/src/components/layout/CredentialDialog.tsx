import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useSession } from '../../lib/auth/session'
import { Button, ErrorState } from '../ui'

export default function CredentialDialog({ open, close }: { open: boolean; close(): void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [candidate, setCandidate] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)
  const active = useRef<AbortController | null>(null)
  const { connect } = useSession()
  useEffect(() => {
    if (open && !dialog.current?.open) dialog.current?.showModal()
    if (!open && dialog.current?.open) dialog.current.close()
  }, [open])
  function dismiss() {
    active.current?.abort()
    active.current = null
    setCandidate('')
    setError(null)
    setBusy(false)
    close()
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    const controller = new AbortController()
    active.current = controller
    setBusy(true)
    setError(null)
    try {
      await connect(candidate, controller.signal)
      if (!controller.signal.aborted) dismiss()
    } catch (cause) {
      if (!controller.signal.aborted) setError(cause)
    } finally {
      if (active.current === controller) { active.current = null; setBusy(false) }
    }
  }
  return <dialog ref={dialog} className="confirm-dialog" onClose={dismiss} aria-labelledby="credential-title"><h2 id="credential-title">Connect administrator key</h2><p className="muted small">Verified against an authenticated, bounded suppression-list request. Kept only in this tab's memory.</p>
    <form className="form" onSubmit={event => void submit(event)}><div className="field"><label className="label" htmlFor="admin-key">Administrative API key</label><input className="input" id="admin-key" type="password" autoComplete="off" value={candidate} maxLength={256} onChange={event => setCandidate(event.target.value)} required /></div>{error !== null && <ErrorState error={error} />}<div className="dialog-actions"><Button type="button" variant="secondary" onClick={dismiss}>Cancel</Button><Button type="submit" disabled={busy}>{busy ? 'Checking…' : 'Connect'}</Button></div></form>
  </dialog>
}
