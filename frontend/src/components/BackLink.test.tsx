import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useScrollMemory } from '../lib/useScrollMemory'
import { BackLink } from './BackLink'

function Frame() {
  useScrollMemory()
  return <Outlet />
}

function renderAt(start: string) {
  const router = createMemoryRouter([
    {
      path: '/', element: <Frame />, children: [
        { index: true, element: <p>events list</p> },
        { path: 'events/:id', element: <BackLink to="/">All events</BackLink> },
      ],
    },
  ], { initialEntries: [start] })
  render(<RouterProvider router={router} />)
  return router
}

describe('BackLink', () => {
  beforeEach(() => {
    window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
    sessionStorage.clear()
  })

  it('came from the list: acts like Back (no new history entry)', async () => {
    const router = renderAt('/')
    await act(() => router.navigate('/events/7')) // clicked an event card
    const openedBy = router.state.historyAction // PUSH: a link opened the event page

    await userEvent.click(screen.getByRole('link', { name: /All events/ }))

    expect(openedBy).toBe('PUSH')
    expect(router.state.location.pathname).toBe('/')
    expect(router.state.historyAction).toBe('POP') // a real Back: the scroll memory restores the spot
    expect(screen.getByText('events list')).toBeInTheDocument()
  })

  it('opened directly (shared link, new tab): a normal link to the list', async () => {
    const router = renderAt('/events/7')
    await userEvent.click(screen.getByRole('link', { name: /All events/ }))

    expect(router.state.location.pathname).toBe('/')
    expect(router.state.historyAction).toBe('PUSH')
  })

  it('keeps a real href, so "open in new tab" works', () => {
    renderAt('/events/7')
    expect(screen.getByRole('link', { name: /All events/ })).toHaveAttribute('href', '/')
  })
})
