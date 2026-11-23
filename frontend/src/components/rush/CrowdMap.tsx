import { useEffect, useRef, useState } from 'react'
import { CROWD_KEY, countCrowd, crowdColor } from '../../lib/rush'

const MAX_HEIGHT = 300

/**
 * One dot per fan, in line order (front of the line = top-left). Your dot glows.
 * Drawn on a <canvas>: 2,000 dots redrawn twice a second would be slow as 2,000 DOM elements.
 */
export function CrowdMap({ crowd, myIndex, shuffling, note }: { crowd: string; myIndex: number | null; shuffling: boolean; note?: string }) {
  const wrapRef = useRef<HTMLDivElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [width, setWidth] = useState(0)

  // Follow the container's width (phone vs desktop).
  useEffect(() => {
    const el = wrapRef.current
    if (!el) return
    const observer = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)))
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  const n = crowd.length
  // Pick a dot size so everyone fits in about MAX_HEIGHT pixels.
  const cell = n === 0 || width === 0 ? 12 : Math.max(5, Math.min(18, Math.floor(Math.sqrt((width * MAX_HEIGHT) / n))))
  const cols = Math.max(1, Math.floor(width / cell))
  const rows = Math.max(1, Math.ceil(n / cols))
  const height = rows * cell

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas || width === 0) return
    const dpr = window.devicePixelRatio || 1
    canvas.width = width * dpr
    canvas.height = height * dpr
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, width, height)

    const r = Math.max(1.6, cell * 0.36)
    for (let i = 0; i < n; i++) {
      const x = (i % cols) * cell + cell / 2
      const y = Math.floor(i / cols) * cell + cell / 2
      const code = crowd[i]
      ctx.globalAlpha = code === 'X' || code === 'G' || code === 'O' ? 0.55 : 1
      ctx.fillStyle = crowdColor(code)
      ctx.beginPath()
      ctx.arc(x, y, r, 0, Math.PI * 2)
      ctx.fill()
    }
    ctx.globalAlpha = 1

    // You: a bigger gradient dot with a white ring, so you can always find yourself.
    if (myIndex != null && myIndex < n) {
      const x = (myIndex % cols) * cell + cell / 2
      const y = Math.floor(myIndex / cols) * cell + cell / 2
      const big = Math.max(6, cell * 0.75)
      const g = ctx.createLinearGradient(x - big, y - big, x + big, y + big)
      g.addColorStop(0, '#ff5a1f')
      g.addColorStop(0.5, '#ff2d75')
      g.addColorStop(1, '#8b5cf6')
      ctx.shadowColor = 'rgba(255,45,117,0.9)'
      ctx.shadowBlur = 14
      ctx.fillStyle = g
      ctx.beginPath()
      ctx.arc(x, y, big, 0, Math.PI * 2)
      ctx.fill()
      ctx.shadowBlur = 0
      ctx.lineWidth = 2
      ctx.strokeStyle = '#ffffff'
      ctx.stroke()
    }
  }, [crowd, myIndex, width, height, cell, cols, n])

  const counts = countCrowd(crowd)

  return (
    <div className="crowd">
      <div ref={wrapRef} className={`crowd-canvas ${shuffling ? 'is-shuffling' : ''}`}>
        <canvas
          ref={canvasRef}
          style={{ width, height }}
          role="img"
          aria-label={`Crowd of ${n} fans: ${counts.W} waiting, ${counts.P + counts.T} with tickets, ${counts.X} missed out`}
        />
        {note && <div className="crowd-shuffle">{note}</div>}
      </div>
      <ul className="crowd-legend">
        <li>
          <span className="legend-dot legend-you" /> You
        </li>
        {CROWD_KEY.map((k) => (
          <li key={k.code}>
            <span className="legend-dot" style={{ background: k.color }} /> {k.label}
            <b>{counts[k.code].toLocaleString()}</b>
          </li>
        ))}
      </ul>
    </div>
  )
}
