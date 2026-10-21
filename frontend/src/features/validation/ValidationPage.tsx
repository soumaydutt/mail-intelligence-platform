import { useEffect, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button, Card, ErrorState } from '../../components/ui'
import { useSession } from '../../lib/auth/session'
import { validationSchema, type Validation } from '../../lib/api/contracts'
import ValidationResult from './ValidationResult'

const emailSchema = z.string().trim().min(1, 'Enter an email address.').max(255, 'Email must be 255 characters or fewer.')
  .regex(/^[^\s@]+@[^\s@]+\.[^\s@]+$/, 'Enter a valid email address.')

type FormValues = { email: string }

export default function ValidationPage() {
  const { api, connected, trackValidation } = useSession()
  const { register, handleSubmit, setError, clearErrors, formState: { errors } } = useForm<FormValues>({ defaultValues: { email: '' } })
  const [result, setResult] = useState<Validation | null>(null)
  const [error, setRequestError] = useState<unknown>(null)
  const [loading, setLoading] = useState(false)
  const active = useRef<AbortController | null>(null)

  useEffect(() => () => { active.current?.abort(); active.current = null }, [api])

  async function submit({ email }: FormValues) {
    active.current?.abort()
    active.current = null
    setResult(null)
    setRequestError(null)
    clearErrors('email')
    const parsed = emailSchema.safeParse(email)
    if (!parsed.success) {
      setLoading(false)
      setError('email', { type: 'validate', message: parsed.error.issues[0]?.message ?? 'Enter a valid email address.' })
      return
    }
    const controller = new AbortController()
    active.current = controller
    setLoading(true)
    try {
      const response = await api.json('/api/email/validate', validationSchema, {
        method: 'POST', body: { email: parsed.data }, signal: controller.signal, auth: connected,
      })
      if (!controller.signal.aborted) {
        setResult(response)
        trackValidation(response)
      }
    } catch (failure) {
      if (!controller.signal.aborted) setRequestError(failure)
    } finally {
      if (active.current === controller) {
        active.current = null
        setLoading(false)
      }
    }
  }

  return <div className="space-y-6">
    <header><h1>Validate an email</h1><p className="muted">Check syntax and DNS. A mail route does not verify a mailbox.</p></header>
    <Card>
      <form noValidate onSubmit={handleSubmit(submit)} className="space-y-4">
        <div>
          <label className="label" htmlFor="validation-email">Email address</label>
          <input id="validation-email" type="email" autoComplete="email" maxLength={255} className="input"
            aria-invalid={!!errors.email} aria-describedby={errors.email ? 'validation-email-error' : undefined}
            {...register('email', { onChange: () => {
              active.current?.abort()
              active.current = null
              setLoading(false)
              setResult(null)
              setRequestError(null)
            } })} />
          {errors.email && <p id="validation-email-error" role="alert">{errors.email.message}</p>}
        </div>
        <Button type="submit">{loading ? 'Check again' : 'Validate email'}</Button>
        {loading && <p role="status">Checking email…</p>}
      </form>
    </Card>
    {error !== null && <ErrorState error={error} />}
    {result && <ValidationResult result={result} />}
  </div>
}
