import { describe, expect, it } from 'vitest'
import { HttpError, fail } from './http'

async function failureOf(response: Response): Promise<HttpError> {
  try {
    await fail(response)
  } catch (e) {
    return e as HttpError
  }
  throw new Error('fail did not throw')
}

describe('fail', () => {
  it('keeps the status and the body', async () => {
    const e = await failureOf(new Response('Name taken', { status: 400 }))

    expect(e).toBeInstanceOf(HttpError)
    expect(e.status).toBe(400)
    expect(e.message).toBe('Name taken')
  })

  it('falls back to the status line when the body is empty', async () => {
    const e = await failureOf(new Response(null, { status: 404, statusText: 'Not Found' }))

    expect(e.message).toBe('404 Not Found')
  })

  it('words a rate limit from Retry-After instead of the raw body', async () => {
    const e = await failureOf(
      new Response('{"status":429,"error":"Too Many Requests"}', { status: 429, headers: { 'Retry-After': '2' } }),
    )

    expect(e.status).toBe(429)
    expect(e.message).toBe("You're going a bit fast. Try again in 2 seconds.")
  })

  it('says "second" for one', async () => {
    const e = await failureOf(new Response(null, { status: 429, headers: { 'Retry-After': '1' } }))

    expect(e.message).toBe("You're going a bit fast. Try again in 1 second.")
  })

  it('still words a rate limit without a usable Retry-After', async () => {
    const missing = await failureOf(new Response(null, { status: 429 }))
    // Retry-After may also be an HTTP date; the API never sends one, so it isn't parsed.
    const date = await failureOf(
      new Response(null, { status: 429, headers: { 'Retry-After': 'Wed, 21 Oct 2026 07:28:00 GMT' } }),
    )

    expect(missing.message).toBe("You're going a bit fast. Wait a moment and try again.")
    expect(date.message).toBe("You're going a bit fast. Wait a moment and try again.")
  })
})
