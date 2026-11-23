import type { OrderStatus } from '../api/types'

const LABELS: Record<OrderStatus, string> = {
  QUEUED: 'Processing',
  PENDING_PAYMENT: 'Awaiting payment',
  PAID: 'Paid',
  EXPIRED: 'Expired',
  CANCELLED: 'Cancelled',
  CONFIRMED: 'Confirmed',
}

export function StatusBadge({ status }: { status: OrderStatus }) {
  return (
    <span className={`badge badge-${status.toLowerCase()}`}>
      <span className="badge-dot" aria-hidden="true" />
      {LABELS[status]}
    </span>
  )
}
