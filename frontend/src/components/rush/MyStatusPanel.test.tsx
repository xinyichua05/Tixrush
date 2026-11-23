import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getEvent } from '../../api/events'
import { buyTicket, cancelOrder, getOrder } from '../../api/orders'
import type { EventResponse, OrderResponse, RushMe, RushState, TicketType } from '../../api/types'
import { tokenStore } from '../../auth/tokenStore'
import { fakeToken, testQueryClient } from '../../test/setup'
import { MyStatusPanel } from './MyStatusPanel'

vi.mock('../../api/events', () => ({ getEvent: vi.fn() }))
vi.mock('../../api/orders', () => ({ buyTicket: vi.fn(), getOrder: vi.fn(), payOrder: vi.fn(), cancelOrder: vi.fn() }))
vi.mock('../../api/seats', () => ({ getSeatLayout: vi.fn(), getTakenSeats: vi.fn() }))

const zone = (id: number, name: string, kind: 'SEATED' | 'STANDING', price: number, total: number, rows: number | null = null): TicketType =>
  ({ id, name, kind, color: '#2563eb', price, totalTickets: total, availableTickets: total, seatRows: rows, seatsPerRow: rows ? 10 : null })

// The cached event still says "full"; the live rush state says what's really left.
const practiceEvent = {
  id: 5, name: 'Stellar Eclipse', venue: 'Bukit Jalil', startsAt: '2027-03-01T12:00:00Z', mapUrl: null,
  ticketTypes: [zone(11, 'VIP', 'SEATED', 988, 30, 3), zone(12, 'Rock Zone', 'STANDING', 388, 60)],
} as unknown as EventResponse

const me = (patch: Partial<RushMe>): RushMe => ({
  status: 'YOUR_TURN', number: 12, ahead: 0, passExpiresAt: new Date(Date.now() + 80_000).toISOString(),
  reservationId: null, holdExpiresAt: null, clickedAfterMs: null, ...patch,
})
const state = (myself: RushMe): RushState => ({
  phase: 'OPEN', scenario: 'POPULAR', scenarioLabel: 'Popular show', eventId: 5, eventName: 'Stellar Eclipse', venue: 'Bukit Jalil',
  opensAt: '2027-01-01T00:00:00Z', msSinceOpen: 9000, fans: 600, tickets: 90, admitPerSecond: 25, passSeconds: 90, holdSeconds: 30,
  ticketsLeft: 19, sold: 71, paid: 40, returned: 0, missed: 0, gaveUp: 3, waiting: 400, lobby: 0, admitted: 80, stillComing: 0,
  soldOutAfterMs: null, soldOutAtNumber: null, crowd: '', myIndex: 11, feed: [], me: myself, check: null, endReason: null,
  lineStyle: 'RANDOM_LOBBY', serverNow: new Date().toISOString(),
  zones: [{ ticketTypeId: 11, name: 'VIP', left: 7 }, { ticketTypeId: 12, name: 'Rock Zone', left: 12 }],
})

function renderPanel(s: RushState, onChanged = vi.fn()) {
  render(
    <QueryClientProvider client={testQueryClient()}>
      <MemoryRouter initialEntries={['/practice']}>
        <MyStatusPanel state={s} onJoin={vi.fn()} joining={false} onChanged={onChanged} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return onChanged
}

describe('MyStatusPanel: practising the real ticket grab', () => {
  beforeEach(() => {
    tokenStore.set(fakeToken())
    vi.mocked(getEvent).mockResolvedValue(practiceEvent)
    vi.mocked(buyTicket).mockReset()
  })

  it('your turn: the real zone picker, with LIVE counts, and you stay on the practice page after buying', async () => {
    vi.mocked(buyTicket).mockResolvedValue({ reservationId: 'r1', status: 'QUEUED', eventId: 5 } as OrderResponse)
    const onChanged = renderPanel(state(me({})))

    expect(screen.getByRole('timer', { name: 'Time left for your turn' })).toBeInTheDocument()
    expect(await screen.findByRole('radio', { name: /VIP.*7 left/ })).toBeInTheDocument() // live, not the cached 30
    await userEvent.click(screen.getByRole('radio', { name: /Rock Zone.*12 left/ }))
    await userEvent.click(screen.getByRole('button', { name: 'More tickets' }))
    await userEvent.click(screen.getByRole('button', { name: 'Continue to payment' }))

    expect(buyTicket).toHaveBeenCalledWith(5, expect.any(String), { ticketTypeId: 12, quantity: 2 })
    expect(onChanged).toHaveBeenCalled() // the rush page refreshes and shows your hold; no checkout page
    expect(screen.getByText(/30 seconds while you pay/)).toBeInTheDocument() // the practice hold, not 10 minutes
  })

  it('seated zones offer the seat map, like a real concert', async () => {
    renderPanel(state(me({})))
    await userEvent.click(await screen.findByRole('radio', { name: /VIP/ }))
    expect(screen.getByRole('button', { name: /Open seat map/ })).toBeInTheDocument()
  })

  it('holding: shows what you got (zone + seats + total), with Pay and Cancel', async () => {
    vi.mocked(getOrder).mockResolvedValue({
      orderId: 1, reservationId: 'r1', eventId: 5, status: 'PENDING_PAYMENT', strategy: 'queue',
      expiresAt: new Date(Date.now() + 25_000).toISOString(), ticketTypeId: 11, quantity: 2, unitPrice: 988,
      seats: [{ row: 'A', number: 5 }, { row: 'A', number: 6 }], payWith: 'TEST', refunded: false,
    })
    vi.mocked(cancelOrder).mockResolvedValue({} as OrderResponse)
    const onChanged = renderPanel(state(me({ status: 'BOUGHT', reservationId: 'r1', holdExpiresAt: new Date(Date.now() + 25_000).toISOString() })))

    const summary = await screen.findByLabelText('Your order')
    expect(within(summary).getByText('2 × VIP')).toBeInTheDocument()
    expect(within(summary).getByText('Row A · Seats 5, 6')).toBeInTheDocument()
    expect(within(summary).getByText('RM 1,976')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Pay now/ })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Cancel order' }))
    await userEvent.click(screen.getByRole('button', { name: /Yes, cancel/ }))
    expect(cancelOrder).toHaveBeenCalledWith('r1')
    expect(onChanged).toHaveBeenCalled()
  })
})
