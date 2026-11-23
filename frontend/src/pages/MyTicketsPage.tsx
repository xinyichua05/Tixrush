import { useQuery } from '@tanstack/react-query'
import { CreditCard, QrCode, Ticket } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { listEvents } from '../api/events'
import { myOrders } from '../api/orders'
import type { OrderResponse } from '../api/types'
import { Countdown } from '../components/Countdown'
import { TicketStub } from '../components/TicketStub'
import { EmptyState, ErrorMessage, Skeleton } from '../components/ui'

type Tab = 'tickets' | 'pending' | 'expired'

const TAB_OF: Record<OrderResponse['status'], Tab> = {
  PAID: 'tickets',
  CONFIRMED: 'tickets',
  PENDING_PAYMENT: 'pending',
  QUEUED: 'pending',
  EXPIRED: 'expired',
  CANCELLED: 'expired',
}

export function MyTicketsPage() {
  const orders = useQuery({ queryKey: ['orders', 'mine'], queryFn: myOrders, refetchInterval: 3000 })
  // Orders only carry an eventId; the event details come from the (already cached) events list.
  const events = useQuery({ queryKey: ['events'], queryFn: listEvents })
  const eventById = new Map((events.data ?? []).map((e) => [e.id, e]))

  const all = orders.data ?? []
  const counts = { tickets: 0, pending: 0, expired: 0 }
  all.forEach((o) => counts[TAB_OF[o.status]]++)

  const [chosenTab, setTab] = useState<Tab | null>(null)
  // Until you pick a tab: show "Awaiting payment" if something needs paying, else your tickets.
  const tab: Tab = chosenTab ?? (counts.pending > 0 ? 'pending' : 'tickets')
  const [openQr, setOpenQr] = useState<string | null>(null)

  const shown = all.filter((o) => TAB_OF[o.status] === tab)
  const tabs: { id: Tab; label: string }[] = [
    { id: 'tickets', label: 'My tickets' },
    { id: 'pending', label: 'Awaiting payment' },
    { id: 'expired', label: 'Expired & cancelled' },
  ]

  return (
    <div className="container narrow-wide">
      <div className="page-head">
        <div>
          <h1>My tickets</h1>
          <p className="muted">Everything you've bought, in one place.</p>
        </div>
      </div>

      <div className="tabs" role="tablist">
        {tabs.map((t) => (
          <button key={t.id} type="button" role="tab" aria-selected={tab === t.id} className={`tab ${tab === t.id ? 'active' : ''}`} onClick={() => setTab(t.id)}>
            {t.label} <span className="tab-count">{counts[t.id]}</span>
          </button>
        ))}
      </div>

      <ErrorMessage error={orders.error} />
      {orders.isPending && (
        <div className="stub-list">
          {[0, 1].map((i) => <Skeleton key={i} height={132} radius={18} />)}
        </div>
      )}

      {orders.data && shown.length === 0 && (
        <EmptyState icon={<Ticket size={28} />} title={tab === 'tickets' ? 'No tickets yet' : tab === 'pending' ? 'Nothing to pay' : 'No expired or cancelled orders'}>
          {tab === 'tickets' && <Link to="/">Find a concert →</Link>}
        </EmptyState>
      )}

      <div className="stub-list">
        {shown.map((o) => {
          const key = o.reservationId ?? `order-${o.orderId}`
          const isTicket = TAB_OF[o.status] === 'tickets'
          return (
            <TicketStub
              key={key}
              order={o}
              event={eventById.get(o.eventId)}
              showQr={isTicket && openQr === key}
              actions={
                o.status === 'PENDING_PAYMENT' && o.expiresAt && o.reservationId ? (
                  <>
                    <span className="pay-timer">Pay within <Countdown until={o.expiresAt} /></span>
                    <Link className="btn btn-primary btn-sm" to={`/orders/${o.reservationId}`}>
                      <CreditCard size={15} /> Pay now
                    </Link>
                  </>
                ) : isTicket ? (
                  <button type="button" className="btn btn-glass btn-sm" onClick={() => setOpenQr(openQr === key ? null : key)}>
                    <QrCode size={15} /> {openQr === key ? 'Hide QR' : 'Show QR'}
                  </button>
                ) : null
              }
            />
          )
        })}
      </div>
    </div>
  )
}
