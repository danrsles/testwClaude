import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useState } from 'react'
import {
  addMember,
  createCircle,
  deleteCircle,
  listCircles,
  listMembers,
  removeMember,
  renameCircle,
} from '../api/circles'
import { listFollowers } from '../api/followers'
import { HttpError } from '../api/http'
import type { Circle, UserSummary } from '../types/circles'
import { useMe } from './useMe'

function messageOf(e: unknown): string {
  return e instanceof Error ? e.message : 'Request failed'
}

/** What a change was trying to do, so a refusal can say why. */
type Action = 'name' | 'delete' | 'leave' | 'add' | 'remove'

/** A 400 for each action. The screen checks names first, so for `name` it is a rule the screen missed. */
const REFUSED: Record<Action, string> = {
  name: "That name can't be used. Try another.",
  delete: "Couldn't delete it.",
  leave: "Couldn't leave it.",
  add: "Couldn't add them. They may have stopped following you.",
  remove: "Couldn't remove them.",
}

/**
 * A refusal in words. The API's error bodies are Spring's raw JSON with no
 * message, so its status is all there is to go on. Anything that never got
 * a response, such as a network failure, keeps its own message.
 */
function explain(e: unknown, action: Action): string {
  if (!(e instanceof HttpError)) return messageOf(e)
  if (e.status === 404) return 'This circle no longer exists.'
  if (e.status === 403) return "Only the circle's owner can do that."
  if (e.status === 400) return REFUSED[action]
  return 'Something went wrong. Try again.'
}

/** True when the API says the circle is gone: deleted, left or never visible to you. */
function isMissing(e: unknown): boolean {
  return e instanceof HttpError && e.status === 404
}

/** For delete and leave, where a circle that is already gone is the outcome you asked for. */
function unlessMissing(e: unknown) {
  if (!isMissing(e)) throw e
}

/** A failed load in words, for the same reason. */
function loadProblem(e: unknown): string | null {
  if (!e) return null
  if (!(e instanceof HttpError)) return messageOf(e)
  if (e.status === 404) return 'This circle no longer exists.'
  return "Couldn't load this. Reload the page to try again."
}

/** Which rows have a change in flight, and the last change that failed, to show in its row. */
export interface RowChanges {
  pending: ReadonlySet<number>
  failed: { id: number; message: string } | null
}

/**
 * Per-row bookkeeping for changes, as in usePeople: a set of ids rather than
 * one, so a second row's request finishing never unblocks the first.
 * `run` resolves true when the API accepted the change, false when it refused,
 * leaving the refusal in `failed` for that row. On a 404 it also calls
 * `onMissing`, so the list can drop a circle deleted elsewhere.
 */
function useRowChanges(onMissing: () => void) {
  const [pending, setPending] = useState<ReadonlySet<number>>(new Set())
  const [failed, setFailed] = useState<RowChanges['failed']>(null)

  const run = useCallback(
    async (id: number, action: Action, change: () => Promise<void>) => {
      setPending((ids) => new Set(ids).add(id))
      try {
        await change()
        setFailed((f) => (f?.id === id ? null : f))
        return true
      } catch (e) {
        setFailed({ id, message: explain(e, action) })
        if (isMissing(e)) onMissing()
        return false
      } finally {
        setPending((ids) => {
          const rest = new Set(ids)
          rest.delete(id)
          return rest
        })
      }
    },
    [onMissing],
  )

  return { pending, failed, run }
}

/** Row id for the create form, which has no circle yet. */
export const NEW_CIRCLE = 0

export interface UseCircles extends RowChanges {
  /** Null until the first load finishes. */
  circles: Circle[] | null
  /** Why loading failed, if it did. */
  error: string | null
  /** Each resolves true on success, false if the API refused; the refusal is in `failed` for that row. */
  create: (name: string) => Promise<boolean>
  rename: (circle: Circle, name: string) => Promise<boolean>
  remove: (circle: Circle) => Promise<boolean>
  leave: (circle: Circle) => Promise<boolean>
}

/**
 * The circles you own or are in, on TanStack Query, keyed by your user id so
 * no one sees the previous person's after signing in as someone else.
 * Disabled until /me has loaded.
 *
 * Each change writes its result into the cache at once, so the row updates
 * without waiting for the refetch that follows.
 */
export function useCircles(): UseCircles {
  const { me } = useMe()
  const queryClient = useQueryClient()
  const refetchCircles = useCallback(
    () => void queryClient.invalidateQueries({ queryKey: ['circles', me?.id], exact: true }),
    [queryClient, me?.id],
  )
  const key = ['circles', me?.id]
  const query = useQuery({ queryKey: key, queryFn: listCircles, enabled: me !== null })
  const { pending, failed, run } = useRowChanges(refetchCircles)

  const write = useCallback(
    async (update: (all: Circle[]) => Circle[]) => {
      queryClient.setQueryData<Circle[]>(['circles', me?.id], (all) => all && update(all))
      await queryClient.invalidateQueries({ queryKey: ['circles', me?.id], exact: true })
    },
    [queryClient, me?.id],
  )

  const create = useCallback(
    (name: string) =>
      run(NEW_CIRCLE, 'name', async () => {
        const circle = await createCircle(name)
        await write((all) => [...all, circle])
      }),
    [run, write],
  )

  const rename = useCallback(
    (circle: Circle, name: string) =>
      run(circle.id, 'name', async () => {
        const renamed = await renameCircle(circle.id, name)
        await write((all) => all.map((c) => (c.id === circle.id ? renamed : c)))
      }),
    [run, write],
  )

  const remove = useCallback(
    (circle: Circle) =>
      run(circle.id, 'delete', async () => {
        await deleteCircle(circle.id).catch(unlessMissing)
        await write((all) => all.filter((c) => c.id !== circle.id))
      }),
    [run, write],
  )

  const leave = useCallback(
    (circle: Circle) =>
      run(circle.id, 'leave', async () => {
        if (!me) throw new Error('Not signed in')
        await removeMember(circle.id, me.id).catch(unlessMissing)
        await write((all) => all.filter((c) => c.id !== circle.id))
      }),
    [run, write, me],
  )

  return {
    circles: query.data ?? null,
    error: loadProblem(query.error),
    pending,
    failed,
    create,
    rename,
    remove,
    leave,
  }
}

export interface UseCircleMembers extends RowChanges {
  /** Null until the first load finishes. The owner is not among them. */
  members: UserSummary[] | null
  error: string | null
  /** Owner only: adds a follower, or removes a member. Rows are keyed by the person's id. */
  setMember: (person: UserSummary, inCircle: boolean) => Promise<boolean>
}

/** One circle's members, for its owner or its members. */
export function useCircleMembers(circleId: number): UseCircleMembers {
  const { me } = useMe()
  const queryClient = useQueryClient()
  const refetchCircles = useCallback(
    () => void queryClient.invalidateQueries({ queryKey: ['circles', me?.id], exact: true }),
    [queryClient, me?.id],
  )
  const key = ['circles', me?.id, circleId, 'members']
  const query = useQuery({ queryKey: key, queryFn: () => listMembers(circleId), enabled: me !== null })
  const { pending, failed, run } = useRowChanges(refetchCircles)

  const setMember = useCallback(
    (person: UserSummary, inCircle: boolean) =>
      run(person.id, inCircle ? 'add' : 'remove', async () => {
        await (inCircle ? addMember(circleId, person.id) : removeMember(circleId, person.id))
        const membersKey = ['circles', me?.id, circleId, 'members']
        queryClient.setQueryData<UserSummary[]>(
          membersKey,
          (all) =>
            all &&
            (inCircle
              ? all.some((m) => m.id === person.id)
                ? all
                : [...all, person]
              : all.filter((m) => m.id !== person.id)),
        )
        await queryClient.invalidateQueries({ queryKey: membersKey })
      }),
    [run, queryClient, me?.id, circleId],
  )

  return { members: query.data ?? null, error: loadProblem(query.error), pending, failed, setMember }
}

/** Everyone who follows you: the people you can add to your circles. */
export function useFollowers(): { followers: UserSummary[] | null; error: string | null } {
  const { me } = useMe()
  const query = useQuery({ queryKey: ['followers', me?.id], queryFn: listFollowers, enabled: me !== null })
  return { followers: query.data ?? null, error: loadProblem(query.error) }
}
