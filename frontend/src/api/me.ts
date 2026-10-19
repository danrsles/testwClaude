import type { User } from '../types/wants'
import { authFetch, fail } from './http'

const ME = '/api/me'

/** The signed-in user, account and profile in one; the API creates it on first call. */
export async function getMe(): Promise<User> {
  const response = await authFetch(ME)
  return response.ok ? response.json() : fail(response)
}
