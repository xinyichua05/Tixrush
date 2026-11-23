import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import type { EventResponse } from '../api/types'
import type { FeaturedSlide } from '../lib/events'
import { FeaturedCarousel } from './FeaturedHero'

const ev = (id: number, name: string): EventResponse => ({
  id, name, venue: 'Axiata Arena, Kuala Lumpur', startsAt: '2027-03-13T12:00:00Z', saleStartsAt: '2026-12-01T00:00:00Z',
  totalTickets: 100, availableTickets: 50, imageUrl: null, venueId: null, mapUrl: null, ticketTypes: [], minPrice: null,
})
const slides: FeaturedSlide[] = [
  { event: ev(1, 'Neon Monsoon'), reason: 'next' },
  { event: ev(2, 'Lunar Tides'), reason: 'selling' },
  { event: ev(3, 'Aurora Fields'), reason: 'big' },
]

const renderCarousel = (s = slides) => render(<MemoryRouter><FeaturedCarousel slides={s} /></MemoryRouter>)
/** The slide people can see: the only one that isn't hidden. */
const visibleShow = () => screen.getByRole('heading', { level: 2 }).textContent
/** The progress bar in the active dot is the timer: when its 6 s animation ends, the next show slides in. */
// (jsdom has no AnimationEvent, so React listens for the old prefixed name there; real browsers use "animationend".)
const timeIsUp = () => {
  const bar = screen.getByTestId('hero-progress')
  fireEvent(bar, new Event('webkitAnimationEnd', { bubbles: true }))
  if (bar.isConnected) fireEvent.animationEnd(bar)
}

describe('FeaturedCarousel', () => {
  it('moves on to the next show when the time is up, and loops back to the first', () => {
    renderCarousel()
    expect(visibleShow()).toBe('Neon Monsoon')
    timeIsUp()
    expect(visibleShow()).toBe('Lunar Tides')
    timeIsUp()
    timeIsUp()
    expect(visibleShow()).toBe('Neon Monsoon')
  })

  it('holds still while the mouse is over it', () => {
    renderCarousel()
    fireEvent.mouseEnter(screen.getByRole('region', { name: 'Featured concerts' }))
    expect(screen.getByTestId('hero-progress')).toHaveStyle({ animationPlayState: 'paused' })
    fireEvent.mouseLeave(screen.getByRole('region', { name: 'Featured concerts' }))
    expect(screen.getByTestId('hero-progress')).toHaveStyle({ animationPlayState: 'running' })
  })

  it('the arrows and dots jump straight to a show', () => {
    renderCarousel()
    fireEvent.click(screen.getByRole('button', { name: 'Previous show' }))
    expect(visibleShow()).toBe('Aurora Fields')
    fireEvent.click(screen.getByRole('button', { name: 'Next show' }))
    expect(visibleShow()).toBe('Neon Monsoon')
    fireEvent.click(screen.getByRole('button', { name: 'Show Lunar Tides' }))
    expect(visibleShow()).toBe('Lunar Tides')
    expect(screen.getByRole('button', { name: 'Show Lunar Tides' })).toHaveAttribute('aria-current', 'true')
  })

  it('Pause stops the slideshow, Play starts it again', () => {
    renderCarousel()
    fireEvent.click(screen.getByRole('button', { name: 'Pause slideshow' }))
    expect(screen.queryByTestId('hero-progress')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Play slideshow' }))
    expect(screen.getByTestId('hero-progress')).toBeInTheDocument()
  })

  it('a single show is just a banner: no arrows, dots or timer', () => {
    renderCarousel(slides.slice(0, 1))
    expect(visibleShow()).toBe('Neon Monsoon')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})
