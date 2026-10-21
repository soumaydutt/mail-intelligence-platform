import { createContext, useContext, useState, type PropsWithChildren } from 'react'

type Notice = { id: string; message: string; kind: 'success' | 'error' }
const NoticeContext = createContext<((message: string, kind?: Notice['kind']) => void) | null>(null)

export function NoticeProvider({ children }: PropsWithChildren) {
  const [notices, setNotices] = useState<Notice[]>([])
  function notify(message: string, kind: Notice['kind'] = 'success') {
    setNotices(previous => [...previous, { id: crypto.randomUUID(), message, kind }].slice(-3))
  }
  return <NoticeContext.Provider value={notify}>
    {children}
    <div className="toast-stack" aria-label="Notifications">
      {notices.map(notice => <div key={notice.id} className={`toast toast-${notice.kind}`} role={notice.kind === 'error' ? 'alert' : 'status'}>
        <span>{notice.message}</span><button type="button" aria-label="Dismiss notification" onClick={() => setNotices(previous => previous.filter(item => item.id !== notice.id))}>×</button>
      </div>)}
    </div>
  </NoticeContext.Provider>
}

export function useNotice() {
  const notify = useContext(NoticeContext)
  if (!notify) throw new Error('NoticeProvider is required')
  return notify
}
