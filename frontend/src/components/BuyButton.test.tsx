import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { buyTicket } from '../api/orders'
import { tokenStore } from '../auth/tokenStore'
import { fakeToken } from '../test/setup'
import { BuyButton } from './BuyButton'

// Replace the real API call with a fake we control in each test.
vi.mock('../api/orders', () => ({ buyTicket: vi.fn() }))
const buy = vi.mocked(buyTicket)

function renderOnEventPage(soldOut = false) {
  render(
    <MemoryRouter initialEntries={['/events/1']}>
      <Routes>
        <Route path="/events/:id" element={<BuyButton eventId={1} soldOut={soldOut} />} />
        <Route path="/orders/:reservationId" element={<p>checkout page</p>} />
        <Route path="/login" element={<p>login page</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('BuyButton', () => {
  beforeEach(() => {
    buy.mockReset()
    tokenStore.set(fakeToken())
  })

  it('asks you to log in first', () => {
    tokenStore.clear()
    renderOnEventPage()
    expect(screen.getByRole('link', { name: 'Log in to buy' })).toBeInTheDocument()
  })

  it('202 -> goes to the checkout page', async () => {
    buy.mockResolvedValue({ orderId: null, reservationId: 'abc-123', eventId: 1, status: 'QUEUED', strategy: 'queue', expiresAt: '2030-01-01T00:00:00Z', ticketTypeId: null, quantity: 1, unitPrice: null, seats: [], payWith: 'TEST', refunded: false })
    renderOnEventPage()

    await userEvent.click(screen.getByRole('button', { name: 'Buy ticket' }))

    expect(await screen.findByText('checkout page')).toBeInTheDocument()
    expect(buy).toHaveBeenCalledWith(1, expect.any(String))
  })

  it('409 -> shows sold out and disables the button', async () => {
    buy.mockRejectedValue(new ApiError(409, 'Event 1 is sold out'))
    renderOnEventPage()

    await userEvent.click(screen.getByRole('button', { name: 'Buy ticket' }))

    expect(await screen.findByRole('button', { name: 'Sold out' })).toBeDisabled()
  })

  it('429 -> waits for the Retry-After countdown', async () => {
    buy.mockRejectedValue(new ApiError(429, 'Too many attempts', {}, 30))
    renderOnEventPage()

    await userEvent.click(screen.getByRole('button', { name: 'Buy ticket' }))

    expect(await screen.findByRole('button', { name: /Try again in 30s/ })).toBeDisabled()
  })

  it('makes a NEW idempotency key for every separate click', async () => {
    buy.mockRejectedValue(new ApiError(503, 'busy'))
    renderOnEventPage()

    await userEvent.click(screen.getByRole('button', { name: 'Buy ticket' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Try again' }))

    expect(buy).toHaveBeenCalledTimes(2)
    expect(buy.mock.calls[0][1]).not.toEqual(buy.mock.calls[1][1])
  })

  it('is disabled while the purchase is in flight (no double submit)', async () => {
    let finish: (v: never) => void = () => {}
    buy.mockReturnValue(new Promise((resolve) => (finish = resolve)))
    renderOnEventPage()

    await userEvent.click(screen.getByRole('button', { name: 'Buy ticket' }))

    expect(screen.getByRole('button', { name: 'Getting your ticket…' })).toBeDisabled()
    finish(undefined as never)
  })
})
