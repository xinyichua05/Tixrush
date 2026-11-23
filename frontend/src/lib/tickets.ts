import type { OrderResponse, TicketType, ZoneKind } from '../api/types'

/** Same limit as the backend (TicketStockService.MAX_PER_PERSON): over all orders for one event. */
export const MAX_PER_PERSON = 4

/** Zone colours an admin can pick from. Bright enough to read on both themes, and easy to tell apart. */
export const ZONE_COLORS = [
  '#e11d48', '#f97316', '#eab308', '#16a34a', '#0d9488',
  '#0ea5e9', '#2563eb', '#7c3aed', '#c026d3', '#64748b',
]

export const KIND_LABEL: Record<ZoneKind, string> = { SEATED: 'Seated', STANDING: 'Standing' }

/** At or below this many left, we say "Only N left" instead of "N left". */
export const FEW_LEFT = 20

/** How long tickets are held while you pay. Mirrors the backend's tixrush.orders.payment-hold (default 10m). */
export const HOLD_MINUTES = 10

/**
 * The Practice rush reuses the real ticket picker and seat map, with a few differences:
 *   onBought   stay on the practice page (no checkout page)
 *   holdSeconds the practice hold is 30 s, not 10 minutes
 *   seatPollMs  refresh taken seats faster, so you see the virtual fans grabbing them
 *   locked      your turn is over: no more buying
 */
export interface PracticeOptions {
  onBought: (order: OrderResponse) => void
  holdSeconds: number
  seatPollMs: number
  locked: boolean
}

/** "10 minutes" (real events) or "30 seconds" (practice); short = "10 min" / "30 s". */
export function holdText(practice?: Pick<PracticeOptions, 'holdSeconds'>, short = false): string {
  if (practice) return short ? `${practice.holdSeconds} s` : `${practice.holdSeconds} seconds`
  return short ? `${HOLD_MINUTES} min` : `${HOLD_MINUTES} minutes`
}

export const ticketsWord = (n: number) => (n === 1 ? 'ticket' : 'tickets')

/** The most tickets someone may pick for this type right now: the per-person limit, or what's left. */
export const maxQuantity = (t: TicketType) => Math.max(0, Math.min(MAX_PER_PERSON, t.availableTickets))
