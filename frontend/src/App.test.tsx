import { render, screen, within } from '@testing-library/react'
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
}))

// Wants and the profile load on mount; stub the transport so routing and the
// shell are all that is under test here.
vi.mock('./api/wants', () => ({
  listWants: vi.fn().mockResolvedValue([]),
  createWant: vi.fn(),
  updateWant: vi.fn(),
  deleteWant: vi.fn(),
}))

vi.mock('./api/me', () => ({
  getMe: vi.fn().mockResolvedValue({
    id: 1,
    username: 'dani',
    clerkId: 'user_dani',
    email: 'dani@example.com',
    lastSignInAt: null,
    nickname: 'Dani',
    s3Url: null,
  }),
  updateMe: vi.fn(),
}))

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )
}

describe('App sign-in gate', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows only the sign-in form when signed out, and calls no API', () => {
    auth.signedIn = false
    renderAt('/profile')

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

  it('shows the info page at /info', () => {
    renderAt('/info')

    expect(screen.getByRole('heading', { level: 1, name: 'About Wants' })).toBeInTheDocument()
  })

  it('shows the profile page at /profile', async () => {
    renderAt('/profile')

    expect(screen.getByRole('heading', { level: 1, name: 'Profile' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: 'Dani' })).toBeInTheDocument()
  })

  it("shows the user's nickname and picture in the header of the main page", async () => {
    renderAt('/')

    const badge = await screen.findByRole('link', { name: 'Dani' })
    expect(within(badge).getByRole('presentation')).toHaveAttribute('src')
  })

  it('goes to the profile page from the header badge', async () => {
    renderAt('/')

    await userEvent.click(await screen.findByRole('link', { name: 'Dani' }))

    expect(screen.getByRole('heading', { level: 1, name: 'Profile' })).toBeInTheDocument()
  })

  it('navigates between screens from the nav', async () => {
    renderAt('/')
    const nav = screen.getByRole('navigation', { name: 'Main' })

    await userEvent.click(within(nav).getByRole('link', { name: 'Info' }))
    expect(screen.getByRole('heading', { level: 1, name: 'About Wants' })).toBeInTheDocument()

    await userEvent.click(within(nav).getByRole('link', { name: 'Profile' }))
    expect(screen.getByRole('heading', { level: 1, name: 'Profile' })).toBeInTheDocument()

    await userEvent.click(within(nav).getByRole('link', { name: 'Wants' }))
    expect(screen.getByRole('heading', { level: 1, name: 'Wants' })).toBeInTheDocument()
  })
})
