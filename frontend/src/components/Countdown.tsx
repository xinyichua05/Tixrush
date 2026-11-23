import { formatMmSs } from '../lib/format'
import { useNow } from '../lib/useNow'

/** Live "9:41" until the given time. Turns red in the last minute, shows "0:00" after. */
export function Countdown({ until, className = '' }: { until: string; className?: string }) {
  const now = useNow(1000)
  const secondsLeft = Math.max(0, (new Date(until).getTime() - now) / 1000)
  const urgent = secondsLeft < 60
  return (
    <span className={`countdown ${urgent ? 'urgent' : ''} ${className}`} role="timer" aria-live="off">
      {formatMmSs(secondsLeft)}
    </span>
  )
}
