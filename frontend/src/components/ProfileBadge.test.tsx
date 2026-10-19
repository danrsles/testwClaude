import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getMe } from '../api/me'
import MeProvider from '../hooks/MeProvider'
import type { User } from '../types/wants'
import ProfileBadge from './ProfileBadge'

vi.mock('../api/me', () => ({
  getMe: vi.fn(),
  updateMe: vi.fn(),
}))

function me(overrides: Partial<User> = {}): User {
  return {
    id: 3,
    username: 'dani2',
    clerkId: 'user_dani',
    email: 'dani@example.com',
    lastSignInAt: null,
    nickname: 'Dani',
    s3Url: 'https://example.com/dani.png',
    ...overrides,
  }
}

function renderBadge() {
  render(
    <MemoryRouter>
      <MeProvider>
        <ProfileBadge />
      </MeProvider>
    </MemoryRouter>,
  )
}

describe('ProfileBadge', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows the nickname and links to the profile page', async () => {
    vi.mocked(getMe).mockResolvedValue(me())
    renderBadge()

    const link = await screen.findByRole('link', { name: 'Dani' })
    expect(link).toHaveAttribute('href', '/profile')
  })

  it('invites the user to set up a profile when there is no nickname', async () => {
    vi.mocked(getMe).mockResolvedValue(me({ nickname: null }))
    renderBadge()

    expect(await screen.findByRole('link', { name: 'Set up profile' })).toHaveAttribute(
      'href',
      '/profile',
    )
  })

  it('renders nothing when the user cannot be loaded', async () => {
    vi.mocked(getMe).mockRejectedValue(new Error('Network down'))
    renderBadge()

    await vi.waitFor(() => expect(getMe).toHaveBeenCalled())
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByText('Network down')).not.toBeInTheDocument()
  })
})
