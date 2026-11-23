import { QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { EventInput, EventResponse, Venue } from '../api/types'
import { testQueryClient } from '../test/setup'
import { EventForm, type PictureChange } from './EventForm'

// jsdom has no canvas, so the real shrinking can't run here: pretend it worked.
const shrunk = new Blob(['small'], { type: 'image/webp' })
vi.mock('../lib/image', () => ({ MAX_SIDE: 1600, PICTURE_TYPES: ['image/jpeg', 'image/png', 'image/webp'], shrinkImage: vi.fn(async () => shrunk) }))

const arena: Venue = {
  id: 3, name: 'Axiata Arena, Kuala Lumpur', mapUrl: null, totalCapacity: 3500, eventCount: 0,
  zones: [
    { id: 31, name: 'Rock Zone', kind: 'STANDING', capacity: 2000, color: '#e11d48', rows: null, seatsPerRow: null },
    { id: 32, name: 'CAT 1', kind: 'SEATED', capacity: 1500, color: '#2563eb', rows: 30, seatsPerRow: 50 },
  ],
}
vi.mock('../api/venues', () => ({ listVenues: vi.fn(async () => [arena]) }))

const existing: EventResponse = {
  id: 4, name: 'Neon Monsoon', venue: 'Axiata Arena, Kuala Lumpur', startsAt: '2030-03-13T12:00:00Z',
  saleStartsAt: '2030-01-10T02:00:00Z', totalTickets: 6000, availableTickets: 6000, imageUrl: '/api/events/4/image?v=1',
  venueId: null, mapUrl: null, ticketTypes: [], minPrice: null,
}

// jsdom has no object URLs either (used for the local preview).
beforeEach(() => {
  URL.createObjectURL = vi.fn(() => 'blob:preview')
  URL.revokeObjectURL = vi.fn()
})
afterEach(() => vi.clearAllMocks())

const submitSpy = () => vi.fn<(input: EventInput, picture: PictureChange) => Promise<void>>(async () => {})
const save = () => fireEvent.click(screen.getByRole('button', { name: /save changes|create event/i }))
const renderForm = (props: Partial<Parameters<typeof EventForm>[0]> & { onSubmit: Parameters<typeof EventForm>[0]['onSubmit'] }) =>
  render(<QueryClientProvider client={testQueryClient()}><EventForm onCancel={() => {}} {...props} /></QueryClientProvider>)
const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } })

describe('EventForm picture', () => {
  it('keeps the current picture when you don\'t touch it', async () => {
    const onSubmit = submitSpy()
    renderForm({ initial: existing, onSubmit })
    save()
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit.mock.calls[0][1]).toEqual({ kind: 'keep' })
  })

  it('sends the shrunk picture you chose', async () => {
    const onSubmit = submitSpy()
    renderForm({ onSubmit })
    fireEvent.change(screen.getByTestId('picture-input'), { target: { files: [new File(['big'], 'photo.jpg', { type: 'image/jpeg' })] } })
    await screen.findByRole('button', { name: /change picture/i })
    type('Ticket type 1 price', '25')
    save()
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit.mock.calls[0][1]).toEqual({ kind: 'set', blob: shrunk })
  })

  it('Remove on an event with a picture asks to delete it', async () => {
    const onSubmit = submitSpy()
    renderForm({ initial: existing, onSubmit })
    fireEvent.click(screen.getByRole('button', { name: /^remove$/i }))
    expect(screen.getByRole('button', { name: /choose picture/i })).toBeInTheDocument()
    save()
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit.mock.calls[0][1]).toEqual({ kind: 'remove' })
  })
})

describe('EventForm tickets', () => {
  it('simple tickets: one General admission row to start, add more, totals add up', async () => {
    const onSubmit = submitSpy()
    renderForm({ onSubmit })
    type('Name', 'Batik Through Time')
    type('Venue', 'ILHAM Gallery, Kuala Lumpur')
    expect(screen.getByLabelText('Ticket type 1 name')).toHaveValue('General admission')

    // Without a price it isn't sent: the form says what's missing.
    save()
    expect(await screen.findByText(/enter a price/i)).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()

    type('Ticket type 1 name', 'Adult')
    type('Ticket type 1 price', '25')
    type('Ticket type 1 tickets', '800')
    fireEvent.click(screen.getByRole('button', { name: /add ticket type/i }))
    type('Ticket type 2 name', 'Student')
    type('Ticket type 2 price', '15.50')
    type('Ticket type 2 tickets', '300')
    expect(screen.getByTestId('tickets-total')).toHaveTextContent('1,100')

    save()
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    const input = onSubmit.mock.calls[0][0]
    expect(input.venue).toBe('ILHAM Gallery, Kuala Lumpur')
    expect(input.venueId).toBeNull()
    expect(input.totalTickets).toBeUndefined()
    expect(input.ticketTypes).toEqual([
      { name: 'Adult', price: 25, tickets: 800 },
      { name: 'Student', price: 15.5, tickets: 300 },
    ])
  })

  it('venue seating plan: its zones appear, you price them, and can leave one out', async () => {
    const onSubmit = submitSpy()
    renderForm({ onSubmit })
    type('Name', 'Neon Monsoon')
    fireEvent.click(screen.getByRole('radio', { name: /venue seating plan/i }))
    await screen.findByRole('option', { name: arena.name })
    type('Venue', String(arena.id)) // the <select>
    expect(screen.queryByRole('textbox', { name: 'Venue' })).not.toBeInTheDocument() // the venue name comes from the venue

    expect(screen.getByLabelText('Rock Zone tickets')).toHaveValue(2000) // defaults to the zone's capacity
    type('Rock Zone price', '488')
    fireEvent.click(screen.getByLabelText('Sell CAT 1'))
    expect(screen.getByTestId('tickets-total')).toHaveTextContent('2,000')

    save()
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    const input = onSubmit.mock.calls[0][0]
    expect(input.venue).toBe(arena.name)
    expect(input.venueId).toBe(3)
    expect(input.ticketTypes).toEqual([{ venueZoneId: 31, price: 488, tickets: 2000 }])
  })

  it('an old single-pool event keeps its tickets unless you switch', async () => {
    const onSubmit = submitSpy()
    renderForm({ initial: existing, onSubmit })
    expect(screen.getByText(/one standard ticket/i)).toBeInTheDocument()
    save()
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit.mock.calls[0][0].ticketTypes).toBeUndefined() // nothing about tickets is sent
  })

  it('after tickets are sold, only prices can change', () => {
    const sold: EventResponse = {
      ...existing, availableTickets: 790, totalTickets: 800, minPrice: 25,
      ticketTypes: [{ id: 9, name: 'Adult', kind: null, color: null, price: 25, totalTickets: 800, availableTickets: 790, seatRows: null, seatsPerRow: null }],
    }
    renderForm({ initial: sold, onSubmit: submitSpy() })
    expect(screen.getByText(/only the prices can change/i)).toBeInTheDocument()
    expect(screen.getByLabelText('Ticket type 1 name')).toBeDisabled()
    expect(screen.getByLabelText('Ticket type 1 tickets')).toBeDisabled()
    expect(screen.getByLabelText('Ticket type 1 price')).toBeEnabled()
    expect(screen.queryByRole('button', { name: /add ticket type/i })).not.toBeInTheDocument()
  })
})
