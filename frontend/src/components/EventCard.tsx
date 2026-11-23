import { CalendarDays, Flame, MapPin, Ticket } from 'lucide-react'
import { Link } from 'react-router'
import type { EventResponse } from '../api/types'
import { isSellingFast } from '../lib/events'
import { dateParts, formatLongDate, formatPrice, formatTime } from '../lib/format'
import { EventPoster } from './EventPoster'
import { SeatsBar } from './SeatsBar'
import { Skeleton } from './ui'

export function EventCard({ event }: { event: EventResponse }) {
  const d = dateParts(event.startsAt)
  const soldOut = event.availableTickets === 0
  return (
    <Link to={`/events/${event.id}`} className={`event-card ${soldOut ? 'is-sold-out' : ''}`}>
      <EventPoster event={event} className="event-card-media">
        <div className="date-badge">
          <span className="date-month">{d.month}</span>
          <span className="date-day">{d.day}</span>
        </div>
        {soldOut && <span className="ribbon ribbon-out">Sold out</span>}
        {isSellingFast(event) && (
          <span className="ribbon ribbon-hot">
            <Flame size={13} /> Selling fast
          </span>
        )}
      </EventPoster>
      <div className="event-card-body">
        <h3 className="event-name">{event.name}</h3>
        <p className="meta">
          <MapPin size={14} /> <span>{event.venue}</span>
        </p>
        <p className="meta">
          <CalendarDays size={14} /> <span>{formatLongDate(event.startsAt)} · {formatTime(event.startsAt)}</span>
        </p>
        {event.minPrice !== null && event.minPrice !== undefined && (
          <p className="meta price-from">
            <Ticket size={14} /> <span>From <strong>{formatPrice(event.minPrice)}</strong></span>
          </p>
        )}
        <SeatsBar available={event.availableTickets} total={event.totalTickets} />
      </div>
    </Link>
  )
}

export function EventCardSkeleton() {
  return (
    <div className="event-card is-skeleton" aria-hidden="true">
      <span className="skeleton event-card-media" />
      <div className="event-card-body">
        <Skeleton height={20} width="80%" />
        <Skeleton height={14} width="60%" />
        <Skeleton height={14} width="50%" />
        <Skeleton height={6} />
      </div>
    </div>
  )
}
