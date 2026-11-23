import { Armchair, Flame, Music2, Star, Ticket, Users, Zap } from 'lucide-react'
import type { RushLineStyle, RushScenarioId } from '../../api/types'
import { SCENARIOS } from '../../lib/rush'

const ICONS = { hot: Flame, warm: Star, calm: Music2 }

/** Three cards: pick how crazy the sale should be. */
export function ScenarioPicker({ onPick, busy, disabled, lineStyle }: {
  onPick: (id: RushScenarioId) => void
  lineStyle: RushLineStyle
  busy: RushScenarioId | null
  disabled?: boolean
}) {
  return (
    <div className="scenario-grid">
      {SCENARIOS.map((s) => {
        const Icon = ICONS[s.tone]
        return (
          <button
            key={s.id}
            type="button"
            className={`scenario glass scenario-${s.tone}`}
            onClick={() => onPick(s.id)}
            disabled={disabled || busy !== null}
          >
            <span className="scenario-icon"><Icon size={22} /></span>
            <span className="scenario-name">{s.label}</span>
            <span className="scenario-blurb">{s.blurb}</span>
            <span className="scenario-zones"><Armchair size={14} aria-hidden="true" /> {s.zones}</span>
            <span className="scenario-facts">
              <span><Users size={14} /> {s.fans.toLocaleString()} fans</span>
              <span><Ticket size={14} /> {s.tickets} tickets</span>
              <span><Zap size={14} /> {s.perSecond}/s let in</span>
            </span>
            <span className="scenario-odds">{lineStyle === 'FASTEST_CLICK' ? s.fastOdds : s.odds}</span>
            <span className="btn btn-primary btn-block scenario-go">
              {busy === s.id ? 'Starting…' : 'Start this rush'}
            </span>
          </button>
        )
      })}
    </div>
  )
}
