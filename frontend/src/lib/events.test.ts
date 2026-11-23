import { describe, expect, it } from 'vitest'
import type { EventResponse } from '../api/types'
import { cityOf, filterEvents, pickFeaturedSlides, type EventFilter } from './events'

const ev = (id: number, name: string, venue: string, startsAt: string, available: number, total = 100): EventResponse => ({
  id, name, venue, startsAt, saleStartsAt: '2026-12-01T00:00:00Z', totalTickets: total, availableTickets: available, imageUrl: null, venueId: null, mapUrl: null, ticketTypes: [], minPrice: null,
})

const events = [
  ev(1, 'Neon Monsoon', 'Axiata Arena, Kuala Lumpur', '2027-03-13T12:00:00Z', 80),
  ev(2, 'Lunar Tides', 'SPICE Arena, Penang', '2027-04-17T12:00:00Z', 10),
  ev(3, 'Echo Valley', 'The Bee, Publika', '2027-02-26T13:00:00Z', 0),
]
const base: EventFilter = { query: '', city: null, hideSoldOut: false, sort: 'date' }

describe('event list helpers', () => {
  it('works out the city from the venue', () => {
    expect(cityOf('Axiata Arena, Kuala Lumpur')).toBe('Kuala Lumpur')
    expect(cityOf('The Bee, Publika')).toBe('Kuala Lumpur')
    expect(cityOf('SPICE Arena, Penang')).toBe('Penang')
    expect(cityOf('Somewhere Hall, Ipoh')).toBe('Ipoh')
  })

  it('searches by name or venue, case-insensitive', () => {
    expect(filterEvents(events, { ...base, query: 'spice' }).map((e) => e.id)).toEqual([2])
    expect(filterEvents(events, { ...base, query: 'MONSOON' }).map((e) => e.id)).toEqual([1])
  })

  it('filters by city and hides sold-out shows', () => {
    expect(filterEvents(events, { ...base, city: 'Kuala Lumpur' }).map((e) => e.id)).toEqual([3, 1])
    expect(filterEvents(events, { ...base, hideSoldOut: true }).map((e) => e.id)).toEqual([1, 2])
  })

  it('sorts by date or by how fast seats are selling', () => {
    expect(filterEvents(events, base).map((e) => e.id)).toEqual([3, 1, 2])
    expect(filterEvents(events, { ...base, sort: 'selling' }).map((e) => e.id)).toEqual([3, 2, 1])
  })

  it('features the next show, then the fastest-selling, then the biggest; skips past and sold-out shows', () => {
    const now = Date.parse('2027-01-01')
    const list = [
      ...events, // 3 is sold out
      ev(4, 'Old Show', 'Axiata Arena, Kuala Lumpur', '2026-06-01T12:00:00Z', 50), // already happened
      ev(5, 'Aurora Fields', 'Sepang, Selangor', '2027-05-22T08:00:00Z', 20000, 20000),
      ev(6, 'Kopi & Keys', 'KLPAC, Kuala Lumpur', '2027-01-23T11:30:00Z', 850, 850),
      ev(7, 'Small Gig', 'The Bee, Publika', '2027-06-01T12:00:00Z', 30, 30),
    ]
    const slides = pickFeaturedSlides(list, now)
    expect(slides.map((s) => [s.event.id, s.reason])).toEqual([
      [6, 'next'], // soonest
      [2, 'selling'], // 90% sold
      [5, 'big'], // 20,000 seats
      [1, 'big'], // 100
      [7, 'big'], // 30
    ])
    expect(pickFeaturedSlides(list, now, 2)).toHaveLength(2)
    // Barely sold (1 of 5,000) isn't "selling fast": no selling slide, just the next show and the big ones.
    const quiet = [ev(8, 'Rush Hour', 'Bukit Jalil, Kuala Lumpur', '2027-03-20T12:00:00Z', 4999, 5000), ev(9, 'Next', 'The Bee, Publika', '2027-01-15T12:00:00Z', 10, 10)]
    expect(pickFeaturedSlides(quiet, now).map((s) => s.reason)).toEqual(['next', 'big'])
    expect(pickFeaturedSlides([], now)).toEqual([])
  })
})
