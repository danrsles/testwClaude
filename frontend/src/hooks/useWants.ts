import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useRef, useState } from 'react'
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

/**
 * A refusal in words. The API's error body is Spring's JSON, which must never
 * reach the screen, so an HttpError is worded from its status. A 429 keeps
 * the wording `fail` gave it, from Retry-After.
 */
function messageOf(e: unknown): string {
  if (e instanceof HttpError) {
    if (e.status === 429) return e.message
    if (e.status === 409) return "That didn't go through: it changed meanwhile, such as closing or filling up."
    if (e.status === 403) return 'Only the person who posted it can change it.'
    if (e.status === 404) return "It's no longer there."
    if (e.status === 400) return "That change isn't allowed."
    return 'Something went wrong. Try again.'
  }
  return e instanceof Error ? e.message : 'Something went wrong. Try again.'
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
  /** A load failure, or a refused change that belongs to no card on screen, such as Add. */
  error: string | null
  /** Why the last change to this want was refused, shown in its card; null if it wasn't. */
  refusalFor: (id: number) => string | null
  /** Whether a change to this want is on its way. A second one for the same want is refused until it lands. */
  isPending: (id: number) => boolean
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
  // so switching filters clears it as a reload used to, and with the want it
  // was about, so its card can say so where the person is looking.
  const [refusal, setRefusal] = useState<{ filter: Category | ''; message: string; wantId?: number } | null>(null)
  const loadError = query.error ? messageOf(query.error) : null
  const current = refusal?.filter === filter ? refusal : null
  // A want that has left the feed, such as one cancelled elsewhere, has no
  // card to show it in, so its refusal goes to the top of the page instead.
  const onCard = current?.wantId !== undefined && wants.some((w) => w.id === current.wantId)
  const error = current && !onCard ? current.message : loadError
  const refusalFor = useCallback(
    (id: number) => (onCard && current?.wantId === id ? current.message : null),
    [onCard, current],
  )

  // Wants with a change on its way, so a double tap sends one request, not two.
  const [pending, setPending] = useState<ReadonlySet<number>>(new Set())
  const isPending = useCallback((id: number) => pending.has(id), [pending])

  // Every filter's pages, since a change can move a want in or out of any of them.
  const refresh = useCallback(async () => {
    await queryClient.invalidateQueries({ queryKey: ['wants', me?.id] })
  }, [queryClient, me?.id])

  // Never while the pages are being fetched, a refresh after a change
  // included: fetchNextPage would cancel that refresh by default, leaving the
  // screen on the pages from before the change. LoadMore's observer is made
  // again when this changes, so it loads the next page once the refresh lands.
  const { hasNextPage, isFetching, fetchNextPage } = query
  const loadMore = useCallback(() => {
    if (hasNextPage && !isFetching) void fetchNextPage({ cancelRefetch: false })
  }, [hasNextPage, isFetching, fetchNextPage])

  // A ref mirrors `pending` so two calls in the same render both see the first.
  const inFlight = useRef(new Set<number>())
  const track = useCallback((id: number, on: boolean) => {
    if (on) inFlight.current.add(id)
    else inFlight.current.delete(id)
    setPending(new Set(inFlight.current))
  }, [])

  // circleId is the circle being posted to, if any, so its 404 can be worded;
  // wantId the want changed, if any, so its card shows the refusal.
  const attempt = useCallback(
    async (action: () => Promise<unknown>, circleId?: number, wantId?: number): Promise<boolean> => {
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
            wantId,
            message: "You can't post to that circle any more. It was deleted, or you're no longer in it.",
          })
          void queryClient.invalidateQueries({ queryKey: ['circles', me?.id], exact: true })
        } else {
          setRefusal({ filter, wantId, message: messageOf(e) })
        }
        return false
      }
    },
    [refresh, queryClient, me?.id, filter, setRefusal],
  )

  // A change to one want waits for the last change to it, so its buttons can
  // say so and a double tap sends one request.
  const mutate = useCallback(
    async (action: () => Promise<unknown>, { circleId, wantId }: { circleId?: number; wantId?: number } = {}) => {
      if (wantId === undefined) return attempt(action, circleId)
      if (inFlight.current.has(wantId)) return false
      track(wantId, true)
      const ok = await attempt(action, circleId, wantId)
      track(wantId, false)
      return ok
    },
    [attempt, track],
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
        { circleId },
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
      mutate(() => updateWant(id, { message, category, ...change, ...deadline, ...audience }), {
        circleId: audience && 'circleId' in audience ? audience.circleId : undefined,
        wantId: id,
      }),
    [mutate],
  )

  const close = useCallback((id: number) => mutate(() => closeWant(id), { wantId: id }), [mutate])

  // No deadline means no body at all, rather than a body saying "none".
  const reopen = useCallback(
    (id: number, expiresAt?: string) =>
      mutate(() => (expiresAt === undefined ? reopenWant(id) : reopenWant(id, expiresAt)), { wantId: id }),
    [mutate],
  )

  const start = useCallback((id: number) => mutate(() => startWant(id), { wantId: id }), [mutate])

  const end = useCallback((id: number) => mutate(() => endWant(id), { wantId: id }), [mutate])

  const cancel = useCallback((id: number) => mutate(() => cancelWant(id), { wantId: id }), [mutate])

  // The list is reloaded afterwards, so the seats shown come from the feed
  // rather than from the details these return.
  const join = useCallback((id: number) => mutate(() => signUp(id), { wantId: id }), [mutate])

  const leave = useCallback((id: number) => mutate(() => withdraw(id), { wantId: id }), [mutate])

  return {
    wants,
    loading: query.isFetching,
    error,
    refusalFor,
    isPending,
    refresh,
    hasMore: hasNextPage,
    loadMore,
    loadingMore: query.isFetchingNextPage,
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
