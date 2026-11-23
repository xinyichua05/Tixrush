import { apiFetch } from './client'
import type { RushLineStyle, RushScenarioId, RushState } from './types'

/**
 * Remember how far the computer's clock is from the site's clock, so the countdown hits zero
 * when the SITE opens the sale (real ticket fans check the "server time" for exactly this reason).
 */
const withClock = (s: RushState): RushState => ({ ...s, clockOffsetMs: Date.parse(s.serverNow) - Date.now() })

/** Everything the Practice rush page draws. Polled about twice a second while a rush runs. */
export const getRushState = async () => withClock(await apiFetch<RushState>('/api/rush-demo'))

/** 409 if a rush is already running. */
export const startRush = async (scenario: RushScenarioId, lineStyle: RushLineStyle) =>
  withClock(await apiFetch<RushState>('/api/rush-demo/start', { method: 'POST', body: { scenario, lineStyle } }))

/** Stop button: ends the current run right away, so a new one can start. */
export const stopRush = async () => withClock(await apiFetch<RushState>('/api/rush-demo/stop', { method: 'POST' }))

/** Step into the line yourself (fastest click: 409 "Too early!" before the sale opens). */
export const joinRush = async () => withClock(await apiFetch<RushState>('/api/rush-demo/join', { method: 'POST' }))
