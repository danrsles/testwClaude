import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import App from './App'

// Wants and the profile load on mount; stub the transport so routing and the
// shell are all that is under test here.
vi.mock('./api/wants', () => ({
  listWants: vi.fn().mockResolvedValue([]),
  createWant: vi.fn(),
  updateWant: vi.fn(),
  deleteWant: vi.fn(),
}))

vi.mock('./api/userProfiles', () => ({
  listUserProfiles: vi.fn().mockResolvedValue([
    {
      id: 7,
      user: { id: 1, username: 'dani' },
      email: 'dani@example.com',
      nickname: 'Dani',
      s3Url: null,
    },
  ]),
  createUserProfile: vi.fn(),
  updateUserProfile: vi.fn(),
  deleteUserProfile: vi.fn(),
}))

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  )
}

describe('App routing', () => {
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
