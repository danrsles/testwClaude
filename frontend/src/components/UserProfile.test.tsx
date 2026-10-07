import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createUserProfile,
  deleteUserProfile,
  listUserProfiles,
  updateUserProfile,
} from '../api/userProfiles'
import placeholder from '../assets/avatar-placeholder.svg'
import type { UserProfile as Profile } from '../types/userProfiles'
import UserProfile from './UserProfile'

// Stubbed at the api boundary, as in Wants.test.tsx: the component and its
// hook are under test, the transport is not.
vi.mock('../api/userProfiles', () => ({
  listUserProfiles: vi.fn(),
  createUserProfile: vi.fn(),
  updateUserProfile: vi.fn(),
  deleteUserProfile: vi.fn(),
}))

function profile(overrides: Partial<Profile> = {}): Profile {
  return {
    id: 7,
    user: { id: 1, username: 'dani' },
    email: 'dani@example.com',
    nickname: 'Dani',
    s3Url: null,
    ...overrides,
  }
}

describe('UserProfile', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.restoreAllMocks()
    vi.mocked(listUserProfiles).mockResolvedValue([profile()])
  })

  it('shows the profile the API returns, for the given user', async () => {
    render(<UserProfile userId={1} />)

    expect(await screen.findByRole('heading', { level: 2, name: 'Dani' })).toBeInTheDocument()
    expect(screen.getByText('dani@example.com')).toBeInTheDocument()
    expect(screen.getByText('@dani')).toBeInTheDocument()
    expect(listUserProfiles).toHaveBeenCalledWith(1)
  })

  it('shows the placeholder picture when there is no avatar URL', async () => {
    render(<UserProfile userId={1} />)

    const img = await screen.findByRole('img', { name: "Dani's profile picture" })
    expect(img).toHaveAttribute('src', placeholder)
  })

  it('shows the avatar URL when the profile has one', async () => {
    vi.mocked(listUserProfiles).mockResolvedValue([
      profile({ s3Url: 'https://example.com/dani.png' }),
    ])
    render(<UserProfile userId={1} />)

    const img = await screen.findByRole('img', { name: "Dani's profile picture" })
    expect(img).toHaveAttribute('src', 'https://example.com/dani.png')
  })

  it('saves an edited profile and closes the form', async () => {
    vi.mocked(updateUserProfile).mockResolvedValue(profile({ nickname: 'Dan' }))
    render(<UserProfile userId={1} />)
    await screen.findByRole('heading', { name: 'Dani' })

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    const nickname = screen.getByLabelText('Nickname')
    expect(nickname).toHaveValue('Dani')
    await userEvent.clear(nickname)
    await userEvent.type(nickname, 'Dan')
    await userEvent.type(screen.getByLabelText('Avatar URL (optional)'), 'https://example.com/d.png')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(updateUserProfile).toHaveBeenCalledWith(7, {
      email: 'dani@example.com',
      nickname: 'Dan',
      s3Url: 'https://example.com/d.png',
    })
    await waitFor(() => expect(screen.queryByLabelText('Nickname')).not.toBeInTheDocument())
  })

  it('cancels an edit without calling the API', async () => {
    render(<UserProfile userId={1} />)
    await screen.findByRole('heading', { name: 'Dani' })

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByLabelText('Nickname')).not.toBeInTheDocument()
    expect(updateUserProfile).not.toHaveBeenCalled()
  })

  it('keeps the typed text and shows the error when saving fails', async () => {
    vi.mocked(updateUserProfile).mockRejectedValue(new Error('Email taken@example.com is already in use'))
    render(<UserProfile userId={1} />)
    await screen.findByRole('heading', { name: 'Dani' })

    await userEvent.click(screen.getByRole('button', { name: 'Edit' }))
    const email = screen.getByLabelText('Email')
    await userEvent.clear(email)
    await userEvent.type(email, 'taken@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('is already in use')
    expect(screen.getByLabelText('Email')).toHaveValue('taken@example.com')
  })

  it('deletes the profile after confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(deleteUserProfile).mockResolvedValue(undefined)
    render(<UserProfile userId={1} />)
    await screen.findByRole('heading', { name: 'Dani' })

    await userEvent.click(screen.getByRole('button', { name: 'Delete' }))

    expect(window.confirm).toHaveBeenCalled()
    expect(deleteUserProfile).toHaveBeenCalledWith(7)
  })

  it('does not delete when the confirmation is dismissed', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    render(<UserProfile userId={1} />)
    await screen.findByRole('heading', { name: 'Dani' })

    await userEvent.click(screen.getByRole('button', { name: 'Delete' }))

    expect(deleteUserProfile).not.toHaveBeenCalled()
  })

  it('offers a create form when the user has no profile, and creates one', async () => {
    vi.mocked(listUserProfiles).mockResolvedValue([])
    vi.mocked(createUserProfile).mockResolvedValue(profile())
    render(<UserProfile userId={1} />)

    expect(await screen.findByRole('heading', { name: 'Create your profile' })).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Nickname'), 'Dani')
    await userEvent.type(screen.getByLabelText('Email'), 'dani@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Create profile' }))

    expect(createUserProfile).toHaveBeenCalledWith({
      email: 'dani@example.com',
      nickname: 'Dani',
      s3Url: null,
      userId: 1,
    })
  })

  it('keeps the create form filled in when creating fails', async () => {
    vi.mocked(listUserProfiles).mockResolvedValue([])
    vi.mocked(createUserProfile).mockRejectedValue(new Error('No user with id 1'))
    render(<UserProfile userId={1} />)
    await screen.findByRole('heading', { name: 'Create your profile' })

    await userEvent.type(screen.getByLabelText('Nickname'), 'Dani')
    await userEvent.type(screen.getByLabelText('Email'), 'dani@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Create profile' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('No user with id 1')
    expect(screen.getByLabelText('Nickname')).toHaveValue('Dani')
    expect(screen.getByLabelText('Email')).toHaveValue('dani@example.com')
  })
})
