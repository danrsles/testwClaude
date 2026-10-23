import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { listNotifications, markNotificationsRead } from '../api/notifications'
import type { Notification } from '../types/notifications'
import { useMe } from './useMe'

/** How often the list is refetched while the tab is visible. */
export const POLL_MS = 30_000

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : 'Request failed'
}

export interface UseNotifications {
  /** Null until the first load finishes. */
  notifications: Notification[] | null
  /** How many of them are unread. */
  unread: number
  /** Why the last load failed, if it did. */
  error: string | null
  /** When the list was last fetched, in epoch milliseconds, for showing how old each one is. */
  fetchedAt: number
  /** Marks everything up to and including an id read, at once on screen. */
  markRead: (upTo: number) => void
}

/**
 * Your notifications, shared by the nav's bell and the notifications page
 * through TanStack Query's cache: one request serves both, and marking them
 * read on the page clears the bell's badge in the same render.
 *
 * Polls every POLL_MS while the tab is visible, and again when you come back
 * to it. The cache is keyed by your user id, so after signing out and in as
 * someone else no one sees the previous person's list.
 */
export function useNotifications(): UseNotifications {
  const { me } = useMe()
  const queryClient = useQueryClient()
  const key = ['notifications', me?.id]

  const query = useQuery({
    queryKey: key,
    queryFn: listNotifications,
    enabled: me !== null,
    refetchInterval: POLL_MS,
  })

  const mutation = useMutation({
    // Wrapped: TanStack passes a context object as a second argument.
    mutationFn: (upTo: number) => markNotificationsRead(upTo),
    // Mark them on screen before the server answers, so the badge drops at
    // once; the refetch afterwards brings the server's version either way.
    onMutate: async (upTo: number) => {
      await queryClient.cancelQueries({ queryKey: key })
      queryClient.setQueryData<Notification[]>(key, (all) => all?.map((n) => (n.id <= upTo ? { ...n, read: true } : n)))
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: key }),
  })

  const { mutate } = mutation
  const markRead = useCallback((upTo: number) => mutate(upTo), [mutate])

  const notifications = query.data ?? null
  return {
    notifications,
    unread: notifications?.filter((n) => !n.read).length ?? 0,
    error: query.error ? messageOf(query.error) : null,
    fetchedAt: query.dataUpdatedAt,
    markRead,
  }
}
