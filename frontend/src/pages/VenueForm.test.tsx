import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { VenueInput } from '../api/types'
import type { PictureChange } from '../lib/usePicture'
import { VenueForm } from './VenueForm'

const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } })
const submitSpy = () => vi.fn<(input: VenueInput, map: PictureChange) => Promise<void>>(async () => {})

describe('VenueForm', () => {
  it('builds the zones list: add, colour, reorder, remove', async () => {
    const onSubmit = submitSpy()
    render(<VenueForm onSubmit={onSubmit} onCancel={() => {}} />)
    type('Name', 'Zepp Kuala Lumpur')
    type('Zone 1 name', 'Standing Free')
    type('Zone 1 capacity', '1000')

    fireEvent.click(screen.getByRole('button', { name: /add zone/i }))
    type('Zone 2 name', 'Balcony') // a new zone is seated: numbered seats, rows x seats per row
    type('Zone 2 rows', '8')
    type('Zone 2 seats per row', '25')
    expect(screen.getByText('= 200 seats')).toBeInTheDocument()
    fireEvent.click(screen.getAllByRole('radio', { name: 'Colour #7c3aed' })[1])
    expect(screen.getByTestId('venue-total')).toHaveTextContent('1,200')

    fireEvent.click(screen.getByRole('button', { name: /add zone/i }))
    type('Zone 3 name', 'Oops')
    fireEvent.click(screen.getByRole('button', { name: 'Remove zone 3' }))
    fireEvent.click(screen.getByRole('button', { name: 'Move zone 2 up' })) // Balcony first

    fireEvent.click(screen.getByRole('button', { name: 'Create venue' }))
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit.mock.calls[0][0]).toEqual({
      name: 'Zepp Kuala Lumpur',
      zones: [
        { name: 'Balcony', kind: 'SEATED', rows: 8, seatsPerRow: 25, color: '#7c3aed' },
        { name: 'Standing Free', kind: 'STANDING', capacity: 1000, color: '#e11d48' },
      ],
    })
    expect(onSubmit.mock.calls[0][1]).toEqual({ kind: 'keep' })
  })

  it('a seated zone needs its seat grid', () => {
    const onSubmit = submitSpy()
    render(<VenueForm onSubmit={onSubmit} onCancel={() => {}} />)
    type('Name', 'Somewhere')
    type('Zone 1 name', 'Floor')
    fireEvent.change(screen.getByLabelText('Zone 1 kind'), { target: { value: 'SEATED' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create venue' }))
    expect(screen.getByText(/1-60 rows and 1-80 seats per row/i)).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('says what is missing instead of sending', () => {
    const onSubmit = submitSpy()
    render(<VenueForm onSubmit={onSubmit} onCancel={() => {}} />)
    type('Name', 'Somewhere')
    fireEvent.click(screen.getByRole('button', { name: 'Create venue' }))
    expect(screen.getByText(/give every zone a name/i)).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })
})
