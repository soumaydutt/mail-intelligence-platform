import { createContext, useContext, useMemo, useState, type PropsWithChildren } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'
import { createApi, type Api } from '../api/client'
import { suppressionSchema, type Validation } from '../api/contracts'

interface Session {
  connected: boolean
  connect(candidate: string, signal?: AbortSignal): Promise<void>
  disconnect(): void
  trackValidation(value: Validation): void
  trackJob(id: string): void
  trackImport(id: string): void
  validations: Validation[]
  jobs: string[]
  imports: string[]
  api: Api
}

const SessionContext = createContext<Session | null>(null)
const recentIdLimit = 50

export function SessionProvider({ children }: PropsWithChildren) {
  const cache = useQueryClient()
  const [key, setKey] = useState<string | null>(null)
  const [validations, setValidations] = useState<Validation[]>([])
  const [jobs, setJobs] = useState<string[]>([])
  const [imports, setImports] = useState<string[]>([])
  const api = useMemo(() => createApi(key), [key])

  async function connect(candidate: string, signal?: AbortSignal) {
    if (!candidate || candidate.length > 256) throw new Error('Enter a valid administrator key (up to 256 characters).')
    await createApi(candidate).json('/api/suppressions?limit=1', z.array(suppressionSchema), { timeoutMs: 8000, signal })
    if (signal?.aborted) return
    cache.clear()
    setValidations([])
    setJobs([])
    setImports([])
    setKey(candidate)
  }

  function disconnect() {
    cache.clear()
    setKey(null)
    setValidations([])
    setJobs([])
    setImports([])
  }

  const value: Session = {
    connected: key !== null, connect, disconnect, validations, jobs, imports, api,
    trackValidation: value => setValidations(previous => [value, ...previous].slice(0, 10)),
    trackJob: id => setJobs(previous => [id, ...previous.filter(other => other !== id)].slice(0, recentIdLimit)),
    trackImport: id => setImports(previous => [id, ...previous.filter(other => other !== id)].slice(0, recentIdLimit)),
  }
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession(): Session {
  const value = useContext(SessionContext)
  if (!value) throw new Error('SessionProvider is required')
  return value
}
