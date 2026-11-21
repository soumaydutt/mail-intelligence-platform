import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { NoticeProvider, useNotice } from './notifications'

function NoticeConsumer() {
  const notify = useNotice()
  return <>
    <button onClick={() => notify('First notice')}>Show first</button>
    <button onClick={() => notify('Second notice', 'error')}>Show second</button>
    <button onClick={() => notify('Third notice')}>Show third</button>
    <button onClick={() => notify('Fourth notice')}>Show fourth</button>
  </>
}

function mount() {
  return render(<StrictMode><NoticeProvider><NoticeConsumer /></NoticeProvider></StrictMode>)
}

beforeEach(() => vi.useFakeTimers())
afterEach(() => { cleanup(); vi.useRealTimers() })

describe('notifications', () => {
  it('expires success and error notices independently after five seconds without restarting earlier lifetimes', () => {
    mount()
    fireEvent.click(screen.getByRole('button', { name: 'Show first' }))
    expect(screen.getByRole('status')).toHaveTextContent('First notice')
    act(() => vi.advanceTimersByTime(2000))
    fireEvent.click(screen.getByRole('button', { name: 'Show second' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Second notice')

    act(() => vi.advanceTimersByTime(2999))
    expect(screen.getByRole('status')).toHaveTextContent('First notice')
    expect(screen.getByRole('alert')).toHaveTextContent('Second notice')
    act(() => vi.advanceTimersByTime(1))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Second notice')
    act(() => vi.advanceTimersByTime(1999))
    expect(screen.getByRole('alert')).toHaveTextContent('Second notice')
    act(() => vi.advanceTimersByTime(1))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('preserves manual dismissal and clears only the dismissed notice timer', () => {
    mount()
    fireEvent.click(screen.getByRole('button', { name: 'Show first' }))
    act(() => vi.advanceTimersByTime(2000))
    fireEvent.click(screen.getByRole('button', { name: 'Show second' }))
    expect(vi.getTimerCount()).toBe(2)
    fireEvent.click(within(screen.getByRole('status')).getByRole('button', { name: 'Dismiss notification' }))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Second notice')
    expect(vi.getTimerCount()).toBe(1)
    act(() => vi.advanceTimersByTime(4999))
    expect(screen.getByRole('alert')).toHaveTextContent('Second notice')
    act(() => vi.advanceTimersByTime(1))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('keeps only the newest three notices and clears the evicted notice timer', () => {
    mount()
    for (const name of ['Show first', 'Show second', 'Show third', 'Show fourth']) {
      fireEvent.click(screen.getByRole('button', { name }))
    }
    expect(screen.queryByText('First notice')).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Second notice')
    expect(screen.getAllByRole('button', { name: 'Dismiss notification' })).toHaveLength(3)
    expect(vi.getTimerCount()).toBe(3)
  })

  it('clears all pending notice timers when the provider unmounts', () => {
    const view = mount()
    fireEvent.click(screen.getByRole('button', { name: 'Show first' }))
    fireEvent.click(screen.getByRole('button', { name: 'Show second' }))
    expect(vi.getTimerCount()).toBe(2)
    view.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
})
