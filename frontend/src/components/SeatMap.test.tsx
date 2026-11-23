import { QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { buyTicket } from '../api/orders'
import { getSeatLayout, getTakenSeats } from '../api/seats'
import type { EventResponse, SeatMapLayout, TicketType } from '../api/types'
import { tokenStore } from '../auth/tokenStore'
import { fakeToken, testQueryClient } from '../test/setup'
import { SeatMap } from './SeatMap'

vi.mock('../api/orders', () => ({ buyTicket: vi.fn() }))
vi.mock('../api/seats', () => ({ getSeatLayout: vi.fn(), getTakenSeats: vi.fn() }))
const buy = vi.mocked(buyTicket)
const layoutApi = vi.mocked(getSeatLayout)
const takenApi = vi.mocked(getTakenSeats)

// Rows A and B, 6 seats each. Seat ids: A1..A6 = 1..6, B1..B6 = 11..16.
const layout: SeatMapLayout = {
  ticketTypeId: 2, name: 'CAT 1',
  rows: ['A', 'B'].map((label, r) => ({ label, seats: [1, 2, 3, 4, 5, 6].map((n) => ({ id: r * 10 + n, number: n })) })),
}
const cat1: TicketType = { id: 2, name: 'CAT 1', kind: 'SEATED', color: '#2563eb', price: 688, totalTickets: 12, availableTickets: 10, seatRows: 2, seatsPerRow: 6 }
const event = { id: 7, name: 'Neon Monsoon', venue: 'Axiata Arena', ticketTypes: [cat1] } as unknown as EventResponse

function renderMap() {
  render(
    <QueryClientProvider client={testQueryClient()}>
      <MemoryRouter initialEntries={['/events/7']}>
        <Routes>
          <Route path="/events/:id" element={<SeatMap event={event} type={cat1} />} />
          <Route path="/orders/:reservationId" element={<p>checkout page</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
const seat = (row: string, n: number) => screen.getByRole('button', { name: new RegExp(`^Row ${row}, seat ${n},`) })

describe('SeatMap', () => {
  beforeEach(() => {
    buy.mockReset()
    layoutApi.mockResolvedValue(layout)
    takenApi.mockResolvedValue([3, 4]) // A3 and A4 are taken
    tokenStore.set(fakeToken())
  })

  it('shows every seat; taken ones are grey and cannot be clicked', async () => {
    renderMap()
    await waitFor(() => expect(seat('A', 3)).toBeDisabled())
    expect(seat('A', 3)).toHaveAccessibleName('Row A, seat 3, taken')
    expect(seat('A', 1)).toBeEnabled()
    expect(screen.getAllByRole('button', { name: /^Row [AB], seat/ })).toHaveLength(12)
    expect(screen.getByRole('button', { name: 'Choose seats to continue' })).toBeDisabled()
  })

  it('pick seats: the footer shows them and the total; Continue sends exactly those seats', async () => {
    buy.mockResolvedValue({ orderId: null, reservationId: 'r1', eventId: 7, status: 'QUEUED', strategy: 'queue', expiresAt: null, ticketTypeId: 2, quantity: 2, unitPrice: null, seats: [], payWith: 'TEST', refunded: false })
    renderMap()
    await waitFor(() => expect(seat('A', 3)).toBeDisabled())
    await userEvent.click(seat('B', 2))
    await userEvent.click(seat('B', 1))
    expect(seat('B', 2)).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('CAT 1 · Row B · Seats 1, 2')).toBeInTheDocument()
    expect(screen.getByText('RM 1,376')).toBeInTheDocument()

    await userEvent.click(seat('B', 2)) // click again = unpick
    expect(seat('B', 2)).toHaveAttribute('aria-pressed', 'false')
    await userEvent.click(seat('B', 2))

    await userEvent.click(screen.getByRole('button', { name: 'Continue to payment' }))
    expect(await screen.findByText('checkout page')).toBeInTheDocument()
    expect(buy).toHaveBeenCalledWith(7, expect.any(String), { ticketTypeId: 2, quantity: 2, seatIds: [11, 12] })
  })

  it('two very fast clicks (before the screen redraws) both count', async () => {
    renderMap()
    await waitFor(() => expect(seat('A', 3)).toBeDisabled())
    act(() => {
      seat('B', 1).click()
      seat('B', 2).click() // same moment: no redraw in between
    })
    expect(screen.getByText('CAT 1 · Row B · Seats 1, 2')).toBeInTheDocument()
  })

  it('no more than 4 seats', async () => {
    renderMap()
    await waitFor(() => expect(seat('A', 3)).toBeDisabled())
    for (const n of [1, 2, 3, 4, 5]) await userEvent.click(seat('B', n))
    expect(screen.getByText('You can choose up to 4 seats.')).toBeInTheDocument()
    expect(seat('B', 5)).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByRole('button', { name: 'Continue to payment' })).toBeEnabled()
  })

  it('a seat you picked that someone else takes drops out of your choice', async () => {
    renderMap()
    await waitFor(() => expect(seat('A', 3)).toBeDisabled())
    await userEvent.click(seat('B', 6))
    expect(screen.getByRole('button', { name: 'Continue to payment' })).toBeEnabled()

    takenApi.mockResolvedValue([3, 4, 16]) // the next refresh: B6 is gone
    await waitFor(() => expect(seat('B', 6)).toBeDisabled(), { timeout: 6000 })
    expect(screen.getByText('A seat you chose was just taken by someone else.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Choose seats to continue' })).toBeDisabled()
  }, 10_000)
})
