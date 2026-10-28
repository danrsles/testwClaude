import { getToken } from '@clerk/react'

/** A refused request: its status, so a hook can say why in words, and the response body as the message. */
export class HttpError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'HttpError'
    this.status = status
  }
}

/** Turns a failed response into an HttpError carrying its status and something readable. */
export async function fail(response: Response): Promise<never> {
  const body = await response.text()
  throw new HttpError(response.status, body || `${response.status} ${response.statusText}`)
}

/**
 * fetch with the signed-in user's Clerk session token attached. Every API route
 * needs one. getToken waits for Clerk to load and returns a cached token,
 * refreshing it shortly before it expires, so calling it per request is cheap.
 */
export async function authFetch(url: string, init: RequestInit = {}): Promise<Response> {
  const token = await getToken()
  const headers = new Headers(init.headers)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  return fetch(url, { ...init, headers })
}
