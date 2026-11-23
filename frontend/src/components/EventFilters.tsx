import { Search, X } from 'lucide-react'
import type { EventFilter, SortBy } from '../lib/events'

/** Search box, city chips, sort and a "hide sold out" switch. The parent owns the filter state. */
export function EventFilters({ value, onChange, cities }: {
  value: EventFilter
  onChange: (next: EventFilter) => void
  cities: string[]
}) {
  const set = (patch: Partial<EventFilter>) => onChange({ ...value, ...patch })

  return (
    <div className="filters">
      <div className="search">
        <Search size={18} className="search-icon" />
        <input
          type="search"
          placeholder="Search concerts or venues…"
          value={value.query}
          onChange={(e) => set({ query: e.target.value })}
          aria-label="Search concerts or venues"
        />
        {value.query && (
          <button type="button" className="search-clear" onClick={() => set({ query: '' })} aria-label="Clear search">
            <X size={16} />
          </button>
        )}
      </div>

      <div className="filter-row">
        <div className="chip-group" role="group" aria-label="City">
          <button type="button" className={`chip-btn ${value.city === null ? 'active' : ''}`} onClick={() => set({ city: null })}>
            All cities
          </button>
          {cities.map((c) => (
            <button key={c} type="button" className={`chip-btn ${value.city === c ? 'active' : ''}`} onClick={() => set({ city: c })}>
              {c}
            </button>
          ))}
        </div>

        <div className="filter-right">
          <label className="switch">
            <input type="checkbox" checked={value.hideSoldOut} onChange={(e) => set({ hideSoldOut: e.target.checked })} />
            <span className="switch-track" aria-hidden="true" />
            Hide sold out
          </label>
          <select className="select" value={value.sort} onChange={(e) => set({ sort: e.target.value as SortBy })} aria-label="Sort by">
            <option value="date">Soonest first</option>
            <option value="selling">Selling fast</option>
          </select>
        </div>
      </div>
    </div>
  )
}
