import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getMe, updateMe } from '../api/me'
import placeholder from '../assets/avatar-placeholder.svg'
import MeProvider from '../hooks/MeProvider'
import type { User } from '../types/wants'
import UserProfile from './UserProfile'

// Stubbed at the api boundary, as in Wants.test.tsx: the component and the
// provider are under test, the transport is not.
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
    s3Url: null,
    ...overrides,
  }
}

function renderProfile() {
  render(
    <MeProvider>
      <UserProfile />
    </MeProvider>,
  )
}

describe('UserProfile', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getMe).mockResolvedValue(me())
  })

  it('shows your nickname, email and username', async () => {
    renderProfile()

    expect(await screen.findByRole('heading', { level: 2, name: 'Dani' })).toBeInTheDocument()
    expect(screen.getByText('dani@example.com')).toBeInTheDocument()
    expect(screen.getByText('@dani2')).toBeInTheDocument()
  })

  it('falls back to the username, and suggests a nickname, when there is none', async () => {
    vi.mocked(getMe).mockResolvedValue(me({ nickname: null }))
    renderProfile()

    expect(await screen.findByRole('heading', { level: 2, name: 'dani2' })).toBeInTheDocument()
    expect(screen.getByText(/Add a nickname/)).toBeInTheDocument()
  })

  it('shows the placeholder picture when there is no avatar URL', async () => {
    renderProfile()

    const img = await screen.findByRole('img', { name: "Dani's profile picture" })
    expect(img).toHaveAttribute('src', placeholder)
  })

  it('shows the avatar URL when there is one', async () => {
    vi.mocked(getMe).mockResolvedValue(me({ s3Url: 'https://example.com/dani.png' }))
    renderProfile()

    const img = await screen.findByRole('img', { name: "Dani's profile picture" })
    expect(img).toHaveAttribute('src', 'https://example.com/dani.png')
  })

  it('saves the nickname and avatar and closes the form', async () => {
    vi.mocked(updateMe).mockResolvedValue(me({ nickname: 'Dan', s3Url: 'https://example.com/d.png' }))
    renderProfile()
    await screen.findByRole('heading', { name: 'Dani' })

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    const nickname = screen.getByLabelText('Nickname (optional)')
    expect(nickname).toHaveValue('Dani')
    await userEvent.clear(nickname)
    await userEvent.type(nickname, 'Dan')
    await userEvent.type(screen.getByLabelText('Avatar URL (optional)'), 'https://example.com/d.png')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(updateMe).toHaveBeenCalledWith({ nickname: 'Dan', s3Url: 'https://example.com/d.png' })
    expect(await screen.findByRole('heading', { level: 2, name: 'Dan' })).toBeInTheDocument()
  })

  it('sends a blank nickname as null, to clear it', async () => {
    vi.mocked(updateMe).mockResolvedValue(me({ nickname: null }))
    renderProfile()
    await screen.findByRole('heading', { name: 'Dani' })

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    await userEvent.clear(screen.getByLabelText('Nickname (optional)'))
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(updateMe).toHaveBeenCalledWith({ nickname: null, s3Url: null })
  })

  it('has no field for the email or username, which come from Clerk', async () => {
    renderProfile()
    await screen.findByRole('heading', { name: 'Dani' })

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))

    expect(screen.queryByLabelText(/email/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/username/i)).not.toBeInTheDocument()
  })

  it('cancels an edit without calling the API', async () => {
    renderProfile()
    await screen.findByRole('heading', { name: 'Dani' })

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByLabelText('Nickname (optional)')).not.toBeInTheDocument()
    expect(updateMe).not.toHaveBeenCalled()
  })

  it('keeps the typed text and shows the error when saving fails', async () => {
    vi.mocked(updateMe).mockRejectedValue(new Error('size must be between 0 and 100'))
    renderProfile()
    await screen.findByRole('heading', { name: 'Dani' })

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    const nickname = screen.getByLabelText('Nickname (optional)')
    await userEvent.clear(nickname)
    await userEvent.type(nickname, 'Dan')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('size must be between 0 and 100')
    await waitFor(() => expect(screen.getByLabelText('Nickname (optional)')).toHaveValue('Dan'))
  })
})
