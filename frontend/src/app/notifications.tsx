import { createContext, useCallback, useContext, useEffect, useState, type PropsWithChildren } from 'react'

type Notice = { id: string; message: string; kind: 'success' | 'error' }
const NoticeContext = createContext<((message: string, kind?: Notice['kind']) => void) | null>(null)

function NoticeToast({ notice, dismiss }: { notice: Notice; dismiss: (id: string) => void }) {
  useEffect(() => {
    const timer = window.setTimeout(() => dismiss(notice.id), 5000)
    return () => window.clearTimeout(timer)
  }, [notice.id, dismiss])

  return <div className={`toast toast-${notice.kind}`} role={notice.kind === 'error' ? 'alert' : 'status'}>
    <span>{notice.message}</span><button type="button" aria-label="Dismiss notification" onClick={() => dismiss(notice.id)}>×</button>
  </div>
}

export function NoticeProvider({ children }: PropsWithChildren) {
  const [notices, setNotices] = useState<Notice[]>([])
  const dismiss = useCallback((id: string) => {
    setNotices(previous => previous.filter(notice => notice.id !== id))
  }, [])
  function notify(message: string, kind: Notice['kind'] = 'success') {
    setNotices(previous => [...previous, { id: crypto.randomUUID(), message, kind }].slice(-3))
  }
  return <NoticeContext.Provider value={notify}>
    {children}
    <div className="toast-stack" aria-label="Notifications">
      {notices.map(notice => <NoticeToast key={notice.id} notice={notice} dismiss={dismiss} />)}
    </div>
  </NoticeContext.Provider>
}

export function useNotice() {
  const notify = useContext(NoticeContext)
  if (!notify) throw new Error('NoticeProvider is required')
  return notify
}
