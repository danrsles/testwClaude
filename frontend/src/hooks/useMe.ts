import { createContext, useContext } from 'react'
import type { UpdateMeRequest, User } from '../types/wants'

export interface MeState {
  /** Null while loading, or if loading failed. */
  me: User | null
  loading: boolean
  error: string | null
  /** Resolves true on success, false if the API rejected it. */
  update: (fields: UpdateMeRequest) => Promise<boolean>
}

export const MeContext = createContext<MeState | null>(null)

/**
 * The signed-in user, shared by every component under MeProvider: one
 * GET /me for the whole app, and a nickname saved on /profile shows in the
 * header straight away.
 */
export function useMe(): MeState {
  const state = useContext(MeContext)
  if (!state) throw new Error('useMe must be used inside <MeProvider>')
  return state
}
