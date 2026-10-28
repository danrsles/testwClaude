import { useCallback, useEffect, useRef, useState } from 'react'
import { follow, listFollowing, muteNewWants, searchPeople, unfollow, unmuteNewWants } from '../api/people'
import type { Person } from '../types/people'

/** How long typing must pause before a search is sent. */
const DEBOUNCE_MS = 250

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : 'Request failed'
}

export interface UsePeople {
  /** Null until the first load finishes. */
  people: Person[] | null
  /**
   * The trimmed query the shown people belong to ('' for who you follow).
   * Lags the typed query while a load is in flight, so headings and counts
   * derived from it always describe the rows on screen.
   */
  shownFor: string | null
  /** True while the shown people are not yet for the current query. */
  loading: boolean
  /** Why the last load failed, if it did. */
  error: string | null
  /** The people with a follow, unfollow, mute or unmute in flight. */
  pending: ReadonlySet<number>
  /** The last follow, unfollow, mute or unmute that failed, to show beside that person. */
  failed: { id: number; message: string } | null
  /** Follows or unfollows; resolves true on success, false if the API rejected it. */
  toggleFollow: (person: Person) => Promise<boolean>
  /** Mutes or unmutes someone you follow's new wants; resolves as toggleFollow does. */
  toggleNotify: (person: Person) => Promise<boolean>
}

/**
 * Owns the people screen's server state. With an empty query it lists who you
 * follow; otherwise it searches by username, waiting for typing to pause.
 *
 * A follow or unfollow updates that person's flag in place rather than
 * reloading, so someone just unfollowed stays in "You follow" until the next
 * load instead of vanishing from under the pointer.
 */
export function usePeople(query: string): UsePeople {
  const [people, setPeople] = useState<Person[] | null>(null)
  const [shownFor, setShownFor] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  // A set rather than one id: following someone while another request is
  // still running must not unblock the first person's button.
  const [pending, setPending] = useState<ReadonlySet<number>>(new Set())
  const [failed, setFailed] = useState<UsePeople['failed']>(null)

  // As in useWants: only the newest load may write to state, so a slow
  // earlier search cannot overwrite a faster later one.
  const latest = useRef(0)
  const text = query.trim()
  // Derived rather than set in the effect, so the first render after typing
  // is already loading instead of showing old rows under the new query.
  const loading = shownFor !== text

  useEffect(() => {
    const request = ++latest.current
    const timer = setTimeout(
      async () => {
        try {
          const result = text ? await searchPeople(text) : await listFollowing()
          if (request !== latest.current) return
          setPeople(result)
          setError(null)
        } catch (e) {
          if (request !== latest.current) return
          // Rows from an earlier query would not match this one.
          setPeople([])
          setError(messageOf(e))
        }
        setFailed(null)
        setShownFor(text)
      },
      // Who you follow needs no typing to settle.
      text ? DEBOUNCE_MS : 0,
    )
    return () => clearTimeout(timer)
  }, [text])

  /**
   * Sends one change for a person and, once the API accepts it, writes what
   * it did onto their row: the fields given, not a flip of what is on
   * screen, so two requests that both followed cannot flip it back.
   */
  const change = useCallback(async (person: Person, request: () => Promise<void>, done: Partial<Person>) => {
    setPending((ids) => new Set(ids).add(person.id))
    try {
      await request()
      setPeople((all) => all && all.map((p) => (p.id === person.id ? { ...p, ...done } : p)))
      setFailed(null)
      return true
    } catch (e) {
      setFailed({ id: person.id, message: messageOf(e) })
      return false
    } finally {
      setPending((ids) => {
        const rest = new Set(ids)
        rest.delete(person.id)
        return rest
      })
    }
  }, [])

  const toggleFollow = useCallback(
    (person: Person) => {
      const following = !person.following
      // A new follow starts unmuted, and an unfollow forgets the mute.
      return change(person, () => (following ? follow(person.id) : unfollow(person.id)), {
        following,
        notifyNewWants: following,
      })
    },
    [change],
  )

  const toggleNotify = useCallback(
    (person: Person) => {
      const notifyNewWants = !person.notifyNewWants
      return change(person, () => (notifyNewWants ? unmuteNewWants(person.id) : muteNewWants(person.id)), {
        notifyNewWants,
      })
    },
    [change],
  )

  return { people, shownFor, loading, error, pending, failed, toggleFollow, toggleNotify }
}
