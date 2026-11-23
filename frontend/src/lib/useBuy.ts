import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { toast } from 'sonner'
import { ApiError } from '../api/client'
import { buyTicket, type TicketChoice } from '../api/orders'
import type { OrderResponse } from '../api/types'
import { ticketsWord } from './tickets'
import { useNow } from './useNow'

export type BuyPhase = 'idle' | 'buying' | 'conflict' | 'error'

/**
 * What happens when someone clicks a buy button. Shared by the one-click BuyButton (old single-pool
 * events) and the TicketPicker (events with ticket types), so both react the same way:
 *  1. makes a NEW random Idempotency-Key for this click
 *  2. phase "buying" while waiting (the buttons disable themselves: no double submit)
 *  3. 202 -> go to checkout | 409 -> "conflict" (sold out / not enough left / over your limit)
 *     429 -> a wait countdown | 401 -> log in | 503 or anything else -> "error", try again
 *
 * Even if the browser sends the request twice (flaky network, impatient user), the backend sees
 * the same key and returns the same order. That's the server-side half of double-click safety.
 */
export function useBuy(eventId: number, { onConflict, onBought }: {
  onConflict?: () => void
  /** Given: stay on this page and call it (the Practice rush). Not given: go to the checkout page. */
  onBought?: (order: OrderResponse) => void
} = {}) {
  const navigate = useNavigate()
  const location = useLocation()
  const now = useNow(500)
  const [phase, setPhase] = useState<BuyPhase>('idle')
  const [message, setMessage] = useState<string | null>(null)
  const [cooldownUntil, setCooldownUntil] = useState(0)

  async function buy(choice?: TicketChoice) {
    setPhase('buying')
    setMessage(null)
    try {
      const key = crypto.randomUUID()
      const order = choice ? await buyTicket(eventId, key, choice) : await buyTicket(eventId, key)
      const what = choice && choice.quantity > 1 ? `${choice.quantity} ${ticketsWord(choice.quantity)} reserved!` : 'Ticket reserved!'
      toast.success(`${what} Complete payment to lock it in.`)
      setPhase('idle')
      if (onBought) onBought(order)
      else navigate(`/orders/${order.reservationId}`)
    } catch (e) {
      const err = e instanceof ApiError ? e : new ApiError(0, String(e))
      if (err.status === 409) {
        // With ticket types the server says exactly what's wrong ("Only 2 left...", "you already have 4").
        const text = choice ? err.message : 'Sold out. Someone was faster this time.'
        setPhase('conflict')
        setMessage(text)
        toast.error(text)
        onConflict?.() // e.g. reload the event, to show the real numbers left
      } else if (err.status === 429) {
        const wait = err.retryAfterSeconds ?? 60
        setPhase('idle')
        setCooldownUntil(Date.now() + wait * 1000)
        setMessage('Too many attempts. Please wait a moment.')
        toast.warning(`Too many attempts. Try again in ${wait}s.`)
      } else if (err.status === 401) {
        navigate('/login', { state: { from: location.pathname } })
      } else {
        setPhase('error')
        const text = err.status === 503 ? 'The system is very busy. Please try again.' : err.message
        setMessage(text)
        toast.error(text)
      }
    }
  }

  /** Forget the last message, e.g. when the buyer picks another ticket type. */
  function reset() {
    if (phase !== 'buying') setPhase('idle')
    setMessage(null)
  }

  const coolingDown = cooldownUntil > now
  return { phase, message, coolingDown, secondsLeft: Math.ceil((cooldownUntil - now) / 1000), buy, reset }
}
