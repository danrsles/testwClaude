import { useCallback, useEffect, useRef, useState } from 'react'
import { follow, listFollowing, searchPeople, unfollow } from '../api/people'
import type { Person } from '../types/people'

/** How long typing must pause before a search is sent. */
const DEBOUNCE_MS = 250

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : 'Request failed'
}

export interface UsePeople {
  people: Person[]
  loading: boolean
  error: string | null
  /** The person whose follow or unfollow is in flight, if any. */
  pendingId: number | null
  /** Follows or unfollows; resolves true on success, false if the API rejected it. */
  toggleFollow: (person: Person) => Promise<boolean>
}

/**
 * Owns the people screen's server state. With an empty query it lists who you
 * follow; otherwise it searches by username, waiting for typing to pause.
 *
 * A follow or unfollow flips that person's flag in place rather than
 * reloading, so someone just unfollowed stays in "You follow" until the next
 * load instead of vanishing from under the pointer.
 */
export function usePeople(query: string): UsePeople {
  const [people, setPeople] = useState<Person[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [pendingId, setPendingId] = useState<number | null>(null)

  // As in useWants: only the newest load may write to state, so a slow
  // earlier search cannot overwrite a faster later one.
  const latest = useRef(0)
  const text = query.trim()

  // Loading on query changes synchronises with an external system, which is
  // what effects are for; the lint rule fires because loading is set
  // synchronously, as in useWants.
  useEffect(() => {
    const request = ++latest.current
    // oxlint-disable-next-line react/set-state-in-effect
    setLoading(true)
    const timer = setTimeout(
      async () => {
        try {
          const result = text ? await searchPeople(text) : await listFollowing()
          if (request !== latest.current) return
          setPeople(result)
          setError(null)
        } catch (e) {
          if (request !== latest.current) return
          setError(messageOf(e))
        } finally {
          if (request === latest.current) setLoading(false)
        }
      },
      // Who you follow needs no typing to settle.
      text ? DEBOUNCE_MS : 0,
    )
    return () => clearTimeout(timer)
  }, [text])

  const toggleFollow = useCallback(async (person: Person) => {
    setPendingId(person.id)
    try {
      await (person.following ? unfollow(person.id) : follow(person.id))
      setPeople((all) => all.map((p) => (p.id === person.id ? { ...p, following: !p.following } : p)))
      setError(null)
      return true
    } catch (e) {
      setError(messageOf(e))
      return false
    } finally {
      setPendingId(null)
    }
  }, [])

  return { people, loading, error, pendingId, toggleFollow }
}
