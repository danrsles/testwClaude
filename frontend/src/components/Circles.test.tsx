import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  addMember,
  createCircle,
  deleteCircle,
  listCircles,
  listMembers,
  removeMember,
  renameCircle,
} from '../api/circles'
import { listFollowers } from '../api/followers'
import { HttpError } from '../api/http'
import { getMe } from '../api/me'
import MeProvider from '../hooks/MeProvider'
import type { Circle, UserSummary } from '../types/circles'
import Circles from './Circles'

// Stubbed at the api boundary, as in People.test.tsx: the component and its
// hooks are under test, fetch is not.
vi.mock('../api/circles', () => ({
  listCircles: vi.fn(),
  createCircle: vi.fn(),
  renameCircle: vi.fn(),
  deleteCircle: vi.fn(),
  listMembers: vi.fn(),
  addMember: vi.fn(),
  removeMember: vi.fn(),
}))

vi.mock('../api/followers', () => ({ listFollowers: vi.fn() }))

vi.mock('../api/me', () => ({ getMe: vi.fn() }))

function user(id: number, username: string): UserSummary {
  return { id, username, imageUrl: null }
}

const dani = user(1, 'dani')
const alex = user(2, 'alex')
const sam = user(3, 'sam')
const jordan = user(4, 'jordan')

const friends: Circle = { id: 10, name: 'Friends', owner: dani }
const bookClub: Circle = { id: 20, name: 'Book club', owner: alex }

/** A fresh query client per test, without retries, so failures show at once and nothing is cached between tests. */
function renderCircles() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MeProvider>
        <Circles />
      </MeProvider>
    </QueryClientProvider>,
  )
}

/** The list item for a circle, found by the button that opens it. */
async function rowOf(name: string) {
  return (await screen.findByRole('button', { name })).closest('li')!
}

describe('Circles', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getMe).mockResolvedValue({ ...dani, clerkId: 'user_dani', email: null, lastSignInAt: null })
    vi.mocked(listCircles).mockResolvedValue([friends, bookClub])
    vi.mocked(listFollowers).mockResolvedValue([alex, sam, jordan])
    vi.mocked(listMembers).mockImplementation(async (id) => (id === friends.id ? [sam] : [dani, sam]))
  })

  it("lists circles you own and circles you're in, with who owns them", async () => {
    renderCircles()

    expect(within(await rowOf('Friends')).getByText('yours')).toBeInTheDocument()
    expect(within(await rowOf('Book club')).getByText('by alex')).toBeInTheDocument()
  })

  it('says so when you have none', async () => {
    vi.mocked(listCircles).mockResolvedValue([])
    renderCircles()

    expect(await screen.findByText("You aren't in any circles yet. Create one above.")).toBeInTheDocument()
  })

  it('creates a circle', async () => {
    const climbing: Circle = { id: 30, name: 'Climbing', owner: dani }
    vi.mocked(createCircle).mockImplementation(async () => {
      vi.mocked(listCircles).mockResolvedValue([friends, bookClub, climbing])
      return climbing
    })
    renderCircles()
    await rowOf('Friends')

    await userEvent.type(screen.getByLabelText('New circle'), '  Climbing ')
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))

    expect(createCircle).toHaveBeenCalledWith('Climbing')
    expect(within(await rowOf('Climbing')).getByText('yours')).toBeInTheDocument()
    expect(screen.getByLabelText('New circle')).toHaveValue('')
  })

  it('explains why Add is disabled: blank, too long, "General", a duplicate', async () => {
    renderCircles()
    await rowOf('Friends')
    const field = screen.getByLabelText('New circle')
    const add = screen.getByRole('button', { name: 'Add' })

    expect(add).toBeDisabled()
    expect(field).toHaveAccessibleDescription('Type a name.')

    await userEvent.type(field, '   ')
    expect(add).toBeDisabled()
    expect(field).toHaveAccessibleDescription('Type a name.')

    await userEvent.clear(field)
    await userEvent.type(field, 'x'.repeat(41))
    expect(add).toBeDisabled()
    expect(field).toHaveAccessibleDescription('Use 40 characters or fewer.')

    await userEvent.clear(field)
    await userEvent.type(field, 'general')
    expect(add).toBeDisabled()
    expect(field).toHaveAccessibleDescription('"General" means all your followers. Pick another name.')

    await userEvent.clear(field)
    await userEvent.type(field, 'FRIENDS')
    expect(add).toBeDisabled()
    expect(field).toHaveAccessibleDescription('You already have a circle called Friends.')

    // Only circles you own count: you can have your own "Book club".
    await userEvent.clear(field)
    await userEvent.type(field, 'Book club')
    expect(add).toBeEnabled()
    expect(field).not.toHaveAccessibleDescription()
  })

  it('renames a circle you own', async () => {
    vi.mocked(renameCircle).mockImplementation(async (_, name) => {
      vi.mocked(listCircles).mockResolvedValue([{ ...friends, name }, bookClub])
      return { ...friends, name }
    })
    renderCircles()

    await userEvent.click(within(await rowOf('Friends')).getByRole('button', { name: 'Rename Friends' }))
    const field = screen.getByLabelText('New name for Friends')
    await userEvent.clear(field)
    await userEvent.type(field, 'Close friends')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(renameCircle).toHaveBeenCalledWith(friends.id, 'Close friends')
    expect(await rowOf('Close friends')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Friends' })).not.toBeInTheDocument()
  })

  it('deletes a circle you own after confirming', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(deleteCircle).mockImplementation(async () => {
      vi.mocked(listCircles).mockResolvedValue([bookClub])
    })
    renderCircles()

    await userEvent.click(within(await rowOf('Friends')).getByRole('button', { name: 'Delete Friends' }))

    expect(window.confirm).toHaveBeenCalled()
    expect(deleteCircle).toHaveBeenCalledWith(friends.id)
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Friends' })).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Book club' })).toBeInTheDocument()
  })

  it('after deleting a circle, focus moves to the next one', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(deleteCircle).mockImplementation(async () => {
      vi.mocked(listCircles).mockResolvedValue([bookClub])
    })
    renderCircles()

    await userEvent.click(within(await rowOf('Friends')).getByRole('button', { name: 'Delete Friends' }))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Book club' })).toHaveFocus())
  })

  it('after leaving the last circle, focus moves to the name field', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(listCircles).mockResolvedValue([bookClub])
    vi.mocked(removeMember).mockImplementation(async () => {
      vi.mocked(listCircles).mockResolvedValue([])
    })
    renderCircles()

    await userEvent.click(within(await rowOf('Book club')).getByRole('button', { name: 'Leave Book club' }))

    await waitFor(() => expect(screen.getByLabelText('New circle')).toHaveFocus())
  })

  it('keeps a circle when delete is not confirmed', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderCircles()

    await userEvent.click(within(await rowOf('Friends')).getByRole('button', { name: 'Delete Friends' }))

    expect(window.confirm).toHaveBeenCalled()
    expect(deleteCircle).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Friends' })).toBeInTheDocument()
  })

  it("shows the members of a circle you're in, without owner controls", async () => {
    renderCircles()
    const row = await rowOf('Book club')

    await userEvent.click(within(row).getByRole('button', { name: 'Book club' }))

    const members = await within(row).findByRole('list', { name: 'In Book club' })
    expect(
      within(members)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual([expect.stringContaining('alex'), expect.stringContaining('dani'), expect.stringContaining('sam')])
    expect(within(members).getAllByRole('listitem')[0]).toHaveTextContent('owner')
    expect(listMembers).toHaveBeenCalledWith(bookClub.id)
    expect(within(row).queryByRole('button', { name: 'Rename Book club' })).not.toBeInTheDocument()
    expect(within(row).queryByRole('button', { name: 'Delete Book club' })).not.toBeInTheDocument()
    expect(within(row).queryByRole('checkbox')).not.toBeInTheDocument()
    expect(listFollowers).not.toHaveBeenCalled()
  })

  it('leaves a circle after confirming', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(removeMember).mockImplementation(async () => {
      vi.mocked(listCircles).mockResolvedValue([friends])
    })
    renderCircles()

    await userEvent.click(within(await rowOf('Book club')).getByRole('button', { name: 'Leave Book club' }))

    expect(window.confirm).toHaveBeenCalled()
    expect(removeMember).toHaveBeenCalledWith(bookClub.id, dani.id)
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Book club' })).not.toBeInTheDocument())
  })

  it('shows your followers with whether each is in the circle', async () => {
    renderCircles()
    const row = await rowOf('Friends')

    await userEvent.click(within(row).getByRole('button', { name: 'Friends' }))

    expect(await within(row).findByRole('checkbox', { name: 'sam in Friends' })).toBeChecked()
    expect(within(row).getByRole('checkbox', { name: 'alex in Friends' })).not.toBeChecked()
    expect(within(row).getByRole('checkbox', { name: 'jordan in Friends' })).not.toBeChecked()
    expect(within(row).queryByRole('button', { name: 'Leave Friends' })).not.toBeInTheDocument()
  })

  it('filters your followers by name as you type', async () => {
    renderCircles()
    const row = await rowOf('Friends')
    await userEvent.click(within(row).getByRole('button', { name: 'Friends' }))
    await within(row).findByRole('checkbox', { name: 'sam in Friends' })

    await userEvent.type(within(row).getByLabelText('Search your followers'), 'AL')

    expect(within(row).getByRole('checkbox', { name: 'alex in Friends' })).toBeInTheDocument()
    expect(within(row).queryByRole('checkbox', { name: 'sam in Friends' })).not.toBeInTheDocument()
    expect(within(row).queryByRole('checkbox', { name: 'jordan in Friends' })).not.toBeInTheDocument()
    expect(listFollowers).toHaveBeenCalledTimes(1)
  })

  it('ticking adds a member, unticking removes one', async () => {
    let members = [sam]
    vi.mocked(listMembers).mockImplementation(async () => members)
    vi.mocked(addMember).mockImplementation(async (_, userId) => {
      members = [...members, [alex, sam, jordan].find((u) => u.id === userId)!]
    })
    vi.mocked(removeMember).mockImplementation(async (_, userId) => {
      members = members.filter((u) => u.id !== userId)
    })
    renderCircles()
    const row = await rowOf('Friends')
    await userEvent.click(within(row).getByRole('button', { name: 'Friends' }))

    await userEvent.click(await within(row).findByRole('checkbox', { name: 'alex in Friends' }))
    expect(addMember).toHaveBeenCalledWith(friends.id, alex.id)
    await waitFor(() => expect(within(row).getByRole('checkbox', { name: 'alex in Friends' })).toBeChecked())

    await userEvent.click(within(row).getByRole('checkbox', { name: 'sam in Friends' }))
    expect(removeMember).toHaveBeenCalledWith(friends.id, sam.id)
    await waitFor(() => expect(within(row).getByRole('checkbox', { name: 'sam in Friends' })).not.toBeChecked())
  })

  it('shows an error in the row when a change fails', async () => {
    vi.mocked(addMember).mockRejectedValue(new Error('alex does not follow you'))
    vi.mocked(renameCircle).mockRejectedValue(new Error('Name taken'))
    renderCircles()
    const row = await rowOf('Friends')
    await userEvent.click(within(row).getByRole('button', { name: 'Friends' }))

    const box = await within(row).findByRole('checkbox', { name: 'alex in Friends' })
    await userEvent.click(box)
    expect(await within(box.closest('li')!).findByRole('alert')).toHaveTextContent('alex does not follow you')
    expect(box).not.toBeChecked()

    await userEvent.click(within(row).getByRole('button', { name: 'Rename Friends' }))
    await userEvent.type(screen.getByLabelText('New name for Friends'), '!')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    const alerts = await within(row).findAllByRole('alert')
    expect(alerts.map((a) => a.textContent)).toContain('Name taken')
    expect(screen.getByLabelText('New name for Friends')).toHaveValue('Friends!')
  })

  it("explains a refused change in words, not the API's raw reply", async () => {
    const raw = (status: number, error: string) =>
      `{"timestamp":"2026-10-28T21:00:00Z","status":${status},"error":"${error}","path":"/circles/10"}`
    vi.mocked(addMember).mockRejectedValue(new HttpError(400, raw(400, 'Bad Request')))
    vi.mocked(removeMember).mockRejectedValue(new HttpError(404, raw(404, 'Not Found')))
    renderCircles()
    const row = await rowOf('Friends')
    await userEvent.click(within(row).getByRole('button', { name: 'Friends' }))

    const box = await within(row).findByRole('checkbox', { name: 'alex in Friends' })
    await userEvent.click(box)
    const added = await within(box.closest('li')!).findByRole('alert')
    expect(added).toHaveTextContent("Couldn't add them. They may have stopped following you.")
    expect(added).not.toHaveTextContent('Bad Request')

    const sam = within(row).getByRole('checkbox', { name: 'sam in Friends' })
    await userEvent.click(sam)
    expect(await within(sam.closest('li')!).findByRole('alert')).toHaveTextContent('This circle no longer exists.')
    expect(row).not.toHaveTextContent('Not Found')
  })

  it('a circle that no longer exists leaves the list', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    // Each circle was deleted elsewhere: the API answers 404, and the next list leaves it out.
    vi.mocked(renameCircle).mockImplementation(async () => {
      vi.mocked(listCircles).mockResolvedValue([bookClub])
      throw new HttpError(404, '')
    })
    vi.mocked(removeMember).mockImplementation(async () => {
      vi.mocked(listCircles).mockResolvedValue([])
      throw new HttpError(404, '')
    })
    renderCircles()

    await userEvent.click(within(await rowOf('Friends')).getByRole('button', { name: 'Rename Friends' }))
    await userEvent.type(screen.getByLabelText('New name for Friends'), '!')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.queryByLabelText('New name for Friends')).not.toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Friends' })).not.toBeInTheDocument()

    // Leaving a circle that is already gone counts as done: no error, and focus moves on.
    await userEvent.click(within(await rowOf('Book club')).getByRole('button', { name: 'Leave Book club' }))
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Book club' })).not.toBeInTheDocument())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByLabelText('New circle')).toHaveFocus()
  })
})
