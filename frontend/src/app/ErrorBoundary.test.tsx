import { render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import ErrorBoundary from './ErrorBoundary'

function BrokenView(): never {
  throw new Error('secret-address@example.invalid')
}

afterEach(() => vi.restoreAllMocks())

it('contains a broken route without exposing its error and recovers on navigation', () => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
  const view = render(<ErrorBoundary key="before"><BrokenView /></ErrorBoundary>)
  expect(screen.getByRole('heading', { name: 'The view could not load' })).toBeInTheDocument()
  expect(view.container).not.toHaveTextContent('secret-address@example.invalid')
  view.rerender(<ErrorBoundary key="after"><p>Recovered route</p></ErrorBoundary>)
  expect(screen.getByText('Recovered route')).toBeInTheDocument()
})
