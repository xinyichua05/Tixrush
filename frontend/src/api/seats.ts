import { apiFetch } from './client'
import type { SeatMapLayout } from './types'

/** Every seat of a numbered ticket type. It never changes, so the browser may cache it. */
export const getSeatLayout = (eventId: number, ticketTypeId: number) =>
  apiFetch<SeatMapLayout>(`/api/events/${eventId}/ticket-types/${ticketTypeId}/seats`)

/** The ids of the seats that are taken right now (held or sold). Small and live: poll it. */
export const getTakenSeats = (eventId: number, ticketTypeId: number) =>
  apiFetch<number[]>(`/api/events/${eventId}/ticket-types/${ticketTypeId}/seats/taken`)
