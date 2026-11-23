import { Dices, MousePointerClick } from 'lucide-react'
import type { RushLineStyle } from '../../api/types'
import { LINE_STYLES } from '../../lib/rush'

const ICONS = { RANDOM_LOBBY: Dices, FASTEST_CLICK: MousePointerClick }

/** "How does the line work?": the two real-world styles, as a two-option switch. */
export function LineStylePicker({ value, onChange }: { value: RushLineStyle; onChange: (v: RushLineStyle) => void }) {
  return (
    <div className="style-picker" role="radiogroup" aria-label="How the line works">
      {LINE_STYLES.map((s) => {
        const Icon = ICONS[s.id]
        const active = s.id === value
        return (
          <button
            key={s.id}
            type="button"
            role="radio"
            aria-checked={active}
            className={`style-option ${active ? 'active' : ''}`}
            onClick={() => onChange(s.id)}
          >
            <span className="style-icon"><Icon size={20} /></span>
            <span className="style-text">
              <strong>{s.label}</strong>
              <span className="style-like">{s.like}</span>
              <span className="style-blurb">{s.blurb}</span>
            </span>
          </button>
        )
      })}
    </div>
  )
}
