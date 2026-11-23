import type { SeatLabel, TicketType } from '../api/types'

/** Does this ticket type have numbered seats (a seat map)? */
export const hasSeatMap = (t: Pick<TicketType, 'seatRows' | 'seatsPerRow'>) => !!t.seatRows && !!t.seatsPerRow

/**
 * Seats as people say them, grouped by row:
 *   [F12, F13]      -> "Row F · Seats 12, 13"
 *   [F12, G3]       -> "Row F · Seat 12; Row G · Seat 3"
 */
export function formatSeats(seats: SeatLabel[]): string {
  const rows = new Map<string, number[]>()
  for (const s of seats) rows.set(s.row, [...(rows.get(s.row) ?? []), s.number])
  return [...rows.entries()]
    .map(([row, numbers]) => {
      const sorted = [...numbers].sort((a, b) => a - b)
      return `Row ${row} · ${sorted.length === 1 ? 'Seat' : 'Seats'} ${sorted.join(', ')}`
    })
    .join('; ')
}
