import { describe, expect, it } from 'vitest'
import { formatSeats, hasSeatMap } from './seats'

describe('formatSeats', () => {
  it('groups seats by row, in order', () => {
    expect(formatSeats([{ row: 'F', number: 13 }, { row: 'F', number: 12 }])).toBe('Row F · Seats 12, 13')
    expect(formatSeats([{ row: 'F', number: 12 }])).toBe('Row F · Seat 12')
    expect(formatSeats([{ row: 'F', number: 12 }, { row: 'G', number: 3 }])).toBe('Row F · Seat 12; Row G · Seat 3')
    expect(formatSeats([])).toBe('')
  })

  it('only types with a seat grid have a seat map', () => {
    expect(hasSeatMap({ seatRows: 30, seatsPerRow: 50 })).toBe(true)
    expect(hasSeatMap({ seatRows: null, seatsPerRow: null })).toBe(false)
  })
})
