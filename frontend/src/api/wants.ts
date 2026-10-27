import type { Category, SignupEventDetails, Want, WantRequest } from '../types/wants'
import { authFetch, fail } from './http'

const BASE = '/api/wants'

export async function listWants(category?: Category | ''): Promise<Want[]> {
  const url = category ? `${BASE}?category=${category}` : BASE
  const response = await authFetch(url)
  return response.ok ? response.json() : fail(response)
}

export async function createWant(body: WantRequest): Promise<Want> {
  const response = await authFetch(BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return response.ok ? response.json() : fail(response)
}

export async function updateWant(id: number, body: WantRequest): Promise<Want> {
  const response = await authFetch(`${BASE}/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return response.ok ? response.json() : fail(response)
}

/** Fixes the lineup. Owner only; Reopen undoes it. Idempotent. */
export async function closeWant(id: number): Promise<Want> {
  const response = await authFetch(`${BASE}/${id}/close`, { method: 'POST' })
  return response.ok ? response.json() : fail(response)
}

/**
 * Takes signups again. Owner only. Idempotent. A new deadline must be in the
 * future (400); without one, a deadline still ahead is kept and one that has
 * passed is cleared.
 */
export async function reopenWant(id: number, expiresAt?: string): Promise<Want> {
  const response = await authFetch(
    `${BASE}/${id}/reopen`,
    expiresAt === undefined
      ? { method: 'POST' }
      : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ expiresAt }) },
  )
  return response.ok ? response.json() : fail(response)
}

/** Marks it happening, without changing who can join. Owner only. Idempotent. */
export async function startWant(id: number): Promise<Want> {
  const response = await authFetch(`${BASE}/${id}/start`, { method: 'POST' })
  return response.ok ? response.json() : fail(response)
}

/** Makes a happening want ENDED, which is final. Owner only. Idempotent. */
export async function endWant(id: number): Promise<Want> {
  const response = await authFetch(`${BASE}/${id}/end`, { method: 'POST' })
  return response.ok ? response.json() : fail(response)
}

/** DELETE cancels: final, and the want leaves the feed, but the row is kept. Owner only. */
export async function cancelWant(id: number): Promise<void> {
  const response = await authFetch(`${BASE}/${id}`, { method: 'DELETE' })
  if (!response.ok) await fail(response)
}

/** Takes a seat. Idempotent: already in, it changes nothing. Full is a 409. */
export async function signUp(id: number): Promise<SignupEventDetails> {
  const response = await authFetch(`${BASE}/${id}/signups`, { method: 'POST' })
  return response.ok ? response.json() : fail(response)
}

/** Gives up your seat. Idempotent: not in, it changes nothing. The owner can't. */
export async function withdraw(id: number): Promise<SignupEventDetails> {
  const response = await authFetch(`${BASE}/${id}/signups`, { method: 'DELETE' })
  return response.ok ? response.json() : fail(response)
}
