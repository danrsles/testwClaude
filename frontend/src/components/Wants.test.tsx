import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createWant, deleteWant, listWants, updateWant } from '../api/wants'
import type { Category, Want } from '../types/wants'
import Wants from './Wants'

// Stubbed at the api boundary: the component and its hook are what is under
// test, and fetch is not. Keeping the seam here means these tests survive a
// change of transport.
vi.mock('../api/wants', () => ({
  listWants: vi.fn(),
  createWant: vi.fn(),
  updateWant: vi.fn(),
  deleteWant: vi.fn(),
}))

function want(id: number, message: string, category: Category = 'FOOD'): Want {
  return { id, message, category, user: { id: 1, username: 'dani' } }
}

describe('Wants', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listWants).mockResolvedValue([
      want(1, 'Ramen downtown'),
      want(2, 'Dune Part Two', 'MOVIE'),
    ])
  })

  it('shows the wants the API returns', async () => {
    render(<Wants />)

    expect(await screen.findByText('Ramen downtown')).toBeInTheDocument()
    expect(screen.getByText('Dune Part Two')).toBeInTheDocument()
  })

  it('creates a want and clears the input', async () => {
    vi.mocked(createWant).mockResolvedValue(want(3, 'Tacos'))
    render(<Wants />)
    await screen.findByText('Ramen downtown')

    const input = screen.getByLabelText('Want description')
    await userEvent.type(input, 'Tacos')
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))

    expect(createWant).toHaveBeenCalledWith({ message: 'Tacos', category: 'FOOD', userId: 1 })
    await waitFor(() => expect(input).toHaveValue(''))
  })

  it('keeps the typed text when creating fails', async () => {
    vi.mocked(createWant).mockRejectedValue(new Error('message must not be blank'))
    render(<Wants />)
    await screen.findByText('Ramen downtown')

    const input = screen.getByLabelText('Want description')
    await userEvent.type(input, 'Tacos')
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))

    expect(await screen.findByText('message must not be blank')).toBeInTheDocument()
    expect(input).toHaveValue('Tacos')
  })

  it('asks the API for one category when a filter is chosen', async () => {
    render(<Wants />)
    await screen.findByText('Ramen downtown')

    await userEvent.click(screen.getByRole('button', { name: 'MOVIE' }))

    await waitFor(() => expect(listWants).toHaveBeenLastCalledWith('MOVIE'))
  })

  it('saves an edited message', async () => {
    vi.mocked(updateWant).mockResolvedValue(want(1, 'Ramen uptown'))
    render(<Wants />)
    await screen.findByText('Ramen downtown')

    await userEvent.click(screen.getAllByRole('button', { name: 'Edit' })[0])
    const editor = screen.getByLabelText('Edit want')
    await userEvent.clear(editor)
    await userEvent.type(editor, 'Ramen uptown')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(updateWant).toHaveBeenCalledWith(1, {
      message: 'Ramen uptown',
      category: 'FOOD',
      userId: 1,
    })
  })

  it('deletes a want', async () => {
    vi.mocked(deleteWant).mockResolvedValue(undefined)
    render(<Wants />)
    await screen.findByText('Ramen downtown')

    await userEvent.click(screen.getAllByRole('button', { name: 'Delete' })[0])

    expect(deleteWant).toHaveBeenCalledWith(1)
  })
})
