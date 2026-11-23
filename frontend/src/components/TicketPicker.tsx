import { useQueryClient } from '@tanstack/react-query'
import { Armchair, LoaderCircle, Lock, Map as MapIcon, Minus, Plus, Sparkles, Ticket } from 'lucide-react'
import { useCallback, useState } from 'react'
import { Link, useLocation } from 'react-router'
import type { EventResponse } from '../api/types'
import { useAuth } from '../auth/useAuth'
import { formatPrice } from '../lib/format'
import { FEW_LEFT, holdText, KIND_LABEL, MAX_PER_PERSON, maxQuantity, type PracticeOptions } from '../lib/tickets'
import { hasSeatMap } from '../lib/seats'
import { useBuy } from '../lib/useBuy'
import { SeatMap } from './SeatMap'
import { Dialog } from './ui'

/**
 * Choose what to buy, for events that sell ticket types. Numbered steps, so a first-time buyer
 * knows what to do next without asking:
 *   1. a ticket type (concert zone "CAT 1" with its map colour, or "Adult" / "Student"...)
 *   2. how many (1-4, never more than are left)
 *      ...or, for seated zones: "Pick on the map" (exact seats) or "Best available" (we pick)
 *   3. Continue to payment -> we HOLD the tickets for 10 minutes and open checkout
 *
 * The note under the button says what "hold" means: nothing is charged until you pay.
 * With only one ticket type it's chosen for you, so it's just quantity + button.
 */
export function TicketPicker({ event, practice }: { event: EventResponse; practice?: PracticeOptions }) {
  const { user } = useAuth()
  const location = useLocation()
  const queryClient = useQueryClient()
  const { phase, message, coolingDown, secondsLeft, buy, reset } = useBuy(event.id, {
    onBought: practice?.onBought,
    onConflict: () => queryClient.invalidateQueries({ queryKey: ['event', event.id] }),
  })

  const types = event.ticketTypes
  const onlyOne = types.length === 1 && types[0].availableTickets > 0
  const [chosenId, setChosenId] = useState<number | null>(onlyOne ? types[0].id : null)
  const [wanted, setWanted] = useState(1)
  const [mapOpen, setMapOpen] = useState(false)
  const closeMap = useCallback(() => setMapOpen(false), [])
  const [seatsOpen, setSeatsOpen] = useState(false)
  const closeSeats = useCallback(() => setSeatsOpen(false), [])
  // Seated zones: choose exact seats on the map, or let us pick the best ones.
  const [mode, setMode] = useState<'map' | 'best'>('map')

  const chosen = types.find((t) => t.id === chosenId && t.availableTickets > 0) ?? null
  const max = chosen ? maxQuantity(chosen) : 0
  // If fewer are left than you picked (the numbers refresh every few seconds), lower it for you.
  const quantity = Math.max(1, Math.min(wanted, max))
  // Numbered seats: pick them on the seat map, or let us pick the best ones side by side.
  const seated = chosen ? hasSeatMap(chosen) : false

  function choose(id: number) {
    setChosenId(id)
    reset()
  }

  // Concert zones (copied from a venue, or the practice concert) vs simple types like "Adult" / "Student".
  const zoned = !!event.mapUrl || types.some((t) => t.kind !== null)
  let label = !chosen ? (zoned ? 'Choose a zone first' : 'Choose your tickets first') : 'Continue to payment'
  if (phase === 'buying') label = 'Getting your tickets…'
  else if (coolingDown) label = `Try again in ${secondsLeft}s`
  // Seated zone + "Pick on the map": the seat map has its own Continue button, so hide ours.
  const onMap = seated && mode === 'map'

  return (
    <div className="ticket-picker">
      <fieldset className="tt-list">
        <legend className="tt-legend">
          <span className="tt-step-title"><span className="tt-step" aria-hidden="true">1</span>{zoned ? 'Choose a zone' : 'Choose your tickets'}</span>
          {/* On a phone the big seating plan is far below this box: keep it one tap away. */}
          {event.mapUrl && (
            <button type="button" className="tt-map-link" onClick={() => setMapOpen(true)}>
              <MapIcon size={14} aria-hidden="true" /> See seating plan
            </button>
          )}
        </legend>
        {types.map((t) => {
          const out = t.availableTickets === 0
          return (
            <label key={t.id} className={`tt-option ${chosen?.id === t.id ? 'is-selected' : ''} ${out ? 'is-out' : ''}`}>
              <input
                type="radio"
                name={`ticket-type-${event.id}`}
                value={t.id}
                checked={chosen?.id === t.id}
                disabled={out}
                onChange={() => choose(t.id)}
              />
              {t.color && <span className="tt-swatch" style={{ background: t.color }} aria-hidden="true" />}
              <span className="tt-main">
                <span className="tt-name">{t.name}</span>
                {t.kind && <span className="tt-kind">{KIND_LABEL[t.kind]}</span>}
              </span>
              <span className="tt-side">
                <span className="tt-price">{formatPrice(t.price)}</span>
                <span className={`tt-left ${out ? 'is-out' : t.availableTickets <= FEW_LEFT ? 'is-few' : ''}`}>
                  {out ? 'Sold out' : t.availableTickets <= FEW_LEFT ? `Only ${t.availableTickets} left` : `${t.availableTickets.toLocaleString()} left`}
                </span>
              </span>
            </label>
          )
        })}
      </fieldset>

      {chosen && seated && (
        <fieldset className="tt-list">
          <legend className="tt-legend">
            <span className="tt-step-title"><span className="tt-step" aria-hidden="true">2</span>How do you want your seats?</span>
          </legend>
          <div className="seat-modes">
            <label className={`seat-mode ${mode === 'map' ? 'is-selected' : ''}`}>
              <input type="radio" name={`seat-mode-${event.id}`} checked={mode === 'map'} onChange={() => setMode('map')} />
              <Armchair size={20} aria-hidden="true" />
              <strong>Pick on the map</strong>
              <span>Choose your exact seats</span>
            </label>
            <label className={`seat-mode ${mode === 'best' ? 'is-selected' : ''}`}>
              <input type="radio" name={`seat-mode-${event.id}`} checked={mode === 'best'} onChange={() => setMode('best')} />
              <Sparkles size={20} aria-hidden="true" />
              <strong>Best available</strong>
              <span>We pick the best seats side by side</span>
            </label>
          </div>
        </fieldset>
      )}

      {chosen && !seated && (
        <span className="tt-legend tt-step-title"><span className="tt-step" aria-hidden="true">2</span>How many tickets?</span>
      )}

      {onMap && user && (
        <button type="button" className="btn btn-primary btn-xl" onClick={() => setSeatsOpen(true)} disabled={practice?.locked}>
          <MapIcon size={20} aria-hidden="true" /> Open seat map
        </button>
      )}

      {chosen && !onMap && (
        <div className="tt-order">
          <div className="qty">
            <span className="qty-label">Tickets</span>
            <div className="qty-stepper">
              <button type="button" className="icon-btn" aria-label="Fewer tickets" onClick={() => setWanted(quantity - 1)} disabled={quantity <= 1}>
                <Minus size={16} />
              </button>
              <output className="qty-value" aria-live="polite" aria-label="Number of tickets">{quantity}</output>
              <button type="button" className="icon-btn" aria-label="More tickets" onClick={() => setWanted(quantity + 1)} disabled={quantity >= max}>
                <Plus size={16} />
              </button>
            </div>
          </div>
          <div className="tt-total">
            <span className="muted">{quantity} × {chosen.name} · {formatPrice(chosen.price)}</span>
            <strong data-testid="ticket-total">{formatPrice(quantity * chosen.price)}</strong>
          </div>
        </div>
      )}

      {!user ? (
        <Link className="btn btn-primary btn-xl" to="/login" state={{ from: location.pathname }}>
          Log in to buy
        </Link>
      ) : !onMap && (
        <button
          type="button"
          className={`btn btn-primary btn-xl ${phase === 'buying' ? 'is-busy' : ''}`}
          onClick={() => chosen && buy({ ticketTypeId: chosen.id, quantity })}
          disabled={!chosen || phase === 'buying' || coolingDown || practice?.locked}
        >
          {phase === 'buying' ? <LoaderCircle size={20} className="spin" aria-hidden="true" /> : <Ticket size={20} aria-hidden="true" />}
          {label}
        </button>
      )}
      <p className="hold-note">
        <Lock size={14} aria-hidden="true" />
        <span>
          We hold your tickets for {holdText(practice)} while you pay. Nothing is charged before that.
          {' '}Up to {MAX_PER_PERSON} tickets per person.
        </span>
      </p>
      {message && (
        <p className={`buy-message ${phase === 'conflict' ? '' : 'warn'}`} role="status">
          {message}
        </p>
      )}
      {chosen && seated && (
        <Dialog open={seatsOpen} onClose={closeSeats} title={`${chosen.name} · choose your seats`} variant="wide">
          <SeatMap event={event} type={chosen} practice={practice} />
        </Dialog>
      )}
      {event.mapUrl && (
        <Dialog open={mapOpen} onClose={closeMap} title={`Seating plan · ${event.venue}`} variant="wide">
          <img className="seat-map-full" src={event.mapUrl} alt={`Seating plan of ${event.venue}`} />
        </Dialog>
      )}
    </div>
  )
}
