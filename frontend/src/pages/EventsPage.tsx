import { useQuery } from '@tanstack/react-query'
import { ArrowRight, Flame, SearchX } from 'lucide-react'
import { Link } from 'react-router'
import { useMemo, useState } from 'react'
import { listEvents } from '../api/events'
import { EventCard, EventCardSkeleton } from '../components/EventCard'
import { EventFilters } from '../components/EventFilters'
import { FeaturedCarousel, FeaturedHeroSkeleton } from '../components/FeaturedHero'
import { EmptyState, ErrorMessage } from '../components/ui'
import { citiesOf, filterEvents, pickFeaturedSlides, type EventFilter } from '../lib/events'
import { useNow } from '../lib/useNow'

export function EventsPage() {
  // useQuery = "fetch this, cache it, give me loading/error states, refresh every 5 s".
  // The ['events'] key is shared: other pages asking for it reuse the same cached data.
  const { data: events, isPending, error } = useQuery({
    queryKey: ['events'],
    queryFn: listEvents,
    refetchInterval: 5000,
  })
  const now = useNow(60_000)
  const [filter, setFilter] = useState<EventFilter>({ query: '', city: null, hideSoldOut: false, sort: 'date' })

  const all = useMemo(() => events ?? [], [events])
  const featured = useMemo(() => pickFeaturedSlides(all, now), [all, now])
  const shown = useMemo(() => filterEvents(all, filter), [all, filter])
  const cities = useMemo(() => citiesOf(all), [all])

  return (
    <div className="container">
      <h1 className="sr-only">TixRush concert tickets</h1>
      {isPending ? <FeaturedHeroSkeleton /> : <FeaturedCarousel slides={featured} />}

      <Link to="/practice" className="rush-promo glass">
        <span className="rush-promo-text">
          <span className="rush-promo-icon"><Flame size={22} /></span>
          <span>
            <strong>Practice a ticket rush: can you beat 2,000 fans to a seat?</strong>
            <span className="muted small">Wait in line, grab a seat and pay before time runs out. Just for fun, no real tickets.</span>
          </span>
        </span>
        <span className="btn btn-primary btn-sm">Try it <ArrowRight size={16} /></span>
      </Link>

      <section id="all-events" className="section">
        <div className="section-head">
          <div>
            <h2 className="section-title">All concerts</h2>
            <p className="muted">
              {events ? `${shown.length} of ${all.length} shows` : 'Loading shows…'} · seat counts update live
            </p>
          </div>
        </div>

        <EventFilters value={filter} onChange={setFilter} cities={cities} />
        <ErrorMessage error={error} />

        <div className="event-grid">
          {isPending && Array.from({ length: 6 }, (_, i) => <EventCardSkeleton key={i} />)}
          {shown.map((e) => (
            <EventCard key={e.id} event={e} />
          ))}
        </div>

        {events && shown.length === 0 && (
          <EmptyState icon={<SearchX size={28} />} title={all.length ? 'No shows match your filters' : 'No concerts yet'}>
            {all.length ? 'Try another search or city.' : 'An admin can add the first one.'}
          </EmptyState>
        )}
      </section>
    </div>
  )
}
