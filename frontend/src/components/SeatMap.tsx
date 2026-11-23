import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, LoaderCircle, Lock, MousePointerClick, Ticket } from 'lucide-react'
import { useRef, useState, type CSSProperties } from 'react'
import { Link, useLocation } from 'react-router'
import { getSeatLayout, getTakenSeats } from '../api/seats'
import type { EventResponse, SeatLabel, TicketType } from '../api/types'
import { useAuth } from '../auth/useAuth'
import { formatPrice } from '../lib/format'
import { formatSeats } from '../lib/seats'
import { holdText, MAX_PER_PERSON, type PracticeOptions } from '../lib/tickets'
import { useBuy } from '../lib/useBuy'
import { ErrorMessage, Loading } from './ui'

/**
 * The seat map of one seated zone: tap the exact seats you want (up to 4), then Continue to payment
 * (which holds them for 10 minutes and opens checkout).
 *   - taken seats are grey; the list of taken seats refreshes every 4 s while the map is open
 *   - if someone else takes a seat you had chosen, it drops out of your choice and you're told
 *   - the server has the last word: if two people click the same seat at the same moment,
 *     exactly one gets it and the other sees "was just taken"
 */
export function SeatMap({ event, type, practice }: { event: EventResponse; type: TicketType; practice?: PracticeOptions }) {
  const { user } = useAuth()
  const location = useLocation()
  const queryClient = useQueryClient()
  const layout = useQuery({
    queryKey: ['seat-layout', event.id, type.id],
    queryFn: () => getSeatLayout(event.id, type.id),
    staleTime: Infinity, // a seat layout never changes
  })
  const taken = useQuery({
    queryKey: ['seats-taken', event.id, type.id],
    queryFn: () => getTakenSeats(event.id, type.id),
    refetchInterval: practice?.seatPollMs ?? 4000,
  })
  const { phase, message, coolingDown, secondsLeft, buy } = useBuy(event.id, {
    onBought: practice?.onBought,
    onConflict: () => {
      taken.refetch() // show who got there first
      queryClient.invalidateQueries({ queryKey: ['event', event.id] })
    },
  })
  const [picked, setPicked] = useState<number[]>([])
  const latestPick = useRef<number[]>([])
  const [limitHit, setLimitHit] = useState(false)

  const takenSet = new Set(taken.data ?? [])
  // A seat you picked that has since been taken by someone else simply drops out of your choice.
  const chosen = picked.filter((id) => !takenSet.has(id))
  const lostSome = chosen.length < picked.length

  const labelOf = new Map<number, SeatLabel>()
  layout.data?.rows.forEach((r) => r.seats.forEach((s) => labelOf.set(s.id, { row: r.label, number: s.number })))
  const chosenLabels = chosen.map((id) => labelOf.get(id)).filter((l): l is SeatLabel => !!l)

  // Built from the LATEST choice (a ref, updated at once), not from this render's copy: two quick
  // clicks in a row must both count, even before React has re-drawn in between.
  function toggle(id: number) {
    const current = latestPick.current.filter((x) => !takenSet.has(x))
    let next = current
    let full = false
    if (current.includes(id)) next = current.filter((x) => x !== id)
    else if (current.length >= MAX_PER_PERSON) full = true
    else next = [...current, id]
    latestPick.current = next
    setPicked(next)
    setLimitHit(full)
  }

  if (layout.error) return <ErrorMessage error={layout.error} />
  if (!layout.data) return <Loading label="Loading the seat map…" />

  const columns = Math.max(...layout.data.rows.map((r) => r.seats.length))
  const free = layout.data.rows.reduce((n, r) => n + r.seats.filter((s) => !takenSet.has(s.id)).length, 0)

  let label = chosen.length ? 'Continue to payment' : 'Choose seats to continue'
  if (phase === 'buying') label = 'Holding your seats…'
  else if (coolingDown) label = `Try again in ${secondsLeft}s`

  return (
    <div className="seat-map-picker" style={{ '--seat-color': type.color ?? 'var(--accent)' } as CSSProperties}>
      <p className="seat-howto">
        <MousePointerClick size={16} aria-hidden="true" />
        Tap the seats you want, up to {MAX_PER_PERSON}. Tap a seat again to remove it.
      </p>
      <div className="seat-legend">
        <span><i className="seat-key is-free" /> Available</span>
        <span><i className="seat-key is-taken" /> Taken</span>
        <span><i className="seat-key is-chosen" /> Your choice</span>
        <span className="seat-legend-price">{formatPrice(type.price)} each · {free.toLocaleString()} free</span>
      </div>

      <div className="seat-grid-wrap">
        <div className="seat-stage">STAGE</div>
        {/* Every row is its own grid with the same columns: label | seat 1..N | label. */}
        <div className="seat-grid" style={{ '--cols': columns } as CSSProperties}>
          <div className="seat-row seat-row-head" aria-hidden="true">
            <span />
            {Array.from({ length: columns }, (_, i) => <span key={i} className="seat-col-no">{i + 1}</span>)}
            <span />
          </div>
          {layout.data.rows.map((row) => (
            <div className="seat-row" role="group" aria-label={`Row ${row.label}`} key={row.label}>
              <span className="seat-row-label" aria-hidden="true">{row.label}</span>
              {row.seats.map((s) => {
                const isTaken = takenSet.has(s.id)
                const isChosen = chosen.includes(s.id)
                const state = isTaken ? 'taken' : isChosen ? 'your choice' : 'available'
                return (
                  <button
                    key={s.id}
                    type="button"
                    className={`seat ${isTaken ? 'is-taken' : isChosen ? 'is-chosen' : 'is-free'}`}
                    style={{ gridColumn: s.number + 1 }}
                    aria-label={`Row ${row.label}, seat ${s.number}, ${state}`}
                    aria-pressed={isChosen}
                    disabled={isTaken || phase === 'buying'}
                    title={`Row ${row.label}, Seat ${s.number}`}
                    onClick={() => toggle(s.id)}
                  >
                    {isChosen && <Check size={12} strokeWidth={3} aria-hidden="true" />}
                  </button>
                )
              })}
              <span className="seat-row-label end" style={{ gridColumn: columns + 2 }} aria-hidden="true">{row.label}</span>
            </div>
          ))}
        </div>
      </div>

      <div className="seat-footer">
        <div className="seat-footer-info" aria-live="polite">
          {chosen.length ? (
            <>
              <strong>{type.name} · {formatSeats(chosenLabels)}</strong>
              <span className="muted small">{chosen.length} × {formatPrice(type.price)} = <strong>{formatPrice(chosen.length * type.price)}</strong></span>
            </>
          ) : (
            <span className="muted">No seats chosen yet</span>
          )}
          {limitHit && <span className="seat-note warn">You can choose up to {MAX_PER_PERSON} seats.</span>}
          {lostSome && <span className="seat-note warn">A seat you chose was just taken by someone else.</span>}
          {message && <span className={`seat-note ${phase === 'conflict' ? 'bad' : 'warn'}`} role="status">{message}</span>}
        </div>
        {user ? (
          <div className="seat-footer-action">
            <button
              type="button"
              className={`btn btn-primary ${phase === 'buying' ? 'is-busy' : ''}`}
              disabled={!chosen.length || phase === 'buying' || coolingDown || practice?.locked}
              onClick={() => buy({ ticketTypeId: type.id, quantity: chosen.length, seatIds: chosen })}
            >
              {phase === 'buying' ? <LoaderCircle size={18} className="spin" aria-hidden="true" /> : <Ticket size={18} aria-hidden="true" />}
              {label}
            </button>
            <span className="muted small"><Lock size={12} aria-hidden="true" /> Held for {holdText(practice, true)} while you pay</span>
          </div>
        ) : (
          <Link className="btn btn-primary" to="/login" state={{ from: location.pathname }}>Log in to buy</Link>
        )}
      </div>
    </div>
  )
}
