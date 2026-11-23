import { Flame, LayoutDashboard, Music, Ticket } from 'lucide-react'
import { Link, NavLink } from 'react-router'
import { useAuth } from '../auth/useAuth'
import { ThemeToggle } from './ThemeToggle'
import { UserMenu } from './UserMenu'

export function Logo() {
  return (
    <Link to="/" className="brand" aria-label="TixRush home">
      <span className="brand-mark">
        <Ticket size={18} strokeWidth={2.4} />
      </span>
      <span className="brand-text">
        Tix<span className="grad-text">Rush</span>
      </span>
    </Link>
  )
}

export function Header() {
  const { user, isAdmin } = useAuth()

  return (
    <header className="site-header">
      <div className="container header-inner">
        <Logo />
        <nav className="nav" aria-label="Main">
          <NavLink to="/" end>
            <Music size={16} /> <span>Events</span>
          </NavLink>
          <NavLink to="/practice">
            <Flame size={16} /> <span>Practice rush</span>
          </NavLink>
          {user && (
            <NavLink to="/my-tickets">
              <Ticket size={16} /> <span>My tickets</span>
            </NavLink>
          )}
          {isAdmin && (
            <NavLink to="/admin">
              <LayoutDashboard size={16} /> <span>Admin</span>
            </NavLink>
          )}
        </nav>
        <div className="header-actions">
          <ThemeToggle />
          {user ? (
            <UserMenu />
          ) : (
            <>
              <Link to="/login" className="btn btn-ghost btn-sm">
                Log in
              </Link>
              <Link to="/register" className="btn btn-primary btn-sm">
                Sign up
              </Link>
            </>
          )}
        </div>
      </div>
    </header>
  )
}
