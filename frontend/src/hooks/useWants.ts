import { useCallback, useEffect, useRef, useState } from 'react'
import {
  cancelWant,
  closeWant,
  createWant,
  listWants,
  reopenWant,
  signUp,
  updateWant,
  withdraw,
} from '../api/wants'
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
  add: (message: string, category: Category, capacity: number) => Promise<boolean>
  /** Leaving capacity out keeps it as it is. */
  update: (id: number, message: string, category: Category, capacity?: number) => Promise<boolean>
  close: (id: number) => Promise<boolean>
  reopen: (id: number) => Promise<boolean>
  /** Final: there is no undoing a cancel. */
  cancel: (id: number) => Promise<boolean>
  signUp: (id: number) => Promise<boolean>
  withdraw: (id: number) => Promise<boolean>
}

/**
 * Owns everything about wants that lives on the server: the cached list, its
 * loading and error state, and the operations that change it. Components
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
    (message: string, category: Category, capacity: number) =>
      mutate(() => createWant({ message, category, capacity })),
    [mutate],
  )

  const update = useCallback(
    (id: number, message: string, category: Category, capacity?: number) =>
      mutate(() =>
        updateWant(id, capacity === undefined ? { message, category } : { message, category, capacity }),
      ),
    [mutate],
  )

  const close = useCallback((id: number) => mutate(() => closeWant(id)), [mutate])

  const reopen = useCallback((id: number) => mutate(() => reopenWant(id)), [mutate])

  const cancel = useCallback((id: number) => mutate(() => cancelWant(id)), [mutate])

  // The list is reloaded afterwards, so the seats shown come from the feed
  // rather than from the details these return.
  const join = useCallback((id: number) => mutate(() => signUp(id)), [mutate])

  const leave = useCallback((id: number) => mutate(() => withdraw(id)), [mutate])

  return { wants, loading, error, refresh, add, update, close, reopen, cancel, signUp: join, withdraw: leave }
}
