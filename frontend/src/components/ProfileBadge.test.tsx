import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listUserProfiles } from '../api/userProfiles'
import ProfileBadge from './ProfileBadge'

vi.mock('../api/userProfiles', () => ({
  listUserProfiles: vi.fn(),
  createUserProfile: vi.fn(),
  updateUserProfile: vi.fn(),
  deleteUserProfile: vi.fn(),
}))

function renderBadge() {
  render(
    <MemoryRouter>
      <ProfileBadge userId={1} />
    </MemoryRouter>,
  )
}

describe('ProfileBadge', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows the nickname and links to the profile page', async () => {
    vi.mocked(listUserProfiles).mockResolvedValue([
      {
        id: 7,
        user: { id: 1, username: 'dani' },
        email: 'dani@example.com',
        nickname: 'Dani',
        s3Url: 'https://example.com/dani.png',
      },
    ])
    renderBadge()

    const link = await screen.findByRole('link', { name: 'Dani' })
    expect(link).toHaveAttribute('href', '/profile')
    expect(listUserProfiles).toHaveBeenCalledWith(1)
  })

  it('invites the user to set up a profile when there is none', async () => {
    vi.mocked(listUserProfiles).mockResolvedValue([])
    renderBadge()

    expect(await screen.findByRole('link', { name: 'Set up profile' })).toHaveAttribute(
      'href',
      '/profile',
    )
  })

  it('renders nothing when the profile cannot be loaded', async () => {
    vi.mocked(listUserProfiles).mockRejectedValue(new Error('Network down'))
    renderBadge()

    await vi.waitFor(() => expect(listUserProfiles).toHaveBeenCalled())
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByText('Network down')).not.toBeInTheDocument()
  })
})
