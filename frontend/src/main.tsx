import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SessionProvider } from './lib/auth/session'
import { NoticeProvider } from './app/notifications'
import App from './app/App'
import './styles.css'

const client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false, staleTime: 5000 }, mutations: { retry: false } } })

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={client}>
      <SessionProvider><NoticeProvider><BrowserRouter><App /></BrowserRouter></NoticeProvider></SessionProvider>
    </QueryClientProvider>
  </React.StrictMode>,
)
