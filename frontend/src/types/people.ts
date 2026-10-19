/**
 * Another user as the people search shows them: public fields only, plus
 * whether the signed-in user follows them.
 */
export interface Person {
  id: number
  username: string
  /** Clerk profile picture; null for users Clerk has told us nothing about, when the UI shows a placeholder. */
  imageUrl: string | null
  following: boolean
}
