import { QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getEvent } from '../api/events'
import { cancelOrder, checkPayment, getOrder, startCheckout } from '../api/orders'
import { goTo } from '../lib/redirect'
import type { EventResponse, OrderResponse } from '../api/types'
import { testQueryClient } from '../test/setup'
import { CheckoutPage } from './CheckoutPage'

vi.mock('../api/orders', () => ({ getOrder: vi.fn(), payOrder: vi.fn(), cancelOrder: vi.fn(), startCheckout: vi.fn(), checkPayment: vi.fn() }))
vi.mock('../lib/redirect', () => ({ goTo: vi.fn() }))
vi.mock('../api/events', () => ({ getEvent: vi.fn() }))
const orderApi = vi.mocked(getOrder)
const cancelApi = vi.mocked(cancelOrder)

const RESERVATION = 'd1bdf8c0-d0bf-4583-aa07-a066114073b2'

function renderPage(path = `/orders/${RESERVATION}`) {
  render(
    <QueryClientProvider client={testQueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/orders/:reservationId" element={<CheckoutPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
/** Move the fake clock forward and let the pending fetches and re-renders finish. */
const wait = (ms: number) => act(() => vi.advanceTimersByTimeAsync(ms))

describe('CheckoutPage while the order is not in the database', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    orderApi.mockReset()
    orderApi.mockResolvedValue(null) // the API answers 404 = "not created yet"
    vi.mocked(getEvent).mockResolvedValue({ id: 87, name: 'Neon Monsoon', venue: 'Axiata Arena', startsAt: '2027-01-30T12:00:00Z', ticketTypes: [] } as unknown as EventResponse)
  })
  afterEach(() => vi.useRealTimers())

  it('waits up to a minute, then says the order could not be found', async () => {
    renderPage()
    await wait(0)
    expect(screen.getByText(/You got a seat!/)).toBeInTheDocument()

    await wait(30_000)
    expect(screen.getByText(/You got a seat!/)).toBeInTheDocument() // still patient

    await wait(31_000)
    expect(screen.getByRole('heading', { name: "We couldn't find this order" })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /My tickets/ })).toHaveAttribute('href', '/my-tickets')
    expect(screen.queryByText(/You got a seat!/)).not.toBeInTheDocument()
  })

  it('still switches to payment if the order turns up late', async () => {
    renderPage()
    await wait(61_000)
    expect(screen.getByText("We couldn't find this order")).toBeInTheDocument()

    const late: OrderResponse = {
      orderId: 1, reservationId: RESERVATION, eventId: 87, status: 'PENDING_PAYMENT', strategy: 'queue',
      expiresAt: new Date(Date.now() + 9 * 60_000).toISOString(),
      ticketTypeId: null, quantity: 1, unitPrice: null, seats: [],
    } as unknown as OrderResponse
    orderApi.mockResolvedValue(late)
    await wait(5_000) // the slow check after giving up

    expect(screen.getByRole('button', { name: /Pay now/ })).toBeInTheDocument()
  })
})

describe('CheckoutPage: cancelling an unpaid order', () => {
  const pending: OrderResponse = {
    orderId: 1, reservationId: RESERVATION, eventId: 87, status: 'PENDING_PAYMENT', strategy: 'queue',
    expiresAt: new Date(Date.now() + 9 * 60_000).toISOString(),
    ticketTypeId: 3, quantity: 2, unitPrice: 988, seats: [{ row: 'A', number: 20 }, { row: 'A', number: 21 }],
    payWith: 'TEST', refunded: false,
  }

  beforeEach(() => {
    orderApi.mockReset()
    cancelApi.mockReset()
    orderApi.mockResolvedValue(pending)
    vi.mocked(getEvent).mockResolvedValue({ id: 87, name: 'Neon Monsoon', venue: 'Axiata Arena', startsAt: '2027-01-30T12:00:00Z', ticketTypes: [] } as unknown as EventResponse)
  })

  it('asks first; "Keep my seats" changes nothing', async () => {
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel order' }))
    expect(screen.getByRole('dialog', { name: 'Cancel this order?' })).toHaveTextContent(/Your seats go back on sale right away/)

    await userEvent.click(screen.getByRole('button', { name: 'Keep my seats' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(cancelApi).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: /Pay now/ })).toBeInTheDocument()
  })

  it('"Yes, cancel" gives the seats back and shows that the order is cancelled', async () => {
    cancelApi.mockResolvedValue({ ...pending, status: 'CANCELLED', seats: [] })
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel order' }))
    await userEvent.click(screen.getByRole('button', { name: /Yes, cancel/ }))

    expect(cancelApi).toHaveBeenCalledWith(RESERVATION)
    expect(await screen.findByRole('heading', { name: 'Order cancelled' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Pay now/ })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Choose again' })).toHaveAttribute('href', '/events/87')
  })
})

describe('CheckoutPage: real payment with Stripe (test mode)', () => {
  const stripeOrder: OrderResponse = {
    orderId: 2, reservationId: RESERVATION, eventId: 87, status: 'PENDING_PAYMENT', strategy: 'queue',
    expiresAt: new Date(Date.now() + 9 * 60_000).toISOString(), ticketTypeId: 3, quantity: 2, unitPrice: 688,
    seats: [], payWith: 'STRIPE', refunded: false,
  }

  beforeEach(() => {
    orderApi.mockReset()
    vi.mocked(checkPayment).mockReset()
    vi.mocked(startCheckout).mockReset()
    vi.mocked(goTo).mockReset()
    vi.mocked(getEvent).mockResolvedValue({ id: 87, name: 'Neon Monsoon', venue: 'Axiata Arena', startsAt: '2027-01-30T12:00:00Z', ticketTypes: [] } as unknown as EventResponse)
  })

  it('Pay sends you to the Stripe payment page, with the test card shown', async () => {
    orderApi.mockResolvedValue(stripeOrder)
    vi.mocked(startCheckout).mockResolvedValue({ url: 'https://checkout.stripe.com/c/pay/cs_test_1' })
    renderPage()

    const pay = await screen.findByRole('button', { name: /Pay RM 1,376 with Stripe/ })
    expect(screen.getByText(/4242 4242 4242 4242/)).toBeInTheDocument()
    await userEvent.click(pay)

    expect(startCheckout).toHaveBeenCalledWith(RESERVATION)
    expect(goTo).toHaveBeenCalledWith('https://checkout.stripe.com/c/pay/cs_test_1')
    expect(screen.getByRole('button', { name: /Opening Stripe/ })).toBeDisabled() // no second click
  })

  it('back from Stripe (?paid=1): asks the backend to check with Stripe, then shows the ticket', async () => {
    vi.mocked(checkPayment).mockResolvedValue({ ...stripeOrder, status: 'PAID' })
    renderPage(`/orders/${RESERVATION}?paid=1`)

    expect(await screen.findByRole('heading', { name: "You're going!" })).toBeInTheDocument()
    expect(checkPayment).toHaveBeenCalledWith(RESERVATION)
    expect(orderApi).not.toHaveBeenCalled()
  })

  it('money that arrived too late: says it was refunded', async () => {
    orderApi.mockResolvedValue({ ...stripeOrder, status: 'EXPIRED', refunded: true })
    renderPage()
    expect(await screen.findByText(/We refunded you in full/)).toBeInTheDocument()
  })
})
