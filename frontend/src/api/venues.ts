import { apiFetch } from './client'
import type { Venue, VenueInput } from './types'

export const listVenues = () => apiFetch<Venue[]>('/api/venues')

export const getVenue = (id: number) => apiFetch<Venue>(`/api/venues/${id}`)

// Admin only
export const createVenue = (input: VenueInput) => apiFetch<Venue>('/api/venues', { method: 'POST', body: input })

export const updateVenue = (id: number, input: VenueInput) =>
  apiFetch<Venue>(`/api/venues/${id}`, { method: 'PUT', body: input })

export const deleteVenue = (id: number) => apiFetch<void>(`/api/venues/${id}`, { method: 'DELETE' })

/** Admin: set the venue's seating-plan picture (already shrunk by lib/image.ts). */
export const uploadVenueMap = (id: number, picture: Blob) => {
  const form = new FormData()
  form.append('file', picture, picture.type === 'image/jpeg' ? 'plan.jpg' : 'plan.webp')
  return apiFetch<Venue>(`/api/venues/${id}/map`, { method: 'PUT', body: form })
}

export const removeVenueMap = (id: number) => apiFetch<void>(`/api/venues/${id}/map`, { method: 'DELETE' })
