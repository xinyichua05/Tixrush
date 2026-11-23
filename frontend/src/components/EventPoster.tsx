import { useId, useMemo, useState, type ReactNode } from 'react'
import { posterFor, seededRandom, type PosterArt } from '../lib/poster'

/**
 * An event's artwork. If an admin uploaded a picture (event.imageUrl), that photo fills the frame.
 * Otherwise (and underneath, while the photo loads or if it fails) we draw generated art:
 * SVG, so it's sharp at any size and needs no image files. Gradient background + one of 4 patterns
 * + film grain + the event's initials as a watermark.
 */
export function EventPoster({ event, className = '', showInitials = true, children }: {
  event: { id: number; name: string; imageUrl?: string | null }
  className?: string
  showInitials?: boolean
  children?: ReactNode
}) {
  const art = useMemo(() => posterFor(event), [event])
  // Remember a picture URL that failed to load, so we fall back to the generated art for it.
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  const photo = event.imageUrl && event.imageUrl !== failedUrl ? event.imageUrl : null
  // useId gives each SVG its own gradient ids, so several posters on one page don't clash.
  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '')

  return (
    <div className={`poster ${className}${photo ? ' has-photo' : ''}`}>
      <svg className="poster-svg" viewBox="0 0 400 300" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
        <defs>
          <linearGradient id={`bg${uid}`} gradientTransform={`rotate(${art.angle} .5 .5)`}>
            <stop offset="0" stopColor={art.colors[0]} />
            <stop offset=".55" stopColor={art.colors[1]} />
            <stop offset="1" stopColor={art.colors[2]} />
          </linearGradient>
          <radialGradient id={`glow${uid}`} cx=".5" cy=".5" r=".5">
            <stop offset="0" stopColor="#fff" stopOpacity=".55" />
            <stop offset="1" stopColor="#fff" stopOpacity="0" />
          </radialGradient>
          <linearGradient id={`shade${uid}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset=".45" stopColor="#000" stopOpacity="0" />
            <stop offset="1" stopColor="#000" stopOpacity=".45" />
          </linearGradient>
          <filter id={`grain${uid}`}>
            <feTurbulence type="fractalNoise" baseFrequency=".9" numOctaves="2" stitchTiles="stitch" />
            <feColorMatrix type="saturate" values="0" />
          </filter>
          <filter id={`blur${uid}`}>
            <feGaussianBlur stdDeviation="18" />
          </filter>
        </defs>

        <rect width="400" height="300" fill={`url(#bg${uid})`} />
        <Pattern art={art} uid={uid} />
        <rect width="400" height="300" fill={`url(#shade${uid})`} />
        <rect width="400" height="300" filter={`url(#grain${uid})`} opacity=".14" style={{ mixBlendMode: 'overlay' }} />
        {showInitials && (
          <text x="22" y="282" className="poster-initials" fill="#fff" fillOpacity=".22">
            {art.initials}
          </text>
        )}
      </svg>
      {photo && (
        <img className="poster-img" src={photo} alt="" loading="lazy" decoding="async" onError={() => setFailedUrl(photo)} />
      )}
      {children}
    </div>
  )
}

function Pattern({ art, uid }: { art: PosterArt; uid: string }) {
  const rnd = seededRandom(art.seed)

  switch (art.pattern) {
    case 'waves': {
      const lines = Array.from({ length: 9 }, (_, i) => {
        const y = 60 + i * 26 + rnd() * 10
        const amp = 12 + rnd() * 26
        const shift = rnd() * 200
        let d = `M -20 ${y}`
        for (let x = -20; x <= 420; x += 20) d += ` L ${x} ${y + Math.sin((x + shift) / 45) * amp}`
        return <path key={i} d={d} fill="none" stroke="#fff" strokeOpacity={0.08 + i * 0.035} strokeWidth={1.5 + (i % 3)} />
      })
      return (
        <g>
          <circle cx={80 + rnd() * 240} cy={70 + rnd() * 60} r="90" fill={`url(#glow${uid})`} opacity=".7" />
          {lines}
        </g>
      )
    }
    case 'orbs':
      return (
        <g filter={`url(#blur${uid})`}>
          {Array.from({ length: 5 }, (_, i) => (
            <circle
              key={i}
              cx={rnd() * 400}
              cy={rnd() * 300}
              r={40 + rnd() * 90}
              fill={i % 2 ? '#fff' : art.colors[(i + 1) % 3]}
              opacity={i % 2 ? 0.25 : 0.85}
            />
          ))}
        </g>
      )
    case 'rays': {
      const cx = 120 + rnd() * 160
      return (
        <g>
          {Array.from({ length: 16 }, (_, i) => {
            const a = Math.PI + (i / 15) * Math.PI
            return (
              <line
                key={i}
                x1={cx}
                y1={310}
                x2={cx + Math.cos(a) * 520}
                y2={310 + Math.sin(a) * 520}
                stroke="#fff"
                strokeOpacity={0.06 + rnd() * 0.14}
                strokeWidth={6 + rnd() * 14}
              />
            )
          })}
          <circle cx={cx} cy={300} r="70" fill={`url(#glow${uid})`} />
        </g>
      )
    }
    case 'horizon':
    default: {
      // A synthwave sun over a perspective grid.
      const sunX = 140 + rnd() * 120
      return (
        <g>
          <circle cx={sunX} cy="150" r="78" fill="#fff" fillOpacity=".22" />
          {Array.from({ length: 5 }, (_, i) => (
            <rect key={i} x={sunX - 90} y={150 + i * 13} width="180" height={2 + i * 1.6} fill={art.colors[1]} />
          ))}
          <g stroke="#fff" strokeOpacity=".28" strokeWidth="1.2">
            {Array.from({ length: 7 }, (_, i) => (
              <line key={`h${i}`} x1="0" x2="400" y1={205 + i * i * 2.4} y2={205 + i * i * 2.4} />
            ))}
            {Array.from({ length: 13 }, (_, i) => (
              <line key={`v${i}`} x1={200 + (i - 6) * 14} y1="205" x2={200 + (i - 6) * 90} y2="300" />
            ))}
          </g>
        </g>
      )
    }
  }
}
