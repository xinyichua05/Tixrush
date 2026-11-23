import { ArrowLeft } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { cameFrom } from '../lib/useScrollMemory'

/**
 * "← All events". If you opened this page FROM that page, it acts like the browser's Back button,
 * so the list comes back exactly where you left it (scroll position and all). If you came here
 * any other way (a shared link, a new tab, My tickets), it's a normal link and opens at the top.
 *
 * It stays a real <a href>, so "open in new tab" still works.
 */
export function BackLink({ to, className = 'back', children }: { to: string; className?: string; children: ReactNode }) {
  const location = useLocation()
  const navigate = useNavigate()
  return (
    <Link
      to={to}
      className={className}
      onClick={(e) => {
        const plainClick = e.button === 0 && !e.metaKey && !e.ctrlKey && !e.shiftKey && !e.altKey
        if (plainClick && cameFrom(location.key) === to) {
          e.preventDefault()
          navigate(-1)
        }
      }}
    >
      <ArrowLeft size={16} aria-hidden="true" /> {children}
    </Link>
  )
}
