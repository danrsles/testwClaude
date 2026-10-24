export const CATEGORIES = ['FOOD', 'MOVIE', 'GAME', 'TRIP'] as const

export type Category = (typeof CATEGORIES)[number]

/**
 * Whether people can join. Only an ACTIVE want takes signups, withdrawals and
 * edits. CLOSED has a fixed lineup until the owner reopens it; ENDED is final
 * but stays in the feed; CANCELLED is final and never in the feed. Full is
 * not a status: it is seatsTaken >= capacity, and an open invite is never
 * full. Whether it is live is a separate flag, `happening`.
 */
export type WantStatus = 'ACTIVE' | 'CLOSED' | 'ENDED' | 'CANCELLED'

/**
 * The signed-in user, from GET /me, every field copied from Clerk. The API
 * only ever returns your own, so the private fields are yours.
 */
export interface User {
  id: number
  username: string
  /** The Clerk account behind this user; null for users seeded before logins. */
  clerkId: string | null
  /** Primary email in Clerk; null until a token carrying it has been seen. */
  email: string | null
  /** ISO timestamp of the last sign-in, per Clerk. */
  lastSignInAt: string | null
  /** Clerk profile picture (a silhouette if no photo); null only before Clerk has sent it, when the UI shows a placeholder. */
  imageUrl: string | null
}

/**
 * Everyone's wants are visible to every signed-in user, so the owner comes as
 * just an id, a username and a picture, never a whole User.
 */
export interface Want {
  id: number
  message: string
  category: Category
  userId: number
  username: string
  /** The owner's Clerk profile picture; null for owners Clerk has told us nothing about, such as the seeded dani. */
  imageUrl: string | null
  /** How many people the want is for, the owner included; at least 1. Null for an open invite, which has no limit. */
  capacity: number | null
  /** Seats taken, counting the owner. Full when seatsTaken >= capacity; an open invite never is. */
  seatsTaken: number
  /** Whether you hold a seat; always true on your own wants. */
  signedUp: boolean
  status: WantStatus
  /** Started by the owner and not yet ended. Says nothing about who can join: that is `status`. */
  happening: boolean
}

/** The owner is whoever is signed in, so there is no userId. */
export interface WantRequest {
  message: string
  category: Category
  /** At least 1. Defaults to 1 on create; left out on update, it stays as it was. It can't be lowered. */
  capacity?: number
  /**
   * True removes the limit, on create or on a limited want. False on an open
   * want limits it to exactly the people already signed up; on a limited want
   * it changes nothing. Never sent together with capacity: that is a 400.
   */
  openInvite?: boolean
}

/** What signing up or withdrawing returns: the want's seats after the change. */
export interface SignupEventDetails {
  wantId: number
  message: string
  /** Null for an open invite. */
  capacity: number | null
  seatsTaken: number
  /** Usernames only, the owner included. */
  lineup: string[]
  signedUp: boolean
  status: WantStatus
}
