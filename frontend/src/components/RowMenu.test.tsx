import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import RowMenu from './RowMenu'

function renderMenu() {
  const close = vi.fn()
  const cancel = vi.fn()
  const pick = vi.fn()
  render(
    <>
      <RowMenu
        label="More actions for Ramen"
        groups={[
          { items: [{ label: 'Close', onSelect: close }] },
          {
            label: 'Category',
            items: [
              { label: 'FOOD', checked: true, onSelect: pick },
              { label: 'GAME', checked: false, onSelect: pick },
            ],
          },
          { items: [{ label: 'Cancel want', danger: true, onSelect: cancel }] },
        ]}
      />
      <button>Elsewhere</button>
    </>,
  )
  return { close, cancel, pick, trigger: screen.getByRole('button', { name: 'More actions for Ramen' }) }
}

describe('RowMenu', () => {
  it('is closed until its button is pressed, then focuses the first item', async () => {
    const { trigger } = renderMenu()
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()

    await userEvent.click(trigger)

    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('menuitem', { name: 'Close' })).toHaveFocus()
  })

  it('runs an item, closes, and hands focus back to its button', async () => {
    const { trigger, close } = renderMenu()
    await userEvent.click(trigger)

    await userEvent.click(screen.getByRole('menuitem', { name: 'Close' }))

    expect(close).toHaveBeenCalledOnce()
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })

  it('moves between items with the arrow keys, Home and End, wrapping around', async () => {
    const { trigger } = renderMenu()
    await userEvent.click(trigger)

    await userEvent.keyboard('{ArrowDown}')
    expect(screen.getByRole('menuitemradio', { name: 'FOOD' })).toHaveFocus()
    await userEvent.keyboard('{End}')
    expect(screen.getByRole('menuitem', { name: 'Cancel want' })).toHaveFocus()
    await userEvent.keyboard('{ArrowDown}')
    expect(screen.getByRole('menuitem', { name: 'Close' })).toHaveFocus()
    await userEvent.keyboard('{ArrowUp}')
    expect(screen.getByRole('menuitem', { name: 'Cancel want' })).toHaveFocus()
    await userEvent.keyboard('{Home}')
    expect(screen.getByRole('menuitem', { name: 'Close' })).toHaveFocus()
  })

  it('jumps to the next item starting with a typed letter, wrapping round', async () => {
    const { trigger } = renderMenu()
    await userEvent.click(trigger)

    await userEvent.keyboard('g')
    expect(screen.getByRole('menuitemradio', { name: 'GAME' })).toHaveFocus()
    // Two items start with C: Close, then Cancel want, then round to Close.
    await userEvent.keyboard('c')
    expect(screen.getByRole('menuitem', { name: 'Cancel want' })).toHaveFocus()
    await userEvent.keyboard('c')
    expect(screen.getByRole('menuitem', { name: 'Close' })).toHaveFocus()
    // A letter nothing starts with leaves focus where it is.
    await userEvent.keyboard('z')
    expect(screen.getByRole('menuitem', { name: 'Close' })).toHaveFocus()
  })

  it('opens from the keyboard with Enter or Arrow Down', async () => {
    const { trigger } = renderMenu()
    trigger.focus()

    await userEvent.keyboard('{ArrowDown}')
    expect(screen.getByRole('menuitem', { name: 'Close' })).toHaveFocus()

    await userEvent.keyboard('{Escape}')
    await userEvent.keyboard('{Enter}')
    expect(screen.getByRole('menuitem', { name: 'Close' })).toHaveFocus()
  })

  it('closes on Escape and returns focus, running nothing', async () => {
    const { trigger, close, cancel } = renderMenu()
    await userEvent.click(trigger)

    await userEvent.keyboard('{Escape}')

    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
    expect(close).not.toHaveBeenCalled()
    expect(cancel).not.toHaveBeenCalled()
  })

  it('closes on a click outside', async () => {
    const { trigger } = renderMenu()
    await userEvent.click(trigger)

    await userEvent.click(screen.getByRole('button', { name: 'Elsewhere' }))

    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('ticks the checked choice in a group', async () => {
    const { trigger } = renderMenu()
    await userEvent.click(trigger)

    expect(screen.getByRole('group', { name: 'Category' })).toBeInTheDocument()
    expect(screen.getByRole('menuitemradio', { name: 'FOOD' })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByRole('menuitemradio', { name: 'GAME' })).toHaveAttribute('aria-checked', 'false')
  })
})
