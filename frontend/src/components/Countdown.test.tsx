import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Countdown } from './Countdown'

describe('Countdown', () => {
  beforeEach(() => {
    vi.useFakeTimers() // control the clock instead of really waiting
    vi.setSystemTime(new Date('2027-01-01T12:00:00Z'))
  })
  afterEach(() => vi.useRealTimers())

  it('ticks down every second and turns urgent in the last minute', () => {
    render(<Countdown until="2027-01-01T12:01:30Z" />)
    expect(screen.getByRole('timer')).toHaveTextContent('1:30')
    expect(screen.getByRole('timer')).not.toHaveClass('urgent')

    act(() => vi.advanceTimersByTime(31_000))
    expect(screen.getByRole('timer')).toHaveTextContent('0:59')
    expect(screen.getByRole('timer')).toHaveClass('urgent')
  })

  it('stops at 0:00', () => {
    render(<Countdown until="2027-01-01T12:00:02Z" />)
    act(() => vi.advanceTimersByTime(10_000))
    expect(screen.getByRole('timer')).toHaveTextContent('0:00')
  })
})
