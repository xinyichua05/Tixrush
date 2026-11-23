import type { EventResponse, TicketTypeInput, Venue, VenueZone } from '../api/types'

/**
 * The state behind the "Tickets" part of the event form (pages/TicketsField.tsx). Three ways an event can sell tickets:
 *   simple  -> ticket types typed by hand (Adult / Student / General admission), no map. Exhibitions etc.
 *   venue   -> the zones of a saved venue (with its seating plan); the admin sets price + tickets per zone
 *   single  -> the old single pool ("Standard ticket"). Only shown for old events; nothing is sent.
 */
export type TicketsMode = 'simple' | 'venue' | 'single'

export interface SimpleRow { key: string; name: string; price: string; tickets: string }
export interface ZoneEdit { onSale: boolean; price: string; tickets: string }

export interface TicketsState {
  mode: TicketsMode
  simple: SimpleRow[]
  venueId: number | null
  zoneEdits: Record<number, ZoneEdit> // per venue zone id; zones not in here use defaultZoneEdit()
}

export const newKey = () => Math.random().toString(36).slice(2)

/** Starting state: from the event being edited, or one "General admission" row for a new event. */
export function initialTickets(initial?: EventResponse): TicketsState {
  if (initial && initial.ticketTypes.length === 0) {
    return { mode: 'single', simple: [{ key: newKey(), name: 'General admission', price: '', tickets: String(initial.totalTickets) }], venueId: null, zoneEdits: {} }
  }
  const simple = initial && !initial.venueId
    ? initial.ticketTypes.map((t) => ({ key: newKey(), name: t.name, price: String(t.price), tickets: String(t.totalTickets) }))
    : [{ key: newKey(), name: 'General admission', price: '', tickets: '500' }]
  return { mode: initial?.venueId ? 'venue' : 'simple', simple, venueId: initial?.venueId ?? null, zoneEdits: {} }
}

/** A zone nobody has touched yet: what the event already sells there, or "on sale, full capacity, no price yet". */
/** A seated zone with numbered seats sells every seat: rows x seats per row. */
export const isNumbered = (zone: VenueZone) => !!zone.rows && !!zone.seatsPerRow

export function defaultZoneEdit(zone: VenueZone, venueId: number, initial?: EventResponse): ZoneEdit {
  if (initial && initial.venueId === venueId) {
    const existing = initial.ticketTypes.find((t) => t.name === zone.name)
    return existing
      ? { onSale: true, price: String(existing.price), tickets: String(existing.totalTickets) }
      : { onSale: false, price: '', tickets: String(zone.capacity) }
  }
  return { onSale: true, price: '', tickets: String(zone.capacity) }
}

export type TicketsPayload =
  | { ok: true; venueId: number | null; venueName: string | null; ticketTypes: TicketTypeInput[] | undefined }
  | { ok: false; error: string }

const validPrice = (s: string) => s.trim() !== '' && Number.isFinite(Number(s)) && Number(s) >= 0
const validTickets = (s: string) => Number.isInteger(Number(s)) && Number(s) >= 1

/** Turns the form state into the API body, or says what's missing. */
export function ticketsPayload(state: TicketsState, venues: Venue[], initial?: EventResponse): TicketsPayload {
  if (state.mode === 'single') {
    return { ok: true, venueId: null, venueName: null, ticketTypes: undefined } // tickets stay as they are
  }
  if (state.mode === 'simple') {
    if (state.simple.some((r) => !r.name.trim())) return { ok: false, error: 'Give every ticket type a name.' }
    if (state.simple.some((r) => !validPrice(r.price))) return { ok: false, error: 'Enter a price for every ticket type (0 = free).' }
    if (state.simple.some((r) => !validTickets(r.tickets))) return { ok: false, error: 'Every ticket type needs at least 1 ticket.' }
    return {
      ok: true, venueId: null, venueName: null,
      ticketTypes: state.simple.map((r) => ({ name: r.name.trim(), price: Number(r.price), tickets: Number(r.tickets) })),
    }
  }
  const venue = venues.find((v) => v.id === state.venueId)
  if (!venue) return { ok: false, error: 'Choose a venue.' }
  const rows = venue.zones
    .map((z) => ({ zone: z, edit: state.zoneEdits[z.id] ?? defaultZoneEdit(z, venue.id, initial) }))
    .filter((r) => r.edit.onSale)
  if (rows.length === 0) return { ok: false, error: 'Put at least one zone on sale.' }
  if (rows.some((r) => !validPrice(r.edit.price))) return { ok: false, error: 'Enter a price for every zone on sale (0 = free).' }
  if (rows.some((r) => !isNumbered(r.zone) && !validTickets(r.edit.tickets))) return { ok: false, error: 'Every zone on sale needs at least 1 ticket.' }
  return {
    ok: true, venueId: venue.id, venueName: venue.name,
    ticketTypes: rows.map((r) => ({
      venueZoneId: r.zone.id, price: Number(r.edit.price),
      tickets: isNumbered(r.zone) ? r.zone.capacity : Number(r.edit.tickets), // every seat (the server checks too)
    })),
  }
}
