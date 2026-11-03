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

/**
 * Turns a failed response into an HttpError carrying its status and something readable.
 * A 429 is worded here, once, from its Retry-After, because every screen shows it the same way.
 */
export async function fail(response: Response): Promise<never> {
  if (response.status === 429) throw new HttpError(429, tooMany(response.headers.get('Retry-After')))
  const body = await response.text()
  throw new HttpError(response.status, body || `${response.status} ${response.statusText}`)
}

/** The API's rate limit, in words. Retry-After is whole seconds. */
function tooMany(retryAfter: string | null): string {
  const seconds = Number(retryAfter)
  if (!Number.isInteger(seconds) || seconds < 1) return "You're going a bit fast. Wait a moment and try again."
  return `You're going a bit fast. Try again in ${seconds} ${seconds === 1 ? 'second' : 'seconds'}.`
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
