import type { EventResponse } from '../api/types'

// Pure helpers for the events list: no React, so they're easy to test.

// Venue text -> city, for the city filter chips. Unknown venues use the text after the last comma.
const CITY_HINTS: [RegExp, string][] = [
  [/kuala lumpur|\bkl\b|bukit jalil|publika|petronas|zepp/i, 'Kuala Lumpur'],
  [/penang|george town/i, 'Penang'],
  [/sepang|shah alam|petaling jaya|selangor/i, 'Selangor'],
  [/johor/i, 'Johor'],
]

export function cityOf(venue: string): string {
  for (const [pattern, city] of CITY_HINTS) if (pattern.test(venue)) return city
  const parts = venue.split(',')
  return parts.length > 1 ? parts[parts.length - 1].trim() : 'Other'
}

export type SortBy = 'date' | 'selling'

export interface EventFilter {
  query: string
  city: string | null // null = all cities
  hideSoldOut: boolean
  sort: SortBy
}

export const soldRatio = (e: EventResponse) => (e.totalTickets ? 1 - e.availableTickets / e.totalTickets : 0)
export const isSellingFast = (e: EventResponse) => e.availableTickets > 0 && e.availableTickets / e.totalTickets <= 0.25

export function filterEvents(events: EventResponse[], f: EventFilter): EventResponse[] {
  const q = f.query.trim().toLowerCase()
  return events
    .filter((e) => !q || e.name.toLowerCase().includes(q) || e.venue.toLowerCase().includes(q))
    .filter((e) => !f.city || cityOf(e.venue) === f.city)
    .filter((e) => !f.hideSoldOut || e.availableTickets > 0)
    .sort((a, b) =>
      f.sort === 'selling' ? soldRatio(b) - soldRatio(a) || a.startsAt.localeCompare(b.startsAt) : a.startsAt.localeCompare(b.startsAt),
    )
}

export function citiesOf(events: EventResponse[]): string[] {
  return [...new Set(events.map((e) => cityOf(e.venue)))].sort()
}

/** A show needs at least this share sold before we call it "selling fast" (1 of 5,000 isn't). */
const SELLING_FAST = 0.1

export type FeaturedReason = 'next' | 'selling' | 'big'
export interface FeaturedSlide {
  event: EventResponse
  reason: FeaturedReason
}

/**
 * The home-page carousel: the shows worth a big banner. Only upcoming shows that still have seats.
 * 1. "next": the soonest show. 2. "selling": the one selling fastest (biggest share sold, at least 10%).
 * 3. "big": the rest, biggest concerts first. No show appears twice; at most `max` slides.
 */
export function pickFeaturedSlides(events: EventResponse[], now: number, max = 5): FeaturedSlide[] {
  const open = events.filter((e) => e.availableTickets > 0 && new Date(e.startsAt).getTime() > now)
  const slides: FeaturedSlide[] = []
  const add = (event: EventResponse | undefined, reason: FeaturedReason) => {
    if (event && slides.length < max && !slides.some((s) => s.event.id === event.id)) slides.push({ event, reason })
  }
  const soldShare = (e: EventResponse) => (e.totalTickets - e.availableTickets) / e.totalTickets

  add([...open].sort((a, b) => a.startsAt.localeCompare(b.startsAt))[0], 'next')
  add([...open].filter((e) => soldShare(e) >= SELLING_FAST).sort((a, b) => soldShare(b) - soldShare(a))
    .find((e) => !slides.some((s) => s.event.id === e.id)), 'selling')
  for (const e of [...open].sort((a, b) => b.totalTickets - a.totalTickets)) add(e, 'big')
  return slides
}
