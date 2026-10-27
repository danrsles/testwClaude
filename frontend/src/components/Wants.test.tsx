import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getToken } from '@clerk/react'
import { getMe } from '../api/me'
import {
  cancelWant,
  closeWant,
  createWant,
  endWant,
  listWants,
  reopenWant,
  signUp,
  startWant,
  updateWant,
  withdraw,
} from '../api/wants'
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
  startWant: vi.fn(),
  endWant: vi.fn(),
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
const SAM: User = {
  id: 2,
  username: 'sam',
  clerkId: 'user_sam',
  email: null,
  lastSignInAt: null,
  imageUrl: 'https://img.clerk.com/sam.jpg',
}

// An active want for one, not happening, with only its owner seated, unless `seats` says otherwise.
function want(
  id: number,
  message: string,
  category: Category = 'FOOD',
  owner = DANI,
  seats: Partial<
    Pick<Want, 'capacity' | 'seatsTaken' | 'lineup' | 'signedUp' | 'status' | 'happening' | 'expiresAt'>
  > = {},
): Want {
  return {
    id,
    message,
    category,
    userId: owner.id,
    username: owner.username,
    imageUrl: owner.imageUrl,
    capacity: 1,
    seatsTaken: 1,
    lineup: [{ id: owner.id, username: owner.username, imageUrl: owner.imageUrl }],
    signedUp: owner === DANI,
    status: 'ACTIVE',
    happening: false,
    expiresAt: null,
    ...seats,
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
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown'), want(2, 'Dune Part Two', 'MOVIE')])
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
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown'), want(2, 'Dune Part Two', 'MOVIE', SAM)])
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
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }))

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

  it("offers Start on your own active and closed wants, but not on other people's", async () => {
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown'),
      want(2, 'Catan night', 'GAME', DANI, { status: 'CLOSED' }),
      want(3, 'Dune Part Two', 'MOVIE', SAM),
    ])
    renderWants()
    await screen.findByText('Dune Part Two')

    expect(within(row('Ramen downtown')).getByRole('button', { name: 'Start' })).toBeEnabled()
    expect(within(row('Catan night')).getByRole('button', { name: 'Start' })).toBeEnabled()
    expect(within(row('Dune Part Two')).queryByRole('button', { name: 'Start' })).not.toBeInTheDocument()
  })

  it('starts a want and reloads the list', async () => {
    vi.mocked(startWant).mockResolvedValue(want(1, 'Ramen downtown', 'FOOD', DANI, { happening: true }))
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(within(row('Ramen downtown')).getByRole('button', { name: 'Start' }))

    expect(startWant).toHaveBeenCalledWith(1)
    await waitFor(() => expect(listWants).toHaveBeenCalledTimes(2))
  })

  it('shows Happening now on a happening want', async () => {
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown', 'FOOD', DANI, { happening: true }),
      want(2, 'Dune Part Two', 'MOVIE', SAM),
    ])
    renderWants()
    await screen.findByText('Dune Part Two')

    expect(within(row('Ramen downtown')).getByText('Happening now')).toBeInTheDocument()
    expect(within(row('Dune Part Two')).queryByText('Happening now')).not.toBeInTheDocument()
  })

  it('offers End instead of Start on your happening wants', async () => {
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown', 'FOOD', DANI, { happening: true }),
      want(2, 'Catan night', 'GAME', DANI, { status: 'CLOSED', happening: true }),
      want(3, 'Dune Part Two', 'MOVIE', SAM, { happening: true }),
    ])
    renderWants()
    await screen.findByText('Dune Part Two')

    for (const mine of ['Ramen downtown', 'Catan night']) {
      expect(within(row(mine)).getByRole('button', { name: 'End' })).toBeEnabled()
      expect(within(row(mine)).queryByRole('button', { name: 'Start' })).not.toBeInTheDocument()
    }
    expect(within(row('Dune Part Two')).queryByRole('button', { name: 'End' })).not.toBeInTheDocument()
  })

  it('asks before ending, and ends nothing if you say no', async () => {
    // Ending is final, like cancelling: an ended want can't be started again.
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { happening: true })])
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(screen.getByRole('button', { name: 'End' }))

    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('Ramen downtown'))
    expect(endWant).not.toHaveBeenCalled()
  })

  it('ends a want and reloads the list', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { happening: true })])
    vi.mocked(endWant).mockResolvedValue(want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'ENDED' }))
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(screen.getByRole('button', { name: 'End' }))

    expect(endWant).toHaveBeenCalledWith(1)
    await waitFor(() => expect(listWants).toHaveBeenCalledTimes(2))
  })

  it('still offers Sign up to others, and Edit to you, on an active want that is happening', async () => {
    // Happening says it is live, not that the lineup is fixed: Close does that.
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown', 'FOOD', DANI, { happening: true }),
      want(2, 'Dune Part Two', 'MOVIE', SAM, { capacity: 4, happening: true }),
      want(3, 'Catan night', 'GAME', SAM, { capacity: 4, seatsTaken: 2, signedUp: true, happening: true }),
    ])
    renderWants()
    await screen.findByText('Catan night')

    expect(within(row('Ramen downtown')).getByRole('button', { name: 'Edit' })).toBeEnabled()
    expect(within(row('Dune Part Two')).getByRole('button', { name: 'Sign up' })).toBeEnabled()
    expect(within(row('Catan night')).getByRole('button', { name: 'Withdraw' })).toBeEnabled()
  })

  it('shows Ended on an ended want', async () => {
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'ENDED' }),
      want(2, 'Dune Part Two', 'MOVIE', SAM),
    ])
    renderWants()
    await screen.findByText('Dune Part Two')

    expect(within(row('Ramen downtown')).getByText('Ended')).toBeInTheDocument()
    expect(within(row('Dune Part Two')).queryByText('Ended')).not.toBeInTheDocument()
  })

  it("offers no buttons on an ended want, yours or anyone else's", async () => {
    // Every change to an ended want is a 409, for the owner and everyone else.
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'ENDED' }),
      want(2, 'Dune Part Two', 'MOVIE', SAM, { capacity: 4, status: 'ENDED' }),
      want(3, 'Catan night', 'GAME', SAM, { capacity: 4, seatsTaken: 2, signedUp: true, status: 'ENDED' }),
    ])
    renderWants()
    await screen.findByText('Catan night')

    for (const ended of ['Ramen downtown', 'Dune Part Two', 'Catan night']) {
      expect(within(row(ended)).queryByRole('button')).not.toBeInTheDocument()
      expect(within(row(ended)).queryByLabelText('Change category')).not.toBeInTheDocument()
    }
  })

  it("offers edit controls only on the signed-in user's own wants", async () => {
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown'), want(2, 'Dune Part Two', 'MOVIE', SAM)])
    renderWants()
    await screen.findByText('Dune Part Two')

    // One set of controls, for dani's want; sam's row is read-only.
    expect(await screen.findAllByRole('button', { name: 'Edit' })).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: 'Cancel want' })).toHaveLength(1)
    expect(screen.getAllByLabelText('Change category')).toHaveLength(1)
  })

  it('names each owner by username', async () => {
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown'), want(2, 'Dune Part Two', 'MOVIE', SAM)])
    renderWants()

    expect(await screen.findByText('sam')).toBeInTheDocument()
    expect(screen.getByText('dani')).toBeInTheDocument()
  })

  it("shows each owner's picture beside their want, or the placeholder without one", async () => {
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown'), want(2, 'Dune Part Two', 'MOVIE', SAM)])
    renderWants()
    await screen.findByText('Dune Part Two')

    // Decorative: the username beside each picture already names the owner.
    const pictures = screen.getAllByRole('presentation')
    expect(pictures.map((img) => img.getAttribute('src'))).toEqual([placeholder, 'https://img.clerk.com/sam.jpg'])
  })

  it('shows the picture of everyone going, in signup order, named by its alt text', async () => {
    vi.mocked(listWants).mockResolvedValue([
      want(2, 'Dune Part Two', 'MOVIE', SAM, {
        capacity: 4,
        seatsTaken: 2,
        signedUp: true,
        lineup: [
          { id: SAM.id, username: SAM.username, imageUrl: SAM.imageUrl },
          { id: DANI.id, username: DANI.username, imageUrl: null },
        ],
      }),
    ])
    renderWants()
    await screen.findByText('Dune Part Two')

    const going = within(screen.getByRole('list', { name: 'Going to Dune Part Two' })).getAllByRole('img')
    expect(going.map((img) => img.getAttribute('alt'))).toEqual(['sam', 'dani'])
    // dani has no picture, so gets the placeholder, still named.
    expect(going.map((img) => img.getAttribute('src'))).toEqual(['https://img.clerk.com/sam.jpg', placeholder])
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
      wantId: 2,
      message: 'Dune Part Two',
      capacity: 4,
      seatsTaken: 2,
      lineup: [
        { id: SAM.id, username: SAM.username, imageUrl: null },
        { id: DANI.id, username: DANI.username, imageUrl: null },
      ],
      signedUp: true,
      status: 'ACTIVE',
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
      wantId: 2,
      message: 'Dune Part Two',
      capacity: 4,
      seatsTaken: 1,
      lineup: [{ id: SAM.id, username: SAM.username, imageUrl: null }],
      signedUp: false,
      status: 'ACTIVE',
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

  it('creates an open invite, sending openInvite and no capacity', async () => {
    vi.mocked(createWant).mockResolvedValue(want(3, 'Tacos', 'FOOD', DANI, { capacity: null }))
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.type(screen.getByLabelText('Want description'), 'Tacos')
    await userEvent.click(screen.getByLabelText('Open invite'))
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))

    expect(createWant).toHaveBeenCalledWith({ message: 'Tacos', category: 'FOOD', openInvite: true })
  })

  it('disables the capacity field while Open invite is ticked, and enables it again when unticked', async () => {
    renderWants()
    await screen.findByText('Ramen downtown')

    const openInvite = screen.getByLabelText('Open invite')
    await userEvent.click(openInvite)
    expect(screen.getByLabelText('Capacity')).toBeDisabled()

    await userEvent.click(openInvite)
    expect(screen.getByLabelText('Capacity')).toBeEnabled()
  })

  it('shows how many are going instead of seats on an open want', async () => {
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { capacity: null, seatsTaken: 3 })])
    renderWants()
    await screen.findByText('Ramen downtown')

    expect(within(row('Ramen downtown')).getByText('3 going')).toBeInTheDocument()
    expect(within(row('Ramen downtown')).queryByText(/seats/)).not.toBeInTheDocument()
  })

  it('never shows Full or disables Sign up on an open want', async () => {
    vi.mocked(listWants).mockResolvedValue([want(2, 'Dune Part Two', 'MOVIE', SAM, { capacity: null, seatsTaken: 40 })])
    renderWants()
    await screen.findByText('Dune Part Two')

    expect(screen.getByRole('button', { name: 'Sign up' })).toBeEnabled()
    expect(within(row('Dune Part Two')).queryByText('Full')).not.toBeInTheDocument()
  })

  it('shows Open invite ticked and the capacity field disabled when editing an open want', async () => {
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { capacity: null, seatsTaken: 3 })])
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))

    expect(screen.getByLabelText('Edit open invite')).toBeChecked()
    expect(screen.getByLabelText('Edit capacity')).toBeDisabled()
  })

  it('opens a limited want, sending openInvite: true and no capacity', async () => {
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { capacity: 4, seatsTaken: 2 })])
    vi.mocked(updateWant).mockResolvedValue(want(1, 'Ramen downtown', 'FOOD', DANI, { capacity: null, seatsTaken: 2 }))
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    expect(screen.getByLabelText('Edit open invite')).not.toBeChecked()
    await userEvent.click(screen.getByLabelText('Edit open invite'))
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(updateWant).toHaveBeenCalledWith(1, { message: 'Ramen downtown', category: 'FOOD', openInvite: true })
  })

  it('limits an open want to the people already in, sending openInvite: false and no capacity', async () => {
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { capacity: null, seatsTaken: 3 })])
    vi.mocked(updateWant).mockResolvedValue(want(1, 'Ramen downtown', 'FOOD', DANI, { capacity: 3, seatsTaken: 3 }))
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    await userEvent.click(screen.getByLabelText('Edit open invite'))
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(updateWant).toHaveBeenCalledWith(1, { message: 'Ramen downtown', category: 'FOOD', openInvite: false })
  })

  it('keeps the capacity field disabled after unticking Open invite on an open want', async () => {
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { capacity: null, seatsTaken: 3 })])
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    await userEvent.click(screen.getByLabelText('Edit open invite'))

    // The API sets the limit to the lineup size; a bigger one takes a second save.
    const capacity = screen.getByLabelText('Edit capacity')
    expect(capacity).toBeDisabled()
    expect(capacity).toHaveValue(3)
  })

  it('saves an edited message on an open want without openInvite or capacity', async () => {
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { capacity: null, seatsTaken: 3 })])
    vi.mocked(updateWant).mockResolvedValue(want(1, 'Ramen uptown', 'FOOD', DANI, { capacity: null, seatsTaken: 3 }))
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    const editor = screen.getByLabelText('Edit want')
    await userEvent.clear(editor)
    await userEvent.type(editor, 'Ramen uptown')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(updateWant).toHaveBeenCalledWith(1, { message: 'Ramen uptown', category: 'FOOD' })
  })

  it('sends no openInvite after ticking and unticking Open invite on a limited want', async () => {
    vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { capacity: 4, seatsTaken: 2 })])
    vi.mocked(updateWant).mockResolvedValue(want(1, 'Ramen downtown', 'FOOD', DANI, { capacity: 4, seatsTaken: 2 }))
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    const openInvite = screen.getByLabelText('Edit open invite')
    await userEvent.click(openInvite)
    await userEvent.click(openInvite)
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(updateWant).toHaveBeenCalledWith(1, { message: 'Ramen downtown', category: 'FOOD' })
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

  it('empties the capacity field and shows Any while Open invite is ticked on Add', async () => {
    renderWants()
    await screen.findByText('Ramen downtown')

    const capacity = screen.getByLabelText('Capacity')
    await userEvent.click(screen.getByLabelText('Open invite'))
    expect(capacity).toHaveValue(null)
    expect(capacity).toHaveAttribute('placeholder', 'Any')

    // Unticking brings back what was there.
    await userEvent.click(screen.getByLabelText('Open invite'))
    expect(capacity).toHaveValue(1)
  })

  it('explains the disabled capacity field to screen readers while Open invite is ticked', async () => {
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(screen.getByLabelText('Open invite'))

    expect(screen.getByLabelText('Capacity')).toHaveAccessibleDescription('No limit: anyone who can see it may join')
  })

  it('shows an Open invite pill on an open row, and none on a limited one', async () => {
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown', 'FOOD', DANI, { capacity: null, seatsTaken: 4 }),
      want(2, 'Dune Part Two', 'MOVIE', SAM, { capacity: 3, seatsTaken: 1 }),
    ])
    renderWants()
    await screen.findByText('Ramen downtown')

    expect(within(row('Ramen downtown')).getByText('Open invite')).toBeInTheDocument()
    expect(within(row('Dune Part Two')).queryByText('Open invite')).not.toBeInTheDocument()
  })

  it('keeps the list on screen while it reloads after a change', async () => {
    vi.mocked(closeWant).mockResolvedValue(want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'CLOSED' }))
    renderWants()
    await screen.findByText('Ramen downtown')

    // The reload never answers, so whatever shows meanwhile stays on screen.
    vi.mocked(listWants).mockReturnValue(new Promise(() => {}))
    await userEvent.click(within(row('Ramen downtown')).getByRole('button', { name: 'Close' }))

    await waitFor(() => expect(listWants).toHaveBeenCalledTimes(2))
    expect(screen.getByText('Ramen downtown')).toBeInTheDocument()
    expect(screen.queryByText('Loading...')).not.toBeInTheDocument()
  })

  it('puts focus back on Edit after saving', async () => {
    vi.mocked(updateWant).mockResolvedValue(want(1, 'Ramen uptown'))
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(within(row('Ramen downtown')).getByRole('button', { name: 'Edit' }))
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(within(row('Ramen downtown')).getByRole('button', { name: 'Edit' })).toHaveFocus())
  })

  it('puts focus back on Edit after Escape or Cancel', async () => {
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(within(row('Ramen downtown')).getByRole('button', { name: 'Edit' }))
    await userEvent.keyboard('{Escape}')
    expect(within(row('Ramen downtown')).getByRole('button', { name: 'Edit' })).toHaveFocus()

    await userEvent.click(within(row('Ramen downtown')).getByRole('button', { name: 'Edit' }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(within(row('Ramen downtown')).getByRole('button', { name: 'Edit' })).toHaveFocus()
  })

  it('keeps focus in the edit row when saving fails', async () => {
    vi.mocked(updateWant).mockRejectedValue(new Error('409 Conflict'))
    renderWants()
    await screen.findByText('Ramen downtown')

    await userEvent.click(within(row('Ramen downtown')).getByRole('button', { name: 'Edit' }))
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await screen.findByRole('alert')
    const editor = screen.getByLabelText('Edit want')
    expect(editor.closest('li')!.contains(document.activeElement)).toBe(true)
  })

  describe('deadlines', () => {
    // Sunday 25 October 2026, 9:00 in whatever time zone the tests run in.
    // Built from local parts, and so is every expected deadline, so the tests
    // check local-time arithmetic and pass in any zone. Only Date is faked, so
    // promises and user-event's timers run as usual.
    const NOW = new Date(2026, 9, 25, 9, 0)
    const at = (...parts: [number, number, number, number, number]) => new Date(...parts).toISOString()

    beforeEach(() => {
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(NOW)
    })

    afterEach(() => {
      vi.useRealTimers()
    })

    // Date and time inputs take their value whole; typing into them in jsdom
    // is unreliable, so set it as the browser would on a pick.
    function pick(label: string, value: string) {
      fireEvent.change(screen.getByLabelText(label), { target: { value } })
    }

    async function addTacos(choose: () => Promise<void> | void) {
      vi.mocked(createWant).mockResolvedValue(want(3, 'Tacos'))
      renderWants()
      await screen.findByText('Ramen downtown')
      await userEvent.type(screen.getByLabelText('Want description'), 'Tacos')
      await choose()
      await userEvent.click(screen.getByRole('button', { name: 'Add' }))
    }

    async function editRamen(expiresAt: string | null, change: () => Promise<void> | void) {
      vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { expiresAt })])
      vi.mocked(updateWant).mockResolvedValue(want(1, 'Ramen downtown'))
      renderWants()
      await screen.findByText('Ramen downtown')
      await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
      await change()
      await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    }

    it('sends no deadline when none is picked, and the row shows none', async () => {
      await addTacos(() => {
        expect(screen.getByLabelText('Deadline')).toHaveDisplayValue('No deadline')
      })

      expect(createWant).toHaveBeenCalledWith({ message: 'Tacos', category: 'FOOD', capacity: 1 })
      expect(within(row('Ramen downtown')).queryByText(/Closes/)).not.toBeInTheDocument()
    })

    it("'In 1 hour' sends a deadline one hour from now", async () => {
      await addTacos(() => userEvent.selectOptions(screen.getByLabelText('Deadline'), 'In 1 hour'))

      expect(createWant).toHaveBeenCalledWith(expect.objectContaining({ expiresAt: at(2026, 9, 25, 10, 0) }))
    })

    it("'In 1 week' sends a deadline one week from now", async () => {
      // Seven days on the calendar at the same clock time, even across a clock change.
      await addTacos(() => userEvent.selectOptions(screen.getByLabelText('Deadline'), 'In 1 week'))

      expect(createWant).toHaveBeenCalledWith(expect.objectContaining({ expiresAt: at(2026, 10, 1, 9, 0) }))
    })

    it("'Tomorrow' sends noon tomorrow", async () => {
      await addTacos(() => userEvent.selectOptions(screen.getByLabelText('Deadline'), 'Tomorrow'))

      expect(createWant).toHaveBeenCalledWith(expect.objectContaining({ expiresAt: at(2026, 9, 26, 12, 0) }))
    })

    it('a picked date and time is sent as that moment in your time zone', async () => {
      await addTacos(async () => {
        await userEvent.selectOptions(screen.getByLabelText('Deadline'), 'Pick a date')
        pick('Deadline date', '2026-10-30')
        pick('Deadline time', '18:30')
      })

      expect(createWant).toHaveBeenCalledWith(expect.objectContaining({ expiresAt: at(2026, 9, 30, 18, 30) }))
    })

    it('a date picked without a time is noon that day', async () => {
      await addTacos(async () => {
        await userEvent.selectOptions(screen.getByLabelText('Deadline'), 'Pick a date')
        pick('Deadline date', '2026-10-30')
      })

      expect(createWant).toHaveBeenCalledWith(expect.objectContaining({ expiresAt: at(2026, 9, 30, 12, 0) }))
    })

    it("today's date picked after noon is 23:59 today", async () => {
      // Noon has passed, so noon would be in the past; the end of today is not.
      vi.setSystemTime(new Date(2026, 9, 25, 15, 0))
      await addTacos(async () => {
        await userEvent.selectOptions(screen.getByLabelText('Deadline'), 'Pick a date')
        pick('Deadline date', '2026-10-25')
      })

      expect(createWant).toHaveBeenCalledWith(expect.objectContaining({ expiresAt: at(2026, 9, 25, 23, 59) }))
    })

    it('a deadline in the past disables Add', async () => {
      // The API refuses it with a 400; the button says so first.
      renderWants()
      await screen.findByText('Ramen downtown')
      await userEvent.type(screen.getByLabelText('Want description'), 'Tacos')
      await userEvent.selectOptions(screen.getByLabelText('Deadline'), 'Pick a date')

      pick('Deadline date', '2026-10-24')
      expect(screen.getByRole('button', { name: 'Add' })).toBeDisabled()

      pick('Deadline date', '2026-10-25')
      pick('Deadline time', '08:00')
      expect(screen.getByRole('button', { name: 'Add' })).toBeDisabled()
    })

    it("Edit shows the want's current deadline", async () => {
      vi.mocked(listWants).mockResolvedValue([
        want(1, 'Ramen downtown', 'FOOD', DANI, { expiresAt: at(2026, 9, 30, 18, 0) }),
      ])
      renderWants()
      await screen.findByText('Ramen downtown')

      await userEvent.click(screen.getByRole('button', { name: 'Edit' }))

      expect(screen.getByLabelText('Edit deadline')).toHaveDisplayValue('Pick a date')
      expect(screen.getByLabelText('Edit deadline date')).toHaveValue('2026-10-30')
      expect(screen.getByLabelText('Edit deadline time')).toHaveValue('18:00')
    })

    it('moving the deadline sends only the new one', async () => {
      await editRamen(at(2026, 9, 30, 18, 0), () => pick('Edit deadline time', '20:00'))

      expect(updateWant).toHaveBeenCalledWith(1, {
        message: 'Ramen downtown',
        category: 'FOOD',
        expiresAt: at(2026, 9, 30, 20, 0),
      })
    })

    it('removing the deadline sends noDeadline', async () => {
      await editRamen(at(2026, 9, 30, 18, 0), () =>
        userEvent.selectOptions(screen.getByLabelText('Edit deadline'), 'No deadline'),
      )

      expect(updateWant).toHaveBeenCalledWith(1, { message: 'Ramen downtown', category: 'FOOD', noDeadline: true })
    })

    it('saving with the deadline unchanged sends no deadline', async () => {
      // Seconds the picker can't show must not count as a change.
      await editRamen(new Date(2026, 9, 30, 18, 0, 42).toISOString(), () => {})

      expect(updateWant).toHaveBeenCalledWith(1, { message: 'Ramen downtown', category: 'FOOD' })
    })

    it('an active want with a deadline shows when it closes', async () => {
      const deadline = at(2026, 9, 30, 18, 0)
      vi.mocked(listWants).mockResolvedValue([
        want(1, 'Ramen downtown', 'FOOD', DANI, { expiresAt: deadline }),
        want(2, 'Dune Part Two', 'MOVIE', SAM),
      ])
      renderWants()
      await screen.findByText('Dune Part Two')

      const closes = within(row('Ramen downtown')).getByText(/Closes/)
      expect(closes.querySelector('time')).toHaveAttribute('dateTime', deadline)
      expect(within(row('Dune Part Two')).queryByText(/Closes/)).not.toBeInTheDocument()
    })

    it('a want whose deadline passed says "Closed at its deadline"', async () => {
      vi.mocked(listWants).mockResolvedValue([
        want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'CLOSED', expiresAt: at(2026, 9, 25, 8, 0) }),
        // Closed by hand with its deadline still ahead: just Closed.
        want(2, 'Catan night', 'GAME', DANI, { status: 'CLOSED', expiresAt: at(2026, 9, 30, 18, 0) }),
      ])
      renderWants()
      await screen.findByText('Catan night')

      expect(within(row('Ramen downtown')).getByText('Closed at its deadline')).toBeInTheDocument()
      expect(within(row('Catan night')).getByText('Closed')).toBeInTheDocument()
      expect(within(row('Catan night')).queryByText('Closed at its deadline')).not.toBeInTheDocument()
    })

    it('a want past its deadline offers Reopen and not Edit', async () => {
      // A deadline can't be moved on a closed want: it is reopened first.
      vi.mocked(listWants).mockResolvedValue([
        want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'CLOSED', expiresAt: at(2026, 9, 25, 8, 0) }),
      ])
      renderWants()
      await screen.findByText('Ramen downtown')

      expect(screen.getByRole('button', { name: 'Reopen' })).toBeEnabled()
      expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    })

    it('Reopen without a new deadline sends no body', async () => {
      // The API then keeps a deadline still ahead and clears one that passed.
      vi.mocked(listWants).mockResolvedValue([
        want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'CLOSED', expiresAt: at(2026, 9, 30, 18, 0) }),
      ])
      vi.mocked(reopenWant).mockResolvedValue(want(1, 'Ramen downtown'))
      renderWants()
      await screen.findByText('Ramen downtown')

      await userEvent.click(screen.getByRole('button', { name: 'Reopen' }))
      expect(screen.getByLabelText('Reopen deadline')).toHaveDisplayValue(/^Keep /)
      await userEvent.click(screen.getByRole('button', { name: 'Confirm' }))

      expect(reopenWant).toHaveBeenCalledWith(1)
    })

    it('Reopen offers to keep a deadline still ahead', async () => {
      // Without a new one the API keeps a deadline ahead and clears one that passed, so say which.
      vi.mocked(listWants).mockResolvedValue([
        want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'CLOSED', expiresAt: at(2026, 9, 30, 18, 0) }),
        want(2, 'Catan night', 'GAME', DANI, { status: 'CLOSED', expiresAt: at(2026, 9, 25, 8, 0) }),
      ])
      renderWants()
      await screen.findByText('Catan night')

      await userEvent.click(within(row('Ramen downtown')).getByRole('button', { name: 'Reopen' }))
      const keep = within(row('Ramen downtown')).getByLabelText('Reopen deadline')
      expect(keep).toHaveDisplayValue(/^Keep .*30/)
      await userEvent.click(within(row('Ramen downtown')).getByRole('button', { name: 'Cancel' }))

      await userEvent.click(within(row('Catan night')).getByRole('button', { name: 'Reopen' }))
      expect(within(row('Catan night')).getByLabelText('Reopen deadline')).toHaveDisplayValue('No deadline')
    })

    it('Cancel in the Reopen step puts focus back on Reopen', async () => {
      vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'CLOSED' })])
      renderWants()
      await screen.findByText('Ramen downtown')

      await userEvent.click(screen.getByRole('button', { name: 'Reopen' }))
      await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

      // Not on Cancel want, which would sit where Cancel was.
      expect(screen.getByRole('button', { name: 'Reopen' })).toHaveFocus()
    })

    it('Confirming Reopen puts focus on Edit', async () => {
      vi.mocked(listWants).mockResolvedValueOnce([want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'CLOSED' })])
      vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown')])
      vi.mocked(reopenWant).mockResolvedValue(want(1, 'Ramen downtown'))
      renderWants()
      await screen.findByText('Ramen downtown')

      await userEvent.click(screen.getByRole('button', { name: 'Reopen' }))
      await userEvent.click(screen.getByRole('button', { name: 'Confirm' }))

      // Not on Start, which would sit where Confirm was and acts without asking.
      await waitFor(() => expect(screen.getByRole('button', { name: 'Edit' })).toHaveFocus())
    })

    it('Escape closes the Reopen step', async () => {
      vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'CLOSED' })])
      renderWants()
      await screen.findByText('Ramen downtown')

      await userEvent.click(screen.getByRole('button', { name: 'Reopen' }))
      await userEvent.keyboard('{Escape}')

      expect(screen.queryByLabelText('Reopen deadline')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Reopen' })).toHaveFocus()
      expect(reopenWant).not.toHaveBeenCalled()
    })

    it('a missing or past date says why the button is disabled', async () => {
      renderWants()
      await screen.findByText('Ramen downtown')
      await userEvent.type(screen.getByLabelText('Want description'), 'Tacos')
      await userEvent.selectOptions(screen.getByLabelText('Deadline'), 'Pick a date')

      expect(screen.getByLabelText('Deadline date')).toHaveAccessibleDescription('Choose a date')

      pick('Deadline date', '2026-10-24')
      expect(screen.getByLabelText('Deadline date')).toHaveAccessibleDescription("Pick a time that hasn't passed")
      expect(screen.getByLabelText('Deadline date')).toHaveAttribute('aria-invalid', 'true')

      pick('Deadline date', '2026-10-30')
      expect(screen.getByLabelText('Deadline date')).not.toHaveAccessibleDescription()
      expect(screen.getByRole('button', { name: 'Add' })).toBeEnabled()
    })

    it('a deadline that passes before Add says why instead of doing nothing', async () => {
      renderWants()
      await screen.findByText('Ramen downtown')
      await userEvent.type(screen.getByLabelText('Want description'), 'Tacos')
      await userEvent.selectOptions(screen.getByLabelText('Deadline'), 'Pick a date')
      pick('Deadline date', '2026-10-25')
      pick('Deadline time', '10:00')

      // Nothing re-renders as the clock passes 10:00, so Add is still enabled.
      vi.setSystemTime(new Date(2026, 9, 25, 10, 30))
      await userEvent.click(screen.getByRole('button', { name: 'Add' }))

      expect(createWant).not.toHaveBeenCalled()
      expect(screen.getByText("Pick a time that hasn't passed")).toBeInTheDocument()
    })

    it('a save refused because the want closed reloads the list and leaves edit mode', async () => {
      // The deadline passed while the row was open: the API answers 409.
      vi.mocked(listWants).mockResolvedValueOnce([
        want(1, 'Ramen downtown', 'FOOD', DANI, { expiresAt: at(2026, 9, 25, 9, 30) }),
      ])
      vi.mocked(listWants).mockResolvedValue([
        want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'CLOSED', expiresAt: at(2026, 9, 25, 9, 30) }),
      ])
      vi.mocked(updateWant).mockRejectedValue(new Error('409 Conflict'))
      renderWants()
      await screen.findByText('Ramen downtown')

      await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
      vi.setSystemTime(new Date(2026, 9, 25, 9, 45))
      await userEvent.click(screen.getByRole('button', { name: 'Save' }))

      expect(await screen.findByRole('alert')).toHaveTextContent('409 Conflict')
      await waitFor(() => expect(screen.queryByLabelText('Edit want')).not.toBeInTheDocument())
      expect(within(row('Ramen downtown')).getByText('Closed at its deadline')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Reopen' })).toHaveFocus()
    })

    it('Reopen with a new deadline sends it', async () => {
      vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'CLOSED' })])
      vi.mocked(reopenWant).mockResolvedValue(want(1, 'Ramen downtown'))
      renderWants()
      await screen.findByText('Ramen downtown')

      await userEvent.click(screen.getByRole('button', { name: 'Reopen' }))
      await userEvent.selectOptions(screen.getByLabelText('Reopen deadline'), 'Tomorrow')
      await userEvent.click(screen.getByRole('button', { name: 'Confirm' }))

      expect(reopenWant).toHaveBeenCalledWith(1, at(2026, 9, 26, 12, 0))
    })

    it('after reopening, a deadline still ahead shows the want active with that deadline', async () => {
      const deadline = at(2026, 9, 30, 18, 0)
      vi.mocked(listWants).mockResolvedValueOnce([
        want(1, 'Ramen downtown', 'FOOD', DANI, { status: 'CLOSED', expiresAt: deadline }),
      ])
      // The reload after reopening: the API kept the deadline, so the want is active again.
      vi.mocked(listWants).mockResolvedValue([want(1, 'Ramen downtown', 'FOOD', DANI, { expiresAt: deadline })])
      vi.mocked(reopenWant).mockResolvedValue(want(1, 'Ramen downtown', 'FOOD', DANI, { expiresAt: deadline }))
      renderWants()
      await screen.findByText('Ramen downtown')

      await userEvent.click(screen.getByRole('button', { name: 'Reopen' }))
      await userEvent.click(screen.getByRole('button', { name: 'Confirm' }))

      expect(await within(row('Ramen downtown')).findByRole('button', { name: 'Edit' })).toBeEnabled()
      expect(
        within(row('Ramen downtown'))
          .getByText(/Closes/)
          .querySelector('time'),
      ).toHaveAttribute('dateTime', deadline)
      expect(within(row('Ramen downtown')).queryByText('Closed')).not.toBeInTheDocument()
    })
  })
})
