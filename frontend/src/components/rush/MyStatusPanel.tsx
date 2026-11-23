import { useMutation, useQuery } from '@tanstack/react-query'
import { Clock, CreditCard, Hourglass, LogIn, MousePointerClick, PartyPopper, Timer, Users, XCircle } from 'lucide-react'
import { useCallback, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '../../api/client'
import { getEvent } from '../../api/events'
import { cancelOrder, getOrder, payOrder } from '../../api/orders'
import type { EventResponse, RushState } from '../../api/types'
import { formatMmSs, formatPrice } from '../../lib/format'
import { formatSeats } from '../../lib/seats'
import { estimateWaitSeconds, formatClickTime, lineProgress, outcomeOf } from '../../lib/rush'
import { useNow } from '../../lib/useNow'
import { Confetti } from '../Confetti'
import { CountdownRing } from '../CountdownRing'
import { TicketPicker } from '../TicketPicker'
import { TicketStub } from '../TicketStub'
import { Dialog, ErrorMessage } from '../ui'

/** The big panel about YOU: lobby, place in line, your turn (choose zone + seats), paying, and how it ended. */
export function MyStatusPanel({ state, onJoin, joining, onChanged }: {
  state: RushState
  onJoin: () => void
  joining: boolean
  onChanged: () => void
}) {
  const [paidFor, setPaidFor] = useState<string | null>(null)
  const me = state.me
  const running = state.phase !== 'FINISHED'

  if (!me) {
    if (!running) return <OutcomeCard state={state} />
    if (state.lineStyle === 'FASTEST_CLICK') return <ClickToJoinCard state={state} onJoin={onJoin} joining={joining} />
    return (
      <div className="me-card">
        <span className="me-icon"><Users size={26} /></span>
        <h2 className="me-title">A rush is running right now</h2>
        <p className="muted">
          {state.phase === 'LOBBY'
            ? 'The sale has not opened yet. Join now and you get a random place, like everyone in the lobby.'
            : 'The sale is already open. If you join now, you go to the back of the line.'}
        </p>
        <button type="button" className="btn btn-primary btn-lg" onClick={onJoin} disabled={joining}>
          <LogIn size={18} /> {joining ? 'Joining…' : 'Join the line'}
        </button>
      </div>
    )
  }

  const paid = me.status === 'PAID' || (me.reservationId != null && paidFor === me.reservationId)
  if (paid) return <PaidCard state={state} />
  // Stopped with the Stop button: no more buying or paying in this run, just the result.
  if (state.phase === 'FINISHED' && state.endReason === 'stopped') return <OutcomeCard state={state} />

  switch (me.status) {
    case 'LOBBY':
      return <LobbyCard state={state} />
    case 'WAITING':
      return <WaitingCard state={state} />
    case 'YOUR_TURN':
      return <YourTurnCard state={state} onChanged={onChanged} />
    case 'BOUGHT':
      return <HoldCard state={state} onPaid={(id) => { setPaidFor(id); onChanged() }} onChanged={onChanged} />
    default:
      return <OutcomeCard state={state} />
  }
}

/** The site's time: the computer's clock corrected by how far off it is from the server. */
function useSiteNow(state: RushState, intervalMs: number) {
  return useNow(intervalMs) + (state.clockOffsetMs ?? 0)
}

/**
 * Fastest click: no early queueing. Watch the countdown, then click Join the instant it opens.
 * Your number = how fast you clicked, compared with every other fan.
 */
function ClickToJoinCard({ state, onJoin, joining }: { state: RushState; onJoin: () => void; joining: boolean }) {
  const now = useSiteNow(state, 50)
  const [earlyClicks, setEarlyClicks] = useState(0)
  const opensAt = state.opensAt ? Date.parse(state.opensAt) : 0
  const left = (opensAt - now) / 1000
  const open = left <= 0

  const click = () => {
    if (!open) {
      setEarlyClicks((n) => n + 1) // real sites ignore early clicks too
      return
    }
    onJoin()
  }

  return (
    <div className="me-card">
      <span className="eyebrow">{open ? 'The sale is open!' : 'Get ready to click'}</span>
      {open ? (
        <div className="click-elapsed" role="timer">+{(-left).toFixed(1)} s</div>
      ) : (
        <div className="lobby-count" role="timer">{left.toFixed(1)}</div>
      )}
      <h2 className="me-title">{open ? 'Click Join now!' : 'The Join button unlocks when the sale opens'}</h2>
      <button
        key={earlyClicks}
        type="button"
        className={`btn btn-xl join-btn ${open ? 'btn-primary is-live' : 'btn-glass'} ${earlyClicks > 0 && !open ? 'shake' : ''}`}
        onClick={click}
        disabled={joining}
      >
        <MousePointerClick size={20} /> {joining ? 'Joining…' : open ? 'Join the queue NOW' : 'Join the queue'}
      </button>
      {earlyClicks > 0 && !open && <p className="me-note bad">Too early! The sale hasn't opened yet.</p>}
      <p className="small muted sync-note">
        <Clock size={12} /> Synced with TixRush time · {state.fans.toLocaleString()} fans are ready to click
      </p>
    </div>
  )
}

function LobbyCard({ state }: { state: RushState }) {
  const now = useSiteNow(state, 250)
  const left = state.opensAt ? Math.max(0, (new Date(state.opensAt).getTime() - now) / 1000) : 0
  return (
    <div className="me-card">
      <span className="eyebrow">You are in the lobby</span>
      <div className="lobby-count" role="timer">{Math.ceil(left)}</div>
      <h2 className="me-title">The sale opens in {Math.ceil(left)} seconds</h2>
      <p className="muted">
        {state.lobby.toLocaleString()} fans are waiting with you. When the sale opens, everyone in the lobby gets a{' '}
        <b>random</b> place in line, so refreshing doesn't help. That keeps it fair for everyone.
      </p>
    </div>
  )
}

function WaitingCard({ state }: { state: RushState }) {
  const me = state.me!
  const ahead = me.ahead ?? 0
  const progress = lineProgress(me.number, me.ahead)
  const wait = estimateWaitSeconds(ahead, state.admitPerSecond)
  const paused = state.phase === 'WAITING_FOR_RETURNS'
  return (
    <div className="me-card">
      <span className="eyebrow"><Hourglass size={14} /> You are in the waiting room</span>
      <div className="me-number">
        <small>Your number</small>#{me.number?.toLocaleString()}
      </div>
      {me.clickedAfterMs != null && me.number != null && (
        <p className="click-stat">
          You clicked in <b>{formatClickTime(me.clickedAfterMs)}</b> ·{' '}
          {(me.number - 1).toLocaleString()} {me.number - 1 === 1 ? 'fan was' : 'fans were'} faster
        </p>
      )}
      <h2 className="me-title">
        {ahead === 0 ? "You're next!" : `${ahead.toLocaleString()} ${ahead === 1 ? 'fan' : 'fans'} ahead of you`}
      </h2>
      <div className="line-bar" aria-label="Progress through the line">
        <span style={{ width: `${Math.round(progress * 100)}%` }} />
      </div>
      <div className="line-meta">
        <span>{paused ? 'Line paused' : `About ${formatMmSs(wait)} to go`}</span>
        <span>{state.ticketsLeft} tickets left</span>
      </div>
      {paused ? (
        <p className="me-note warn">
          Every ticket is reserved right now. Stay in line: anyone who doesn't pay within {state.holdSeconds}s loses
          their ticket, and the line moves again.
        </p>
      ) : ahead > state.ticketsLeft ? (
        <p className="me-note">
          More fans are ahead of you than tickets are left. Some will give up or not pay, so you still have a chance.
        </p>
      ) : (
        <p className="me-note good">Tickets are still available. Get ready to click Buy!</p>
      )}
    </div>
  )
}

/**
 * Your turn: the SAME ticket box as a real event page (zones, quantity, seat map, best available),
 * with your turn's countdown on top. The zone counts are live, so you see virtual fans buying.
 */
function YourTurnCard({ state, onChanged }: { state: RushState; onChanged: () => void }) {
  const now = useSiteNow(state, 250)
  const left = state.me?.passExpiresAt ? Math.max(0, (new Date(state.me.passExpiresAt).getTime() - now) / 1000) : 0
  const event = usePracticeEvent(state)

  return (
    <div className="me-card me-turn me-turn-pick">
      <div className="turn-head">
        <span className="eyebrow">It's your turn!</span>
        <span className={`turn-clock ${left <= 15 ? 'is-low' : ''}`} role="timer" aria-label="Time left for your turn">
          <Timer size={16} aria-hidden="true" /> {formatMmSs(left)}
        </span>
      </div>
      <h2 className="me-title">Grab your tickets</h2>
      <p className="muted">
        {state.ticketsLeft > 0
          ? `${state.ticketsLeft} tickets left right now. Other fans let in with you are choosing too, so be quick.`
          : 'No tickets left right now, but unpaid ones can still come back while your turn lasts.'}
      </p>
      {event.data ? (
        <TicketPicker
          event={withLiveCounts(event.data, state)}
          practice={{ onBought: () => onChanged(), holdSeconds: state.holdSeconds, seatPollMs: 1000, locked: left <= 0 }}
        />
      ) : (
        <ErrorMessage error={event.error} />
      )}
    </div>
  )
}

/** The practice concert (its zones, colours and seat layout). A new run has new zones, so the run is in the key. */
function usePracticeEvent(state: RushState) {
  return useQuery({
    queryKey: ['practice-event', state.eventId, state.opensAt],
    queryFn: () => getEvent(state.eventId!),
    enabled: state.eventId != null,
    staleTime: Infinity, // the zones don't change during a run; the counts come live from the rush state
  })
}

/** The zone list shows what's left RIGHT NOW (from the rush, twice a second), not the cached event. */
function withLiveCounts(event: EventResponse, state: RushState): EventResponse {
  const left = new Map(state.zones.map((z) => [z.ticketTypeId, z.left]))
  return {
    ...event,
    availableTickets: state.ticketsLeft,
    ticketTypes: event.ticketTypes.map((t) => ({ ...t, availableTickets: left.get(t.id) ?? t.availableTickets })),
  }
}

/** The order you just got, once the background worker has written it (a moment after the 202). */
function useMyOrder(reservationId: string | null) {
  return useQuery({
    queryKey: ['order', reservationId],
    queryFn: () => getOrder(reservationId!),
    enabled: reservationId != null,
    refetchInterval: (q) => (q.state.data ? false : 600),
  })
}

/** Tickets held: what you got (zone, seats, total), then Pay, or Cancel to give them to the next fan. */
function HoldCard({ state, onPaid, onChanged }: { state: RushState; onPaid: (reservationId: string) => void; onChanged: () => void }) {
  const me = state.me!
  const order = useMyOrder(me.reservationId).data
  const [askCancel, setAskCancel] = useState(false)
  const closeAskCancel = useCallback(() => setAskCancel(false), [])
  const pay = useMutation({
    // The worker may not have written the order yet (404): wait a moment and try again.
    mutationFn: async () => {
      for (let attempt = 0; attempt < 12; attempt++) {
        try {
          return await payOrder(me.reservationId!)
        } catch (e) {
          if (!(e instanceof ApiError && e.status === 404)) throw e
          await new Promise((r) => setTimeout(r, 600))
        }
      }
      throw new Error('Your order is still being created. Try again in a moment.')
    },
    onSuccess: () => {
      toast.success('Payment successful')
      onPaid(me.reservationId!)
    },
    onError: (e) => toast.error(e instanceof Error ? e.message : 'Payment failed'),
  })
  const cancel = useMutation({
    mutationFn: () => cancelOrder(me.reservationId!),
    onSuccess: () => {
      setAskCancel(false)
      toast.success('Order cancelled')
      onChanged()
    },
    onError: (e) => {
      setAskCancel(false)
      toast.error(e instanceof Error ? e.message : "Couldn't cancel the order")
    },
  })
  const zone = order ? state.zones.find((z) => z.ticketTypeId === order.ticketTypeId)?.name : undefined
  const count = order?.quantity ?? 1
  const what = order && order.seats.length > 0 ? (count > 1 ? 'seats' : 'seat') : count > 1 ? 'tickets' : 'ticket'

  return (
    <div className="me-card me-hold">
      <span className="eyebrow">{count > 1 ? 'Tickets reserved' : 'Ticket reserved'}</span>
      {me.holdExpiresAt && <CountdownRing until={me.holdExpiresAt} size={160} />}
      {order ? (
        <div className="hold-summary" aria-label="Your order">
          <div><span>Tickets</span><strong>{order.quantity} × {zone ?? 'Ticket'}</strong></div>
          {order.seats.length > 0 && <div><span>Seats</span><strong>{formatSeats(order.seats)}</strong></div>}
          {order.unitPrice != null && (
            <div className="hold-total"><span>Total</span><strong>{formatPrice(order.unitPrice * order.quantity)}</strong></div>
          )}
        </div>
      ) : (
        <p className="muted small">Confirming your order…</p>
      )}
      <h2 className="me-title">Pay before the ring runs out</h2>
      <p className="muted">
        Your {what} {count > 1 ? 'are' : 'is'} held for {state.holdSeconds} seconds. If you don't pay in time,{' '}
        {count > 1 ? 'they go' : 'it goes'} to the next fan in line.
      </p>
      <button type="button" className="btn btn-primary btn-xl" onClick={() => pay.mutate()} disabled={pay.isPending || cancel.isPending}>
        <CreditCard size={20} /> {pay.isPending ? 'Paying…' : 'Pay now'}
      </button>
      <button type="button" className="link-btn" onClick={() => setAskCancel(true)} disabled={!order || pay.isPending || cancel.isPending}>
        Cancel order
      </button>
      <Dialog open={askCancel} onClose={closeAskCancel} title="Cancel this order?">
        <p>Your {what} go{count > 1 ? '' : 'es'} back on sale right away, to the next fan in line. Nothing is charged.</p>
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

function PaidCard({ state }: { state: RushState }) {
  const event = usePracticeEvent(state)
  const me = state.me!
  const order = useMyOrder(me.reservationId).data
  return (
    <div className="me-card me-paid">
      <Confetti />
      <span className="me-icon good"><PartyPopper size={26} /></span>
      <h2 className="me-title">{(order?.quantity ?? 1) > 1 ? 'You got tickets!' : 'You got a ticket!'}</h2>
      <p className="small muted">This was practice, so the ticket isn't real. You'd be ready for the real thing.</p>
      <p className="muted">{outcomeOf({ ...state, me: { ...me, status: 'PAID' } }).detail}</p>
      <TicketStub
        order={{
          ...(order ?? { orderId: null, eventId: state.eventId!, strategy: 'queue', expiresAt: null, ticketTypeId: null, quantity: 1, unitPrice: null, seats: [], payWith: 'TEST' as const, refunded: false }),
          reservationId: me.reservationId,
          status: 'PAID',
        }}
        event={event.data}
        practice
      />
    </div>
  )
}

function OutcomeCard({ state }: { state: RushState }) {
  const out = outcomeOf(state)
  return (
    <div className={`me-card outcome outcome-${out.tone}`}>
      <span className="eyebrow">{state.phase === 'FINISHED' ? 'How it ended for you' : 'Your result'}</span>
      <h2 className="me-title">{out.title}</h2>
      <p className="muted">{out.detail}</p>
      {state.phase !== 'FINISHED' && <p className="me-note">Keep watching: the rush is still going for everyone else.</p>}
    </div>
  )
}
