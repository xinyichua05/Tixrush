import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { useAuth } from './useAuth'

/**
 * Wraps pages that need a login. Not logged in -> go to /login, and come back here afterwards.
 * admin = also needs the ADMIN role, otherwise back to the home page.
 *
 * This only hides pages in the browser. The REAL protection is the backend (SecurityConfig):
 * anyone can call the API directly, so the server must always check the token itself.
 */
export function RequireAuth({ children, admin = false }: { children: ReactNode; admin?: boolean }) {
  const { user, isAdmin } = useAuth()
  const location = useLocation()

  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  if (admin && !isAdmin) return <Navigate to="/" replace />
  return children
}
