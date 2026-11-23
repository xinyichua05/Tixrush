import { act, render } from '@testing-library/react'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cameFrom, useScrollMemory } from './useScrollMemory'

function Frame() {
  useScrollMemory()
  return <Outlet />
}

/** A fake window scroll: scrollTo moves it, like a page tall enough for any position. */
let scrollY = 0
const scrollTo = vi.fn((opts: ScrollToOptions) => { scrollY = opts.top ?? 0 })

/** You scroll the page yourself to y. */
async function userScrollsTo(y: number) {
  scrollY = y
  window.dispatchEvent(new Event('scroll'))
  await act(() => new Promise((r) => setTimeout(r, 50))) // let the once-per-frame note happen
}

function renderApp() {
  const router = createMemoryRouter([
    {
      path: '/', element: <Frame />, children: [
        { index: true, element: <p>events list</p> },
        { path: 'events/:id', element: <p>event page</p> },
      ],
    },
  ])
  render(<RouterProvider router={router} />)
  return router
}

describe('useScrollMemory', () => {
  beforeEach(() => {
    scrollY = 0
    scrollTo.mockClear()
    Object.defineProperty(window, 'scrollY', { configurable: true, get: () => scrollY })
    window.scrollTo = scrollTo as unknown as typeof window.scrollTo
    sessionStorage.clear()
  })
  afterEach(() => sessionStorage.clear())

  it('a clicked link opens the new page at the top', async () => {
    const router = renderApp()
    await userScrollsTo(1800) // far down the events list
    scrollTo.mockClear()

    await act(() => router.navigate('/events/7'))

    expect(scrollTo).toHaveBeenLastCalledWith({ top: 0, left: 0, behavior: 'instant' })
    expect(scrollY).toBe(0)
  })

  it('Back returns to exactly where you were, and Forward too', async () => {
    const router = renderApp()
    await userScrollsTo(1800)
    await act(() => router.navigate('/events/7'))
    await userScrollsTo(350) // read a bit of the event page

    await act(() => router.navigate(-1)) // Back
    expect(scrollTo).toHaveBeenLastCalledWith({ top: 1800, left: 0, behavior: 'instant' })
    expect(scrollY).toBe(1800)

    await act(() => router.navigate(1)) // Forward
    expect(scrollY).toBe(350)
  })

  it('remembers which page opened each page (for "← All events")', async () => {
    const router = renderApp()
    await act(() => router.navigate('/events/7'))
    expect(cameFrom(router.state.location.key)).toBe('/')
  })
})
