import { describe, expect, it } from 'vitest'
import type { RushMe, RushState } from '../api/types'
import { countCrowd, estimateWaitSeconds, formatClickTime, formatSinceOpen, lineProgress, outcomeOf, phaseMessage, tipsFor } from './rush'

const base: RushState = {
  phase: 'FINISHED', scenario: 'MEGA', scenarioLabel: 'Mega star', eventId: 1, eventName: 'Show', venue: 'Hall',
  opensAt: null, msSinceOpen: 60_000, fans: 2000, tickets: 100, admitPerSecond: 50, passSeconds: 60, holdSeconds: 45,
  ticketsLeft: 0, sold: 120, paid: 100, returned: 20, missed: 1800, gaveUp: 80, waiting: 0, lobby: 0, admitted: 400,
  stillComing: 0, soldOutAfterMs: 4000, soldOutAtNumber: 131, crowd: '', myIndex: null, feed: [], me: null,
  check: null, endReason: 'soldout', lineStyle: 'RANDOM_LOBBY', serverNow: '2027-01-01T00:00:00Z', zones: [],
}
const me = (patch: Partial<RushMe>): RushMe => ({
  status: 'WAITING', number: 437, ahead: null, passExpiresAt: null, reservationId: null, holdExpiresAt: null, clickedAfterMs: null, ...patch,
})

describe('live rush helpers', () => {
  it('counts the crowd letters, with "turn expired" counted as missed', () => {
    expect(countCrowd('WWBTPXOG')).toEqual({ W: 2, B: 1, T: 1, P: 1, E: 0, X: 2, G: 1 })
  })

  it('estimates the wait from fans ahead and the admit rate', () => {
    expect(estimateWaitSeconds(100, 50)).toBe(2)
    expect(estimateWaitSeconds(101, 50)).toBe(3)
    expect(estimateWaitSeconds(0, 50)).toBe(0)
  })

  it('shows how far through the line you are', () => {
    expect(lineProgress(101, 100)).toBe(0)
    expect(lineProgress(101, 50)).toBe(0.5)
    expect(lineProgress(101, 0)).toBe(1)
  })

  it('formats feed times relative to the opening', () => {
    expect(formatSinceOpen(-2000)).toBe('lobby')
    expect(formatSinceOpen(65_000)).toBe('+1:05')
  })

  it('explains a miss with your number and where tickets ran out', () => {
    const out = outcomeOf({ ...base, me: me({ status: 'MISSED' }) })
    expect(out.tone).toBe('bad')
    expect(out.title).toBe('Sold out before your turn')
    expect(out.detail).toContain('#437')
    expect(out.detail).toContain('#131')
    expect(out.detail).toContain('20 unpaid tickets came back')
  })

  it('celebrates a paid ticket and explains the other endings', () => {
    expect(outcomeOf({ ...base, me: me({ status: 'PAID', number: 12 }) }).tone).toBe('good')
    expect(outcomeOf({ ...base, me: me({ status: 'PASS_EXPIRED' }) }).detail).toContain('60 seconds')
    expect(outcomeOf(base).title).toBe('You watched this one')
  })

  it('fastest click: tells you your click time and how many fans were faster', () => {
    expect(formatClickTime(432)).toBe('0.43 s')
    const fast = { ...base, lineStyle: 'FASTEST_CLICK' as const }
    const out = outcomeOf({ ...fast, me: me({ status: 'MISSED', number: 312, clickedAfterMs: 432 }) })
    expect(out.detail).toContain('You clicked 0.43 s after the sale opened')
    expect(out.detail).toContain('311 fans were faster')
    expect(outcomeOf(fast).title).toBe("You didn't click Join")
  })

  it('a stopped run says so, unless you already paid', () => {
    const stopped = { ...base, endReason: 'stopped' as const }
    expect(outcomeOf({ ...stopped, me: me({ status: 'WAITING' }) }).title).toBe('You stopped this practice rush')
    expect(outcomeOf(stopped).title).toBe('You stopped this practice rush')
    expect(outcomeOf({ ...stopped, me: me({ status: 'PAID' }) }).title).toBe('You got tickets!')
  })

  it('explains each line style in its own words', () => {
    expect(phaseMessage('LOBBY', 'FASTEST_CLICK')).toContain('Join button unlocks')
    expect(phaseMessage('LOBBY', 'RANDOM_LOBBY')).toContain('random place')
    expect(tipsFor('FASTEST_CLICK')[0].title).toBe("Use the site's clock")
    expect(tipsFor('RANDOM_LOBBY')[0].title).toBe('Be early')
  })
})
