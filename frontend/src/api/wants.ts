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

export async function deleteWant(id: number): Promise<void> {
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
