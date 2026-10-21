import { useEffect, useRef, type ButtonHTMLAttributes, type PropsWithChildren, type ReactNode } from 'react'
import { ApiError } from '../../lib/api/client'

export function Card({ children, className = '' }: PropsWithChildren<{ className?: string }>) {
  return <section className={`panel ${className}`}>{children}</section>
}

export function Button({ children, className = '', variant = 'primary', ...props }: PropsWithChildren<ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'danger' }>) {
  return <button className={`button button-${variant} ${className}`} {...props}>{children}</button>
}

export function Status({ value }: { value: string | null | undefined }) {
  const tone = value === 'COMPLETED' || value === 'FRESH' || value === 'DELIVERED' ? 'good'
    : value === 'FAILED' || value === 'INVALID' || value === 'CANCELLED' || value === 'UNAVAILABLE' ? 'bad' : 'neutral'
  return <span className={`tag tag-${tone}`}>{value ?? 'Unknown'}</span>
}

export function EmptyState({ title, children }: PropsWithChildren<{ title: string }>) {
  return <Card className="empty-state"><h2>{title}</h2><p className="muted">{children}</p></Card>
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const message = error instanceof ApiError ? error.message : 'This request could not be completed.'
  return <div className="error-state" role="alert"><strong>Request failed</strong><p>{message}</p>{onRetry && <Button variant="secondary" onClick={onRetry}>Try again</Button>}</div>
}

export function ConfirmDialog({ open, title, children, onConfirm, onClose, busy = false }: {
  open: boolean; title: string; children: ReactNode; onConfirm(): void; onClose(): void; busy?: boolean
}) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const dialog = ref.current
    if (open && dialog && !dialog.open) dialog.showModal()
    if (!open && dialog?.open) dialog.close()
  }, [open])
  return open ? <dialog ref={ref} className="confirm-dialog" onClose={onClose} onCancel={event => {
    if (busy) event.preventDefault()
    else onClose()
  }} aria-labelledby="confirm-heading">
    <h2 id="confirm-heading">{title}</h2><div className="muted">{children}</div>
    <div className="dialog-actions"><Button variant="secondary" onClick={onClose} disabled={busy}>Keep current state</Button><Button variant="danger" onClick={onConfirm} disabled={busy}>{busy ? 'Working…' : 'Confirm'}</Button></div>
  </dialog> : null
}
