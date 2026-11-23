import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Map as MapIcon, Pencil, Plus, Trash2 } from 'lucide-react'
import { useCallback, useState } from 'react'
import { toast } from 'sonner'
import type { Venue } from '../api/types'
import { createVenue, deleteVenue, listVenues, removeVenueMap, updateVenue, uploadVenueMap } from '../api/venues'
import { Dialog, EmptyState, ErrorMessage, Skeleton } from '../components/ui'
import { VenueForm } from './VenueForm'

type Editing = { mode: 'none' } | { mode: 'create' } | { mode: 'edit'; venue: Venue }

/** Admin → Venues: saved venues with their seating plan and zones, reused by events. */
export function VenuesPanel() {
  const queryClient = useQueryClient()
  const { data: venues, isPending, error } = useQuery({ queryKey: ['venues'], queryFn: listVenues })
  const [editing, setEditing] = useState<Editing>({ mode: 'none' })
  const [toDelete, setToDelete] = useState<Venue | null>(null)
  const [deleting, setDeleting] = useState(false)
  const closeEditor = useCallback(() => setEditing({ mode: 'none' }), [])
  const closeDelete = useCallback(() => setToDelete(null), [])
  // Renaming a venue renames its events too, so refresh both lists.
  const refresh = () => Promise.all([
    queryClient.invalidateQueries({ queryKey: ['venues'] }),
    queryClient.invalidateQueries({ queryKey: ['events'] }),
  ])

  async function confirmDelete() {
    if (!toDelete) return
    setDeleting(true)
    try {
      await deleteVenue(toDelete.id)
      toast.success(`Deleted "${toDelete.name}"`)
      setToDelete(null)
      await refresh()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Delete failed')
    } finally {
      setDeleting(false)
    }
  }

  return (
    <>
      <div className="panel-head">
        <p className="muted">Set up a venue once, with its seating plan and zones. Every event held there can reuse it.</p>
        <button type="button" className="btn btn-primary" onClick={() => setEditing({ mode: 'create' })}>
          <Plus size={18} /> New venue
        </button>
      </div>

      <ErrorMessage error={error} />
      {venues && venues.length === 0 && (
        <EmptyState icon={<MapIcon size={28} />} title="No venues yet">
          Add one to sell concert zones like VIP, CAT 1 or Rock Zone.
        </EmptyState>
      )}

      {(isPending || (venues && venues.length > 0)) && (
        <div className="table-wrap glass">
          <table className="table">
            <thead>
              <tr>
                <th>Venue</th>
                <th>Zones</th>
                <th>Capacity</th>
                <th>Events</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {isPending && [0, 1].map((i) => <tr key={i}><td colSpan={5}><Skeleton height={36} /></td></tr>)}
              {venues?.map((v) => (
                <tr key={v.id}>
                  <td>
                    <div className="cell-event">
                      {v.mapUrl
                        ? <img className="thumb venue-thumb" src={v.mapUrl} alt="" loading="lazy" />
                        : <span className="thumb venue-thumb is-empty"><MapIcon size={16} aria-hidden="true" /></span>}
                      <strong>{v.name}</strong>
                    </div>
                  </td>
                  <td>
                    <div className="zone-chips">
                      {v.zones.map((z) => (
                        <span className="zone-chip" key={z.id}><span className="tt-swatch small" style={{ background: z.color }} aria-hidden="true" />{z.name}</span>
                      ))}
                    </div>
                  </td>
                  <td className="nowrap">{v.totalCapacity.toLocaleString()}</td>
                  <td>{v.eventCount}</td>
                  <td>
                    <div className="row-actions">
                      <button type="button" className="icon-btn" onClick={() => setEditing({ mode: 'edit', venue: v })} aria-label={`Edit ${v.name}`} title="Edit">
                        <Pencil size={16} />
                      </button>
                      <button type="button" className="icon-btn danger" onClick={() => setToDelete(v)} aria-label={`Delete ${v.name}`} title="Delete">
                        <Trash2 size={16} />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Dialog open={editing.mode !== 'none'} onClose={closeEditor} variant="drawer"
        title={editing.mode === 'edit' ? `Edit ${editing.venue.name}` : 'New venue'}>
        {editing.mode !== 'none' && (
          <VenueForm
            key={editing.mode === 'edit' ? editing.venue.id : 'new'}
            initial={editing.mode === 'edit' ? editing.venue : undefined}
            onCancel={closeEditor}
            onSubmit={async (input, map) => {
              // 1. Name + zones. 2. The seating plan, now that we know the venue's id.
              const saved = editing.mode === 'edit' ? await updateVenue(editing.venue.id, input) : await createVenue(input)
              let mapProblem: string | null = null
              try {
                if (map.kind === 'set') await uploadVenueMap(saved.id, map.blob)
                if (map.kind === 'remove') await removeVenueMap(saved.id)
              } catch (e) {
                mapProblem = e instanceof Error ? e.message : 'unknown error'
              }
              if (mapProblem) toast.error(`Venue saved, but the seating plan didn't upload: ${mapProblem}`)
              else toast.success(editing.mode === 'edit' ? 'Venue updated' : 'Venue created')
              await refresh()
              closeEditor()
            }}
          />
        )}
      </Dialog>

      <Dialog open={!!toDelete} onClose={closeDelete} title="Delete venue?">
        <p>
          <strong>{toDelete?.name}</strong>, its seating plan and its zones will be deleted.
          {toDelete && toDelete.eventCount > 0 && ` It is still used by ${toDelete.eventCount} event(s), so it can't be deleted until those change.`}
        </p>
        <div className="form-actions">
          <button type="button" className="btn btn-ghost" onClick={closeDelete}>Cancel</button>
          <button type="button" className="btn btn-danger-solid" onClick={confirmDelete} disabled={deleting || (toDelete?.eventCount ?? 0) > 0}>
            <Trash2 size={16} /> {deleting ? 'Deleting…' : 'Delete venue'}
          </button>
        </div>
      </Dialog>
    </>
  )
}
