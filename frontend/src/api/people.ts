import type { Person } from '../types/people'
import { authFetch, fail } from './http'

/** Users whose name contains the text, ignoring case, never yourself. */
export async function searchPeople(text: string): Promise<Person[]> {
  const response = await authFetch(`/api/users?q=${encodeURIComponent(text)}`)
  return response.ok ? response.json() : fail(response)
}

/** Everyone you follow, in the order you followed them. */
export async function listFollowing(): Promise<Person[]> {
  const response = await authFetch('/api/follows')
  return response.ok ? response.json() : fail(response)
}

export async function follow(userId: number): Promise<void> {
  const response = await authFetch(`/api/follows/${userId}`, { method: 'PUT' })
  if (!response.ok) await fail(response)
}

export async function unfollow(userId: number): Promise<void> {
  const response = await authFetch(`/api/follows/${userId}`, { method: 'DELETE' })
  if (!response.ok) await fail(response)
}

/** Stops someone you follow's new wants being announced to you. */
export async function muteNewWants(userId: number): Promise<void> {
  const response = await authFetch(`/api/follows/${userId}/notify`, { method: 'DELETE' })
  if (!response.ok) await fail(response)
}

/** Announces someone you follow's new wants to you again. */
export async function unmuteNewWants(userId: number): Promise<void> {
  const response = await authFetch(`/api/follows/${userId}/notify`, { method: 'PUT' })
  if (!response.ok) await fail(response)
}
