import { LoaderCircle, Ticket } from 'lucide-react'
import { Link, useLocation } from 'react-router'
import { useAuth } from '../auth/useAuth'
import { useBuy } from '../lib/useBuy'

/**
 * The one-click button for events with a single pool of tickets ("Standard ticket").
 * Events that sell ticket types use TicketPicker instead; both share useBuy (what a click does).
 */
export function BuyButton({ eventId, soldOut }: { eventId: number; soldOut: boolean }) {
  const { user } = useAuth()
  const location = useLocation()
  const { phase, message, coolingDown, secondsLeft, buy } = useBuy(eventId)

  if (!user) {
    return (
      <Link className="btn btn-primary btn-xl" to="/login" state={{ from: location.pathname }}>
        Log in to buy
      </Link>
    )
  }

  // Here a 409 always means sold out: there's only one kind of ticket.
  const isSoldOut = soldOut || phase === 'conflict'

  let label = 'Buy ticket'
  if (phase === 'buying') label = 'Getting your ticket…'
  else if (isSoldOut) label = 'Sold out'
  else if (coolingDown) label = `Try again in ${secondsLeft}s`
  else if (phase === 'error') label = 'Try again'

  return (
    <div className="buy">
      <button
        type="button"
        className={`btn btn-primary btn-xl ${phase === 'buying' ? 'is-busy' : ''}`}
        onClick={() => buy()}
        disabled={phase === 'buying' || isSoldOut || coolingDown}
      >
        {phase === 'buying' ? <LoaderCircle size={20} className="spin" aria-hidden="true" /> : <Ticket size={20} aria-hidden="true" />}
        {label}
      </button>
      {message && (
        <p className={`buy-message ${isSoldOut ? '' : 'warn'}`} role="status">
          {message}
        </p>
      )}
    </div>
  )
}
