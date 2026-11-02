import { useEffect, useRef } from 'react'

interface LoadMoreProps {
  /** Loads the next page. Not called while one is loading. */
  onLoad: () => void
  loading: boolean
}

/**
 * The end of a paged list: a Load more button that presses itself as it
 * scrolls into view, so scrolling down loads more on its own (infinite
 * scroll), while keyboard and screen reader users, who don't scroll to it,
 * can press it.
 *
 * The observer starts 200px below the screen, so the next page is usually
 * there before you reach the end. It is made again after each load: a new
 * observer reports at once whether the button is in view, so a page too short
 * to push it off screen loads the next one too, instead of waiting for a
 * scroll that may never come. Without IntersectionObserver it is just the button.
 */
export default function LoadMore({ onLoad, loading }: LoadMoreProps) {
  const button = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    const target = button.current
    if (loading || !target || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) onLoad()
      },
      { rootMargin: '0px 0px 200px 0px' },
    )
    observer.observe(target)
    return () => observer.disconnect()
  }, [loading, onLoad])

  // aria-disabled rather than disabled while loading, so a keyboard user's
  // focus stays on the button.
  return (
    <div className="flex justify-center py-4">
      <button
        ref={button}
        onClick={() => {
          if (!loading) onLoad()
        }}
        aria-disabled={loading}
        className="btn px-4 py-2 text-sm"
      >
        {loading ? 'Loading more...' : 'Load more'}
      </button>
    </div>
  )
}
