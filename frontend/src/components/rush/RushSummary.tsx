import type { RushLineStyle, RushState } from '../../api/types'
import { tipsFor } from '../../lib/rush'

/** The live counters while it runs, and the final numbers when it's over. */
export function RushStats({ state }: { state: RushState }) {
  const tiles = [
    { label: 'Tickets left', value: state.ticketsLeft, sub: `of ${state.tickets}`, tone: state.ticketsLeft === 0 ? 'bad' : '' },
    { label: 'In line', value: state.phase === 'LOBBY' ? state.lobby : state.waiting, sub: state.stillComing > 0 ? `+${state.stillComing} arriving` : undefined },
    { label: 'Served', value: state.admitted },
    { label: 'Got a ticket', value: state.sold, tone: 'warn' },
    { label: 'Paid', value: state.paid, tone: 'good' },
    { label: 'Back on sale', value: state.returned, sub: 'unpaid holds' },
    { label: 'Missed out', value: state.missed, tone: 'bad' },
  ]
  return (
    <div className="rush-stats">
      {tiles.map((t) => (
        <div key={t.label} className={`rush-stat ${t.tone ?? ''}`}>
          <span className="rush-stat-label">{t.label}</span>
          <span className="rush-stat-value">{typeof t.value === 'number' ? t.value.toLocaleString() : t.value}</span>
          {t.sub && <span className="rush-stat-sub">{t.sub}</span>}
        </div>
      ))}
    </div>
  )
}

/** The headline numbers once the rush is over. */
export function FinalNumbers({ state }: { state: RushState }) {
  const soldOutIn = state.soldOutAfterMs != null ? `${(state.soldOutAfterMs / 1000).toFixed(1)}s` : 'Never'
  return (
    <dl className="final-numbers">
      <div><dt>Sold out after</dt><dd>{soldOutIn}</dd></div>
      <div><dt>Line reached</dt><dd>{state.soldOutAtNumber != null ? `#${state.soldOutAtNumber.toLocaleString()}` : '-'}</dd></div>
      <div><dt>Fans</dt><dd>{state.fans.toLocaleString()}</dd></div>
      <div><dt>Unpaid, back on sale</dt><dd>{state.returned}</dd></div>
      <div><dt>Missed out</dt><dd>{state.missed.toLocaleString()}</dd></div>
      <div><dt>Gave up</dt><dd>{state.gaveUp.toLocaleString()}</dd></div>
    </dl>
  )
}

/** Friendly advice shown after a practice rush. */
export function RushTips({ lineStyle }: { lineStyle: RushLineStyle | null }) {
  return (
    <ol className="hood">
      {tipsFor(lineStyle).map((t, i) => (
        <li key={t.title}>
          <span className="hood-num">{i + 1}</span>
          <div>
            <strong>{t.title}</strong>
            <p className="small muted">{t.body}</p>
          </div>
        </li>
      ))}
    </ol>
  )
}
