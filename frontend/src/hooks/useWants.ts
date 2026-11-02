import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useState } from 'react'
import { HttpError } from '../api/http'
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
import { useMe } from './useMe'

/** What an edit changes about the seats: a raised limit, or opening or limiting it. Never both, which the API refuses. */
export type SeatsChange = { capacity: number } | { openInvite: boolean }

/** What an edit changes about the deadline: a new one, or none at all. Never both, which the API refuses. */
export type DeadlineChange = { expiresAt: string } | { noDeadline: true }

/** What an edit changes about who sees it: a circle, or back to General. Never both, which the API refuses. */
export type AudienceChange = { circleId: number } | { allFollowers: true }

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : 'Request failed'
}

/** Posting to a circle that was deleted, or that you left or were removed from, is a 404 whose body is Spring's raw JSON. */
function isCircleGone(e: unknown, circleId: number | undefined): boolean {
  return circleId !== undefined && e instanceof HttpError && e.status === 404
}

/** How many wants a page holds. A shorter page is the last. */
export const PAGE_SIZE = 20

export interface UseWants {
  /** Every page loaded so far, newest first. */
  wants: Want[]
  loading: boolean
  error: string | null
  refresh: () => Promise<void>
  /** Whether older wants remain to be loaded. */
  hasMore: boolean
  /** Loads the next page of older wants, unless one is already loading. */
  loadMore: () => void
  loadingMore: boolean
  /** Each mutation resolves true on success, false if the API rejected it. */
  /**
   * A null capacity makes it an open invite, with no limit. Without expiresAt
   * there is no deadline; without circleId it is General.
   */
  add: (
    message: string,
    category: Category,
    capacity: number | null,
    expiresAt?: string,
    circleId?: number,
  ) => Promise<boolean>
  /** Leaving a change out keeps the seats, the deadline, or the audience, as they are. */
  update: (
    id: number,
    message: string,
    category: Category,
    change?: SeatsChange,
    deadline?: DeadlineChange,
    audience?: AudienceChange,
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
 * Owns everything about wants that lives on the server: the loaded pages of
 * the feed, their loading and error state, and the operations that change
 * them. Components keep only their own UI state.
 *
 * The pages are a TanStack infinite query, keyed by your user id and the
 * filter, so switching filters back shows the cached pages at once, and a
 * slow response for an old filter can't overwrite a newer one. Each page
 * asks for wants older than the last one loaded (keyset paging). After every
 * mutation, successful or not, every loaded page is refetched in order, each
 * cursor worked out again from the fresh pages, so the screen matches the
 * server however many pages are loaded.
 */
export function useWants(filter: Category | ''): UseWants {
  const { me } = useMe()
  const queryClient = useQueryClient()

  const query = useInfiniteQuery({
    queryKey: ['wants', me?.id, filter],
    queryFn: ({ pageParam }) => listWants(filter, pageParam, PAGE_SIZE),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (page: Want[]) => (page.length < PAGE_SIZE ? undefined : page[page.length - 1].id),
    enabled: me !== null,
  })

  const wants = query.data?.pages.flat() ?? []

  // Why the last change was refused, kept with the filter it happened under,
  // so switching filters clears it as a reload used to.
  const [refusal, setRefusal] = useState<{ filter: Category | ''; message: string } | null>(null)
  const loadError = query.error ? messageOf(query.error) : null
  const error = refusal?.filter === filter ? refusal.message : loadError

  // Every filter's pages, since a change can move a want in or out of any of them.
  const refresh = useCallback(async () => {
    await queryClient.invalidateQueries({ queryKey: ['wants', me?.id] })
  }, [queryClient, me?.id])

  const { hasNextPage, isFetchingNextPage, fetchNextPage } = query
  const loadMore = useCallback(() => {
    if (hasNextPage && !isFetchingNextPage) void fetchNextPage()
  }, [hasNextPage, isFetchingNextPage, fetchNextPage])

  // circleId is the circle being posted to, if any, so its 404 can be worded.
  const mutate = useCallback(
    async (action: () => Promise<unknown>, circleId?: number): Promise<boolean> => {
      try {
        await action()
        setRefusal(null)
        await refresh()
        return true
      } catch (e) {
        // Reload anyway: a refusal often means the want changed underneath
        // the screen, such as its deadline passing, and the rows should say so.
        await refresh()
        if (isCircleGone(e, circleId)) {
          // Deleted, or you were removed or left: the API can't tell you which.
          setRefusal({
            filter,
            message: "You can't post to that circle any more. It was deleted, or you're no longer in it.",
          })
          void queryClient.invalidateQueries({ queryKey: ['circles', me?.id], exact: true })
        } else {
          setRefusal({ filter, message: messageOf(e) })
        }
        return false
      }
    },
    [refresh, queryClient, me?.id, filter],
  )

  const add = useCallback(
    (message: string, category: Category, capacity: number | null, expiresAt?: string, circleId?: number) =>
      mutate(
        () =>
          createWant({
            message,
            category,
            ...(capacity === null ? { openInvite: true } : { capacity }),
            ...(expiresAt === undefined ? {} : { expiresAt }),
            ...(circleId === undefined ? {} : { circleId }),
          }),
        circleId,
      ),
    [mutate],
  )

  const update = useCallback(
    (
      id: number,
      message: string,
      category: Category,
      change?: SeatsChange,
      deadline?: DeadlineChange,
      audience?: AudienceChange,
    ) =>
      mutate(
        () => updateWant(id, { message, category, ...change, ...deadline, ...audience }),
        audience && 'circleId' in audience ? audience.circleId : undefined,
      ),
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
    loading: query.isFetching,
    error,
    refresh,
    hasMore: hasNextPage,
    loadMore,
    loadingMore: isFetchingNextPage,
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
