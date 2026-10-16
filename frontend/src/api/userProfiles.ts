import type { UserProfile, UserProfileRequest } from '../types/userProfiles'
import { authFetch, fail } from './http'

// Only ever the signed-in user's own profile; the API offers no other.
const BASE = '/api/user/profiles'
const MINE = `${BASE}/me`

/** Null when the signed-in user has not created a profile yet. */
export async function getMyProfile(): Promise<UserProfile | null> {
  const response = await authFetch(MINE)
  if (response.status === 404) return null
  return response.ok ? response.json() : fail(response)
}

export async function createMyProfile(body: UserProfileRequest): Promise<UserProfile> {
  const response = await authFetch(BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return response.ok ? response.json() : fail(response)
}

export async function updateMyProfile(body: UserProfileRequest): Promise<UserProfile> {
  const response = await authFetch(MINE, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return response.ok ? response.json() : fail(response)
}

export async function deleteMyProfile(): Promise<void> {
  const response = await authFetch(MINE, { method: 'DELETE' })
  if (!response.ok) await fail(response)
}
