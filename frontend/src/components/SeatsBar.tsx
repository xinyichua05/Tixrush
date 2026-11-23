/** Seats left, with a glowing bar that fills up as seats sell. */
export function SeatsBar({ available, total, large = false }: { available: number; total: number; large?: boolean }) {
  const soldPct = total > 0 ? ((total - available) / total) * 100 : 0
  const level = available === 0 ? 'out' : available / total <= 0.25 ? 'low' : 'ok'
  return (
    <div className={`seats ${large ? 'seats-large' : ''}`}>
      <div className="seats-track" aria-hidden="true">
        <div className={`seats-fill ${level}`} style={{ width: `${Math.max(soldPct, available === total ? 0 : 2)}%` }} />
      </div>
      <div className="seats-label">
        {available === 0 ? (
          <strong className="text-bad">Sold out</strong>
        ) : (
          <span>
            <strong>{available.toLocaleString()}</strong> of {total.toLocaleString()} left
          </span>
        )}
        <span className="seats-pct">{Math.round(soldPct)}% sold</span>
      </div>
    </div>
  )
}
