/**
 * What a notification is about. WANT_CREATED is sent to yourself, so its actor
 * is you. WANT_EXPIRED is sent by the clock, to the lineup and the owner, with
 * the owner as its actor.
 */
export type NotificationType =
  | 'FOLLOW'
  | 'WANT_CREATED'
  | 'WANT_SIGNUP'
  | 'WANT_WITHDRAWN'
  | 'WANT_UPDATED'
  | 'WANT_CLOSED'
  | 'WANT_REOPENED'
  | 'WANT_CANCELLED'
  | 'WANT_STARTED'
  | 'WANT_ENDED'
  | 'WANT_OPEN_INVITE'
  | 'WANT_LIMITED'
  | 'WANT_DEADLINE_CHANGED'
  | 'WANT_EXPIRED'
  | 'WANT_POSTED'

/** Someone who did something you are told about: public fields only. */
export interface Actor {
  id: number
  username: string
  /** Clerk profile picture; null for users Clerk has told us nothing about. */
  imageUrl: string | null
}

/** One entry in your notifications, newest first in the list the API returns. */
export interface Notification {
  id: number
  type: NotificationType
  actor: Actor
  /** ISO 8601, in UTC. */
  createdAt: string
  read: boolean
  /** The want it is about; null for FOLLOW. */
  wantId: number | null
  /** The want's message when the notification was sent; null for FOLLOW. */
  wantMessage: string | null
  /** On WANT_UPDATED, the message before a rename; null when the name didn't change. */
  previousMessage: string | null
}
