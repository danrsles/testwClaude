/**
 * Another user by public fields only, as the API's UserSummary: a circle's
 * owner, one of its members, or one of your followers.
 */
export interface UserSummary {
  id: number
  username: string
  /** Clerk profile picture; null for users Clerk has told us nothing about, when the UI shows a placeholder. */
  imageUrl: string | null
}

/**
 * A group, like a group chat: one owner, who created it, and members, who
 * must follow the owner. GET /circles lists the live ones you own or are in.
 * The owner is never in the circle's member list.
 */
export interface Circle {
  id: number
  name: string
  owner: UserSummary
}
