/** What a notification is about. Only follows so far; signups on wants come next. */
export type NotificationType = 'FOLLOW'

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
}
