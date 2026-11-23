// TypeScript types that mirror the JSON the Spring Boot backend sends.
// If the backend record changes, update it here too; the compiler then shows every place that breaks.

/** com.tixrush.event.EventResponse */
export interface EventResponse {
  id: number
  name: string
  venue: string
  startsAt: string // ISO date-time, e.g. "2027-03-20T12:00:00Z"
  saleStartsAt: string
  totalTickets: number
  availableTickets: number
  imageUrl: string | null // the uploaded picture, or null = show the generated art
  venueId: number | null // the saved venue whose zones it sells (null = simple tickets or old single pool)
  mapUrl: string | null // that venue's seating-plan picture
  ticketTypes: TicketType[] // empty = the old single "Standard ticket" pool
  minPrice: number | null // cheapest ticket type, for "From RM 25"
}

export type ZoneKind = 'SEATED' | 'STANDING'

/** One kind of ticket an event sells: a concert zone ("CAT 1") or a simple type ("Adult"). */
export interface TicketType {
  id: number
  name: string
  kind: ZoneKind | null // only for zones copied from a venue
  color: string | null // "#e11d48", only for zones copied from a venue (matches the map)
  price: number
  totalTickets: number
  availableTickets: number
  seatRows: number | null // numbered seats: a grid of rows x seats (null = no seat map)
  seatsPerRow: number | null
}

/** A seat as printed on a ticket: { row: "F", number: 12 } */
export interface SeatLabel {
  row: string
  number: number
}

/** GET /api/events/{e}/ticket-types/{t}/seats: every seat, front row first, left to right. */
export interface SeatMapLayout {
  ticketTypeId: number
  name: string
  rows: { label: string; seats: { id: number; number: number }[] }[]
}

/** One row of the event form's "Tickets" section (venueZoneId for venue zones, name for simple tickets). */
export interface TicketTypeInput {
  venueZoneId?: number
  name?: string
  price: number
  tickets: number
}

/** Body of POST /api/events (create) and PUT /api/events/{id} (update; totalTickets is ignored) */
export interface EventInput {
  name: string
  venue: string
  startsAt: string
  saleStartsAt: string
  totalTickets?: number
  venueId?: number | null
  ticketTypes?: TicketTypeInput[] // leave out = keep the tickets as they are (update)
}

/** com.tixrush.venue.VenueResponse */
export interface Venue {
  id: number
  name: string
  mapUrl: string | null
  zones: VenueZone[]
  totalCapacity: number
  eventCount: number
}

export interface VenueZone {
  id: number
  name: string
  kind: ZoneKind
  capacity: number // seated: rows x seatsPerRow
  color: string
  rows: number | null // seated zones: numbered seats in rows A, B, C...
  seatsPerRow: number | null
}

/** Body of POST/PUT /api/venues: the name and ALL zones, in display order. */
export interface VenueInput {
  name: string
  /** STANDING: capacity. SEATED: rows + seatsPerRow (the capacity is worked out from them). */
  zones: { name: string; kind: ZoneKind; capacity?: number; color: string; rows?: number; seatsPerRow?: number }[]
}

export type Role = 'USER' | 'ADMIN'

/** com.tixrush.auth.AuthResponse */
export interface AuthResponse {
  token: string
  expiresAt: string
  role: Role
}

/**
 * QUEUED = the first answer from the queue version (order not in the DB yet)
 * PENDING_PAYMENT -> PAID or EXPIRED = the 10-minute payment hold (or CANCELLED by the buyer)
 * CONFIRMED = bought with the older versions (no payment step)
 */
export type OrderStatus = 'QUEUED' | 'PENDING_PAYMENT' | 'PAID' | 'EXPIRED' | 'CANCELLED' | 'CONFIRMED'

/** com.tixrush.order.OrderResponse */
export interface OrderResponse {
  orderId: number | null
  reservationId: string | null
  eventId: number
  status: OrderStatus
  strategy: string
  expiresAt: string | null
  ticketTypeId: number | null // which ticket type (null = old single pool)
  quantity: number // how many tickets this order holds (1-4)
  unitPrice: number | null // price of one ticket when the order was made (null in the first QUEUED reply)
  seats: SeatLabel[] // numbered seats ("Row F, Seat 12"); empty for everything else
  /** STRIPE = pay on Stripe's page (real payment, test mode). TEST = the instant test button (practice, old orders, Stripe off). */
  payWith: 'STRIPE' | 'TEST'
  /** A payment arrived after the order could no longer be paid, so it was given back. */
  refunded: boolean
}

/** The standard error body (RFC 9457 ProblemDetail) from GlobalExceptionHandler */
export interface ProblemDetail {
  title?: string
  status?: number
  detail?: string
  errors?: Record<string, string> // field -> message, for 400 validation errors
}

// ---- Live Rush demo (com.tixrush.demo.RushDemoService.State) ----

export type RushScenarioId = 'MEGA' | 'POPULAR' | 'INDIE'
/** RANDOM_LOBBY = wait early, places drawn at random. FASTEST_CLICK = your number is how fast you clicked Join. */
export type RushLineStyle = 'RANDOM_LOBBY' | 'FASTEST_CLICK'
export type RushPhase = 'IDLE' | 'LOBBY' | 'OPEN' | 'WAITING_FOR_RETURNS' | 'FINISHED'
export type RushMeStatus = 'LOBBY' | 'WAITING' | 'YOUR_TURN' | 'BOUGHT' | 'PAID' | 'HOLD_EXPIRED' | 'CANCELLED' | 'PASS_EXPIRED' | 'MISSED'

export interface RushFeedItem {
  atMs: number // ms after the sale opened (negative = still in the lobby)
  kind: 'info' | 'open' | 'sold' | 'paid' | 'returned' | 'missed' | 'soldout' | 'you' | 'end'
  text: string
}

export interface RushMe {
  status: RushMeStatus
  number: number | null // your place in line, 1 = first
  ahead: number | null // fans still in front of you (straight from Redis ZRANK)
  passExpiresAt: string | null
  reservationId: string | null
  holdExpiresAt: string | null
  clickedAfterMs: number | null // fastest click: how long after opening you clicked Join
}

/** One zone of the practice concert and how many of its tickets are still free. */
export interface RushZoneLeft {
  ticketTypeId: number
  name: string
  left: number
}

/** Counted in TICKETS (one order can hold 1-4). */
export interface RushDbCheck {
  activeOrders: number
  paidOrders: number
  pendingOrders: number
  expiredOrders: number
  oversold: number
}

export interface RushState {
  phase: RushPhase
  scenario: RushScenarioId | null
  scenarioLabel: string | null
  eventId: number | null
  eventName: string | null
  venue: string | null
  opensAt: string | null
  msSinceOpen: number
  fans: number
  tickets: number
  admitPerSecond: number
  passSeconds: number
  holdSeconds: number
  ticketsLeft: number
  sold: number
  paid: number
  returned: number
  missed: number
  gaveUp: number
  waiting: number
  lobby: number
  admitted: number
  stillComing: number
  soldOutAfterMs: number | null
  soldOutAtNumber: number | null
  /** One letter per fan in line order: W waiting, B buying, T holding a ticket, P paid, E hold expired, X missed, G gave up, O turn expired */
  crowd: string
  myIndex: number | null
  feed: RushFeedItem[]
  me: RushMe | null
  check: RushDbCheck | null
  endReason: 'soldout' | 'line-empty' | 'time' | 'stopped' | null
  lineStyle: RushLineStyle | null
  serverNow: string // the site's clock, so countdowns don't depend on the computer's clock
  /** Tickets still free per zone, live (updated twice a second). */
  zones: RushZoneLeft[]
  /** Added in the browser: site clock minus computer clock, in ms. */
  clockOffsetMs?: number
}
