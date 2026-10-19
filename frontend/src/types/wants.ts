export const CATEGORIES = ['FOOD', 'MOVIE', 'GAME', 'TRIP'] as const

export type Category = (typeof CATEGORIES)[number]

/**
 * The signed-in user, account and profile in one, from GET /me. The API only
 * ever returns your own, so the private fields are yours.
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
  /** Shown to others; null until set, when the UI falls back to username. */
  nickname: string | null
  /** Avatar location; null until set, when the UI shows a placeholder. */
  s3Url: string | null
}

/** Body for PUT /me. Username and email are Clerk's, so they are not here. */
export interface UpdateMeRequest {
  nickname: string | null
  s3Url: string | null
}

/**
 * Everyone's wants are visible to every signed-in user, so the owner comes as
 * public fields only, never a whole User.
 */
export interface Want {
  id: number
  message: string
  category: Category
  userId: number
  username: string
  /** The owner's nickname; null if they have not set one. */
  nickname: string | null
}

/** The owner is whoever is signed in, so there is no userId. */
export interface WantRequest {
  message: string
  category: Category
}
