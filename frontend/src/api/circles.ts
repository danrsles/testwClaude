import type { Circle, UserSummary } from '../types/circles'
import { authFetch, fail } from './http'

function jsonBody(body: unknown): RequestInit {
  return { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
}

/** The live circles you own or are in. */
export async function listCircles(): Promise<Circle[]> {
  const response = await authFetch('/api/circles')
  return response.ok ? response.json() : fail(response)
}

/** Creates a circle you own. */
export async function createCircle(name: string): Promise<Circle> {
  const response = await authFetch('/api/circles', { method: 'POST', ...jsonBody({ name }) })
  return response.ok ? response.json() : fail(response)
}

/** Renames a circle you own. */
export async function renameCircle(id: number, name: string): Promise<Circle> {
  const response = await authFetch(`/api/circles/${id}`, { method: 'PUT', ...jsonBody({ name }) })
  return response.ok ? response.json() : fail(response)
}

/** Deletes a circle you own. The API archives it; to everyone else it is gone. */
export async function deleteCircle(id: number): Promise<void> {
  const response = await authFetch(`/api/circles/${id}`, { method: 'DELETE' })
  if (!response.ok) await fail(response)
}

/** A circle's members in the order they were added, without its owner. */
export async function listMembers(circleId: number): Promise<UserSummary[]> {
  const response = await authFetch(`/api/circles/${circleId}/members`)
  return response.ok ? response.json() : fail(response)
}

/** The owner adds one of their followers. */
export async function addMember(circleId: number, userId: number): Promise<void> {
  const response = await authFetch(`/api/circles/${circleId}/members/${userId}`, { method: 'PUT' })
  if (!response.ok) await fail(response)
}

/** The owner removes someone, or a member removes themselves, which is leaving. */
export async function removeMember(circleId: number, userId: number): Promise<void> {
  const response = await authFetch(`/api/circles/${circleId}/members/${userId}`, { method: 'DELETE' })
  if (!response.ok) await fail(response)
}
