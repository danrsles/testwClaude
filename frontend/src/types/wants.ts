export const CATEGORIES = ['FOOD', 'MOVIE', 'GAME', 'TRIP'] as const

export type Category = (typeof CATEGORIES)[number]

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
}

/** The owner is whoever is signed in, so there is no userId. */
export interface WantRequest {
  message: string
  category: Category
}
