import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getMe } from '../api/me'
import { listNotifications, markNotificationsRead } from '../api/notifications'
import MeProvider from '../hooks/MeProvider'
import { POLL_MS } from '../hooks/useNotifications'
import type { Notification, NotificationType } from '../types/notifications'
import Notifications from './Notifications'
import NotificationsBell from './NotificationsBell'

// Stubbed at the api boundary, as in Wants.test.tsx.
vi.mock('../api/notifications', () => ({
  listNotifications: vi.fn(),
  markNotificationsRead: vi.fn(),
}))

vi.mock('../api/me', () => ({ getMe: vi.fn() }))

function followedBy(id: number, username: string, read = false): Notification {
  return {
    id,
    type: 'FOLLOW',
    actor: { id: id + 100, username, imageUrl: null },
    createdAt: new Date(Date.now() - 5 * 60_000).toISOString(),
    read,
    wantId: null,
    wantMessage: null,
    previousMessage: null,
  }
}

/** A notification about the want 'Pizza Friday', from `username`. */
function aboutWant(
  type: NotificationType,
  username: string,
  wantMessage = 'Pizza Friday',
  previousMessage: string | null = null,
): Notification {
  return { ...followedBy(1, username, true), type, wantId: 7, wantMessage, previousMessage }
}

/**
 * The bell and the page together, as in the app, so a test can see one
 * change the other. A fresh query client per test, without retries, so a
 * failure shows at once and nothing is cached between tests.
 */
function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <MeProvider>
          <NotificationsBell />
          <Routes>
            <Route path="/" element={<p>Home</p>} />
            <Route path="/notifications" element={<Notifications />} />
          </Routes>
        </MeProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('NotificationsBell', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getMe).mockResolvedValue({
      id: 1,
      username: 'dani',
      clerkId: 'user_dani',
      email: null,
      lastSignInAt: null,
      imageUrl: null,
    })
    vi.mocked(markNotificationsRead).mockResolvedValue()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('names the unread count and links to the notifications page', async () => {
    vi.mocked(listNotifications).mockResolvedValue([followedBy(2, 'alex'), followedBy(1, 'sam', true)])
    renderAt('/')

    const bell = await screen.findByRole('link', { name: 'Notifications, 1 unread' })
    expect(bell).toHaveAttribute('href', '/notifications')
  })

  it('has no count when everything is read', async () => {
    vi.mocked(listNotifications).mockResolvedValue([followedBy(1, 'sam', true)])
    renderAt('/')

    await waitFor(() => expect(listNotifications).toHaveBeenCalled())
    expect(screen.getByRole('link', { name: 'Notifications' })).toBeInTheDocument()
  })

  it('polls, so a new follow shows without reloading', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.mocked(listNotifications).mockResolvedValue([])
    renderAt('/')
    await screen.findByRole('link', { name: 'Notifications' })

    vi.mocked(listNotifications).mockResolvedValue([followedBy(1, 'alex')])
    await act(() => vi.advanceTimersByTimeAsync(POLL_MS))

    expect(await screen.findByRole('link', { name: 'Notifications, 1 unread' })).toBeInTheDocument()
  })
})

describe('Notifications', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getMe).mockResolvedValue({
      id: 1,
      username: 'dani',
      clerkId: 'user_dani',
      email: null,
      lastSignInAt: null,
      imageUrl: null,
    })
    vi.mocked(markNotificationsRead).mockResolvedValue()
  })

  it('lists who followed you, newest first', async () => {
    vi.mocked(listNotifications).mockResolvedValue([followedBy(2, 'alex'), followedBy(1, 'sam', true)])
    renderAt('/notifications')

    const items = await screen.findAllByRole('listitem')
    expect(items.map((li) => li.textContent)).toEqual([
      expect.stringContaining('alex started following you'),
      expect.stringContaining('sam started following you'),
    ])
    expect(within(items[0]).getByText('5 minutes ago')).toBeInTheDocument()
  })

  it('marks what it shows read, clears the badge, and keeps the unseen ones marked new', async () => {
    vi.mocked(listNotifications).mockResolvedValue([followedBy(2, 'alex'), followedBy(1, 'sam', true)])
    // As the server would: once marked, the refetch that follows returns them read.
    vi.mocked(markNotificationsRead).mockImplementation(async () => {
      vi.mocked(listNotifications).mockResolvedValue([followedBy(2, 'alex', true), followedBy(1, 'sam', true)])
    })
    renderAt('/notifications')

    await waitFor(() => expect(markNotificationsRead).toHaveBeenCalledWith(2))
    expect(await screen.findByRole('link', { name: 'Notifications' })).toBeInTheDocument()

    const [alex, sam] = screen.getAllByRole('listitem')
    expect(within(alex).getByText('New:')).toBeInTheDocument()
    expect(within(sam).queryByText('New:')).not.toBeInTheDocument()
  })

  it('marks nothing when everything is already read', async () => {
    vi.mocked(listNotifications).mockResolvedValue([followedBy(1, 'sam', true)])
    renderAt('/notifications')

    await screen.findByText(/sam/)
    expect(markNotificationsRead).not.toHaveBeenCalled()
  })

  it('says so when there are none', async () => {
    vi.mocked(listNotifications).mockResolvedValue([])
    renderAt('/notifications')

    expect(await screen.findByText('Nothing yet.')).toBeInTheDocument()
  })

  it('shows why loading failed', async () => {
    vi.mocked(listNotifications).mockRejectedValue(new Error('Server unavailable'))
    renderAt('/notifications')

    expect(await screen.findByRole('alert')).toHaveTextContent('Server unavailable')
  })

  it('does not retry marking read on every render when it fails', async () => {
    vi.mocked(listNotifications).mockResolvedValue([followedBy(2, 'alex')])
    vi.mocked(markNotificationsRead).mockRejectedValue(new Error('Server unavailable'))
    renderAt('/notifications')

    await waitFor(() => expect(markNotificationsRead).toHaveBeenCalledTimes(1))
    // The refetch after the failure brings it back unread; the bell says so.
    expect(await screen.findByRole('link', { name: 'Notifications, 1 unread' })).toBeInTheDocument()
    expect(markNotificationsRead).toHaveBeenCalledTimes(1)
  })

  it('says you created a want, without naming you', async () => {
    vi.mocked(listNotifications).mockResolvedValue([aboutWant('WANT_CREATED', 'dani')])
    renderAt('/notifications')

    const item = await screen.findByRole('listitem')
    expect(item).toHaveTextContent("You created 'Pizza Friday'")
    expect(item).not.toHaveTextContent('dani')
  })

  it('says who signed up for which want', async () => {
    vi.mocked(listNotifications).mockResolvedValue([aboutWant('WANT_SIGNUP', 'alex')])
    renderAt('/notifications')

    expect(await screen.findByRole('listitem')).toHaveTextContent("alex signed up for 'Pizza Friday'")
  })

  it('says who withdrew from which want', async () => {
    vi.mocked(listNotifications).mockResolvedValue([aboutWant('WANT_WITHDRAWN', 'alex')])
    renderAt('/notifications')

    expect(await screen.findByRole('listitem')).toHaveTextContent("alex withdrew from 'Pizza Friday'")
  })

  it('shows a rename with the old and new names', async () => {
    vi.mocked(listNotifications).mockResolvedValue([aboutWant('WANT_UPDATED', 'sam', 'Pizza Saturday', 'Pizza Friday')])
    renderAt('/notifications')

    expect(await screen.findByRole('listitem')).toHaveTextContent("sam renamed 'Pizza Friday' to 'Pizza Saturday'")
  })

  it("shows an edit that kept the name by the want's name", async () => {
    // A change of category only: the name is the same, so there is no previous one.
    vi.mocked(listNotifications).mockResolvedValue([aboutWant('WANT_UPDATED', 'sam')])
    renderAt('/notifications')

    expect(await screen.findByRole('listitem')).toHaveTextContent("sam updated 'Pizza Friday'")
  })

  it('says who closed which want', async () => {
    vi.mocked(listNotifications).mockResolvedValue([aboutWant('WANT_CLOSED', 'sam')])
    renderAt('/notifications')

    expect(await screen.findByRole('listitem')).toHaveTextContent("sam closed 'Pizza Friday'")
  })

  it('says who reopened which want', async () => {
    vi.mocked(listNotifications).mockResolvedValue([aboutWant('WANT_REOPENED', 'sam')])
    renderAt('/notifications')

    expect(await screen.findByRole('listitem')).toHaveTextContent("sam reopened 'Pizza Friday'")
  })

  it('says who cancelled which want', async () => {
    vi.mocked(listNotifications).mockResolvedValue([aboutWant('WANT_CANCELLED', 'sam')])
    renderAt('/notifications')

    expect(await screen.findByRole('listitem')).toHaveTextContent("sam cancelled 'Pizza Friday'")
  })

  it('says who started which want', async () => {
    vi.mocked(listNotifications).mockResolvedValue([aboutWant('WANT_STARTED', 'sam')])
    renderAt('/notifications')

    expect(await screen.findByRole('listitem')).toHaveTextContent("sam started 'Pizza Friday'")
  })

  it('says who ended which want', async () => {
    vi.mocked(listNotifications).mockResolvedValue([aboutWant('WANT_ENDED', 'sam')])
    renderAt('/notifications')

    expect(await screen.findByRole('listitem')).toHaveTextContent("sam ended 'Pizza Friday'")
  })

  it('says who opened which want to everyone', async () => {
    vi.mocked(listNotifications).mockResolvedValue([aboutWant('WANT_OPEN_INVITE', 'sam')])
    renderAt('/notifications')

    expect(await screen.findByRole('listitem')).toHaveTextContent(
      "sam opened 'Pizza Friday' to everyone: bring who you like",
    )
  })

  it('says who limited which want to the people already in', async () => {
    vi.mocked(listNotifications).mockResolvedValue([aboutWant('WANT_LIMITED', 'sam')])
    renderAt('/notifications')

    expect(await screen.findByRole('listitem')).toHaveTextContent("sam limited 'Pizza Friday' to the people already in")
  })

  it('goes to the page from the bell', async () => {
    vi.mocked(listNotifications).mockResolvedValue([followedBy(1, 'alex')])
    renderAt('/')

    await userEvent.click(await screen.findByRole('link', { name: 'Notifications, 1 unread' }))

    expect(screen.getByRole('heading', { level: 1, name: 'Notifications' })).toBeInTheDocument()
  })
})
