import { useCallback, useEffect, useRef, useState } from 'react'
import { createWant, deleteWant, listWants, updateWant } from '../api/wants'
import type { Category, Want } from '../types/wants'

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : 'Request failed'
}

export interface UseWants {
  wants: Want[]
  loading: boolean
  error: string | null
  refresh: () => Promise<void>
  /** Each mutation resolves true on success, false if the API rejected it. */
  add: (message: string, category: Category, userId: number) => Promise<boolean>
  update: (id: number, message: string, category: Category, userId: number) => Promise<boolean>
  remove: (id: number) => Promise<boolean>
}

/**
 * Owns everything about wants that lives on the server: the cached list, its
 * loading and error state, and the four operations that change it. Components
 * keep only their own UI state.
 *
 * Refetches whenever `filter` changes, and after every successful mutation.
 */
export function useWants(filter: Category | ''): UseWants {
  const [wants, setWants] = useState<Want[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Switching filters quickly starts overlapping requests. Each load claims a
  // number, and only the newest is allowed to write to state, so a slow earlier
  // response cannot overwrite a faster later one.
  const latest = useRef(0)

  const refresh = useCallback(async () => {
    const request = ++latest.current
    setLoading(true)
    try {
      const result = await listWants(filter)
      if (request !== latest.current) return
      setWants(result)
      setError(null)
    } catch (e) {
      if (request !== latest.current) return
      setError(messageOf(e))
    } finally {
      if (request === latest.current) setLoading(false)
    }
  }, [filter])

  // Fetching on mount and on filter changes is synchronising with an external
  // system, which is what effects are for. The lint rule fires because refresh
  // sets loading synchronously; a query library would own this instead.
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

  const add = useCallback(
    (message: string, category: Category, userId: number) =>
      mutate(() => createWant({ message, category, userId })),
    [mutate],
  )

  const update = useCallback(
    (id: number, message: string, category: Category, userId: number) =>
      mutate(() => updateWant(id, { message, category, userId })),
    [mutate],
  )

  const remove = useCallback((id: number) => mutate(() => deleteWant(id)), [mutate])

  return { wants, loading, error, refresh, add, update, remove }
}
