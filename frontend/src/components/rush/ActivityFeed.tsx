import { Ban, CircleCheck, DoorOpen, Flag, Info, PanelRightClose, PanelRightOpen, RotateCcw, Ticket, User, XCircle } from 'lucide-react'
import type { RushFeedItem } from '../../api/types'
import { formatSinceOpen, groupRepeats } from '../../lib/rush'

const ICON = {
  info: Info,
  open: DoorOpen,
  sold: Ticket,
  paid: CircleCheck,
  returned: RotateCcw,
  missed: XCircle,
  soldout: Ban,
  you: User,
  end: Flag,
} as const

/** The full log, newest first (shown in the slide-in panel). Back-to-back repeats show as one row with ×N. */
export function ActivityFeed({ items }: { items: RushFeedItem[] }) {
  const rows = groupRepeats(items)
  // Stable keys, so only NEW rows play the fade-in. (A key with the row's position would change for every
  // row each time an event arrives at the top, and the whole list would flicker.) Counted from the oldest
  // end, because new events are added at the top.
  const seen = new Map<string, number>()
  const keys = rows.map((r) => `${r.atMs}|${r.kind}|${r.text}`).reverse()
    .map((base) => {
      const n = seen.get(base) ?? 0
      seen.set(base, n + 1)
      return `${base}|${n}`
    })
    .reverse()
  return (
    <ol className="feed" aria-live="polite">
      {rows.map((row, i) => {
        const Icon = ICON[row.kind] ?? Info
        return (
          <li key={keys[i]} className={`feed-item feed-${row.kind}`}>
            <span className="feed-icon"><Icon size={14} /></span>
            <span className="feed-text" title={row.text}>{row.text}</span>
            <span className="feed-meta">
              {row.count > 1 && <span className="feed-count" aria-label={`${row.count} times`}>×{row.count}</span>}
              <time className="feed-time">{formatSinceOpen(row.atMs)}</time>
            </span>
          </li>
        )
      })}
    </ol>
  )
}

/** One slim line: the newest event. Clicking it opens (or closes) the full log. "N new" = arrived since you last looked. */
export function LatestActivity({ items, unseen, open, onToggle }: {
  items: RushFeedItem[]
  unseen: number
  open: boolean
  onToggle: () => void
}) {
  const latest = items[0]
  const Icon = latest ? ICON[latest.kind] ?? Info : Info
  return (
    <button type="button" className={`latest-activity${latest ? ` feed-${latest.kind}` : ''}`} aria-expanded={open} onClick={onToggle}>
      <span className="feed-icon"><Icon size={14} /></span>
      <span className="feed-text">{latest ? latest.text : 'Nothing yet'}</span>
      {unseen > 0 && <span className="latest-new">{unseen > 9 ? '9+' : unseen} new</span>}
      <span className="latest-open">
        {open ? <><PanelRightClose size={15} /> Close log</> : <><PanelRightOpen size={15} /> Open log</>}
      </span>
    </button>
  )
}
