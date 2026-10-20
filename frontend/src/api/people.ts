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
  if (!response.ok) return fail(response)
  const people: Omit<Person, 'following'>[] = await response.json()
  return people.map((p) => ({ ...p, following: true }))
}

export async function follow(userId: number): Promise<void> {
  const response = await authFetch(`/api/follows/${userId}`, { method: 'PUT' })
  if (!response.ok) await fail(response)
}

export async function unfollow(userId: number): Promise<void> {
  const response = await authFetch(`/api/follows/${userId}`, { method: 'DELETE' })
  if (!response.ok) await fail(response)
}
