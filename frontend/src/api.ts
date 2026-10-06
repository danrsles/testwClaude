import type { Category, Want, WantRequest } from './types'

const BASE = '/api/wants'

/** Turns a failed response into an Error carrying something readable. */
async function fail(response: Response): Promise<never> {
  const body = await response.text()
  throw new Error(body || `${response.status} ${response.statusText}`)
}

export async function listWants(category?: Category | ''): Promise<Want[]> {
  const url = category ? `${BASE}?category=${category}` : BASE
  const response = await fetch(url)
  return response.ok ? response.json() : fail(response)
}

export async function createWant(body: WantRequest): Promise<Want> {
  const response = await fetch(BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return response.ok ? response.json() : fail(response)
}

export async function updateWant(id: number, body: WantRequest): Promise<Want> {
  const response = await fetch(`${BASE}/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return response.ok ? response.json() : fail(response)
}

export async function deleteWant(id: number): Promise<void> {
  const response = await fetch(`${BASE}/${id}`, { method: 'DELETE' })
  if (!response.ok) await fail(response)
}
