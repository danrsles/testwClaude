import { useEffect, useRef, useState } from 'react'
import { useNotifications } from '../hooks/useNotifications'
import type { Notification } from '../types/notifications'
import Avatar from './Avatar'

const relative = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })

/**
 * "just now", "5 minutes ago", "yesterday", as of when the list was fetched:
 * coarse on purpose, and refreshed with every poll.
 */
function timeAgo(iso: string, now: number): string {
  const seconds = Math.round((new Date(iso).getTime() - now) / 1000)
  if (seconds > -60) return 'just now'
  const minutes = Math.round(seconds / 60)
  if (minutes > -60) return relative.format(minutes, 'minute')
  const hours = Math.round(minutes / 60)
  if (hours > -24) return relative.format(hours, 'hour')
  return relative.format(Math.round(hours / 24), 'day')
}

/** Who did it: the actor's username, or "You" for a want you created. */
function who(n: Notification): string {
  return n.type === 'WANT_CREATED' ? 'You' : n.actor.username
}

/** What they did, to follow who(). Every type needs a case, or its rows say nothing. */
function describe(n: Notification): string {
  const want = `'${n.wantMessage}'`
  switch (n.type) {
    case 'FOLLOW':
      return 'started following you'
    case 'WANT_CREATED':
      return `created ${want}`
    case 'WANT_SIGNUP':
      return `signed up for ${want}`
    case 'WANT_WITHDRAWN':
      return `withdrew from ${want}`
    case 'WANT_UPDATED':
      return n.previousMessage === null ? `updated ${want}` : `renamed '${n.previousMessage}' to ${want}`
    case 'WANT_CLOSED':
      return `closed signups for ${want}`
    case 'WANT_REOPENED':
      return `reopened signups for ${want}`
    case 'WANT_CANCELLED':
      return `cancelled ${want}`
  }
}

/**
 * Your notifications, newest first. Opening the page marks what it shows as
 * read, which clears the bell's badge; those stay highlighted as new until
 * you leave, so you can still tell which ones you had not seen.
 */
export default function Notifications() {
  const { notifications, error, fetchedAt, markRead } = useNotifications()
  // The ids that were unread when this page showed them.
  const [fresh, setFresh] = useState<ReadonlySet<number>>(new Set())
  // The newest id already sent to be marked read, so a failure that leaves
  // them unread is not retried on every render.
  const marked = useRef(0)

  useEffect(() => {
    const newest = notifications?.[0]?.id
    const unseen = notifications?.filter((n) => !n.read) ?? []
    if (newest === undefined || unseen.length === 0 || newest <= marked.current) return
    marked.current = newest
    setFresh((ids) => new Set([...ids, ...unseen.map((n) => n.id)]))
    markRead(newest)
  }, [notifications, markRead])

  return (
    <>
      <title>Notifications · Wants</title>
      <h1 className="text-3xl font-semibold tracking-tight">Notifications</h1>
      <p className="mt-1 mb-7 text-stone-500 dark:text-stone-400">
        Follows, signups on your wants, and changes to wants you're in. New ones turn up here within half a minute.
      </p>

      {error && (
        <p
          role="alert"
          className="mb-4 rounded-lg border border-red-600/50 bg-red-600/10 px-3 py-2.5 text-sm text-red-700 dark:text-red-400"
        >
          {error}
        </p>
      )}

      {notifications === null ? (
        !error && <p className="py-9 text-center text-stone-500 dark:text-stone-400">Loading...</p>
      ) : notifications.length === 0 ? (
        <p className="py-9 text-center text-stone-500 dark:text-stone-400">Nothing yet.</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {notifications.map((n) => {
            const isNew = fresh.has(n.id)
            return (
              <li
                key={n.id}
                className={`flex items-center gap-3 rounded-xl border px-3.5 py-3 ${
                  isNew
                    ? 'border-emerald-600/40 bg-emerald-50 dark:border-emerald-400/30 dark:bg-emerald-950/40'
                    : 'border-stone-200 bg-white dark:border-stone-800 dark:bg-stone-900'
                }`}
              >
                {/* Decorative: the username beside it names the person. */}
                <Avatar src={n.actor.imageUrl} alt="" size={32} />
                <p className="min-w-0 flex-1">
                  {isNew && <span className="sr-only">New: </span>}
                  <span className="font-medium">{who(n)}</span> {describe(n)}
                </p>
                <time dateTime={n.createdAt} className="shrink-0 text-sm text-stone-500 dark:text-stone-400">
                  {timeAgo(n.createdAt, fetchedAt)}
                </time>
              </li>
            )
          })}
        </ul>
      )}
    </>
  )
}
