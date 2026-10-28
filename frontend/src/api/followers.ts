import type { UserSummary } from '../types/circles'
import { authFetch, fail } from './http'

/** Everyone who follows you: the people you can add to your circles. */
export async function listFollowers(): Promise<UserSummary[]> {
  const response = await authFetch('/api/followers')
  return response.ok ? response.json() : fail(response)
}
