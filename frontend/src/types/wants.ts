export const CATEGORIES = ['FOOD', 'MOVIE', 'GAME', 'TRIP'] as const

export type Category = (typeof CATEGORIES)[number]

/**
 * A user as the API returns it, which it only ever does for the signed-in user
 * themselves (GET /me, and inside their own profile), so the private fields are
 * their own.
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
}

/**
 * Everyone's wants are visible to every signed-in user, so the owner comes as
 * just an id and a name, never a whole User.
 */
export interface Want {
  id: number
  message: string
  category: Category
  userId: number
  username: string
}

/** The owner is whoever is signed in, so there is no userId. */
export interface WantRequest {
  message: string
  category: Category
}
