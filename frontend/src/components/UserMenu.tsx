import { ChevronDown, LayoutDashboard, LogOut, Ticket } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useAuth } from '../auth/useAuth'

/** Avatar button with a dropdown: who you are, quick links, log out. */
export function UserMenu() {
  const { user, isAdmin, logOut } = useAuth()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  // Close when clicking anywhere else or pressing Escape.
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  if (!user) return null
  const initial = user.email[0]?.toUpperCase() ?? '?'

  return (
    <div className="user-menu" ref={ref}>
      <button type="button" className="user-trigger" onClick={() => setOpen((o) => !o)} aria-expanded={open} aria-haspopup="menu">
        <span className="avatar">{initial}</span>
        <ChevronDown size={16} className={`chevron ${open ? 'up' : ''}`} />
      </button>
      {open && (
        <div className="menu" role="menu">
          <div className="menu-head">
            <span className="avatar avatar-lg">{initial}</span>
            <div className="menu-who">
              <strong title={user.email}>{user.email}</strong>
              <span className={`role-pill ${isAdmin ? 'admin' : ''}`}>{isAdmin ? 'Admin' : 'Fan'}</span>
            </div>
          </div>
          <Link to="/my-tickets" className="menu-item" role="menuitem" onClick={() => setOpen(false)}>
            <Ticket size={16} /> My tickets
          </Link>
          {isAdmin && (
            <Link to="/admin" className="menu-item" role="menuitem" onClick={() => setOpen(false)}>
              <LayoutDashboard size={16} /> Admin dashboard
            </Link>
          )}
          <button
            type="button"
            className="menu-item danger"
            role="menuitem"
            onClick={() => {
              setOpen(false)
              logOut()
              navigate('/')
            }}
          >
            <LogOut size={16} /> Log out
          </button>
        </div>
      )}
    </div>
  )
}
