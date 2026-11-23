import { useId, useState } from 'react'
import { formatMmSs } from '../lib/format'
import { useNow } from '../lib/useNow'

/**
 * A circular timer that drains as the payment hold runs out.
 * The ring starts full at the moment this page opened (we don't know the original hold length,
 * only the deadline), then shrinks every second. Red near the end.
 */
export function CountdownRing({ until, size = 220 }: { until: string; size?: number }) {
  const now = useNow(1000)
  const end = new Date(until).getTime()
  const [total] = useState(() => Math.max(1, (end - Date.now()) / 1000))
  const left = Math.max(0, (end - now) / 1000)
  const fraction = Math.min(1, left / total)

  const r = 92
  const circumference = 2 * Math.PI * r
  const urgent = left < Math.min(60, total / 3) // the last minute, or the last third of a short hold
  const gid = useId().replace(/[^a-zA-Z0-9_-]/g, '')

  return (
    <div className={`ring ${urgent ? 'urgent' : ''}`} style={{ width: size, height: size }}>
      <svg viewBox="0 0 220 220" aria-hidden="true">
        <defs>
          <linearGradient id={`ring${gid}`} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="#ff5a1f" />
            <stop offset=".5" stopColor="#ff2d75" />
            <stop offset="1" stopColor="#8b5cf6" />
          </linearGradient>
        </defs>
        <circle cx="110" cy="110" r={r} className="ring-track" />
        <circle
          cx="110"
          cy="110"
          r={r}
          className="ring-progress"
          stroke={urgent ? 'var(--bad)' : `url(#ring${gid})`}
          strokeDasharray={circumference}
          strokeDashoffset={circumference * (1 - fraction)}
          transform="rotate(-90 110 110)"
        />
      </svg>
      <div className="ring-center">
        <span className="ring-time" role="timer">{formatMmSs(left)}</span>
        <span className="ring-label">left to pay</span>
      </div>
    </div>
  )
}
