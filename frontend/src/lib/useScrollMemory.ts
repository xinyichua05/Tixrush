import { useEffect, useLayoutEffect, useRef } from 'react'
import { useLocation, useNavigationType } from 'react-router'

const STORE_KEY = 'tixrush.scroll'
/** How long Back keeps trying to reach the old spot while the page is still drawing (images, lists). */
const RESTORE_FOR_MS = 1500

type Positions = Record<string, number>

function load(): Positions {
  try {
    return JSON.parse(sessionStorage.getItem(STORE_KEY) ?? '{}') as Positions
  } catch {
    return {} // private window / blocked storage: just remember in memory
  }
}

function store(positions: Positions) {
  try {
    sessionStorage.setItem(STORE_KEY, JSON.stringify(positions))
  } catch {
    // ignore: storage full or blocked
  }
}

const OPENED_FROM_KEY = 'tixrush.openedFrom'
let openedFrom: Record<string, string> | null = null

function openedFromMap(): Record<string, string> {
  if (openedFrom === null) {
    try {
      openedFrom = JSON.parse(sessionStorage.getItem(OPENED_FROM_KEY) ?? '{}') as Record<string, string>
    } catch {
      openedFrom = {}
    }
  }
  return openedFrom
}

/**
 * The page you clicked from to open this history entry ("/" for an event opened from the list),
 * or undefined if it was opened directly (typed, shared link, new tab). BackLink uses it: if you
 * came from the list, "← All events" can simply go Back, and then you're where you were.
 */
export function cameFrom(locationKey: string): string | undefined {
  return openedFromMap()[locationKey]
}

const jumpTo = (top: number) => window.scrollTo({ top, left: 0, behavior: 'instant' }) // not the CSS "smooth"

/**
 * Scrolling like a normal website, for our single-page app:
 *   - click a link -> the new page opens at the TOP
 *   - Back / Forward -> the page is exactly where you left it (e.g. the events list, scrolled
 *     down to the card you clicked)
 *
 * Why we need this: changing pages only swaps the component inside <Outlet/>, the browser never
 * loads a new document, so by itself the window just keeps its old scroll position.
 *
 * How: every history entry has its own location.key. While you scroll we note "key -> scrollY"
 * (also in sessionStorage, so it survives a refresh). On Back we scroll to the noted spot.
 */
export function useScrollMemory() {
  const location = useLocation()
  const navigationType = useNavigationType() // 'PUSH' / 'REPLACE' = a link, 'POP' = Back / Forward
  const positions = useRef<Positions | null>(null)
  positions.current ??= load()
  const currentKey = useRef(location.key)
  const lastPath = useRef<string | null>(null)
  const restoring = useRef(false)

  // The browser would also try to restore on Back, but too early (before React has drawn the
  // page), so it usually lands at the top. Switch it off; we do it ourselves.
  useEffect(() => {
    if (!('scrollRestoration' in history)) return
    const before = history.scrollRestoration
    history.scrollRestoration = 'manual'
    return () => {
      history.scrollRestoration = before
    }
  }, [])

  // Note where you are on the current page, at most once per frame.
  useEffect(() => {
    let frame = 0
    const onScroll = () => {
      if (restoring.current || frame) return // mid-restore positions are not "where you were"
      frame = requestAnimationFrame(() => {
        frame = 0
        positions.current![currentKey.current] = Math.round(window.scrollY)
        store(positions.current!)
      })
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScroll)
      cancelAnimationFrame(frame)
    }
  }, [])

  // A new page: top, or (Back / Forward) the noted spot. useLayoutEffect = before the browser
  // paints, so you never see the old position flash.
  useLayoutEffect(() => {
    const previousKey = currentKey.current
    const previousPath = lastPath.current
    currentKey.current = location.key
    const samePage = previousPath === location.pathname
    lastPath.current = location.pathname

    // Who opened this entry? A link: the page we were on. A replace: whoever opened the old one.
    // Back / Forward: entries don't change, so neither does this.
    if (navigationType !== 'POP' && previousPath !== null) {
      const map = openedFromMap()
      const from = navigationType === 'PUSH' ? previousPath : map[previousKey]
      if (from !== undefined) {
        map[location.key] = from
        try {
          sessionStorage.setItem(OPENED_FROM_KEY, JSON.stringify(map))
        } catch {
          // ignore: storage full or blocked
        }
      }
    }

    if (navigationType !== 'POP') {
      if (!samePage) jumpTo(0) // only the query string changed: stay put
      return
    }

    const target = positions.current![location.key] ?? 0
    const giveUpAt = performance.now() + RESTORE_FOR_MS
    let frame = 0
    restoring.current = true
    const stop = () => {
      restoring.current = false
      cancelAnimationFrame(frame)
      for (const type of USER_INPUT) window.removeEventListener(type, stop)
    }
    // The page may not be tall enough yet (still loading). Try again each frame until it is.
    const attempt = () => {
      jumpTo(target)
      if (Math.abs(window.scrollY - target) <= 1 || performance.now() > giveUpAt) stop()
      else frame = requestAnimationFrame(attempt)
    }
    // If you start scrolling yourself, we stop: never yank you around.
    for (const type of USER_INPUT) window.addEventListener(type, stop, { passive: true })
    attempt()
    return stop
  }, [location.key, location.pathname, navigationType])
}

const USER_INPUT = ['wheel', 'touchstart', 'keydown', 'mousedown'] as const
