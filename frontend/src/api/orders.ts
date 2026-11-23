import { ApiError, apiFetch } from './client'
import type { OrderResponse } from './types'

/**
 * Buy with v4 (Redis + RabbitMQ). Answers 202 with status QUEUED and a reservationId.
 * The Idempotency-Key makes a double-click or a retry return the SAME order instead of a 2nd one.
 */
export const buyTicket = (eventId: number, idempotencyKey: string, choice?: TicketChoice) =>
  apiFetch<OrderResponse>(`/api/events/${eventId}/purchases?strategy=queue`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: choice, // events with ticket types: which type and how many (1-4)
  })

/**
 * For events that sell ticket types: "2 x CAT 1".
 * Numbered seats: seatIds = the exact seats picked on the map (leave out = best available).
 */
export interface TicketChoice {
  ticketTypeId: number
  quantity: number
  seatIds?: number[]
}

export const myOrders = () => apiFetch<OrderResponse[]>('/api/orders/mine')

/** null = not in the database yet (the background worker is still creating it). */
export async function getOrder(reservationId: string): Promise<OrderResponse | null> {
  try {
    return await apiFetch<OrderResponse>(`/api/orders/${reservationId}`)
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null
    throw e
  }
}

/** Real payment: the address of Stripe's payment page for this order (the same page again if you ask twice). */
export const startCheckout = (reservationId: string) =>
  apiFetch<{ url: string }>(`/api/orders/${reservationId}/checkout`, { method: 'POST' })

/** Back from Stripe: the backend asks Stripe "was it paid?" and answers with the order (null = not in the DB yet). */
export async function checkPayment(reservationId: string): Promise<OrderResponse | null> {
  try {
    return await apiFetch<OrderResponse>(`/api/orders/${reservationId}/payment/check`, { method: 'POST' })
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null
    throw e
  }
}

export const payOrder = (reservationId: string) =>
  apiFetch<OrderResponse>(`/api/orders/${reservationId}/pay`, { method: 'POST' })

/** "Cancel order" on the payment step: the tickets go back on sale right away. 409 if already paid / expired. */
export const cancelOrder = (reservationId: string) =>
  apiFetch<OrderResponse>(`/api/orders/${reservationId}/cancel`, { method: 'POST' })
