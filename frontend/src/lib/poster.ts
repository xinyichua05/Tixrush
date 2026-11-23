// Turns an event into unique poster artwork, with no image files needed.
// The same event ALWAYS gets the same art (it's computed from a hash of its id + name), so a
// concert keeps its "look" on every page: card, banner, checkout, ticket.

export type PosterPattern = 'waves' | 'orbs' | 'rays' | 'horizon'

export interface PosterArt {
  colors: [string, string, string]
  pattern: PosterPattern
  angle: number // gradient direction, degrees
  seed: number // drives the random-looking (but repeatable) shapes
  initials: string
}

// Hand-picked "neon night" colour trios that look good on a dark page.
const PALETTES: [string, string, string][] = [
  ['#ff5a1f', '#ff2d75', '#7c3aed'], // sunset
  ['#06b6d4', '#3b82f6', '#8b5cf6'], // ocean night
  ['#f59e0b', '#ef4444', '#be185d'], // ember
  ['#10b981', '#06b6d4', '#6366f1'], // aurora
  ['#ec4899', '#8b5cf6', '#1e3a8a'], // synth
  ['#f97316', '#facc15', '#e11d48'], // festival
  ['#22d3ee', '#a855f7', '#ec4899'], // neon
  ['#84cc16', '#14b8a6', '#0ea5e9'], // lagoon
]
const PATTERNS: PosterPattern[] = ['waves', 'orbs', 'rays', 'horizon']

/** FNV-1a: a tiny, fast string hash. Same text in -> same number out. */
export function hash(text: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return h >>> 0
}

/** Small seeded random generator: repeatable "random" numbers between 0 and 1. */
export function seededRandom(seed: number): () => number {
  let a = seed || 1
  return () => {
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export function initialsOf(name: string): string {
  const words = name
    .replace(/[^\p{L}\p{N}\s]/gu, ' ')
    .split(/\s+/)
    .filter((w) => w.length > 1 && !/^(the|of|and|a|an|live|in|at)$/i.test(w))
  return (words.slice(0, 2).map((w) => w[0]).join('') || name.slice(0, 2)).toUpperCase()
}

export function posterFor(event: { id: number; name: string }): PosterArt {
  const h = hash(`${event.id}:${event.name}`)
  return {
    colors: PALETTES[h % PALETTES.length],
    pattern: PATTERNS[(h >>> 4) % PATTERNS.length],
    angle: (h >>> 8) % 360,
    seed: h,
    initials: initialsOf(event.name),
  }
}
