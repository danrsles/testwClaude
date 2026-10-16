import { useEffect, useState } from 'react'
import { getMe } from '../api/me'
import type { User } from '../types/wants'

/**
 * The signed-in user's record in our database, or null until it has loaded (or
 * if loading failed). Screens use its id to tell which wants are the user's own;
 * the API enforces that anyway, so a failure only hides buttons.
 */
export function useCurrentUser(): User | null {
  const [user, setUser] = useState<User | null>(null)

  useEffect(() => {
    let cancelled = false
    getMe()
      .then((me) => {
        if (!cancelled) setUser(me)
      })
      .catch(() => {
        if (!cancelled) setUser(null)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return user
}
