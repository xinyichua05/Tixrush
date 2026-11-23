import { ArrowRight, CalendarDays, ChevronLeft, ChevronRight, Clock, Flame, MapPin, Pause, Play, Sparkles, Star } from 'lucide-react'
import { useEffect, useRef, useState, type FocusEvent, type PointerEvent } from 'react'
import { Link } from 'react-router'
import type { EventResponse } from '../api/types'
import { cityOf, type FeaturedReason, type FeaturedSlide } from '../lib/events'
import { formatLongDate, formatTime } from '../lib/format'
import { EventPoster } from './EventPoster'
import { SeatsBar } from './SeatsBar'
import { Skeleton } from './ui'

/** How long each show stays on screen. The same time is used by the CSS progress bar (.hero-dot-fill). */
export const SLIDE_MS = 6000

const EYEBROW: Record<FeaturedReason, { icon: typeof Sparkles; label: (city: string) => string }> = {
  next: { icon: Sparkles, label: (city) => `Featured · Next up in ${city}` },
  selling: { icon: Flame, label: (city) => `Selling fast · ${city}` },
  big: { icon: Star, label: (city) => `Big show · ${city}` },
}

/** One big banner: a show, its date and seats, and the poster. */
export function FeaturedHero({ event, reason = 'next' }: { event: EventResponse; reason?: FeaturedReason }) {
  const eyebrow = EYEBROW[reason]
  const Icon = eyebrow.icon
  return (
    <section className="hero-featured">
      {/* the same poster, blurred and huge, as a glowing backdrop */}
      <EventPoster event={event} className="hero-backdrop" showInitials={false} />
      <div className="hero-content">
        <span className="eyebrow">
          <Icon size={14} /> {eyebrow.label(cityOf(event.venue))}
        </span>
        <h2 className="hero-title">{event.name}</h2>
        <div className="chips">
          <span className="chip"><CalendarDays size={15} /> {formatLongDate(event.startsAt)}</span>
          <span className="chip"><Clock size={15} /> {formatTime(event.startsAt)}</span>
          <span className="chip"><MapPin size={15} /> {event.venue}</span>
        </div>
        <div className="hero-seats">
          <SeatsBar available={event.availableTickets} total={event.totalTickets} large />
        </div>
        <div className="hero-actions">
          <Link to={`/events/${event.id}`} className="btn btn-primary btn-lg">
            Get tickets <ArrowRight size={18} />
          </Link>
          <a href="#all-events" className="btn btn-glass btn-lg">
            Browse all
          </a>
        </div>
      </div>
      <Link to={`/events/${event.id}`} className="hero-poster-wrap" aria-label={`${event.name} poster`} tabIndex={-1}>
        <EventPoster event={event} className="hero-poster" showInitials={false}>
          {/* text laid out like a real gig poster */}
          <div className="poster-face">
            <span className="poster-face-top">{cityOf(event.venue)} · Live</span>
            <span className="poster-face-date">{formatLongDate(event.startsAt)}</span>
            <strong className="poster-face-title">{event.name}</strong>
            <span className="poster-face-venue">{event.venue}</span>
          </div>
        </EventPoster>
      </Link>
    </section>
  )
}

/** True when the visitor asked their device for less motion: then the carousel never moves on its own. */
const REDUCED_MOTION = '(prefers-reduced-motion: reduce)'
function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(() => window.matchMedia?.(REDUCED_MOTION).matches ?? false)
  useEffect(() => {
    const query = window.matchMedia?.(REDUCED_MOTION)
    if (!query) return
    const onChange = () => setReduced(query.matches)
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [])
  return reduced
}

/**
 * The home-page banner: the important shows slide by on their own, one every 6 s.
 * The timer IS the CSS progress bar in the active dot: when its animation ends, we move on. So pausing
 * (mouse over it, keyboard focus inside, tab hidden, or the Pause button) just pauses that animation,
 * and the bar and the slide change can never drift apart.
 */
export function FeaturedCarousel({ slides }: { slides: FeaturedSlide[] }) {
  const [index, setIndex] = useState(0)
  const [round, setRound] = useState(0) // bumped on every move: restarts the progress bar
  const [hovered, setHovered] = useState(false)
  const [focused, setFocused] = useState(false)
  const [userPaused, setUserPaused] = useState(false)
  const [tabHidden, setTabHidden] = useState(() => document.visibilityState === 'hidden')
  const reducedMotion = usePrefersReducedMotion()
  const swipeFrom = useRef<number | null>(null)

  useEffect(() => {
    const onVisibility = () => setTabHidden(document.visibilityState === 'hidden')
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }, [])

  const count = slides.length
  if (count === 0) return null
  if (count === 1) return <FeaturedHero event={slides[0].event} reason={slides[0].reason} />

  const current = Math.min(index, count - 1) // the list can shrink when a show sells out
  const autoplay = !userPaused && !reducedMotion
  const running = autoplay && !hovered && !focused && !tabHidden
  const go = (i: number) => {
    setIndex(((i % count) + count) % count) // wraps: after the last comes the first
    setRound((r) => r + 1)
  }

  // Pause while the keyboard is inside (so a Tab doesn't get yanked away), but not after a mouse click.
  const onFocus = (e: FocusEvent<HTMLElement>) => {
    try {
      if (e.target.matches(':focus-visible')) setFocused(true)
    } catch {
      setFocused(true)
    }
  }
  const onBlur = (e: FocusEvent<HTMLElement>) => {
    if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setFocused(false)
  }
  // Swipe left/right on touch screens (vertical scrolling still works: touch-action: pan-y).
  const onPointerDown = (e: PointerEvent) => {
    if (e.pointerType !== 'mouse') swipeFrom.current = e.clientX
  }
  const onPointerUp = (e: PointerEvent) => {
    if (swipeFrom.current === null) return
    const dx = e.clientX - swipeFrom.current
    swipeFrom.current = null
    if (Math.abs(dx) > 50) go(current + (dx < 0 ? 1 : -1))
  }

  return (
    <section
      className="hero-carousel"
      aria-roledescription="carousel"
      aria-label="Featured concerts"
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onFocus={onFocus}
      onBlur={onBlur}
    >
      <div
        className="hero-track"
        style={{ transform: `translateX(-${current * 100}%)` }}
        onPointerDown={onPointerDown}
        onPointerUp={onPointerUp}
        onPointerCancel={() => (swipeFrom.current = null)}
      >
        {slides.map((s, i) => (
          <div
            key={s.event.id}
            className="hero-slide"
            role="group"
            aria-roledescription="slide"
            aria-label={`${i + 1} of ${count}`}
            aria-hidden={i !== current}
            inert={i !== current}
          >
            <FeaturedHero event={s.event} reason={s.reason} />
          </div>
        ))}
      </div>

      <button type="button" className="hero-arrow hero-arrow-prev" onClick={() => go(current - 1)} aria-label="Previous show">
        <ChevronLeft size={22} />
      </button>
      <button type="button" className="hero-arrow hero-arrow-next" onClick={() => go(current + 1)} aria-label="Next show">
        <ChevronRight size={22} />
      </button>

      <div className="hero-controls">
        {slides.map((s, i) => (
          <button
            key={s.event.id}
            type="button"
            className={`hero-dot${i === current ? ' is-active' : ''}${i === current && !autoplay ? ' is-still' : ''}`}
            onClick={() => go(i)}
            aria-label={`Show ${s.event.name}`}
            aria-current={i === current}
          >
            <span className="hero-dot-bar">
              {i === current && autoplay && (
                <span
                  key={round}
                  className="hero-dot-fill"
                  data-testid="hero-progress"
                  style={{ animationDuration: `${SLIDE_MS}ms`, animationPlayState: running ? 'running' : 'paused' }}
                  onAnimationEnd={() => go(current + 1)}
                />
              )}
            </span>
          </button>
        ))}
        {!reducedMotion && (
          <button
            type="button"
            className="hero-pause"
            onClick={() => setUserPaused((p) => !p)}
            aria-label={userPaused ? 'Play slideshow' : 'Pause slideshow'}
          >
            {userPaused ? <Play size={13} fill="currentColor" /> : <Pause size={13} fill="currentColor" />}
          </button>
        )}
      </div>
    </section>
  )
}

export function FeaturedHeroSkeleton() {
  return (
    <section className="hero-featured is-skeleton" aria-hidden="true">
      <div className="hero-content">
        <Skeleton height={14} width={180} />
        <Skeleton height={52} width="80%" />
        <Skeleton height={32} width="60%" />
        <Skeleton height={10} width="70%" />
        <Skeleton height={48} width={220} radius={14} />
      </div>
    </section>
  )
}
