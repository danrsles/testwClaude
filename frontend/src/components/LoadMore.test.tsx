import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LoadMore from './LoadMore'

// jsdom has no IntersectionObserver. This stand-in records each observer, so
// a test can play the browser and report the button scrolling into view.
const observers: { callback: IntersectionObserverCallback; disconnected: boolean }[] = []

class FakeObserver {
  private readonly entry: { callback: IntersectionObserverCallback; disconnected: boolean }
  constructor(callback: IntersectionObserverCallback) {
    this.entry = { callback, disconnected: false }
    observers.push(this.entry)
  }
  observe() {}
  disconnect() {
    this.entry.disconnected = true
  }
}

/** Tells every live observer whether the button is in view, as the browser would. */
function scrolledIntoView(isIntersecting = true) {
  for (const observer of observers.filter((o) => !o.disconnected)) {
    observer.callback([{ isIntersecting } as IntersectionObserverEntry], {} as IntersectionObserver)
  }
}

describe('LoadMore', () => {
  beforeEach(() => {
    observers.length = 0
    vi.stubGlobal('IntersectionObserver', FakeObserver)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads when pressed', async () => {
    const onLoad = vi.fn()
    render(<LoadMore onLoad={onLoad} loading={false} />)

    await userEvent.click(screen.getByRole('button', { name: 'Load more' }))

    expect(onLoad).toHaveBeenCalledTimes(1)
  })

  it('loads on its own when scrolled into view, and not before', () => {
    const onLoad = vi.fn()
    render(<LoadMore onLoad={onLoad} loading={false} />)

    scrolledIntoView(false)
    expect(onLoad).not.toHaveBeenCalled()

    scrolledIntoView()
    expect(onLoad).toHaveBeenCalledTimes(1)
  })

  it('does nothing while a page is loading, but keeps focus', async () => {
    const onLoad = vi.fn()
    render(<LoadMore onLoad={onLoad} loading />)
    const button = screen.getByRole('button', { name: 'Loading more...' })

    scrolledIntoView()
    await userEvent.click(button)

    expect(onLoad).not.toHaveBeenCalled()
    expect(button).toHaveAttribute('aria-disabled', 'true')
    expect(button).toHaveFocus()
  })

  it('watches again after each load, so a short page still leads to the next', () => {
    const onLoad = vi.fn()
    const { rerender } = render(<LoadMore onLoad={onLoad} loading={false} />)
    rerender(<LoadMore onLoad={onLoad} loading />)
    rerender(<LoadMore onLoad={onLoad} loading={false} />)

    // The first observer was let go; the new one reports the button still in view.
    expect(observers.map((o) => o.disconnected)).toEqual([true, false])
    scrolledIntoView()
    expect(onLoad).toHaveBeenCalledTimes(1)
  })

  it('is still a button without IntersectionObserver', async () => {
    vi.unstubAllGlobals()
    vi.stubGlobal('IntersectionObserver', undefined)
    const onLoad = vi.fn()
    render(<LoadMore onLoad={onLoad} loading={false} />)

    await userEvent.click(screen.getByRole('button', { name: 'Load more' }))

    expect(onLoad).toHaveBeenCalledTimes(1)
  })
})
