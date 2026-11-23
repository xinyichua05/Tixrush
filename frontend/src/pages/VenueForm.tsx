import { ArrowDown, ArrowUp, ImagePlus, Map as MapIcon, Plus, Trash2 } from 'lucide-react'
import { useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import type { Venue, VenueInput, ZoneKind } from '../api/types'
import { PICTURE_TYPES } from '../lib/image'
import { ZONE_COLORS } from '../lib/tickets'
import { usePicture, type PictureChange } from '../lib/usePicture'

/** Seating plans have small text on them: keep them sharper than event pictures. */
const MAP_MAX_SIDE = 2000

/** Standing zones use capacity; seated zones use rows x seatsPerRow (numbered seats A1, A2...). */
interface ZoneRow { key: string; name: string; kind: ZoneKind; capacity: string; rows: string; seatsPerRow: string; color: string }

const whole = (s: string, min: number, max: number) => Number.isInteger(Number(s)) && Number(s) >= min && Number(s) <= max
/** How many people fit in a zone (seated: every seat). */
const sizeOf = (z: ZoneRow) => (z.kind === 'SEATED' ? (Number(z.rows) || 0) * (Number(z.seatsPerRow) || 0) : Number(z.capacity) || 0)

const newKey = () => Math.random().toString(36).slice(2)
const nextColor = (rows: ZoneRow[]) => ZONE_COLORS.find((c) => !rows.some((r) => r.color === c)) ?? ZONE_COLORS[0]

/**
 * Create or edit a saved venue: its name, seating-plan picture and zones.
 * Saving replaces all zones. Events that already use the venue keep the zones they copied.
 */
export function VenueForm({ initial, onSubmit, onCancel }: {
  initial?: Venue
  onSubmit: (input: VenueInput, map: PictureChange) => Promise<unknown>
  onCancel: () => void
}) {
  const [name, setName] = useState(initial?.name ?? '')
  const [zones, setZones] = useState<ZoneRow[]>(() =>
    initial?.zones.map((z) => ({
      key: newKey(), name: z.name, kind: z.kind, capacity: String(z.capacity), color: z.color,
      rows: z.rows ? String(z.rows) : '', seatsPerRow: z.seatsPerRow ? String(z.seatsPerRow) : '',
    }))
    ?? [{ key: newKey(), name: '', kind: 'STANDING', capacity: '1000', rows: '', seatsPerRow: '', color: ZONE_COLORS[0] }])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const map = usePicture(initial?.mapUrl, MAP_MAX_SIDE)
  const fileInput = useRef<HTMLInputElement>(null)

  const setRow = (key: string, patch: Partial<ZoneRow>) => setZones(zones.map((z) => (z.key === key ? { ...z, ...patch } : z)))
  function move(index: number, by: -1 | 1) {
    const next = [...zones]
    const [row] = next.splice(index, 1)
    next.splice(index + by, 0, row)
    setZones(next)
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) return setError('Give the venue a name.')
    if (zones.some((z) => !z.name.trim())) return setError('Give every zone a name.')
    if (zones.some((z) => z.kind === 'STANDING' && !whole(z.capacity, 1, 100_000))) {
      return setError('Every standing zone needs a capacity of at least 1.')
    }
    if (zones.some((z) => z.kind === 'SEATED' && !(whole(z.rows, 1, 60) && whole(z.seatsPerRow, 1, 80)))) {
      return setError('Every seated zone needs 1-60 rows and 1-80 seats per row.')
    }
    setError(null)
    setBusy(true)
    try {
      await onSubmit({
        name: name.trim(),
        zones: zones.map((z) => z.kind === 'SEATED'
          ? { name: z.name.trim(), kind: z.kind, color: z.color, rows: Number(z.rows), seatsPerRow: Number(z.seatsPerRow) }
          : { name: z.name.trim(), kind: z.kind, color: z.color, capacity: Number(z.capacity) }),
      }, map.change)
    } catch (err) {
      setError(err instanceof ApiError || err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  const total = zones.reduce((s, z) => s + sizeOf(z), 0)

  return (
    <form className="form" onSubmit={handleSubmit} noValidate>
      <label className="field">
        <span>Name</span>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Axiata Arena, Kuala Lumpur" />
      </label>

      <div className="field">
        <span>Seating plan</span>
        <div className={`venue-map-preview ${map.previewUrl ? '' : 'is-empty'}`}>
          {map.previewUrl
            ? <img src={map.previewUrl} alt="Seating plan preview" />
            : <span className="muted"><MapIcon size={22} aria-hidden="true" /> No seating plan yet</span>}
        </div>
        <div className="picture-actions">
          <input ref={fileInput} type="file" accept={PICTURE_TYPES.join(',')} onChange={map.choose} hidden data-testid="map-input" />
          <button type="button" className="btn btn-glass btn-sm" onClick={() => fileInput.current?.click()} disabled={map.preparing}>
            <ImagePlus size={15} /> {map.preparing ? 'Preparing…' : map.previewUrl ? 'Change seating plan' : 'Choose seating plan'}
          </button>
          {map.previewUrl && (
            <button type="button" className="btn btn-ghost btn-sm" onClick={map.remove} disabled={map.preparing}>
              <Trash2 size={15} /> Remove
            </button>
          )}
        </div>
        <small className="muted small">A picture of the floor with each zone in its own colour. Pick the same colours for the zones below, so buyers can match them.</small>
        {map.error && <small className="field-error">{map.error}</small>}
      </div>

      <fieldset className="field zones-field">
        <legend>Zones</legend>
        {zones.map((z, i) => (
          <div className="zone-row" key={z.key}>
            <div className="zone-row-main">
              <span className="tt-swatch zone-sw" style={{ background: z.color }} aria-hidden="true" />
              <input className="zone-name" aria-label={`Zone ${i + 1} name`} value={z.name} placeholder="CAT 1" onChange={(e) => setRow(z.key, { name: e.target.value })} />
              <select className="zone-kind" aria-label={`Zone ${i + 1} kind`} value={z.kind} onChange={(e) => setRow(z.key, { kind: e.target.value as ZoneKind })}>
                <option value="SEATED">Seated</option>
                <option value="STANDING">Standing</option>
              </select>
              {z.kind === 'SEATED' ? (
                <span className="zone-cap">
                  <input aria-label={`Zone ${i + 1} rows`} type="number" min={1} max={60} value={z.rows} placeholder="Rows"
                    onChange={(e) => setRow(z.key, { rows: e.target.value })} />
                  <span className="muted" aria-hidden="true">×</span>
                  <input aria-label={`Zone ${i + 1} seats per row`} type="number" min={1} max={80} value={z.seatsPerRow} placeholder="Seats"
                    onChange={(e) => setRow(z.key, { seatsPerRow: e.target.value })} />
                  <span className="muted small nowrap">= {sizeOf(z).toLocaleString()} seats</span>
                </span>
              ) : (
                <span className="zone-cap">
                  <input aria-label={`Zone ${i + 1} capacity`} type="number" min={1} value={z.capacity} onChange={(e) => setRow(z.key, { capacity: e.target.value })} />
                  <span className="muted small">places</span>
                </span>
              )}
              <div className="zone-row-actions">
                <button type="button" className="icon-btn" aria-label={`Move zone ${i + 1} up`} disabled={i === 0} onClick={() => move(i, -1)}><ArrowUp size={15} /></button>
                <button type="button" className="icon-btn" aria-label={`Move zone ${i + 1} down`} disabled={i === zones.length - 1} onClick={() => move(i, 1)}><ArrowDown size={15} /></button>
                <button type="button" className="icon-btn danger" aria-label={`Remove zone ${i + 1}`} disabled={zones.length === 1}
                  onClick={() => setZones(zones.filter((x) => x.key !== z.key))}><Trash2 size={15} /></button>
              </div>
            </div>
            <div className="zone-colors" role="radiogroup" aria-label={`Zone ${i + 1} colour`}>
              {ZONE_COLORS.map((c) => (
                <button key={c} type="button" role="radio" aria-checked={z.color === c} aria-label={`Colour ${c}`}
                  className={`color-dot ${z.color === c ? 'is-on' : ''}`} style={{ background: c }} onClick={() => setRow(z.key, { color: c })} />
              ))}
            </div>
          </div>
        ))}
        {zones.length < 12 && (
          <button type="button" className="btn btn-ghost btn-sm add-row"
            onClick={() => setZones([...zones, { key: newKey(), name: '', kind: 'SEATED', capacity: '500', rows: '10', seatsPerRow: '20', color: nextColor(zones) }])}>
            <Plus size={15} /> Add zone
          </button>
        )}
        <small className="muted small">Capacity: <strong data-testid="venue-total">{total.toLocaleString()}</strong>. Seated zones get numbered seats (rows A, B, C… × seats 1, 2, 3…) that buyers can pick on a seat map.</small>
      </fieldset>

      {error && <div className="alert alert-error">{error}</div>}
      <div className="form-actions">
        <button type="button" className="btn btn-ghost" onClick={onCancel}>Cancel</button>
        <button type="submit" className="btn btn-primary" disabled={busy || map.preparing}>
          {busy ? 'Saving…' : initial ? 'Save venue' : 'Create venue'}
        </button>
      </div>
    </form>
  )
}
