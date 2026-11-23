import { Info, Plus, Trash2 } from 'lucide-react'
import type { EventResponse, Venue } from '../api/types'
import { KIND_LABEL } from '../lib/tickets'
import { defaultZoneEdit, isNumbered, newKey, type SimpleRow, type TicketsState, type ZoneEdit } from '../lib/ticketsForm'

/** The "Tickets" part of the event form: simple ticket types, venue zones, or the old single pool. */
export function TicketsField({ state, onChange, venues, initial, locked, error }: {
  state: TicketsState
  onChange: (next: TicketsState) => void
  venues: Venue[] | undefined
  initial?: EventResponse
  /** Tickets are already sold: only prices may change. */
  locked: boolean
  error: string | null
}) {
  const set = (patch: Partial<TicketsState>) => onChange({ ...state, ...patch })
  const setRow = (key: string, patch: Partial<SimpleRow>) =>
    set({ simple: state.simple.map((r) => (r.key === key ? { ...r, ...patch } : r)) })
  const venue = venues?.find((v) => v.id === state.venueId)
  const zoneRows = venue ? venue.zones.map((z) => ({ zone: z, edit: state.zoneEdits[z.id] ?? defaultZoneEdit(z, venue.id, initial) })) : []
  const setZone = (zoneId: number, edit: ZoneEdit, patch: Partial<ZoneEdit>) =>
    set({ zoneEdits: { ...state.zoneEdits, [zoneId]: { ...edit, ...patch } } })

  const total = state.mode === 'simple'
    ? state.simple.reduce((s, r) => s + (Number(r.tickets) || 0), 0)
    : state.mode === 'venue'
      ? zoneRows.filter((r) => r.edit.onSale).reduce((s, r) => s + (isNumbered(r.zone) ? r.zone.capacity : Number(r.edit.tickets) || 0), 0)
      : initial?.totalTickets ?? 0

  return (
    <fieldset className="field tickets-field">
      <legend>Tickets</legend>

      {state.mode === 'single' ? (
        <div className="tickets-note">
          <Info size={16} aria-hidden="true" />
          <span>
            This event sells one standard ticket ({initial?.totalTickets.toLocaleString()} in total).
            {!locked && ' You can switch it to ticket types while nothing is sold.'}
          </span>
          {!locked && (
            <button type="button" className="btn btn-glass btn-sm" onClick={() => set({ mode: 'simple' })}>Use ticket types</button>
          )}
        </div>
      ) : (
        <>
          <div className="mode-switch" role="radiogroup" aria-label="How tickets are sold">
            <label className={`mode-option ${state.mode === 'simple' ? 'is-on' : ''}`}>
              <input type="radio" name="tickets-mode" checked={state.mode === 'simple'} disabled={locked} onChange={() => set({ mode: 'simple' })} />
              <strong>Simple tickets</strong>
              <span className="muted small">No seating: Adult, Student, General admission…</span>
            </label>
            <label className={`mode-option ${state.mode === 'venue' ? 'is-on' : ''}`}>
              <input type="radio" name="tickets-mode" checked={state.mode === 'venue'} disabled={locked} onChange={() => set({ mode: 'venue' })} />
              <strong>Venue seating plan</strong>
              <span className="muted small">Zones of a saved venue, shown on its map</span>
            </label>
          </div>
          {locked && (
            <p className="tickets-note"><Info size={16} aria-hidden="true" /> Tickets are already sold, so only the prices can change.</p>
          )}

          {state.mode === 'simple' && (
            <div className="tt-rows">
              <div className="tt-row tt-row-head" aria-hidden="true">
                <span>Ticket type</span><span>Price (RM)</span><span>Tickets</span><span />
              </div>
              {state.simple.map((r, i) => (
                <div className="tt-row" key={r.key}>
                  <input aria-label={`Ticket type ${i + 1} name`} value={r.name} placeholder="Adult" disabled={locked} onChange={(e) => setRow(r.key, { name: e.target.value })} />
                  <input aria-label={`Ticket type ${i + 1} price`} type="number" min={0} step="0.01" inputMode="decimal" value={r.price} placeholder="25" onChange={(e) => setRow(r.key, { price: e.target.value })} />
                  <input aria-label={`Ticket type ${i + 1} tickets`} type="number" min={1} value={r.tickets} disabled={locked} onChange={(e) => setRow(r.key, { tickets: e.target.value })} />
                  <button type="button" className="icon-btn danger" aria-label={`Remove ticket type ${i + 1}`} disabled={locked || state.simple.length === 1}
                    onClick={() => set({ simple: state.simple.filter((x) => x.key !== r.key) })}>
                    <Trash2 size={15} />
                  </button>
                </div>
              ))}
              {!locked && state.simple.length < 10 && (
                <button type="button" className="btn btn-ghost btn-sm add-row"
                  onClick={() => set({ simple: [...state.simple, { key: newKey(), name: '', price: '', tickets: '100' }] })}>
                  <Plus size={15} /> Add ticket type
                </button>
              )}
            </div>
          )}

          {state.mode === 'venue' && (
            <div className="venue-pick">
              <select aria-label="Venue" value={state.venueId ?? ''} disabled={locked}
                onChange={(e) => set({ venueId: e.target.value ? Number(e.target.value) : null, zoneEdits: {} })}>
                <option value="">{venues ? (venues.length ? 'Choose a venue…' : 'No venues yet: add one in the Venues tab') : 'Loading venues…'}</option>
                {venues?.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
              </select>
              {venue && (
                <div className="tt-rows">
                  {venue.mapUrl && <img className="venue-pick-map" src={venue.mapUrl} alt={`Seating plan of ${venue.name}`} />}
                  <div className="tt-row tt-row-zone tt-row-head" aria-hidden="true">
                    <span>On sale</span><span>Zone</span><span>Price (RM)</span><span>Tickets</span>
                  </div>
                  {zoneRows.map(({ zone, edit }) => (
                    <div className={`tt-row tt-row-zone ${edit.onSale ? '' : 'is-off'}`} key={zone.id}>
                      <input type="checkbox" aria-label={`Sell ${zone.name}`} checked={edit.onSale} disabled={locked}
                        onChange={(e) => setZone(zone.id, edit, { onSale: e.target.checked })} />
                      <span className="tt-zone-name">
                        <span className="tt-swatch small" style={{ background: zone.color }} aria-hidden="true" />
                        {zone.name} <span className="muted small">
                          {KIND_LABEL[zone.kind]} · {isNumbered(zone) ? `${zone.rows} rows × ${zone.seatsPerRow}` : zone.capacity.toLocaleString()}
                        </span>
                      </span>
                      <input aria-label={`${zone.name} price`} type="number" min={0} step="0.01" inputMode="decimal" value={edit.price} placeholder="0"
                        disabled={!edit.onSale} onChange={(e) => setZone(zone.id, edit, { price: e.target.value })} />
                      {isNumbered(zone) ? (
                        <span className="tt-every-seat" aria-label={`${zone.name} tickets`}>
                          {zone.capacity.toLocaleString()} <span className="muted small">every seat</span>
                        </span>
                      ) : (
                        <input aria-label={`${zone.name} tickets`} type="number" min={1} value={edit.tickets}
                          disabled={locked || !edit.onSale} onChange={(e) => setZone(zone.id, edit, { tickets: e.target.value })} />
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </>
      )}

      <small className="muted small">Total: <strong data-testid="tickets-total">{total.toLocaleString()}</strong> tickets</small>
      {error && <small className="field-error">{error}</small>}
    </fieldset>
  )
}
