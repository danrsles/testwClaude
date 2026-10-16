import type { User } from './wants'

export interface UserProfile {
  id: number
  user: User
  email: string
  nickname: string
  /** Avatar location; null until S3 uploads exist, so the UI shows a placeholder. */
  s3Url: string | null
}

/** Body for creating or replacing your profile. The owner is whoever is signed in. */
export interface UserProfileRequest {
  email: string
  nickname: string
  s3Url: string | null
}
