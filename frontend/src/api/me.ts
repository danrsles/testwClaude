import type { UpdateMeRequest, User } from '../types/wants'
import { authFetch, fail } from './http'

const ME = '/api/me'

/** The signed-in user, account and profile in one; the API creates it on first call. */
export async function getMe(): Promise<User> {
  const response = await authFetch(ME)
  return response.ok ? response.json() : fail(response)
}

export async function updateMe(body: UpdateMeRequest): Promise<User> {
  const response = await authFetch(ME, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return response.ok ? response.json() : fail(response)
}
