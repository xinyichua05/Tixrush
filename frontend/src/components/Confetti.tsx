import { useMemo } from 'react'
import { seededRandom } from '../lib/poster'

const COLORS = ['#ff5a1f', '#ff2d75', '#8b5cf6', '#22d3ee', '#facc15', '#34d399']

/** A one-off confetti burst (pure CSS animation, no library). */
export function Confetti({ pieces = 60 }: { pieces?: number }) {
  // Positions come from a seeded generator: random-looking, but the same on every re-render,
  // so a ticking timer elsewhere on the page doesn't restart the animation.
  const bits = useMemo(() => {
    const rnd = seededRandom(pieces * 7919)
    return Array.from({ length: pieces }, (_, i) => ({
      left: rnd() * 100,
      delay: rnd() * 0.4,
      duration: 1.6 + rnd() * 1.4,
      rotate: rnd() * 360,
      color: COLORS[i % COLORS.length],
      size: 6 + rnd() * 6,
    }))
  }, [pieces])
  return (
    <div className="confetti" aria-hidden="true">
      {bits.map((b, i) => (
        <span
          key={i}
          style={{
            left: `${b.left}%`,
            width: b.size,
            height: b.size * 0.45,
            background: b.color,
            animationDelay: `${b.delay}s`,
            animationDuration: `${b.duration}s`,
            transform: `rotate(${b.rotate}deg)`,
          }}
        />
      ))}
    </div>
  )
}
