import { apiFetch } from './client'
import type { EventInput, EventResponse } from './types'

export const listEvents = () => apiFetch<EventResponse[]>('/api/events')

export const getEvent = (id: number) => apiFetch<EventResponse>(`/api/events/${id}`)

// Admin only (the backend returns 403 for normal users)
export const createEvent = (input: EventInput) =>
  apiFetch<EventResponse>('/api/events', { method: 'POST', body: input })

export const updateEvent = (id: number, input: EventInput) =>
  apiFetch<EventResponse>(`/api/events/${id}`, { method: 'PUT', body: input })

export const deleteEvent = (id: number) => apiFetch<void>(`/api/events/${id}`, { method: 'DELETE' })

/** Admin: set the event's picture (a JPG/PNG/WebP, already shrunk by lib/image.ts). */
export const uploadEventImage = (id: number, picture: Blob) => {
  const form = new FormData()
  form.append('file', picture, picture.type === 'image/jpeg' ? 'picture.jpg' : 'picture.webp')
  return apiFetch<EventResponse>(`/api/events/${id}/image`, { method: 'PUT', body: form })
}

/** Admin: remove the picture (the generated art comes back). */
export const removeEventImage = (id: number) => apiFetch<void>(`/api/events/${id}/image`, { method: 'DELETE' })
