import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getMe } from '../api/me'
import { createWant, deleteWant, listWants, updateWant } from '../api/wants'
import MeProvider from '../hooks/MeProvider'
import type { Category, User, Want } from '../types/wants'
import Wants from './Wants'

// Stubbed at the api boundary: the component and its hook are what is under
// test, and fetch is not. Keeping the seam here means these tests survive a
// change of transport.
vi.mock('../api/wants', () => ({
  listWants: vi.fn(),
  createWant: vi.fn(),
  updateWant: vi.fn(),
  deleteWant: vi.fn(),
}))

vi.mock('../api/me', () => ({ getMe: vi.fn(), updateMe: vi.fn() }))

const DANI: User = { id: 1, username: 'dani', clerkId: 'user_dani', email: null, lastSignInAt: null, nickname: null, s3Url: null }
const SAM: User = { id: 2, username: 'sam', clerkId: 'user_sam', email: null, lastSignInAt: null, nickname: 'Sammy', s3Url: null }

function want(id: number, message: string, category: Category = 'FOOD', owner = DANI): Want {
  return { id, message, category, userId: owner.id, username: owner.username, nickname: owner.nickname }
}

// Wants reads the signed-in user from useMe, so it renders inside the provider.
function renderWants() {
  render(
    <MeProvider>
      <Wants />
    </MeProvider>,
  )
}

describe('Wants', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getMe).mockResolvedValue(DANI)
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown'),
      want(2, 'Dune Part Two', 'MOVIE'),
    ])
  })

  it('shows the wants the API returns', async () => {
    renderWants()

    expect(await screen.findByText('Ramen downtown')).toBeInTheDocument()
    expect(screen.getByText('Dune Part Two')).toBeInTheDocument()
  })

  it('creates a want and clears the input', async () => {
    vi.mocked(createWant).mockResolvedValue(want(3, 'Tacos'))
    renderWants()
    await screen.findByText('Ramen downtown')

    const input = screen.getByLabelText('Want description')
    await userEvent.type(input, 'Tacos')
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))

    expect(createWant).toHaveBeenCalledWith({ message: 'Tacos', category: 'FOOD' })
    await waitFor(() => expect(input).toHaveValue(''))
  })

  it('keeps the typed text when creating fails', async () => {
    vi.mocked(createWant).mockRejectedValue(new Error('message must not be blank'))
    renderWants()
    await screen.findByText('Ramen downtown')

    const input = screen.getByLabelText('Want description')
    await userEvent.type(input, 'Tacos')
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))

    expect(await screen.findByText('message must not be blank')).toBeInTheDocument()
    expect(input).toHaveValue('Tacos')
  })

  it('asks the API for one category when a filter is chosen', async () => {
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(screen.getByRole('button', { name: 'MOVIE' }))

    await waitFor(() => expect(listWants).toHaveBeenLastCalledWith('MOVIE'))
  })

  it('saves an edited message', async () => {
    vi.mocked(updateWant).mockResolvedValue(want(1, 'Ramen uptown'))
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click((await screen.findAllByRole('button', { name: 'Edit' }))[0])
    const editor = screen.getByLabelText('Edit want')
    await userEvent.clear(editor)
    await userEvent.type(editor, 'Ramen uptown')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(updateWant).toHaveBeenCalledWith(1, {
      message: 'Ramen uptown',
      category: 'FOOD',
    })
  })

  it('deletes a want', async () => {
    vi.mocked(deleteWant).mockResolvedValue(undefined)
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click((await screen.findAllByRole('button', { name: 'Delete' }))[0])

    expect(deleteWant).toHaveBeenCalledWith(1)
  })

  it("offers edit controls only on the signed-in user's own wants", async () => {
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown'),
      want(2, 'Dune Part Two', 'MOVIE', SAM),
    ])
    renderWants()
    await screen.findByText('Dune Part Two')

    // One set of controls, for dani's want; sam's row is read-only.
    expect(await screen.findAllByRole('button', { name: 'Edit' })).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: 'Delete' })).toHaveLength(1)
    expect(screen.getAllByLabelText('Change category')).toHaveLength(1)
  })

  it("names each owner by nickname, or by username when they haven't set one", async () => {
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown'),
      want(2, 'Dune Part Two', 'MOVIE', SAM),
    ])
    renderWants()

    expect(await screen.findByText('Sammy')).toBeInTheDocument()
    expect(screen.getByText('dani')).toBeInTheDocument()
    expect(screen.queryByText('sam')).not.toBeInTheDocument()
  })
})
