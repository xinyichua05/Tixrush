import type { RushFeedItem, RushLineStyle, RushPhase, RushScenarioId, RushState } from '../api/types'

/** The two ways real ticket sites decide who goes first. */
export const LINE_STYLES: { id: RushLineStyle; label: string; like: string; blurb: string }[] = [
  { id: 'RANDOM_LOBBY', label: 'Random lobby', like: 'Like Ticketmaster',
    blurb: 'Wait in the lobby before the sale. When it opens, everyone waiting gets a random place.' },
  { id: 'FASTEST_CLICK', label: 'Fastest click', like: 'Like Interpark and many Asian ticket sites',
    blurb: 'No waiting early. Click Join the moment the sale opens: your number is your speed.' },
]

/** The three scenarios, as shown on the picker cards (numbers match RushScenario.java). */
export const SCENARIOS: {
  id: RushScenarioId
  label: string
  fans: number
  tickets: number
  perSecond: number
  odds: string
  fastOdds: string // the same show in Fastest-click style
  blurb: string
  zones: string // what's on sale, like a real concert
  tone: 'hot' | 'warm' | 'calm'
}[] = [
  { id: 'MEGA', label: 'Mega star', fans: 2000, tickets: 100, perSecond: 50, odds: 'You usually miss out', fastOdds: 'Click within about 0.5 s', tone: 'hot',
    blurb: 'A world-famous act, 20 fans for every seat. Luck decides who gets in.', zones: 'VIP · CAT 1 seats · Rock Zone' },
  { id: 'POPULAR', label: 'Popular show', fans: 600, tickets: 150, perSecond: 25, odds: 'About a coin flip', fastOdds: 'Click within about 1 s', tone: 'warm',
    blurb: '4 fans per seat. Unpaid tickets come back, so there are second chances.', zones: 'VIP · CAT 1 seats · Rock Zone' },
  { id: 'INDIE', label: 'Indie night', fans: 120, tickets: 150, perSecond: 20, odds: 'Everyone gets one', fastOdds: 'Everyone gets one', tone: 'calm',
    blurb: 'More seats than fans. Watch the happy path from start to finish.', zones: 'Front seats · Standing' },
]

/** What each letter in the crowd string means, plus the dot colour on the crowd map. */
export const CROWD_KEY = [
  { code: 'W', label: 'Waiting in line', color: '#7c7c92' },
  { code: 'B', label: 'Their turn / buying', color: '#60a5fa' },
  { code: 'T', label: 'Got a ticket, paying', color: '#fbbf24' },
  { code: 'P', label: 'Paid', color: '#34d399' },
  { code: 'E', label: 'Did not pay, ticket returned', color: '#f97316' },
  { code: 'X', label: 'Missed out', color: '#fb7185' },
  { code: 'G', label: 'Gave up', color: '#4b4b5e' },
] as const

export const crowdColor = (code: string) =>
  CROWD_KEY.find((k) => k.code === code)?.color ?? (code === 'O' ? '#fb7185' : '#7c7c92')

/** How many fans have each letter. "O" (your turn expired) is counted as missed. */
export function countCrowd(crowd: string): Record<string, number> {
  const counts: Record<string, number> = { W: 0, B: 0, T: 0, P: 0, E: 0, X: 0, G: 0 }
  for (const c of crowd) {
    const key = c === 'O' ? 'X' : c
    counts[key] = (counts[key] ?? 0) + 1
  }
  return counts
}

/** Rough wait: the line moves `perSecond` fans per second while tickets are left. */
export function estimateWaitSeconds(ahead: number, perSecond: number): number {
  if (ahead <= 0 || perSecond <= 0) return 0
  return Math.ceil(ahead / perSecond)
}

/** How far through the line you are, 0 to 1. */
export function lineProgress(number: number | null, ahead: number | null): number {
  if (number == null || ahead == null || number <= 1) return 1
  return Math.min(1, Math.max(0, 1 - ahead / (number - 1)))
}

/** Feed timestamps: "lobby" before opening, then "+0:07" after it. */
export function formatSinceOpen(ms: number): string {
  if (ms < 0) return 'lobby'
  const s = Math.floor(ms / 1000)
  return `+${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/** 432 ms -> "0.43 s" */
export function formatClickTime(ms: number): string {
  return `${(ms / 1000).toFixed(2)} s`
}

export type Tone = 'good' | 'bad' | 'warn' | 'neutral'

/** The headline on the result screen, from your own status and the run's numbers. */
export function outcomeOf(state: RushState): { tone: Tone; title: string; detail: string } {
  const me = state.me
  const ranOut = state.soldOutAtNumber != null
    ? ` Tickets ran out when the line reached #${state.soldOutAtNumber.toLocaleString()}.`
    : ''
  // Stopped with the Stop button: only a paid ticket still counts as a result.
  if (state.endReason === 'stopped' && me?.status !== 'PAID') {
    return { tone: 'neutral', title: 'You stopped this practice rush', detail: 'Pick a style and a show below to try again.' }
  }
  if (!me) {
    return state.lineStyle === 'FASTEST_CLICK'
      ? { tone: 'neutral', title: "You didn't click Join", detail: 'Start another one and click Join the moment the sale opens.' }
      : { tone: 'neutral', title: 'You watched this one', detail: 'Start a new practice rush and join the line yourself.' }
  }
  let you = me.number != null ? `You were #${me.number.toLocaleString()} in line.` : ''
  if (me.clickedAfterMs != null && me.number != null) {
    const faster = me.number - 1
    you = `You clicked ${formatClickTime(me.clickedAfterMs)} after the sale opened, so you were #${me.number.toLocaleString()} in line (${faster.toLocaleString()} ${faster === 1 ? 'fan was' : 'fans were'} faster).`
  }
  switch (me.status) {
    case 'PAID':
      return { tone: 'good', title: 'You got tickets!', detail: `${you}${ranOut}`.trim() || 'Enjoy the show.' }
    case 'BOUGHT':
      return { tone: 'warn', title: 'Tickets reserved: pay now', detail: 'Pay before the timer runs out, or they go to the next fan.' }
    case 'HOLD_EXPIRED':
      return {
        tone: 'bad',
        title: 'You got tickets, but did not pay in time',
        detail: 'Your payment hold ran out, so the tickets went back on sale for the next fans in line.',
      }
    case 'CANCELLED':
      return {
        tone: 'neutral',
        title: 'You cancelled your order',
        detail: 'Your tickets went straight back on sale for the next fans in line. Nothing was charged.',
      }
    case 'PASS_EXPIRED':
      return {
        tone: 'bad',
        title: 'Your turn ran out',
        detail: `You reached the front, but did not choose your tickets within ${state.passSeconds} seconds.`,
      }
    case 'MISSED': {
      const noun = state.returned === 1 ? 'ticket' : 'tickets'
      const second = state.returned > 0 ? ` ${state.returned} unpaid ${noun} came back, but went to fans ahead of you.` : ''
      return { tone: 'bad', title: 'Sold out before your turn', detail: `${you}${ranOut}${second}`.trim() }
    }
    default:
      return { tone: 'neutral', title: 'Still in line', detail: you }
  }
}

/** One friendly sentence about what is happening right now, shown beside the live view. */
export function phaseMessage(phase: RushPhase, lineStyle: RushLineStyle | null = 'RANDOM_LOBBY'): string {
  const fastest = lineStyle === 'FASTEST_CLICK'
  switch (phase) {
    case 'LOBBY':
      return fastest
        ? 'Nobody can queue yet. The Join button unlocks the second the sale opens: the faster you click, the better your number.'
        : 'Everyone waiting now gets a random place in line when the sale opens.'
    case 'OPEN':
      return fastest
        ? 'The line is in click order: the faster a fan clicked, the sooner their turn.'
        : 'Fans are let in a few at a time, so the site stays fast for everyone.'
    case 'WAITING_FOR_RETURNS':
      return "All tickets are reserved. If someone doesn't pay in time, the next fan in line gets it."
    case 'FINISHED':
      return 'This practice rush is over. Try another one below.'
    default:
      return ''
  }
}

/** Advice for the result screen, different for each line style. */
export function tipsFor(lineStyle: RushLineStyle | null): { title: string; body: string }[] {
  const knowYourZone = { title: 'Decide before your turn', body: 'Know which zone you want and a second choice. Picking exact seats takes longer than "Best available", and other fans are grabbing seats while you look.' }
  const comeBack = { title: "Sold out isn't always final", body: "If someone doesn't pay in time, their ticket goes to the next fan in line. Stay in the line." }
  if (lineStyle === 'FASTEST_CLICK') {
    return [
      { title: "Use the site's clock", body: "Your computer's clock can be a second or two off. Watch the countdown on the ticket site itself." },
      { title: 'Click once, then wait', body: "Your number is fixed the moment you click. Refreshing won't move you up, and on many sites it loses your place." },
      { title: 'One tab is enough', body: 'Extra tabs or devices do not help. Many sites close your old place when you open a new one.' },
      knowYourZone,
      comeBack,
    ]
  }
  return [
    { title: 'Be early', body: 'Everyone who is waiting before the sale opens gets a random place in line, so being there on time is what counts.' },
    { title: "Don't refresh", body: 'You keep your place in line. Refreshing or opening more tabs does not move you forward.' },
    { title: 'Be ready to pay', body: 'When it is your turn you only have a short time to buy, then a short time to pay.' },
    knowYourZone,
    comeBack,
  ]
}

/** A feed row: one event, or several identical ones in a row (count > 1). Keeps the newest one's time. */
export type FeedRow = RushFeedItem & { count: number }

/** The feed is newest first. Back-to-back identical messages merge into one row with a count. */
export function groupRepeats(items: RushFeedItem[]): FeedRow[] {
  const rows: FeedRow[] = []
  for (const item of items) {
    const last = rows[rows.length - 1]
    if (last && last.kind === item.kind && last.text === item.text) last.count++
    else rows.push({ ...item, count: 1 })
  }
  return rows
}
