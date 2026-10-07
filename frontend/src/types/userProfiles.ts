import type { User } from './wants'

export interface UserProfile {
  id: number
  user: User
  email: string
  nickname: string
  /** Avatar location; null until S3 uploads exist, so the UI shows a placeholder. */
  s3Url: string | null
}

export interface UpdateUserProfileRequest {
  email: string
  nickname: string
  s3Url: string | null
}

export interface CreateUserProfileRequest extends UpdateUserProfileRequest {
  userId: number
}
