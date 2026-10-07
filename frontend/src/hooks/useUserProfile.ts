import { useCallback, useEffect, useRef, useState } from 'react'
import {
  createUserProfile,
  deleteUserProfile,
  listUserProfiles,
  updateUserProfile,
} from '../api/userProfiles'
import type { UpdateUserProfileRequest, UserProfile } from '../types/userProfiles'

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
  create: (fields: UpdateUserProfileRequest) => Promise<boolean>
  update: (fields: UpdateUserProfileRequest) => Promise<boolean>
  remove: () => Promise<boolean>
}

/**
 * Owns one user's profile as it lives on the server: the cached profile, its
 * loading and error state, and the operations that change it. Refetches when
 * `userId` changes and after every successful mutation.
 */
export function useUserProfile(userId: number): UseUserProfile {
  const [profile, setProfile] = useState<UserProfile | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Only the newest load may write to state, as in useWants.
  const latest = useRef(0)

  const refresh = useCallback(async () => {
    const request = ++latest.current
    setLoading(true)
    try {
      const [found] = await listUserProfiles(userId)
      if (request !== latest.current) return
      setProfile(found ?? null)
      setError(null)
    } catch (e) {
      if (request !== latest.current) return
      setError(messageOf(e))
    } finally {
      if (request === latest.current) setLoading(false)
    }
  }, [userId])

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
    (fields: UpdateUserProfileRequest) => mutate(() => createUserProfile({ ...fields, userId })),
    [mutate, userId],
  )

  const update = useCallback(
    (fields: UpdateUserProfileRequest) =>
      mutate(async () => {
        if (!profile) throw new Error('There is no profile to update')
        await updateUserProfile(profile.id, fields)
      }),
    [mutate, profile],
  )

  const remove = useCallback(
    () =>
      mutate(async () => {
        if (!profile) throw new Error('There is no profile to delete')
        await deleteUserProfile(profile.id)
      }),
    [mutate, profile],
  )

  return { profile, loading, error, refresh, create, update, remove }
}
