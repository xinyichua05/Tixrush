import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Dialog } from './ui'

afterEach(() => {
  document.body.style.overflow = ''
  document.body.classList.remove('side-panel-open')
})

describe('Dialog', () => {
  it('modal (default): freezes the page behind it', () => {
    const { unmount } = render(<Dialog open onClose={() => {}} title="Edit">hi</Dialog>)
    expect(document.body.style.overflow).toBe('hidden')
    unmount()
    expect(document.body.style.overflow).toBe('')
  })

  it('side panel (modal={false}): the page keeps scrolling, and Escape still closes it', () => {
    const onClose = vi.fn()
    const { unmount } = render(<Dialog open onClose={onClose} title="Log" variant="drawer" modal={false}>hi</Dialog>)
    expect(document.body.style.overflow).toBe('')
    expect(document.body).toHaveClass('side-panel-open')
    expect(screen.getByRole('dialog', { name: 'Log' })).toHaveAttribute('aria-modal', 'false')

    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledOnce()
    unmount()
    expect(document.body).not.toHaveClass('side-panel-open')
  })
})
