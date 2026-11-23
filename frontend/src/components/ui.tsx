import { TriangleAlert, X } from 'lucide-react'
import { useEffect, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

/** Small shared UI pieces. */

export function ErrorMessage({ error }: { error: unknown }) {
  if (!error) return null
  const message = error instanceof Error ? error.message : String(error)
  return (
    <div className="alert alert-error" role="alert">
      <TriangleAlert size={18} /> <span>{message}</span>
    </div>
  )
}

export function Loading({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="loading" role="status">
      <span className="spinner" aria-hidden="true" /> {label}
    </div>
  )
}

/** Grey shimmering placeholder shown while data loads (looks calmer than a spinner). */
export function Skeleton({ height = 16, width = '100%', radius = 8 }: { height?: number | string; width?: number | string; radius?: number }) {
  return <span className="skeleton" style={{ height, width, borderRadius: radius }} aria-hidden="true" />
}

export function LiveDot({ label = 'Live' }: { label?: string }) {
  return (
    <span className="live">
      <span className="live-dot" aria-hidden="true" /> {label}
    </span>
  )
}

export function EmptyState({ icon, title, children }: { icon: ReactNode; title: string; children?: ReactNode }) {
  return (
    <div className="empty-state">
      <div className="empty-icon">{icon}</div>
      <h3>{title}</h3>
      {children && <div className="muted">{children}</div>}
    </div>
  )
}

export function StatCard({ icon, label, value, sub }: { icon: ReactNode; label: string; value: ReactNode; sub?: ReactNode }) {
  return (
    <div className="stat-card glass">
      <div className="stat-icon">{icon}</div>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {sub && <div className="stat-sub">{sub}</div>}
    </div>
  )
}

/**
 * A dialog. variant "center" = dialog box, "wide" = big box (full screen on phones),
 * "drawer" = panel sliding in from the right.
 * modal (default): the page behind is dimmed and frozen; Escape or clicking the backdrop closes it.
 * modal={false}: a side panel you can keep open while you scroll and click the page beside it;
 * Escape or the close button closes it.
 */
export function Dialog({ open, onClose, title, variant = 'center', modal = true, children }: {
  open: boolean
  onClose: () => void
  title: string
  variant?: 'center' | 'wide' | 'drawer'
  modal?: boolean
  children: ReactNode
}) {
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    document.addEventListener('keydown', onKey)
    // Modal: freeze the page. Side panel: tell the page so it can make room for the panel on wide screens.
    if (modal) document.body.style.overflow = 'hidden'
    else document.body.classList.add('side-panel-open')
    return () => {
      document.removeEventListener('keydown', onKey)
      if (modal) document.body.style.overflow = ''
      else document.body.classList.remove('side-panel-open')
    }
  }, [open, onClose, modal])

  if (!open) return null
  // Rendered straight into <body>: the page's entrance animation leaves a transform on its wrapper,
  // which would trap position: fixed inside it (the panel would start under the header).
  return createPortal(
    <div
      className={`overlay overlay-${variant}${modal ? '' : ' overlay-passive'}`}
      onMouseDown={modal ? (e) => e.target === e.currentTarget && onClose() : undefined}
    >
      <div className={`dialog dialog-${variant}`} role="dialog" aria-modal={modal} aria-label={title}>
        <div className="dialog-head">
          <h2>{title}</h2>
          <button type="button" className="icon-btn" onClick={onClose} aria-label="Close">
            <X size={18} />
          </button>
        </div>
        <div className="dialog-body">{children}</div>
      </div>
    </div>,
    document.body,
  )
}
