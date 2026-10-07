import type {
  CreateUserProfileRequest,
  UpdateUserProfileRequest,
  UserProfile,
} from '../types/userProfiles'
import { fail } from './http'

const BASE = '/api/user/profiles'

export async function listUserProfiles(userId?: number): Promise<UserProfile[]> {
  const url = userId === undefined ? BASE : `${BASE}?userId=${userId}`
  const response = await fetch(url)
  return response.ok ? response.json() : fail(response)
}

export async function createUserProfile(body: CreateUserProfileRequest): Promise<UserProfile> {
  const response = await fetch(BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return response.ok ? response.json() : fail(response)
}

export async function updateUserProfile(
  id: number,
  body: UpdateUserProfileRequest,
): Promise<UserProfile> {
  const response = await fetch(`${BASE}/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return response.ok ? response.json() : fail(response)
}

export async function deleteUserProfile(id: number): Promise<void> {
  const response = await fetch(`${BASE}/${id}`, { method: 'DELETE' })
  if (!response.ok) await fail(response)
}
