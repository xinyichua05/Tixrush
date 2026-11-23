import { CalendarDays, MapPin } from 'lucide-react'
import { QRCodeSVG } from 'qrcode.react'
import type { ReactNode } from 'react'
import type { EventResponse, OrderResponse } from '../api/types'
import { formatLongDate, formatTime } from '../lib/format'
import { formatSeats } from '../lib/seats'
import { EventPoster } from './EventPoster'
import { StatusBadge } from './StatusBadge'

/**
 * A ticket that looks like a real one: poster strip, details, a perforated tear line and
 * (for paid tickets) a QR code. The QR just encodes the reservation id: a venue scanner
 * would look that id up on the server.
 */
export function TicketStub({ order, event, showQr = false, practice = false, actions }: {
  order: OrderResponse
  event?: EventResponse
  showQr?: boolean
  /** A Practice rush ticket: clearly marked, and never a QR code (it can't get anyone in). */
  practice?: boolean
  actions?: ReactNode
}) {
  const type = event?.ticketTypes?.find((t) => t.id === order.ticketTypeId)
  const code = order.reservationId ? order.reservationId.slice(0, 8).toUpperCase() : `#${order.orderId}`
  return (
    <article className={`stub stub-${order.status.toLowerCase()}`}>
      <EventPoster event={event ?? { id: order.eventId, name: `Event ${order.eventId}` }} className="stub-poster" />
      <div className="stub-main">
        <div className="stub-top">
          {practice ? <span className="badge badge-practice">Practice ticket · not valid for entry</span> : <StatusBadge status={order.status} />}
          <span className="stub-code">{code}</span>
        </div>
        <h3 className="stub-title">{event?.name ?? `Event #${order.eventId}`}</h3>
        {event && (
          <div className="stub-meta">
            <span><CalendarDays size={14} /> {formatLongDate(event.startsAt)} · {formatTime(event.startsAt)}</span>
            <span><MapPin size={14} /> {event.venue}</span>
          </div>
        )}
        {(type || order.quantity > 1) && (
          <div className="stub-type">
            {type?.color && <span className="tt-swatch small" style={{ background: type.color }} aria-hidden="true" />}
            <span>{type?.name ?? 'Ticket'}</span>
            <span className="stub-qty">× {order.quantity}</span>
          </div>
        )}
        {order.seats?.length > 0 && <div className="stub-seats">{formatSeats(order.seats)}</div>}
        {actions && <div className="stub-actions">{actions}</div>}
      </div>
      {showQr && !practice && (
        <div className="stub-qr">
          <div className="qr-box">
            <QRCodeSVG value={`TIXRUSH:${order.reservationId ?? order.orderId}`} size={104} level="M" />
          </div>
          <span className="qr-caption">Scan at the door</span>
        </div>
      )}
    </article>
  )
}
