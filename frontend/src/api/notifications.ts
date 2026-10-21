import type { Notification } from '../types/notifications'
import { authFetch, fail } from './http'

/** Your 50 newest notifications, newest first, read or not. */
export async function listNotifications(): Promise<Notification[]> {
  const response = await authFetch('/api/notifications')
  return response.ok ? response.json() : fail(response)
}

/** Marks yours read up to and including an id: the newest you were shown. */
export async function markNotificationsRead(upTo: number): Promise<void> {
  const response = await authFetch('/api/notifications/read', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ upTo }),
  })
  if (!response.ok) await fail(response)
}
