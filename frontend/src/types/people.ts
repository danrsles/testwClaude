/**
 * Another user as the people search and the list of who you follow show them:
 * public fields only, plus whether the signed-in user follows them and hears
 * about their new wants.
 */
export interface Person {
  id: number
  username: string
  /** Clerk profile picture; null for users Clerk has told us nothing about, when the UI shows a placeholder. */
  imageUrl: string | null
  following: boolean
  /** Whether their new wants are announced to you; false when muted, and when you don't follow them. */
  notifyNewWants: boolean
}
