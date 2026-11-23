import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { buyTicket } from '../api/orders'
import type { EventResponse, TicketType } from '../api/types'
import { tokenStore } from '../auth/tokenStore'
import { fakeToken, testQueryClient } from '../test/setup'
import { TicketPicker } from './TicketPicker'

vi.mock('../api/orders', () => ({ buyTicket: vi.fn() }))
const buy = vi.mocked(buyTicket)

const zone = (id: number, name: string, price: number, left: number, color: string | null = '#2563eb'): TicketType =>
  ({ id, name, kind: color ? 'SEATED' : null, color, price, totalTickets: 1000, availableTickets: left, seatRows: null, seatsPerRow: null })

const concert: EventResponse = {
  id: 7, name: 'Neon Monsoon', venue: 'Axiata Arena', startsAt: '2030-03-13T12:00:00Z', saleStartsAt: '2030-01-10T02:00:00Z',
  totalTickets: 3000, availableTickets: 1203, imageUrl: null, venueId: 3, mapUrl: '/api/venues/3/map?v=1', minPrice: 388,
  ticketTypes: [zone(1, 'VIP', 988, 0), zone(2, 'CAT 1', 688, 1200), zone(3, 'CAT 2', 388, 3)],
}

function renderPicker(event: EventResponse = concert) {
  render(
    <QueryClientProvider client={testQueryClient()}>
      <MemoryRouter initialEntries={['/events/7']}>
        <Routes>
          <Route path="/events/:id" element={<TicketPicker event={event} />} />
          <Route path="/orders/:reservationId" element={<p>checkout page</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('TicketPicker', () => {
  beforeEach(() => {
    buy.mockReset()
    tokenStore.set(fakeToken())
  })

  it('lists every type with its price and what is left; sold-out ones cannot be picked', () => {
    renderPicker()
    expect(screen.getByRole('radio', { name: /VIP.*RM 988.*Sold out/ })).toBeDisabled()
    expect(screen.getByRole('radio', { name: /CAT 1.*RM 688.*1,200 left/ })).toBeEnabled()
    expect(screen.getByRole('radio', { name: /CAT 2.*RM 388.*Only 3 left/ })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Choose a zone first' })).toBeDisabled()
  })

  it('pick a zone and a quantity: the total adds up, and buying sends both', async () => {
    buy.mockResolvedValue({ orderId: null, reservationId: 'abc', eventId: 7, status: 'QUEUED', strategy: 'queue', expiresAt: null, ticketTypeId: 2, quantity: 2, unitPrice: null, seats: [], payWith: 'TEST', refunded: false })
    renderPicker()
    await userEvent.click(screen.getByRole('radio', { name: /CAT 1/ }))
    await userEvent.click(screen.getByRole('button', { name: 'More tickets' }))
    expect(screen.getByTestId('ticket-total')).toHaveTextContent('RM 1,376')

    await userEvent.click(screen.getByRole('button', { name: 'Continue to payment' }))
    expect(await screen.findByText('checkout page')).toBeInTheDocument()
    expect(buy).toHaveBeenCalledWith(7, expect.any(String), { ticketTypeId: 2, quantity: 2 })
  })

  it('never lets you pick more than 4, or more than are left', async () => {
    renderPicker()
    await userEvent.click(screen.getByRole('radio', { name: /CAT 1/ }))
    const more = screen.getByRole('button', { name: 'More tickets' })
    for (let i = 0; i < 6; i++) await userEvent.click(more)
    expect(screen.getByLabelText('Number of tickets')).toHaveTextContent('4')
    expect(more).toBeDisabled()

    await userEvent.click(screen.getByRole('radio', { name: /CAT 2/ })) // only 3 left
    expect(screen.getByLabelText('Number of tickets')).toHaveTextContent('3')
  })

  it('a single ticket type is already chosen for you', () => {
    renderPicker({ ...concert, venueId: null, mapUrl: null, ticketTypes: [zone(5, 'General admission', 25, 500, null)] })
    expect(screen.getByRole('radio', { name: /General admission/ })).toBeChecked()
    expect(screen.getByRole('button', { name: 'Continue to payment' })).toBeEnabled()
  })

  it('409 shows the server\'s reason and lets you try another type', async () => {
    buy.mockRejectedValue(new ApiError(409, 'Only 1 ticket of this type left. Please choose fewer.'))
    renderPicker()
    await userEvent.click(screen.getByRole('radio', { name: /CAT 2/ }))
    await userEvent.click(screen.getByRole('button', { name: 'More tickets' }))
    await userEvent.click(screen.getByRole('button', { name: 'Continue to payment' }))
    expect(await screen.findByText(/Only 1 ticket of this type left/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('radio', { name: /CAT 1/ }))
    expect(screen.queryByText(/Only 1 ticket of this type left/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue to payment' })).toBeEnabled()
  })

  it('a zone with numbered seats: "Pick on the map" first, or switch to "Best available" (no seat ids sent)', async () => {
    buy.mockResolvedValue({ orderId: null, reservationId: 'r2', eventId: 7, status: 'QUEUED', strategy: 'queue', expiresAt: null, ticketTypeId: 2, quantity: 2, unitPrice: null, seats: [], payWith: 'TEST', refunded: false })
    const seated = { ...zone(2, 'CAT 1', 688, 1200), seatRows: 30, seatsPerRow: 40 }
    renderPicker({ ...concert, ticketTypes: [zone(1, 'Rock Zone', 488, 500), seated] })
    await userEvent.click(screen.getByRole('radio', { name: /CAT 1/ }))

    // Default: pick on the map. One clear button, no quantity stepper, no second buy button.
    expect(screen.getByRole('radio', { name: /Pick on the map/ })).toBeChecked()
    expect(screen.getByRole('button', { name: /Open seat map/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'More tickets' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Continue to payment' })).not.toBeInTheDocument()

    // Best available: now you choose how many, and we pick the seats.
    await userEvent.click(screen.getByRole('radio', { name: /Best available/ }))
    expect(screen.queryByRole('button', { name: /Open seat map/ })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'More tickets' }))
    await userEvent.click(screen.getByRole('button', { name: 'Continue to payment' }))
    expect(await screen.findByText('checkout page')).toBeInTheDocument()
    expect(buy).toHaveBeenCalledWith(7, expect.any(String), { ticketTypeId: 2, quantity: 2 })
  })

  it('standing zones have no seat map, just a quantity', async () => {
    renderPicker()
    await userEvent.click(screen.getByRole('radio', { name: /CAT 1/ }))
    expect(screen.queryByRole('radio', { name: /Pick on the map/ })).not.toBeInTheDocument()
    expect(screen.getByText('How many tickets?')).toBeInTheDocument()
  })

  it('says what happens next: tickets are held while you pay, nothing charged yet', () => {
    renderPicker()
    expect(screen.getByText(/We hold your tickets for 10 minutes while you pay/)).toBeInTheDocument()
  })
})
