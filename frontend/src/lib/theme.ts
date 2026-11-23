import { useSyncExternalStore } from 'react'

export type Theme = 'dark' | 'light'
const KEY = 'tixrush.theme'
const listeners = new Set<() => void>()

/** index.html sets data-theme before React loads (no white flash); this reads it back. */
function current(): Theme {
  return document.documentElement.dataset.theme === 'light' ? 'light' : 'dark'
}

export function setTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme
  try {
    localStorage.setItem(KEY, theme)
  } catch {
    /* storage blocked: the theme just won't be remembered */
  }
  listeners.forEach((l) => l())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** The current theme + a toggle. Every component using this re-renders when it changes. */
export function useTheme() {
  const theme = useSyncExternalStore(subscribe, current, () => 'dark' as Theme)
  return { theme, toggle: () => setTheme(theme === 'dark' ? 'light' : 'dark') }
}
