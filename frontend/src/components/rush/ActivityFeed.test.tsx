import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { RushFeedItem } from '../../api/types'
import { groupRepeats } from '../../lib/rush'
import { ActivityFeed, LatestActivity } from './ActivityFeed'

const missed: RushFeedItem = { atMs: 70_000, kind: 'missed', text: '1 fan reached the front, but the tickets were already gone' }

describe('groupRepeats', () => {
  it('merges back-to-back identical messages and keeps the newest time', () => {
    const rows = groupRepeats([
      { atMs: 84_000, kind: 'end', text: 'SOLD OUT.' },
      { ...missed, atMs: 70_000 },
      { ...missed, atMs: 69_000 },
      { atMs: 60_000, kind: 'paid', text: 'Fan #1 paid' },
      { ...missed, atMs: 50_000 },
    ])
    expect(rows.map((r) => [r.kind, r.count, r.atMs])).toEqual([
      ['end', 1, 84_000],
      ['missed', 2, 70_000],
      ['paid', 1, 60_000],
      ['missed', 1, 50_000], // not next to the others: its own row
    ])
  })
})

describe('ActivityFeed', () => {
  it('shows the whole log, with repeats merged', () => {
    const items: RushFeedItem[] = [
      ...Array.from({ length: 8 }, (_, i): RushFeedItem => ({ atMs: 90_000 - i * 1000, kind: 'paid', text: `Fan #${i} paid` })),
      missed, { ...missed, atMs: 69_000 },
    ]
    render(<ActivityFeed items={items} />)
    expect(screen.getAllByRole('listitem')).toHaveLength(9)
    expect(screen.getByLabelText('2 times')).toHaveTextContent('×2')
  })
})

describe('LatestActivity', () => {
  it('shows the newest event and how many are new, and opens the log on click', () => {
    const onOpen = vi.fn()
    render(<LatestActivity items={[{ atMs: 84_000, kind: 'end', text: 'SOLD OUT.' }, missed]} unseen={3} open={false} onToggle={onOpen} />)
    expect(screen.getByText('SOLD OUT.')).toBeInTheDocument()
    expect(screen.getByText('3 new')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /open log/i }))
    expect(onOpen).toHaveBeenCalledOnce()
  })

  it('says "Nothing yet" before anything happened, with no badge', () => {
    render(<LatestActivity items={[]} unseen={0} open={false} onToggle={() => {}} />)
    expect(screen.getByText('Nothing yet')).toBeInTheDocument()
    expect(screen.queryByText(/new$/)).not.toBeInTheDocument()
  })

  it('says "Close log" while the log is open', () => {
    render(<LatestActivity items={[missed]} unseen={0} open onToggle={() => {}} />)
    expect(screen.getByRole('button', { name: /close log/i })).toHaveAttribute('aria-expanded', 'true')
  })
})
