import { useEffect, useMemo, useSyncExternalStore } from 'react'
import { parseJwt, tokenStore, type JwtUser } from './tokenStore'

export interface Auth {
  user: JwtUser | null
  isAdmin: boolean
  /** Save the token from /api/auth/login or /register. */
  logIn: (token: string) => void
  logOut: () => void
}

/**
 * Who is logged in, available in any component.
 * useSyncExternalStore re-renders every component using this hook when the token changes,
 * e.g. after logging in, logging out, or a 401 from the server.
 */
export function useAuth(): Auth {
  const token = useSyncExternalStore(tokenStore.subscribe, tokenStore.get, () => null)

  const user = useMemo(() => (token ? parseJwt(token) : null), [token])

  // Log out automatically the moment the token expires (it lives 1 hour).
  // An already-expired or unreadable token is cleared right away (the timer fires immediately).
  useEffect(() => {
    if (!token) return
    const msLeft = user ? user.expiresAt - Date.now() : 0
    // Only forget THIS token: if a newer login was saved meanwhile, keep it.
    const timer = window.setTimeout(() => {
      if (tokenStore.get() === token) tokenStore.clear()
    }, Math.max(0, msLeft))
    return () => window.clearTimeout(timer)
  }, [token, user])

  return {
    user,
    isAdmin: user?.role === 'ADMIN',
    logIn: (t: string) => tokenStore.set(t),
    logOut: () => tokenStore.clear(),
  }
}
