import { useCallback, useEffect, useRef, useState } from 'react'
import {
  createMyProfile,
  deleteMyProfile,
  getMyProfile,
  updateMyProfile,
} from '../api/userProfiles'
import type { UserProfile, UserProfileRequest } from '../types/userProfiles'

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : 'Request failed'
}

export interface UseUserProfile {
  /** Null when the user has no profile yet. */
  profile: UserProfile | null
  loading: boolean
  error: string | null
  refresh: () => Promise<void>
  /** Each mutation resolves true on success, false if the API rejected it. */
  create: (fields: UserProfileRequest) => Promise<boolean>
  update: (fields: UserProfileRequest) => Promise<boolean>
  remove: () => Promise<boolean>
}

/**
 * Owns the signed-in user's profile as it lives on the server: the cached
 * profile, its loading and error state, and the operations that change it.
 * Which user is decided by the session token, not by an argument. Refetches
 * after every successful mutation.
 */
export function useUserProfile(): UseUserProfile {
  const [profile, setProfile] = useState<UserProfile | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Only the newest load may write to state, as in useWants.
  const latest = useRef(0)

  const refresh = useCallback(async () => {
    const request = ++latest.current
    setLoading(true)
    try {
      const found = await getMyProfile()
      if (request !== latest.current) return
      setProfile(found)
      setError(null)
    } catch (e) {
      if (request !== latest.current) return
      setError(messageOf(e))
    } finally {
      if (request === latest.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    // oxlint-disable-next-line react/set-state-in-effect
    refresh()
  }, [refresh])

  const mutate = useCallback(
    async (action: () => Promise<unknown>): Promise<boolean> => {
      try {
        await action()
        setError(null)
        await refresh()
        return true
      } catch (e) {
        setError(messageOf(e))
        return false
      }
    },
    [refresh],
  )

  const create = useCallback(
    (fields: UserProfileRequest) => mutate(() => createMyProfile(fields)),
    [mutate],
  )

  const update = useCallback(
    (fields: UserProfileRequest) => mutate(() => updateMyProfile(fields)),
    [mutate],
  )

  const remove = useCallback(() => mutate(() => deleteMyProfile()), [mutate])

  return { profile, loading, error, refresh, create, update, remove }
}
