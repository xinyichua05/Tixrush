import { useQuery } from '@tanstack/react-query'
import {
  Armchair, CalendarDays, Clock, Maximize2, MapPin, MousePointerClick, QrCode, ShieldCheck, Ticket, Timer, Users, Zap,
} from 'lucide-react'
import { useCallback, useState } from 'react'
import { useParams } from 'react-router'
import { BackLink } from '../components/BackLink'
import { getEvent } from '../api/events'
import { BuyButton } from '../components/BuyButton'
import { EventPoster } from '../components/EventPoster'
import { SeatsBar } from '../components/SeatsBar'
import { TicketPicker } from '../components/TicketPicker'
import { Dialog, ErrorMessage, LiveDot, Skeleton } from '../components/ui'
import { cityOf } from '../lib/events'
import { formatDateTime, formatLongDate, formatPrice, formatTime } from '../lib/format'
import { useNow } from '../lib/useNow'

const HOW_IT_WORKS = [
  { icon: <Zap size={20} />, title: 'Reserve instantly', text: 'Your tickets are held the moment you click, even when thousands of fans buy at once.' },
  { icon: <Timer size={20} />, title: 'Pay within the hold', text: 'Finish payment before the timer runs out, or the seat goes back on sale.' },
  { icon: <QrCode size={20} />, title: 'Get your QR ticket', text: 'Your ticket appears in My tickets, ready to scan at the door.' },
]

export function EventPage() {
  const id = Number(useParams().id)
  const now = useNow(30_000)
  const [mapOpen, setMapOpen] = useState(false)
  const closeMap = useCallback(() => setMapOpen(false), []) // stable, so the dialog doesn't re-run its effects
  const { data: event, isPending, error } = useQuery({
    queryKey: ['event', id],
    queryFn: () => getEvent(id),
    refetchInterval: 5000, // the backend caches this for 5 s in Redis, so polling faster is pointless
  })

  if (isPending) return <EventPageSkeleton />
  if (error || !event) {
    return (
      <div className="container narrow">
        <ErrorMessage error={error ?? 'Event not found'} />
        <BackLink to="/">All events</BackLink>
      </div>
    )
  }

  const saleOpen = new Date(event.saleStartsAt).getTime() <= now
  const soldOut = event.availableTickets === 0
  const hasTypes = event.ticketTypes.length > 0

  return (
    <div className="event-page">
      <section className="event-banner">
        <EventPoster event={event} className="event-banner-art" showInitials={false} />
        <div className="container event-banner-content">
          <BackLink to="/" className="back back-light">All events</BackLink>
          <span className="eyebrow">{cityOf(event.venue)}</span>
          <h1 className="event-title">{event.name}</h1>
          <div className="chips">
            <span className="chip"><CalendarDays size={15} /> {formatLongDate(event.startsAt)}</span>
            <span className="chip"><Clock size={15} /> {formatTime(event.startsAt)}</span>
            <span className="chip"><MapPin size={15} /> {event.venue}</span>
          </div>
        </div>
      </section>

      <div className="container event-layout">
        <div className="event-main">
          <section className="panel glass">
            <h2>About this show</h2>
            <dl className="facts">
              <div><dt><CalendarDays size={15} /> Date</dt><dd>{formatLongDate(event.startsAt)}</dd></div>
              <div><dt><Clock size={15} /> Starts</dt><dd>{formatTime(event.startsAt)}</dd></div>
              <div><dt><MapPin size={15} /> Venue</dt><dd>{event.venue}</dd></div>
              <div><dt><Armchair size={15} /> Capacity</dt><dd>{event.totalTickets.toLocaleString()} {hasTypes ? 'tickets' : 'seats'}</dd></div>
              {event.minPrice !== null && <div><dt><Ticket size={15} /> Price</dt><dd>From {formatPrice(event.minPrice)}</dd></div>}
            </dl>
          </section>

          {event.mapUrl && (
            <section className="panel glass">
              <h2>Seating plan</h2>
              <button type="button" className="seat-map" onClick={() => setMapOpen(true)} aria-label="Show the seating plan bigger">
                <img src={event.mapUrl} alt={`Seating plan of ${event.venue}`} loading="lazy" decoding="async" />
                <span className="seat-map-zoom"><Maximize2 size={15} /> Enlarge</span>
              </button>
              <p className="muted small">The colours match the zones in the list. Pick your zone, then how many tickets.</p>
              <Dialog open={mapOpen} onClose={closeMap} title={`Seating plan · ${event.venue}`} variant="wide">
                <img className="seat-map-full" src={event.mapUrl} alt={`Seating plan of ${event.venue}`} />
              </Dialog>
            </section>
          )}

          <section className="panel glass">
            <h2>How buying works</h2>
            <ol className="how">
              {HOW_IT_WORKS.map((s, i) => (
                <li key={s.title}>
                  <span className="how-icon">{s.icon}</span>
                  <div>
                    <strong><span className="how-num">{i + 1}</span> {s.title}</strong>
                    <p className="muted">{s.text}</p>
                  </div>
                </li>
              ))}
            </ol>
          </section>
        </div>

        <aside className="purchase glass">
          <div className="purchase-head">
            <span className="purchase-type">{hasTypes ? 'Tickets' : 'Standard ticket'}</span>
            <LiveDot />
          </div>
          <div className="purchase-count">
            {soldOut ? (
              <span className="text-bad">Sold out</span>
            ) : (
              <>
                <span className="grad-text">{event.availableTickets.toLocaleString()}</span>
                <span className="muted"> {hasTypes ? 'tickets' : 'seats'} left</span>
              </>
            )}
          </div>
          <SeatsBar available={event.availableTickets} total={event.totalTickets} large />
          {hasTypes ? <TicketPicker event={event} /> : <BuyButton eventId={event.id} soldOut={soldOut} />}
          <ul className="trust">
            <li><MousePointerClick size={16} /> One click, one order: you'll never be charged twice</li>
            <li><ShieldCheck size={16} /> Your tickets are held while you pay</li>
            <li><Users size={16} /> A fair line for everyone</li>
          </ul>
          {!saleOpen && (
            <p className="purchase-note">
              Official sale opens {formatDateTime(event.saleStartsAt)}. (Demo: buying is already enabled.)
            </p>
          )}
        </aside>
      </div>
    </div>
  )
}

function EventPageSkeleton() {
  return (
    <div className="event-page" aria-hidden="true">
      <section className="event-banner"><span className="skeleton event-banner-art" /></section>
      <div className="container event-layout">
        <div className="event-main">
          <div className="panel glass"><Skeleton height={22} width="40%" /><Skeleton height={80} /></div>
        </div>
        <div className="purchase glass"><Skeleton height={40} width="60%" /><Skeleton height={10} /><Skeleton height={54} radius={14} /></div>
      </div>
    </div>
  )
}
