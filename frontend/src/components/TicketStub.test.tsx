import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { OrderResponse } from '../api/types'
import { TicketStub } from './TicketStub'

const order: OrderResponse = {
  orderId: 1, reservationId: 'abcd1234-0000-0000-0000-000000000000', eventId: 5, status: 'PAID', strategy: 'queue', expiresAt: null, ticketTypeId: null, quantity: 1, unitPrice: null, seats: [], payWith: 'TEST', refunded: false,
}

describe('TicketStub', () => {
  it('shows a QR code for a real paid ticket', () => {
    render(<TicketStub order={order} showQr />)
    expect(screen.getByText('Scan at the door')).toBeInTheDocument()
  })

  it('marks practice tickets and never shows a QR code', () => {
    render(<TicketStub order={order} showQr practice />)
    expect(screen.getByText(/Practice ticket/)).toBeInTheDocument()
    expect(screen.queryByText('Scan at the door')).not.toBeInTheDocument()
  })
})
