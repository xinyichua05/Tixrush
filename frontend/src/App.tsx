import { createBrowserRouter, Navigate } from 'react-router'
import { RequireAuth } from './auth/RequireAuth'
import { Layout } from './components/Layout'
import { AdminPage } from './pages/AdminPage'
import { CheckoutPage } from './pages/CheckoutPage'
import { EventPage } from './pages/EventPage'
import { EventsPage } from './pages/EventsPage'
import { LoginPage } from './pages/LoginPage'
import { MyTicketsPage } from './pages/MyTicketsPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { RegisterPage } from './pages/RegisterPage'
import { RushDemoPage } from './pages/RushDemoPage'

/**
 * URL -> page. Changing pages doesn't reload the browser; React just swaps the component
 * inside <Outlet/> (a "single-page app").
 */
export const router = createBrowserRouter([
  {
    path: '/',
    element: <Layout />,
    children: [
      { index: true, element: <EventsPage /> },
      { path: 'events/:id', element: <EventPage /> },
      { path: 'login', element: <LoginPage /> },
      { path: 'register', element: <RegisterPage /> },
      { path: 'orders/:reservationId', element: <RequireAuth><CheckoutPage /></RequireAuth> },
      { path: 'practice', element: <RequireAuth><RushDemoPage /></RequireAuth> },
      { path: 'rush', element: <Navigate to="/practice" replace /> }, // old link
      { path: 'my-tickets', element: <RequireAuth><MyTicketsPage /></RequireAuth> },
      { path: 'admin', element: <RequireAuth admin><AdminPage /></RequireAuth> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
