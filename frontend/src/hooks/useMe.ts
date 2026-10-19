import { createContext, useContext } from 'react'
import type { User } from '../types/wants'

export interface MeState {
  /** Null while loading, or if loading failed. */
  me: User | null
  loading: boolean
  error: string | null
}

export const MeContext = createContext<MeState | null>(null)

/**
 * The signed-in user, shared by every component under MeProvider: one
 * GET /me for the whole app. Read-only: everything in it comes from Clerk and
 * changes in Clerk's account menu.
 */
export function useMe(): MeState {
  const state = useContext(MeContext)
  if (!state) throw new Error('useMe must be used inside <MeProvider>')
  return state
}
