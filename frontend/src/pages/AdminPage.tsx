import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Armchair, CalendarDays, ChartBar, Flame, Map as MapIcon, Pencil, Plus, Trash2 } from 'lucide-react'
import { useCallback, useState } from 'react'
import { toast } from 'sonner'
import { createEvent, deleteEvent, listEvents, removeEventImage, updateEvent, uploadEventImage } from '../api/events'
import type { EventResponse } from '../api/types'
import { EventPoster } from '../components/EventPoster'
import { Dialog, ErrorMessage, Skeleton, StatCard } from '../components/ui'
import { isSellingFast } from '../lib/events'
import { formatDateTime, formatPrice } from '../lib/format'
import { EventForm, type PictureChange } from './EventForm'
import { VenuesPanel } from './VenuesPanel'

type Editing = { mode: 'none' } | { mode: 'create' } | { mode: 'edit'; event: EventResponse }
type Tab = 'events' | 'venues'

export function AdminPage() {
  const queryClient = useQueryClient()
  const { data: events, isPending, error } = useQuery({ queryKey: ['events'], queryFn: listEvents, refetchInterval: 5000 })
  const [editing, setEditing] = useState<Editing>({ mode: 'none' })
  const [toDelete, setToDelete] = useState<EventResponse | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [tab, setTab] = useState<Tab>('events')

  // After any change, mark the cached lists as stale so every page re-fetches fresh data.
  // (Venues too: their "events using it" count changes.)
  const refresh = () => Promise.all([
    queryClient.invalidateQueries({ queryKey: ['events'] }),
    queryClient.invalidateQueries({ queryKey: ['venues'] }),
  ])
  const closeEditor = useCallback(() => setEditing({ mode: 'none' }), [])
  const closeDelete = useCallback(() => setToDelete(null), [])

  async function confirmDelete() {
    if (!toDelete) return
    setDeleting(true)
    try {
      await deleteEvent(toDelete.id)
      toast.success(`Deleted "${toDelete.name}"`)
      setToDelete(null)
      await refresh()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Delete failed')
    } finally {
      setDeleting(false)
    }
  }

  const list = [...(events ?? [])].sort((a, b) => a.startsAt.localeCompare(b.startsAt))
  const totalSeats = list.reduce((s, e) => s + e.totalTickets, 0)
  const soldSeats = list.reduce((s, e) => s + (e.totalTickets - e.availableTickets), 0)
  const soldOutCount = list.filter((e) => e.availableTickets === 0).length

  return (
    <div className="container">
      <div className="page-head">
        <div>
          <span className="eyebrow">Admin</span>
          <h1>{tab === 'events' ? 'Events dashboard' : 'Venues'}</h1>
        </div>
        {tab === 'events' && (
          <button type="button" className="btn btn-primary" onClick={() => setEditing({ mode: 'create' })}>
            <Plus size={18} /> New event
          </button>
        )}
      </div>

      <div className="tabs" role="tablist">
        <button type="button" role="tab" aria-selected={tab === 'events'} className={`tab ${tab === 'events' ? 'active' : ''}`} onClick={() => setTab('events')}>
          <CalendarDays size={15} /> Events
        </button>
        <button type="button" role="tab" aria-selected={tab === 'venues'} className={`tab ${tab === 'venues' ? 'active' : ''}`} onClick={() => setTab('venues')}>
          <MapIcon size={15} /> Venues
        </button>
      </div>

      {tab === 'venues' && <VenuesPanel />}
      {tab === 'events' && (<>

      <div className="stats">
        <StatCard icon={<CalendarDays size={18} />} label="Events" value={events ? list.length : '–'} />
        <StatCard icon={<Armchair size={18} />} label="Total seats" value={events ? totalSeats.toLocaleString() : '–'} />
        <StatCard
          icon={<ChartBar size={18} />}
          label="Seats sold"
          value={events ? soldSeats.toLocaleString() : '–'}
          sub={events && totalSeats ? `${((soldSeats / totalSeats) * 100).toFixed(1)}% of all seats` : undefined}
        />
        <StatCard icon={<Flame size={18} />} label="Sold-out shows" value={events ? soldOutCount : '–'} />
      </div>

      <ErrorMessage error={error} />

      <div className="table-wrap glass">
        <table className="table">
          <thead>
            <tr>
              <th>Event</th>
              <th>Show</th>
              <th>Seats</th>
              <th>Status</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {isPending &&
              [0, 1, 2].map((i) => (
                <tr key={i}><td colSpan={5}><Skeleton height={36} /></td></tr>
              ))}
            {list.map((e) => {
              const soldPct = e.totalTickets ? ((e.totalTickets - e.availableTickets) / e.totalTickets) * 100 : 0
              const status = e.availableTickets === 0 ? ['out', 'Sold out'] : isSellingFast(e) ? ['hot', 'Selling fast'] : ['on', 'On sale']
              return (
                <tr key={e.id}>
                  <td>
                    <div className="cell-event">
                      <EventPoster event={e} className="thumb" showInitials={false} />
                      <div>
                        <strong>{e.name}</strong>
                        <span className="muted small">#{e.id} · {e.venue}</span>
                        {e.ticketTypes.length > 0 && (
                          <span className="muted small">
                            {e.ticketTypes.length} ticket {e.ticketTypes.length === 1 ? 'type' : 'types'}
                            {e.minPrice !== null && ` · from ${formatPrice(e.minPrice)}`}
                          </span>
                        )}
                      </div>
                    </div>
                  </td>
                  <td className="nowrap">{formatDateTime(e.startsAt)}</td>
                  <td>
                    <div className="mini-bar"><span style={{ width: `${soldPct}%` }} /></div>
                    <span className="small muted">{(e.totalTickets - e.availableTickets).toLocaleString()} / {e.totalTickets.toLocaleString()} sold</span>
                  </td>
                  <td><span className={`pill pill-${status[0]}`}>{status[1]}</span></td>
                  <td>
                    <div className="row-actions">
                      <button type="button" className="icon-btn" onClick={() => setEditing({ mode: 'edit', event: e })} aria-label={`Edit ${e.name}`} title="Edit">
                        <Pencil size={16} />
                      </button>
                      <button type="button" className="icon-btn danger" onClick={() => setToDelete(e)} aria-label={`Delete ${e.name}`} title="Delete">
                        <Trash2 size={16} />
                      </button>
                    </div>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      </>)}

      <Dialog
        open={editing.mode !== 'none'}
        onClose={closeEditor}
        variant="drawer"
        title={editing.mode === 'edit' ? `Edit event #${editing.event.id}` : 'New event'}
      >
        {editing.mode !== 'none' && (
          <EventForm
            key={editing.mode === 'edit' ? editing.event.id : 'new'}
            initial={editing.mode === 'edit' ? editing.event : undefined}
            onCancel={closeEditor}
            onSubmit={async (input, picture: PictureChange) => {
              // 1. The details. If this fails, the form shows the error and stays open.
              const saved = editing.mode === 'edit' ? await updateEvent(editing.event.id, input) : await createEvent(input)
              // 2. The picture, now that we know the event's id (a new event only gets one in step 1).
              //    If only this part fails, the event IS saved: say so, and let them try the picture again.
              let pictureProblem: string | null = null
              try {
                if (picture.kind === 'set') await uploadEventImage(saved.id, picture.blob)
                if (picture.kind === 'remove') await removeEventImage(saved.id)
              } catch (e) {
                pictureProblem = e instanceof Error ? e.message : 'unknown error'
              }
              if (pictureProblem) toast.error(`Event saved, but the picture didn't upload: ${pictureProblem}`)
              else toast.success(editing.mode === 'edit' ? 'Event updated' : 'Event created')
              await refresh()
              closeEditor()
            }}
          />
        )}
      </Dialog>

      <Dialog open={!!toDelete} onClose={closeDelete} title="Delete event?">
        <p>
          <strong>{toDelete?.name}</strong> and all of its orders will be permanently deleted. This can't be undone.
        </p>
        <div className="form-actions">
          <button type="button" className="btn btn-ghost" onClick={closeDelete}>Cancel</button>
          <button type="button" className="btn btn-danger-solid" onClick={confirmDelete} disabled={deleting}>
            <Trash2 size={16} /> {deleting ? 'Deleting…' : 'Delete event'}
          </button>
        </div>
      </Dialog>
    </div>
  )
}
