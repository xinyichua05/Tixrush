// Where the login token (JWT) is kept: localStorage, so you stay logged in after a page refresh.
//
// Trade-off: any JavaScript running on this page can read localStorage, so an XSS bug could steal
// the token. Keeping it only in memory is safer but logs you out on every refresh. An httpOnly
// cookie is the safest, but that's the "session cookie" style we decided against for this API.
// For a learning project, localStorage + a short token lifetime (1 h) is a common middle ground.

const KEY = 'tixrush.token'
type Listener = () => void
const listeners = new Set<Listener>()

function emit() {
  listeners.forEach((l) => l())
}

export const tokenStore = {
  get(): string | null {
    try {
      return localStorage.getItem(KEY)
    } catch {
      return null // storage blocked (private mode etc.)
    }
  },
  set(token: string) {
    try {
      localStorage.setItem(KEY, token)
    } catch {
      /* ignore */
    }
    emit()
  },
  clear() {
    try {
      localStorage.removeItem(KEY)
    } catch {
      /* ignore */
    }
    emit()
  },
  /** React subscribes here, so the whole UI updates the moment you log in or out. */
  subscribe(listener: Listener): () => void {
    listeners.add(listener)
    // Logging out in another tab also logs out this one.
    const onStorage = (e: StorageEvent) => {
      if (e.key === KEY) listener()
    }
    window.addEventListener('storage', onStorage)
    return () => {
      listeners.delete(listener)
      window.removeEventListener('storage', onStorage)
    }
  },
}

export interface JwtUser {
  id: string
  email: string
  role: 'USER' | 'ADMIN'
  expiresAt: number // ms since epoch
}

/**
 * Reads the middle part of the JWT (the payload). This does NOT verify the signature: only the
 * server can do that (it has the secret). The browser just reads it to know who's logged in.
 */
export function parseJwt(token: string): JwtUser | null {
  try {
    const payload = token.split('.')[1]
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4)
    const claims = JSON.parse(atob(padded)) as { sub: string; email: string; role: 'USER' | 'ADMIN'; exp: number }
    return { id: claims.sub, email: claims.email, role: claims.role, expiresAt: claims.exp * 1000 }
  } catch {
    return null
  }
}
