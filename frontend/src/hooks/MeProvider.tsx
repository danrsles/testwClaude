import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { getMe } from '../api/me'
import type { User } from '../types/wants'
import { MeContext } from './useMe'

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : 'Request failed'
}

/**
 * Owns the signed-in user as it lives on the server and shares it through
 * useMe. Rendered once, inside the sign-in gate in App, so it loads only when
 * there is a session.
 */
export default function MeProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    getMe()
      .then((user) => {
        if (!cancelled) setMe(user)
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(messageOf(e))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const state = useMemo(() => ({ me, loading, error }), [me, loading, error])

  return <MeContext value={state}>{children}</MeContext>
}
