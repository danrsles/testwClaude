import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listWants } from './api/wants'
import App from './App'

// Clerk is replaced by a switch: Show renders its children only when `when`
// matches the stubbed state, which is all App relies on. The real components
// would try to load Clerk from the network.
const auth = vi.hoisted(() => ({ signedIn: true }))

vi.mock('@clerk/react', () => ({
  Show: ({ when, children }: { when: 'signed-in' | 'signed-out'; children: ReactNode }) =>
    (when === 'signed-in') === auth.signedIn ? children : null,
  SignIn: () => <div>Clerk sign-in form</div>,
  UserButton: () => <button>Account</button>,
  useUser: () => ({ user: { imageUrl: 'https://img.clerk.com/dani.jpg' } }),
  getToken: vi.fn(),
}))

// Wants and the profile load on mount; stub the transport so routing and the
// shell are all that is under test here.
vi.mock('./api/wants', () => ({
  listWants: vi.fn().mockResolvedValue([]),
  createWant: vi.fn(),
  updateWant: vi.fn(),
  deleteWant: vi.fn(),
}))

vi.mock('./api/people', () => ({
  searchPeople: vi.fn().mockResolvedValue([]),
  listFollowing: vi.fn().mockResolvedValue([]),
  follow: vi.fn(),
  unfollow: vi.fn(),
}))

vi.mock('./api/notifications', () => ({
  listNotifications: vi.fn().mockResolvedValue([]),
  markNotificationsRead: vi.fn(),
}))

vi.mock('./api/me', () => ({
  getMe: vi.fn().mockResolvedValue({
    id: 1,
    username: 'dani',
    clerkId: 'user_dani',
    email: 'dani@example.com',
    lastSignInAt: null,
    imageUrl: null,
  }),
}))

function renderAt(path: string) {
  // main.tsx provides the query client in the app; each test gets its own.
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('App sign-in gate', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows only the sign-in form when signed out, and calls no API', () => {
    auth.signedIn = false
    renderAt('/')

    expect(screen.getByText('Clerk sign-in form')).toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Main' })).not.toBeInTheDocument()
    expect(listWants).not.toHaveBeenCalled()
  })

  it('shows the app and the account menu when signed in', () => {
    auth.signedIn = true
    renderAt('/')

    expect(screen.queryByText('Clerk sign-in form')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Account' })).toBeInTheDocument()
  })
})

describe('App routing', () => {
  beforeEach(() => {
    auth.signedIn = true
  })

  it('shows the wants screen at /', () => {
    renderAt('/')

    expect(screen.getByRole('heading', { level: 1, name: 'Wants' })).toBeInTheDocument()
  })

  it('shows the people screen at /people', () => {
    renderAt('/people')

    expect(screen.getByRole('heading', { level: 1, name: 'People' })).toBeInTheDocument()
  })

  it('names each page in the browser tab', async () => {
    renderAt('/people')
    await waitFor(() => expect(document.title).toBe('People · Wants'))

    await userEvent.click(within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', { name: 'Info' }))
    await waitFor(() => expect(document.title).toBe('About · Wants'))
  })

  it('shows the notifications page at /notifications', () => {
    renderAt('/notifications')

    expect(screen.getByRole('heading', { level: 1, name: 'Notifications' })).toBeInTheDocument()
  })

  it('shows the notifications bell beside the account menu on every screen', async () => {
    renderAt('/info')

    expect(await screen.findByRole('link', { name: 'Notifications' })).toHaveAttribute('href', '/notifications')
  })

  it('shows the info page at /info', () => {
    renderAt('/info')

    expect(screen.getByRole('heading', { level: 1, name: 'About Wants' })).toBeInTheDocument()
  })

  it('navigates between screens from the nav', async () => {
    renderAt('/')
    const nav = screen.getByRole('navigation', { name: 'Main' })

    await userEvent.click(within(nav).getByRole('link', { name: 'Info' }))
    expect(screen.getByRole('heading', { level: 1, name: 'About Wants' })).toBeInTheDocument()

    await userEvent.click(within(nav).getByRole('link', { name: 'Wants' }))
    expect(screen.getByRole('heading', { level: 1, name: 'Wants' })).toBeInTheDocument()
  })

  it('has no profile page: Clerk\'s account menu is the only account control', () => {
    renderAt('/')
    const nav = screen.getByRole('navigation', { name: 'Main' })

    expect(within(nav).getAllByRole('link').map((link) => link.textContent)).toEqual(['Wants', 'People', 'Info'])
    expect(screen.queryByRole('link', { name: /profile/i })).not.toBeInTheDocument()
  })
})
