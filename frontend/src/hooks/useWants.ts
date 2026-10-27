import { useCallback, useEffect, useRef, useState } from 'react'
import {
  cancelWant,
  closeWant,
  createWant,
  endWant,
  listWants,
  reopenWant,
  signUp,
  startWant,
  updateWant,
  withdraw,
} from '../api/wants'
import type { Category, Want } from '../types/wants'

/** What an edit changes about the seats: a raised limit, or opening or limiting it. Never both, which the API refuses. */
export type SeatsChange = { capacity: number } | { openInvite: boolean }

/** What an edit changes about the deadline: a new one, or none at all. Never both, which the API refuses. */
export type DeadlineChange = { expiresAt: string } | { noDeadline: true }

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : 'Request failed'
}

export interface UseWants {
  wants: Want[]
  loading: boolean
  error: string | null
  refresh: () => Promise<void>
  /** Each mutation resolves true on success, false if the API rejected it. */
  /** A null capacity makes it an open invite, with no limit. Without expiresAt there is no deadline. */
  add: (message: string, category: Category, capacity: number | null, expiresAt?: string) => Promise<boolean>
  /** Leaving a change out keeps the seats, or the deadline, as they are. */
  update: (
    id: number,
    message: string,
    category: Category,
    change?: SeatsChange,
    deadline?: DeadlineChange,
  ) => Promise<boolean>
  close: (id: number) => Promise<boolean>
  /** Without expiresAt, the API keeps a deadline still ahead and clears one that has passed. */
  reopen: (id: number, expiresAt?: string) => Promise<boolean>
  start: (id: number) => Promise<boolean>
  /** Final: an ended want can't be started again. */
  end: (id: number) => Promise<boolean>
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
        // Reload anyway: a refusal often means the want changed underneath
        // the screen, such as its deadline passing, and the rows should say so.
        // The error is set after, since a successful reload clears it.
        await refresh()
        setError(messageOf(e))
        return false
      }
    },
    [refresh],
  )

  const add = useCallback(
    (message: string, category: Category, capacity: number | null, expiresAt?: string) =>
      mutate(() =>
        createWant({
          message,
          category,
          ...(capacity === null ? { openInvite: true } : { capacity }),
          ...(expiresAt === undefined ? {} : { expiresAt }),
        }),
      ),
    [mutate],
  )

  const update = useCallback(
    (id: number, message: string, category: Category, change?: SeatsChange, deadline?: DeadlineChange) =>
      mutate(() => updateWant(id, { message, category, ...change, ...deadline })),
    [mutate],
  )

  const close = useCallback((id: number) => mutate(() => closeWant(id)), [mutate])

  // No deadline means no body at all, rather than a body saying "none".
  const reopen = useCallback(
    (id: number, expiresAt?: string) =>
      mutate(() => (expiresAt === undefined ? reopenWant(id) : reopenWant(id, expiresAt))),
    [mutate],
  )

  const start = useCallback((id: number) => mutate(() => startWant(id)), [mutate])

  const end = useCallback((id: number) => mutate(() => endWant(id)), [mutate])

  const cancel = useCallback((id: number) => mutate(() => cancelWant(id)), [mutate])

  // The list is reloaded afterwards, so the seats shown come from the feed
  // rather than from the details these return.
  const join = useCallback((id: number) => mutate(() => signUp(id)), [mutate])

  const leave = useCallback((id: number) => mutate(() => withdraw(id)), [mutate])

  return {
    wants,
    loading,
    error,
    refresh,
    add,
    update,
    close,
    reopen,
    start,
    end,
    cancel,
    signUp: join,
    withdraw: leave,
  }
}
