import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getMyProfile } from '../api/userProfiles'
import ProfileBadge from './ProfileBadge'

vi.mock('../api/userProfiles', () => ({
  getMyProfile: vi.fn(),
  createMyProfile: vi.fn(),
  updateMyProfile: vi.fn(),
  deleteMyProfile: vi.fn(),
}))

function renderBadge() {
  render(
    <MemoryRouter>
      <ProfileBadge />
    </MemoryRouter>,
  )
}

describe('ProfileBadge', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows the nickname and links to the profile page', async () => {
    vi.mocked(getMyProfile).mockResolvedValue({
      id: 7,
      user: { id: 1, username: 'dani', clerkId: 'user_dani', email: null, lastSignInAt: null },
      email: 'dani@example.com',
      nickname: 'Dani',
      s3Url: 'https://example.com/dani.png',
    })
    renderBadge()

    const link = await screen.findByRole('link', { name: 'Dani' })
    expect(link).toHaveAttribute('href', '/profile')
  })

  it('invites the user to set up a profile when there is none', async () => {
    vi.mocked(getMyProfile).mockResolvedValue(null)
    renderBadge()

    expect(await screen.findByRole('link', { name: 'Set up profile' })).toHaveAttribute(
      'href',
      '/profile',
    )
  })

  it('renders nothing when the profile cannot be loaded', async () => {
    vi.mocked(getMyProfile).mockRejectedValue(new Error('Network down'))
    renderBadge()

    await vi.waitFor(() => expect(getMyProfile).toHaveBeenCalled())
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByText('Network down')).not.toBeInTheDocument()
  })
})
