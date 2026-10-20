import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getToken } from '@clerk/react'
import { getMe } from '../api/me'
import { createWant, deleteWant, listWants, updateWant } from '../api/wants'
import placeholder from '../assets/avatar-placeholder.svg'
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

vi.mock('../api/me', () => ({ getMe: vi.fn() }))

// The signed-in user's Clerk picture, which a test can change to play the
// part of the account menu, and the token call made when it changes.
const clerk = vi.hoisted(() => ({
  user: { imageUrl: 'https://img.clerk.com/old.jpg' } as { imageUrl: string } | undefined,
}))

vi.mock('@clerk/react', () => ({
  useUser: () => ({ user: clerk.user }),
  getToken: vi.fn(),
}))

const DANI: User = { id: 1, username: 'dani', clerkId: 'user_dani', email: null, lastSignInAt: null, imageUrl: null }
const SAM: User = { id: 2, username: 'sam', clerkId: 'user_sam', email: null, lastSignInAt: null, imageUrl: 'https://img.clerk.com/sam.jpg' }

function want(id: number, message: string, category: Category = 'FOOD', owner = DANI): Want {
  return { id, message, category, userId: owner.id, username: owner.username, imageUrl: owner.imageUrl }
}

// Wants reads the signed-in user from useMe, so it renders inside the
// provider, and links to People, so inside a router.
function WantsScreen() {
  return (
    <MemoryRouter>
      <MeProvider>
        <Wants />
      </MeProvider>
    </MemoryRouter>
  )
}

function renderWants() {
  return render(<WantsScreen />)
}

describe('Wants', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getMe).mockResolvedValue(DANI)
    vi.mocked(getToken).mockResolvedValue('fresh-token')
    clerk.user = { imageUrl: 'https://img.clerk.com/old.jpg' }
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown'),
      want(2, 'Dune Part Two', 'MOVIE'),
    ])
  })

  it('points to People when the list is empty', async () => {
    vi.mocked(listWants).mockResolvedValue([])
    renderWants()

    expect(await screen.findByRole('link', { name: 'find people to follow' })).toHaveAttribute('href', '/people')
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

    expect(await screen.findByRole('alert')).toHaveTextContent('message must not be blank')
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

  it('names each owner by username', async () => {
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown'),
      want(2, 'Dune Part Two', 'MOVIE', SAM),
    ])
    renderWants()

    expect(await screen.findByText('sam')).toBeInTheDocument()
    expect(screen.getByText('dani')).toBeInTheDocument()
  })

  it("shows each owner's picture beside their want, or the placeholder without one", async () => {
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown'),
      want(2, 'Dune Part Two', 'MOVIE', SAM),
    ])
    renderWants()
    await screen.findByText('Dune Part Two')

    // Decorative: the username beside each picture already names the owner.
    const pictures = screen.getAllByRole('presentation')
    expect(pictures.map((img) => img.getAttribute('src'))).toEqual([placeholder, 'https://img.clerk.com/sam.jpg'])
  })

  it('reloads the list with a fresh token when you change your picture in Clerk', async () => {
    const { rerender } = renderWants()
    await screen.findByText('Ramen downtown')
    expect(listWants).toHaveBeenCalledTimes(1)

    clerk.user = { imageUrl: 'https://img.clerk.com/new.jpg' }
    rerender(<WantsScreen />)

    await waitFor(() => expect(listWants).toHaveBeenCalledTimes(2))
    expect(getToken).toHaveBeenCalledWith({ skipCache: true })
  })

  it('does not reload just because Clerk finished loading your user', async () => {
    clerk.user = undefined
    const { rerender } = renderWants()
    await screen.findByText('Ramen downtown')

    clerk.user = { imageUrl: 'https://img.clerk.com/old.jpg' }
    rerender(<WantsScreen />)

    expect(getToken).not.toHaveBeenCalled()
    expect(listWants).toHaveBeenCalledTimes(1)
  })
})
