import { fireEvent, render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { EventPoster } from './EventPoster'

describe('EventPoster', () => {
  it('shows the uploaded picture over the generated art', () => {
    const { container } = render(<EventPoster event={{ id: 1, name: 'Neon Monsoon', imageUrl: '/api/events/1/image?v=abc' }} />)
    expect(container.querySelector('img.poster-img')).toHaveAttribute('src', '/api/events/1/image?v=abc')
    expect(container.querySelector('.poster')).toHaveClass('has-photo')
    expect(container.querySelector('svg')).toBeInTheDocument() // still underneath, while the photo loads
  })

  it('without a picture, only the generated art', () => {
    const { container } = render(<EventPoster event={{ id: 1, name: 'Neon Monsoon', imageUrl: null }} />)
    expect(container.querySelector('img')).not.toBeInTheDocument()
    expect(container.querySelector('svg')).toBeInTheDocument()
  })

  it('falls back to the generated art if the picture fails to load', () => {
    const { container } = render(<EventPoster event={{ id: 1, name: 'Neon Monsoon', imageUrl: '/broken' }} />)
    fireEvent.error(container.querySelector('img')!)
    expect(container.querySelector('img')).not.toBeInTheDocument()
    expect(container.querySelector('.poster')).not.toHaveClass('has-photo')
  })
})
