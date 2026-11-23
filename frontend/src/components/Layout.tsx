import { Link, Outlet, useLocation } from 'react-router'
import { Toaster } from 'sonner'
import { useScrollMemory } from '../lib/useScrollMemory'
import { useTheme } from '../lib/theme'
import { Header, Logo } from './Header'

const YEAR = new Date().getFullYear()

/** The frame around every page: header, the current page in <Outlet/>, footer, and pop-up toasts. */
export function Layout() {
  const { pathname } = useLocation()
  const { theme } = useTheme()
  useScrollMemory() // new page -> top; Back -> where you were

  return (
    <>
      <div className="bg-glow" aria-hidden="true" />
      <Header />
      {/* key = path, so each new page replays the fade-in animation */}
      <main className="site-main page-enter" key={pathname}>
        <Outlet />
      </main>
      <footer className="site-footer">
        <div className="container footer-inner">
          <div className="footer-brand">
            <Logo />
            <p className="muted small">Concert tickets for the biggest nights. A fair waiting line, instant seat holds and a simple checkout.</p>
          </div>
          <div className="footer-cols">
            <div>
              <h4>Explore</h4>
              <Link to="/">All concerts</Link>
              <Link to="/practice">Practice rush</Link>
              <Link to="/my-tickets">My tickets</Link>
            </div>
            <div>
              <h4>Good to know</h4>
              <span>Your seat is held while you pay</span>
              <span>One click, one ticket: never charged twice</span>
              <span>Test mode: no real money is charged</span>
            </div>
          </div>
        </div>
        <div className="container footer-bottom">© {YEAR} TixRush · Test mode: no real payments are taken</div>
      </footer>
      <Toaster theme={theme} position="top-center" richColors closeButton />
    </>
  )
}
