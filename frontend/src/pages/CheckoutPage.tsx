import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, CalendarDays, CreditCard, Hourglass, MapPin, PartyPopper, SearchX, ShieldCheck, XCircle } from 'lucide-react'
import { useCallback, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { toast } from 'sonner'
import { getEvent } from '../api/events'
import { cancelOrder, checkPayment, getOrder, payOrder, startCheckout } from '../api/orders'
import { Confetti } from '../components/Confetti'
import { CountdownRing } from '../components/CountdownRing'
import { EventPoster } from '../components/EventPoster'
import { Stepper } from '../components/Stepper'
import { TicketStub } from '../components/TicketStub'
import { Dialog, ErrorMessage, Loading } from '../components/ui'
import type { EventResponse, OrderResponse } from '../api/types'
import { formatLongDate, formatPrice, formatTime } from '../lib/format'
import { goTo } from '../lib/redirect'
import { formatSeats } from '../lib/seats'
import { useNow } from '../lib/useNow'

const STEPS = ['Seat reserved', 'Confirming', 'Payment']

/** A normal order shows up within a few seconds. After this long we stop the spinner and say so. */
const GIVE_UP_AFTER_MS = 60_000

/**
 * After clicking Buy you land here with your reservation id. Three moments:
 *  1. "Creating your order…"   -> the order is still in RabbitMQ (GET returns 404 = null here)
 *     ...and if it still isn't there after 60 s: "We couldn't find this order" (the message was
 *     lost, or the order was removed). We keep checking slowly, in case it was only a big backlog.
 *  2. "Pay within 9:59"        -> PENDING_PAYMENT, with a countdown ring and a Pay button
 *     Real orders (payWith STRIPE): Pay now opens Stripe's payment page; Stripe sends you back here with
 *     ?paid=1, and while you're on this page we keep asking the backend to check with Stripe.
 *     ...or "Cancel order" -> asks first, then CANCELLED: the tickets go back on sale right away
 *  3. "You're going!", "Time's up" or "Order cancelled" -> PAID (QR ticket), EXPIRED or CANCELLED, the end
 */
export function CheckoutPage() {
  const reservationId = useParams().reservationId!
  const [searchParams] = useSearchParams()
  const backFromStripe = searchParams.get('paid') === '1'
  const queryClient = useQueryClient()
  const now = useNow(1000)
  const [openedAt] = useState(() => Date.now())
  const [askCancel, setAskCancel] = useState(false)
  const closeAskCancel = useCallback(() => setAskCancel(false), [])

  const orderQuery = useQuery({
    queryKey: ['order', reservationId],
    // Back from Stripe: every refresh asks the backend to check with Stripe ("was it paid?"), so the
    // page turns to "You're going!" by itself. Otherwise a plain read of the order.
    queryFn: () => (backFromStripe ? checkPayment(reservationId) : getOrder(reservationId)),
    retry: false,
    // Poll fast while waiting for the worker (slowly once we've given up), slower while the timer
    // runs, stop when it's final.
    refetchInterval: (query) => {
      const order = query.state.data
      if (order === undefined || order === null) return Date.now() - openedAt > GIVE_UP_AFTER_MS ? 5000 : 1000
      return order.status === 'PENDING_PAYMENT' ? 3000 : false
    },
  })
  const order = orderQuery.data

  const { data: event } = useQuery({
    queryKey: ['event', order?.eventId],
    queryFn: () => getEvent(order!.eventId),
    enabled: !!order,
  })

  const pay = useMutation({
    mutationFn: () => payOrder(reservationId),
    onSuccess: (paid) => {
      queryClient.setQueryData(['order', reservationId], paid)
      queryClient.invalidateQueries({ queryKey: ['orders', 'mine'] })
      toast.success("Payment successful. You're going! 🎉")
    },
    onError: (e) => {
      toast.error(e instanceof Error ? e.message : 'Payment failed')
      orderQuery.refetch() // e.g. 409 "too late": show the real status
    },
  })

  // Real payment: ask for this order's Stripe page and go there. (The button stays "Opening Stripe…"
  // until the browser has left, so nobody clicks twice.)
  const stripe = useMutation({
    mutationFn: () => startCheckout(reservationId),
    onSuccess: ({ url }) => goTo(url),
    onError: (e) => {
      toast.error(e instanceof Error ? e.message : "Couldn't open the payment page")
      orderQuery.refetch()
    },
  })
  const paying = pay.isPending || stripe.isPending || stripe.isSuccess

  const cancel = useMutation({
    mutationFn: () => cancelOrder(reservationId),
    onSuccess: (cancelledOrder) => {
      setAskCancel(false)
      queryClient.setQueryData(['order', reservationId], cancelledOrder)
      queryClient.invalidateQueries({ queryKey: ['orders', 'mine'] })
      queryClient.invalidateQueries({ queryKey: ['event', cancelledOrder.eventId] }) // the tickets are back
      toast.success('Order cancelled')
    },
    onError: (e) => {
      setAskCancel(false)
      toast.error(e instanceof Error ? e.message : "Couldn't cancel the order")
      orderQuery.refetch() // e.g. 409 "already paid": show the real status
    },
  })

  if (orderQuery.error) return <div className="container narrow"><ErrorMessage error={orderQuery.error} /></div>
  if (order === undefined) return <div className="container narrow"><Loading /></div>

  // 1b. Waited too long: the order is not coming (or was removed)
  if (order === null && now - openedAt > GIVE_UP_AFTER_MS) {
    return (
      <div className="container narrow checkout-page">
        <Stepper steps={STEPS} current={1} failed />
        <div className="checkout-card glass center">
          <SearchX size={44} className="text-muted" aria-hidden="true" />
          <h1>We couldn't find this order</h1>
          <p className="muted">
            It may have expired or been cancelled. Nothing was charged, and any seats held for it go back on sale.
          </p>
          <p className="mono small muted">Reservation {reservationId.slice(0, 8).toUpperCase()}</p>
          <div className="done-actions">
            <Link to="/my-tickets" className="btn btn-primary">My tickets <ArrowRight size={16} /></Link>
            <Link to="/" className="btn btn-glass">Browse events</Link>
          </div>
        </div>
      </div>
    )
  }

  // 1. Still in the queue
  if (order === null) {
    return (
      <div className="container narrow checkout-page">
        <Stepper steps={STEPS} current={1} />
        <div className="checkout-card glass center">
          <div className="pulse-ring"><span className="spinner big" aria-hidden="true" /></div>
          <h1>You got a seat! 🎟️</h1>
          <p className="muted">We're confirming your order. During a big sale this can take a few seconds.</p>
          <p className="mono small muted">Reservation {reservationId.slice(0, 8).toUpperCase()}</p>
          {now - openedAt > 15000 && <p className="muted small">Almost there… your seat is safe.</p>}
        </div>
      </div>
    )
  }

  const timeUp = order.expiresAt !== null && new Date(order.expiresAt).getTime() <= now
  const paid = order.status === 'PAID' || order.status === 'CONFIRMED'
  const expired = order.status === 'EXPIRED'
  const cancelled = order.status === 'CANCELLED'
  const step = paid ? 3 : 2
  // "seats" when you picked numbered seats, else "tickets" / "ticket"
  const what = order.seats.length > 0 ? (order.quantity > 1 ? 'seats' : 'seat') : order.quantity > 1 ? 'tickets' : 'ticket'

  return (
    <div className="container checkout-page">
      <Stepper steps={STEPS} current={step} failed={expired || cancelled || timeUp} />

      {/* 2. Waiting for payment */}
      {order.status === 'PENDING_PAYMENT' && (
        <div className="checkout-grid">
          <div className="checkout-card glass center">
            {timeUp ? (
              <>
                <Hourglass size={40} className="text-bad" />
                <h2>Time's up</h2>
                <p className="muted">Releasing your seat…</p>
              </>
            ) : (
              <>
                <CountdownRing until={order.expiresAt!} />
                <p className="muted">
                  {order.quantity > 1 ? `Your ${order.quantity} tickets are held.` : 'Your ticket is held.'} Pay before the timer runs out, or{' '}
                  {order.quantity > 1 ? 'they go' : 'it goes'} back on sale.
                </p>
                {order.payWith === 'STRIPE' ? (
                  <>
                    <button type="button" className="btn btn-primary btn-xl" onClick={() => stripe.mutate()} disabled={paying || cancel.isPending}>
                      <CreditCard size={20} /> {paying ? 'Opening Stripe…' : `Pay ${unitTotal(order)} with Stripe`}
                    </button>
                    {backFromStripe && <p className="muted small" role="status">Checking your payment with Stripe…</p>}
                    <p className="muted small pay-note">
                      <ShieldCheck size={14} /> Secure payment by Stripe · <b>Test mode</b>: use card 4242 4242 4242 4242,
                      any future date and any CVC. No real money is taken.
                    </p>
                  </>
                ) : (
                  <>
                    <button type="button" className="btn btn-primary btn-xl" onClick={() => pay.mutate()} disabled={paying || cancel.isPending}>
                      <CreditCard size={20} /> {pay.isPending ? 'Paying…' : 'Pay now'}
                    </button>
                    <p className="muted small"><ShieldCheck size={14} /> Test mode: no money is charged.</p>
                  </>
                )}
                <button type="button" className="link-btn" onClick={() => setAskCancel(true)} disabled={paying || cancel.isPending}>
                  Cancel order
                </button>
              </>
            )}
          </div>
          <OrderSummary order={order} event={event} code={reservationId} />
        </div>
      )}

      {/* 3. Final states */}
      {paid && (
        <div className="checkout-done">
          <Confetti />
          <div className="done-head">
            <PartyPopper size={40} className="grad-icon" />
            <h1>You're going!</h1>
            <p className="muted">Your ticket is confirmed. Show this QR code at the door.</p>
          </div>
          <TicketStub order={order} event={event} showQr />
          <div className="done-actions">
            <Link to="/my-tickets" className="btn btn-primary">View my tickets <ArrowRight size={16} /></Link>
            <Link to="/" className="btn btn-glass">Find more shows</Link>
          </div>
        </div>
      )}
      {cancelled && (
        <div className="checkout-card glass center narrow-card">
          <XCircle size={44} className="text-muted" aria-hidden="true" />
          <h1>Order cancelled</h1>
          <p className="muted">Your {what} {order.quantity > 1 ? 'are' : 'is'} back on sale. Nothing was charged.</p>
          <div className="done-actions">
            <Link className="btn btn-primary" to={`/events/${order.eventId}`}>Choose again</Link>
            <Link className="btn btn-glass" to="/">Browse events</Link>
          </div>
        </div>
      )}
      {expired && (
        <div className="checkout-card glass center narrow-card">
          <Hourglass size={44} className="text-muted" />
          <h1>Time's up</h1>
          <p className="muted">
            {order.refunded
              ? 'Your payment arrived after the time ran out, so the tickets had already gone back on sale. We refunded you in full.'
              : 'The payment window closed and the seat went back on sale.'}
          </p>
          <Link className="btn btn-primary" to={`/events/${order.eventId}`}>Try again</Link>
        </div>
      )}

      {pay.error && <ErrorMessage error={pay.error} />}

      <Dialog open={askCancel} onClose={closeAskCancel} title="Cancel this order?">
        <p>
          Your {what} go{order.quantity > 1 ? '' : 'es'} back on sale right away, so someone else can buy {order.quantity > 1 ? 'them' : 'it'}.
          Nothing is charged.
        </p>
        <div className="form-actions">
          <button type="button" className="btn btn-ghost" onClick={closeAskCancel}>Keep my {what}</button>
          <button type="button" className="btn btn-danger-solid" onClick={() => cancel.mutate()} disabled={cancel.isPending}>
            <XCircle size={16} aria-hidden="true" /> {cancel.isPending ? 'Cancelling…' : 'Yes, cancel'}
          </button>
        </div>
      </Dialog>
    </div>
  )
}

/** "RM 1,376": what Stripe will charge (blank if the price isn't known yet). */
function unitTotal(order: OrderResponse): string {
  return order.unitPrice == null ? '' : formatPrice(order.unitPrice * order.quantity)
}

function OrderSummary({ order, event, code }: { order: OrderResponse; event?: EventResponse; code: string }) {
  const type = event?.ticketTypes.find((t) => t.id === order.ticketTypeId)
  const unitPrice = order.unitPrice ?? type?.price ?? null
  return (
    <aside className="summary glass">
      <EventPoster event={event ?? { id: order.eventId, name: `Event ${order.eventId}` }} className="summary-poster" />
      <div className="summary-body">
        <span className="eyebrow">Order summary</span>
        <h3>{event?.name ?? 'Loading…'}</h3>
        {event && (
          <ul className="summary-meta">
            <li><CalendarDays size={15} /> {formatLongDate(event.startsAt)} · {formatTime(event.startsAt)}</li>
            <li><MapPin size={15} /> {event.venue}</li>
          </ul>
        )}
        <div className="summary-row">
          <span>Tickets</span>
          <span className="summary-type">
            {type?.color && <span className="tt-swatch small" style={{ background: type.color }} aria-hidden="true" />}
            {order.quantity} × {order.ticketTypeId ? type?.name ?? 'Ticket' : 'Standard'}
          </span>
        </div>
        {order.seats.length > 0 && (
          <div className="summary-row"><span>Seats</span><span className="summary-seats">{formatSeats(order.seats)}</span></div>
        )}
        {unitPrice !== null && (
          <>
            <div className="summary-row"><span>Price each</span><span>{formatPrice(unitPrice)}</span></div>
            <div className="summary-row summary-total"><span>Total</span><span>{formatPrice(unitPrice * order.quantity)}</span></div>
          </>
        )}
        <div className="summary-row"><span>Reservation</span><span className="mono">{code.slice(0, 8).toUpperCase()}</span></div>
      </div>
    </aside>
  )
}
