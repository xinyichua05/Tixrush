import { useQuery } from '@tanstack/react-query'
import { ImagePlus, Trash2 } from 'lucide-react'
import { useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import type { EventInput, EventResponse } from '../api/types'
import { listVenues } from '../api/venues'
import { EventPoster } from '../components/EventPoster'
import { toLocalInput } from '../lib/format'
import { PICTURE_TYPES } from '../lib/image'
import { usePicture, type PictureChange } from '../lib/usePicture'
import { initialTickets, ticketsPayload, type TicketsState } from '../lib/ticketsForm'
import { TicketsField } from './TicketsField'

export type { PictureChange }

const DAY = 86_400_000

/**
 * Create or edit an event. Dates are typed in YOUR local time and sent to the API as UTC (ISO).
 * The backend's Bean Validation rules (CreateEventRequest) come back as a 400 with an "errors"
 * map, which we show next to each field.
 */
export function EventForm({ initial, onSubmit, onCancel }: {
  initial?: EventResponse
  onSubmit: (input: EventInput, picture: PictureChange) => Promise<unknown>
  onCancel: () => void
}) {
  const [name, setName] = useState(initial?.name ?? '')
  const [venueText, setVenueText] = useState(initial?.venue ?? '')
  // "() => ..." = only computed once, when the form first appears (not on every re-render).
  const [startsAt, setStartsAt] = useState(() => toLocalInput(initial ? new Date(initial.startsAt) : new Date(Date.now() + 30 * DAY)))
  const [saleStartsAt, setSaleStartsAt] = useState(() => toLocalInput(initial ? new Date(initial.saleStartsAt) : new Date(Date.now() + DAY)))
  const [tickets, setTickets] = useState<TicketsState>(() => initialTickets(initial))
  const [ticketsError, setTicketsError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const { data: venues } = useQuery({ queryKey: ['venues'], queryFn: listVenues })

  // Once anything is sold, only prices can change (the backend enforces this too).
  const locked = !!initial && initial.totalTickets !== initial.availableTickets

  const picture = usePicture(initial?.imageUrl)
  const fileInput = useRef<HTMLInputElement>(null)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    const t = ticketsPayload(tickets, venues ?? [], initial)
    if (!t.ok) {
      setTicketsError(t.error)
      return
    }
    setTicketsError(null)
    setBusy(true)
    setError(null)
    try {
      await onSubmit({
        name,
        venue: t.venueName ?? venueText,
        startsAt: new Date(startsAt).toISOString(),
        saleStartsAt: new Date(saleStartsAt).toISOString(),
        ...(t.ticketTypes ? { venueId: t.venueId, ticketTypes: t.ticketTypes } : {}),
      }, picture.change)
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError(0, String(err)))
    } finally {
      setBusy(false)
    }
  }

  const fieldError = (field: string) =>
    error?.fieldErrors[field] && <small className="field-error">{error.fieldErrors[field]}</small>

  return (
    <form className="form" onSubmit={handleSubmit} noValidate>
      {/* Picture: a live preview as a card (wide) and as the home-page poster (tall). No picture = generated art. */}
      <div className="field">
        <span>Picture</span>
        <div className="picture-previews">
          <EventPoster event={{ id: initial?.id ?? 0, name: name || 'New event', imageUrl: picture.previewUrl }} className="form-poster">
            {!picture.previewUrl && <span className="form-poster-name">{name || 'Event name'}</span>}
          </EventPoster>
          <EventPoster event={{ id: initial?.id ?? 0, name: name || 'New event', imageUrl: picture.previewUrl }} className="form-poster-tall" showInitials={false} />
        </div>
        <div className="picture-actions">
          <input ref={fileInput} type="file" accept={PICTURE_TYPES.join(',')} onChange={picture.choose} hidden data-testid="picture-input" />
          <button type="button" className="btn btn-glass btn-sm" onClick={() => fileInput.current?.click()} disabled={picture.preparing}>
            <ImagePlus size={15} /> {picture.preparing ? 'Preparing…' : picture.previewUrl ? 'Change picture' : 'Choose picture'}
          </button>
          {picture.previewUrl && (
            <button type="button" className="btn btn-ghost btn-sm" onClick={picture.remove} disabled={picture.preparing}>
              <Trash2 size={15} /> Remove
            </button>
          )}
        </div>
        <small className="muted small">
          JPG, PNG or WebP. Wide pictures work best: cards show the middle, the home-page poster shows a tall slice.
          {!picture.previewUrl && ' No picture? We make poster art for you.'}
        </small>
        {picture.error && <small className="field-error">{picture.error}</small>}
      </div>

      <label className="field">
        <span>Name</span>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Rush Hour Live in KL" />
        {fieldError('name')}
      </label>
      {tickets.mode !== 'venue' && (
        <label className="field">
          <span>Venue</span>
          <input value={venueText} onChange={(e) => setVenueText(e.target.value)} placeholder="ILHAM Gallery, Kuala Lumpur" />
          {fieldError('venue')}
        </label>
      )}
      <div className="form-grid">
        <label className="field">
          <span>Show starts</span>
          <input type="datetime-local" value={startsAt} onChange={(e) => setStartsAt(e.target.value)} />
          {fieldError('startsAt')}
        </label>
        <label className="field">
          <span>Sale opens</span>
          <input type="datetime-local" value={saleStartsAt} onChange={(e) => setSaleStartsAt(e.target.value)} />
          {fieldError('saleStartsAt')}
          {fieldError('saleBeforeShow')}
        </label>
      </div>

      <TicketsField state={tickets} onChange={setTickets} venues={venues} initial={initial} locked={locked} error={ticketsError} />

      {error && Object.keys(error.fieldErrors).length === 0 && <div className="alert alert-error">{error.message}</div>}
      {error && Object.keys(error.fieldErrors).some((k) => k.startsWith('ticketTypes')) && (
        <div className="alert alert-error">Please check the ticket types: {Object.values(error.fieldErrors)[0]}</div>
      )}
      <div className="form-actions">
        <button type="button" className="btn btn-ghost" onClick={onCancel}>Cancel</button>
        <button type="submit" className="btn btn-primary" disabled={busy || picture.preparing}>
          {busy ? 'Saving…' : initial ? 'Save changes' : 'Create event'}
        </button>
      </div>
    </form>
  )
}
