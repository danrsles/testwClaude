import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getToken } from '@clerk/react'
import { getMe } from '../api/me'
import { cancelWant, closeWant, createWant, listWants, reopenWant, signUp, updateWant, withdraw } from '../api/wants'
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
  cancelWant: vi.fn(),
  closeWant: vi.fn(),
  reopenWant: vi.fn(),
  signUp: vi.fn(),
  withdraw: vi.fn(),
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

// An active want for one with only its owner seated, unless `seats` says otherwise.
function want(
  id: number,
  message: string,
  category: Category = 'FOOD',
  owner = DANI,
  seats: Partial<Pick<Want, 'capacity' | 'seatsTaken' | 'signedUp' | 'status'>> = {},
): Want {
  return {
    id, message, category, userId: owner.id, username: owner.username, imageUrl: owner.imageUrl,
    capacity: 1, seatsTaken: 1, signedUp: owner === DANI, status: 'ACTIVE', ...seats,
  }
}

/** The list item holding a want's message. */
function row(message: string): HTMLElement {
  return screen.getByText(message).closest('li')!
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

    expect(createWant).toHaveBeenCalledWith(expect.objectContaining({ message: 'Tacos', category: 'FOOD' }))
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

  it('cancels a want and reloads the list', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(cancelWant).mockResolvedValue(undefined)
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(within(row('Ramen downtown')).getByRole('button', { name: 'Cancel want' }))

    expect(cancelWant).toHaveBeenCalledWith(1)
    await waitFor(() => expect(listWants).toHaveBeenCalledTimes(2))
  })

  it('asks before cancelling, and cancels nothing if you say no', async () => {
    // Cancelling is final and tells the whole lineup, unlike closing, which Reopen undoes.
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(within(row('Ramen downtown')).getByRole('button', { name: 'Cancel want' }))

    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('Ramen downtown'))
    expect(cancelWant).not.toHaveBeenCalled()
  })

  it('says how many others will hear of a cancel, and nobody when you are alone', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown', 'FOOD', DANI, { capacity: 4, seatsTaken: 3 }),
      want(2, 'Dune Part Two', 'MOVIE'),
    ])
    renderWants()
    await screen.findByText('Dune Part Two')

    await userEvent.click(within(row('Ramen downtown')).getByRole('button', { name: 'Cancel want' }))
    expect(confirm).toHaveBeenLastCalledWith(expect.stringContaining('The 2 other people signed up will be told.'))

    await userEvent.click(within(row('Dune Part Two')).getByRole('button', { name: 'Cancel want' }))
    expect(confirm).toHaveBeenLastCalledWith(expect.not.stringContaining('told'))
  })

  it('treats a want without a status as active', async () => {
    // An API from before statuses sends none: the row must work as it did then.
    const legacy = (w: Want) => ({ ...w, status: undefined }) as unknown as Want
    vi.mocked(listWants).mockResolvedValue([
      legacy(want(1, 'Ramen downtown')),
      legacy(want(2, 'Dune Part Two', 'MOVIE', SAM, { capacity: 4 })),
    ])
    renderWants()
    await screen.findByText('Dune Part Two')

    expect(within(row('Ramen downtown')).getByRole('button', { name: 'Edit' })).toBeEnabled()
    expect(within(row('Ramen downtown')).queryByRole('button', { name: 'Reopen' })).not.toBeInTheDocument()
    expect(within(row('Dune Part Two')).getByRole('button', { name: 'Sign up' })).toBeEnabled()
  })

  it("offers Close on your own active wants, but not on other people's", async () => {
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown'),
      want(2, 'Dune Part Two', 'MOVIE', SAM),
    ])
    renderWants()
    await screen.findByText('Dune Part Two')

    expect(within(row('Ramen downtown')).getByRole('button', { name: 'Close' })).toBeEnabled()
    expect(within(row('Dune Part Two')).queryByRole('button', { name: 'Close' })).not.toBeInTheDocument()
  })

  it('closes a want and reloads the list', async () => {
    vi.mocked(closeWant).mockResolvedValue(want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'CLOSED' }))
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(within(row('Ramen downtown')).getByRole('button', { name: 'Close' }))

    expect(closeWant).toHaveBeenCalledWith(1)
    await waitFor(() => expect(listWants).toHaveBeenCalledTimes(2))
  })

  it('shows Closed on a closed want', async () => {
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'CLOSED' }),
      want(2, 'Dune Part Two', 'MOVIE', SAM),
    ])
    renderWants()
    await screen.findByText('Dune Part Two')

    expect(within(row('Ramen downtown')).getByText('Closed')).toBeInTheDocument()
    expect(within(row('Dune Part Two')).queryByText('Closed')).not.toBeInTheDocument()
  })

  it('offers Reopen instead of Close, and no Edit or category change, on your closed wants', async () => {
    // Editing a closed want is a 409 until it is reopened.
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'CLOSED' })])
    renderWants()
    await screen.findByText('Ramen downtown')

    expect(screen.getByRole('button', { name: 'Reopen' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: 'Close' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Change category')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel want' })).toBeEnabled()
  })

  it('reopens a want and reloads the list', async () => {
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'CLOSED' })])
    vi.mocked(reopenWant).mockResolvedValue(want(1, 'Ramen downtown'))
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(screen.getByRole('button', { name: 'Reopen' }))

    expect(reopenWant).toHaveBeenCalledWith(1)
    await waitFor(() => expect(listWants).toHaveBeenCalledTimes(2))
  })

  it("offers neither Sign up nor Withdraw on someone else's closed want", async () => {
    // The lineup is fixed: both are a 409 until the owner reopens it.
    vi.mocked(listWants).mockResolvedValue([
      want(2, 'Dune Part Two', 'MOVIE', SAM, { capacity: 4, status: 'CLOSED' }),
      want(3, 'Catan night', 'GAME', SAM, { capacity: 4, seatsTaken: 2, signedUp: true, status: 'CLOSED' }),
    ])
    renderWants()
    await screen.findByText('Catan night')

    expect(screen.queryByRole('button', { name: 'Sign up' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Withdraw' })).not.toBeInTheDocument()
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
    expect(screen.getAllByRole('button', { name: 'Cancel want' })).toHaveLength(1)
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

  it('creates a want with the capacity entered', async () => {
    vi.mocked(createWant).mockResolvedValue(want(3, 'Tacos', 'FOOD', DANI, { capacity: 4 }))
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.type(screen.getByLabelText('Want description'), 'Tacos')
    const capacity = screen.getByLabelText('Capacity')
    await userEvent.clear(capacity)
    await userEvent.type(capacity, '4')
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))

    expect(createWant).toHaveBeenCalledWith({ message: 'Tacos', category: 'FOOD', capacity: 4 })
  })

  it('creates a want for one when the capacity is left alone', async () => {
    vi.mocked(createWant).mockResolvedValue(want(3, 'Tacos'))
    renderWants()
    await screen.findByText('Ramen downtown')

    expect(screen.getByLabelText('Capacity')).toHaveValue(1)
    await userEvent.type(screen.getByLabelText('Want description'), 'Tacos')
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))

    expect(createWant).toHaveBeenCalledWith({ message: 'Tacos', category: 'FOOD', capacity: 1 })
  })

  it('shows seats taken out of capacity on each row', async () => {
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown', 'FOOD', DANI, { capacity: 4, seatsTaken: 2 }),
      want(2, 'Dune Part Two', 'MOVIE', SAM, { capacity: 3, seatsTaken: 3 }),
    ])
    renderWants()
    await screen.findByText('Ramen downtown')

    expect(within(row('Ramen downtown')).getByText('2 / 4 seats')).toBeInTheDocument()
    expect(within(row('Dune Part Two')).getByText('3 / 3 seats')).toBeInTheDocument()
  })

  it('saves a raised capacity when editing', async () => {
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { capacity: 4, seatsTaken: 2 })])
    vi.mocked(updateWant).mockResolvedValue(want(1, 'Ramen downtown', 'FOOD', DANI, { capacity: 6, seatsTaken: 2 }))
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    const capacity = screen.getByLabelText('Edit capacity')
    await userEvent.clear(capacity)
    await userEvent.type(capacity, '6')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(updateWant).toHaveBeenCalledWith(1, { message: 'Ramen downtown', category: 'FOOD', capacity: 6 })
  })

  it('does not offer a capacity below the current one when editing', async () => {
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { capacity: 4, seatsTaken: 2 })])
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))

    // The API rejects lowering it with a 400, so the field starts where it is and goes no lower.
    const capacity = screen.getByLabelText('Edit capacity')
    expect(capacity).toHaveValue(4)
    expect(capacity).toHaveAttribute('min', '4')
  })

  it("offers Sign up on other people's wants, but not on your own", async () => {
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown', 'FOOD', DANI, { capacity: 4 }),
      want(2, 'Dune Part Two', 'MOVIE', SAM, { capacity: 4 }),
    ])
    renderWants()
    await screen.findByText('Dune Part Two')

    // You already hold the seat on your own want, and can't give it up.
    expect(within(row('Dune Part Two')).getByRole('button', { name: 'Sign up' })).toBeEnabled()
    expect(within(row('Ramen downtown')).queryByRole('button', { name: 'Sign up' })).not.toBeInTheDocument()
    expect(within(row('Ramen downtown')).queryByRole('button', { name: 'Withdraw' })).not.toBeInTheDocument()
  })

  it('signs up and reloads the list', async () => {
    vi.mocked(listWants).mockResolvedValue([want(2, 'Dune Part Two', 'MOVIE', SAM, { capacity: 4 })])
    vi.mocked(signUp).mockResolvedValue({
      wantId: 2, message: 'Dune Part Two', capacity: 4, seatsTaken: 2, lineup: ['sam', 'dani'], signedUp: true, status: 'ACTIVE',
    })
    renderWants()
    await screen.findByText('Dune Part Two')

    await userEvent.click(screen.getByRole('button', { name: 'Sign up' }))

    expect(signUp).toHaveBeenCalledWith(2)
    await waitFor(() => expect(listWants).toHaveBeenCalledTimes(2))
  })

  it("offers Withdraw instead of Sign up once you're in", async () => {
    vi.mocked(listWants).mockResolvedValue([
      want(2, 'Dune Part Two', 'MOVIE', SAM, { capacity: 4, seatsTaken: 2, signedUp: true }),
    ])
    renderWants()
    await screen.findByText('Dune Part Two')

    expect(screen.getByRole('button', { name: 'Withdraw' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: 'Sign up' })).not.toBeInTheDocument()
  })

  it('withdraws and reloads the list', async () => {
    vi.mocked(listWants).mockResolvedValue([
      want(2, 'Dune Part Two', 'MOVIE', SAM, { capacity: 4, seatsTaken: 2, signedUp: true }),
    ])
    vi.mocked(withdraw).mockResolvedValue({
      wantId: 2, message: 'Dune Part Two', capacity: 4, seatsTaken: 1, lineup: ['sam'], signedUp: false, status: 'ACTIVE',
    })
    renderWants()
    await screen.findByText('Dune Part Two')

    await userEvent.click(screen.getByRole('button', { name: 'Withdraw' }))

    expect(withdraw).toHaveBeenCalledWith(2)
    await waitFor(() => expect(listWants).toHaveBeenCalledTimes(2))
  })

  it('disables Sign up when the want is full', async () => {
    vi.mocked(listWants).mockResolvedValue([want(2, 'Dune Part Two', 'MOVIE', SAM, { capacity: 2, seatsTaken: 2 })])
    renderWants()
    await screen.findByText('Dune Part Two')

    expect(screen.getByRole('button', { name: 'Sign up' })).toBeDisabled()
    expect(within(row('Dune Part Two')).getByText('Full')).toBeInTheDocument()
  })

  it("still offers Withdraw on a full want you're in", async () => {
    vi.mocked(listWants).mockResolvedValue([
      want(2, 'Dune Part Two', 'MOVIE', SAM, { capacity: 2, seatsTaken: 2, signedUp: true }),
    ])
    renderWants()
    await screen.findByText('Dune Part Two')

    expect(screen.getByRole('button', { name: 'Withdraw' })).toBeEnabled()
  })

  it('shows why signing up failed', async () => {
    // Someone took the last seat after the list loaded: the API answers 409.
    vi.mocked(listWants).mockResolvedValue([want(2, 'Dune Part Two', 'MOVIE', SAM, { capacity: 2 })])
    vi.mocked(signUp).mockRejectedValue(new Error('This want is full'))
    renderWants()
    await screen.findByText('Dune Part Two')

    await userEvent.click(screen.getByRole('button', { name: 'Sign up' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('This want is full')
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
