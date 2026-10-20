import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { follow, listFollowing, searchPeople, unfollow } from '../api/people'
import type { Person } from '../types/people'
import People from './People'

// Stubbed at the api boundary, as in Wants.test.tsx: the component and its
// hook are under test, fetch is not.
vi.mock('../api/people', () => ({
  searchPeople: vi.fn(),
  listFollowing: vi.fn(),
  follow: vi.fn(),
  unfollow: vi.fn(),
}))

function person(id: number, username: string, following = false): Person {
  return { id, username, imageUrl: null, following }
}

describe('People', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listFollowing).mockResolvedValue([person(2, 'alex', true)])
    vi.mocked(searchPeople).mockResolvedValue([person(2, 'alex', true), person(3, 'sam')])
  })

  it('lists who you follow before anything is typed', async () => {
    render(<People />)

    expect(await screen.findByRole('button', { name: 'Unfollow alex' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'You follow' })).toBeInTheDocument()
    expect(searchPeople).not.toHaveBeenCalled()
  })

  it('says so when you follow no one', async () => {
    vi.mocked(listFollowing).mockResolvedValue([])
    render(<People />)

    expect(
      await screen.findByText("You don't follow anyone yet. Search for someone above."),
    ).toBeInTheDocument()
  })

  it('searches by the trimmed username and marks whom you follow', async () => {
    render(<People />)
    await screen.findByRole('button', { name: 'Unfollow alex' })

    await userEvent.type(screen.getByLabelText('Search by username'), ' a ')

    expect(await screen.findByRole('button', { name: 'Follow sam' })).toBeInTheDocument()
    expect(searchPeople).toHaveBeenLastCalledWith('a')
    expect(screen.getByRole('button', { name: 'Unfollow alex' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Results' })).toBeInTheDocument()
  })

  it('waits for typing to pause before searching', async () => {
    render(<People />)
    await screen.findByRole('button', { name: 'Unfollow alex' })

    await userEvent.type(screen.getByLabelText('Search by username'), 'sam')

    await screen.findByRole('button', { name: 'Follow sam' })
    expect(searchPeople).toHaveBeenCalledTimes(1)
    expect(searchPeople).toHaveBeenCalledWith('sam')
  })

  it('follows someone and turns the button into Unfollow', async () => {
    vi.mocked(follow).mockResolvedValue(undefined)
    render(<People />)
    await userEvent.type(screen.getByLabelText('Search by username'), 'sam')

    await userEvent.click(await screen.findByRole('button', { name: 'Follow sam' }))

    expect(follow).toHaveBeenCalledWith(3)
    expect(await screen.findByRole('button', { name: 'Unfollow sam' })).toBeInTheDocument()
  })

  it('says what a follow and an unfollow did, until the search changes', async () => {
    vi.mocked(follow).mockResolvedValue(undefined)
    vi.mocked(unfollow).mockResolvedValue(undefined)
    render(<People />)
    const box = screen.getByLabelText('Search by username')
    await userEvent.type(box, 'sam')

    await userEvent.click(await screen.findByRole('button', { name: 'Follow sam' }))
    expect(await screen.findByText('Now following sam. Their wants are in your list.')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Unfollow sam' }))
    expect(
      await screen.findByText('Unfollowed sam. Their wants are no longer in your list.'),
    ).toBeInTheDocument()

    await userEvent.type(box, 'x')
    expect(screen.queryByText(/Unfollowed sam/)).not.toBeInTheDocument()
  })

  it('unfollows someone, keeping them listed so they can be followed again', async () => {
    vi.mocked(unfollow).mockResolvedValue(undefined)
    render(<People />)

    await userEvent.click(await screen.findByRole('button', { name: 'Unfollow alex' }))

    expect(unfollow).toHaveBeenCalledWith(2)
    expect(await screen.findByRole('button', { name: 'Follow alex' })).toBeInTheDocument()
  })

  it('shows the error and leaves the button as it was when following fails', async () => {
    vi.mocked(follow).mockRejectedValue(new Error('User 3 not found'))
    render(<People />)
    await userEvent.type(screen.getByLabelText('Search by username'), 'sam')

    await userEvent.click(await screen.findByRole('button', { name: 'Follow sam' }))

    // Shown in sam's own row, beside the button that failed.
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('User 3 not found')
    expect(alert.closest('li')).toHaveTextContent('sam')
    expect(screen.getByRole('button', { name: 'Follow sam' })).toHaveAttribute('aria-disabled', 'false')
  })

  it('keeps keyboard focus on the button through a follow', async () => {
    let finish = () => {}
    vi.mocked(follow).mockReturnValue(new Promise((resolve) => (finish = () => resolve(undefined))))
    render(<People />)
    await userEvent.type(screen.getByLabelText('Search by username'), 'sam')
    const button = await screen.findByRole('button', { name: 'Follow sam' })

    button.focus()
    await userEvent.keyboard('{Enter}')

    // Blocked but still focusable while the request runs, so a second press does nothing.
    expect(button).toHaveAttribute('aria-disabled', 'true')
    expect(button).toHaveFocus()
    await userEvent.keyboard('{Enter}')
    expect(follow).toHaveBeenCalledTimes(1)

    finish()
    await waitFor(() => expect(button).toHaveAccessibleName('Unfollow sam'))
    expect(button).toHaveFocus()
  })

  it('drops the earlier rows when a search fails', async () => {
    vi.mocked(searchPeople).mockRejectedValue(new Error('Search failed'))
    render(<People />)
    await screen.findByRole('button', { name: 'Unfollow alex' })

    await userEvent.type(screen.getByLabelText('Search by username'), 'sam')

    expect(await screen.findByRole('alert')).toHaveTextContent('Search failed')
    expect(screen.queryByRole('button', { name: 'Unfollow alex' })).not.toBeInTheDocument()
    expect(screen.queryByText(/No one matches/)).not.toBeInTheDocument()
  })

  it('keeps the earlier rows, marked busy, while the next search loads', async () => {
    render(<People />)
    await screen.findByRole('button', { name: 'Unfollow alex' })

    await userEvent.type(screen.getByLabelText('Search by username'), 's')

    // Still the follow list, labelled as such, until the results arrive.
    expect(screen.getByRole('list')).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('heading', { level: 2, name: 'You follow' })).toBeInTheDocument()
    await screen.findByRole('button', { name: 'Follow sam' })
    expect(screen.getByRole('list')).toHaveAttribute('aria-busy', 'false')
  })

  it('says when no one matches', async () => {
    vi.mocked(searchPeople).mockResolvedValue([])
    render(<People />)

    await userEvent.type(screen.getByLabelText('Search by username'), 'zed')

    expect(await screen.findByText('No one matches “zed”.')).toBeInTheDocument()
  })

  it('goes back to who you follow when the box is cleared', async () => {
    render(<People />)
    const box = screen.getByLabelText('Search by username')
    await userEvent.type(box, 'sam')
    await screen.findByRole('button', { name: 'Follow sam' })

    await userEvent.clear(box)

    await waitFor(() => expect(listFollowing).toHaveBeenCalledTimes(2))
    expect(await screen.findByRole('heading', { level: 2, name: 'You follow' })).toBeInTheDocument()
  })
})
