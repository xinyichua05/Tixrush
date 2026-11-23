import { describe, expect, it } from 'vitest'
import { initialsOf, posterFor, seededRandom } from './poster'

describe('posterFor', () => {
  it('always gives the same event the same art', () => {
    const a = posterFor({ id: 87, name: 'Neon Monsoon: Electric Rain Tour' })
    const b = posterFor({ id: 87, name: 'Neon Monsoon: Electric Rain Tour' })
    expect(a).toEqual(b)
  })

  it('gives different events different art', () => {
    const arts = [1, 2, 3, 4, 5, 6, 7, 8].map((id) => posterFor({ id, name: `Show ${id}` }))
    const distinct = new Set(arts.map((a) => `${a.colors.join()}|${a.pattern}|${a.angle}`))
    expect(distinct.size).toBeGreaterThan(5)
  })

  it('makes readable initials, skipping filler words', () => {
    expect(initialsOf('The Paper Lanterns: Unplugged')).toBe('PL')
    expect(initialsOf('Rush Hour Live in KL')).toBe('RH')
  })

  it('seeded random numbers repeat for the same seed', () => {
    const r1 = seededRandom(42)
    const r2 = seededRandom(42)
    expect([r1(), r1(), r1()]).toEqual([r2(), r2(), r2()])
  })
})
