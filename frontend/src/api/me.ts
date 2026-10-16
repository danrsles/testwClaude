import type { User } from '../types/wants'
import { authFetch, fail } from './http'

/** The signed-in user's record in our database; the API creates it on first call. */
export async function getMe(): Promise<User> {
  const response = await authFetch('/api/me')
  return response.ok ? response.json() : fail(response)
}
