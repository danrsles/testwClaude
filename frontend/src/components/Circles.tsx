import { useId, useRef, useState } from 'react'
import type { FormEvent, KeyboardEvent } from 'react'
import { NEW_CIRCLE, useCircleMembers, useCircles, useFollowers } from '../hooks/useCircles'
import type { UseCircles } from '../hooks/useCircles'
import { useMe } from '../hooks/useMe'
import type { Circle, UserSummary } from '../types/circles'
import Avatar from './Avatar'

/** The API's limit on a circle's name. */
const MAX_NAME = 40

/**
 * Why a name can't be used, or null if it can. The API checks the same rules
 * (400), but its refusal is a raw error body, so the screen says why first.
 * Only circles you own count as duplicates; `except` is the one being renamed.
 */
function nameProblem(name: string, owned: Circle[], except?: Circle): string | null {
  const text = name.trim()
  if (!text) return 'Type a name.'
  if (text.length > MAX_NAME) return `Use ${MAX_NAME} characters or fewer.`
  if (text.toLowerCase() === 'general') return '"General" means all your followers. Pick another name.'
  const same = owned.find((c) => c.id !== except?.id && c.name.toLowerCase() === text.toLowerCase())
  return same ? `You already have a circle called ${same.name}.` : null
}

const ALERT = 'basis-full text-sm break-words text-red-700 dark:text-red-400'
const HINT = 'text-sm text-stone-500 dark:text-stone-400'

/**
 * Circles: groups, like a group chat. Lists the ones you own and the ones
 * you're in. You create, rename and delete your own, and add your followers
 * to them; in someone else's you see who is in it, and can leave.
 */
export default function Circles() {
  const { me } = useMe()
  const circles = useCircles()
  const [name, setName] = useState('')
  const reasonId = useId()
  const nameField = useRef<HTMLInputElement>(null)
  // Each row's name button by circle id, so focus can move to a neighbour when a row goes.
  const nameButtons = useRef(new Map<number, HTMLButtonElement>())

  const owned = (circles.circles ?? []).filter((c) => c.owner.id === me?.id)
  const problem = nameProblem(name, owned)
  const busy = circles.pending.has(NEW_CIRCLE)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (problem || busy) return
    if (await circles.create(name.trim())) setName('')
  }

  /** After a row is deleted or left: focus the circle that took its place, or the name field when none is left. */
  function focusAfterGone(next: number | null) {
    const target = next === null ? undefined : nameButtons.current.get(next)
    ;(target ?? nameField.current)?.focus()
  }

  return (
    <>
      <title>Circles · Wants</title>
      <h1 className="text-3xl font-semibold tracking-tight">Circles</h1>
      <p className="mt-1 mb-7 text-stone-500 dark:text-stone-400">
        Groups of people, like a group chat. Add your followers to the circles you create.
      </p>

      <form onSubmit={submit} className="mb-7 flex flex-wrap items-center gap-2">
        <input
          ref={nameField}
          value={name}
          onChange={(e) => setName(e.target.value)}
          aria-label="New circle"
          placeholder="New circle"
          aria-describedby={problem ? reasonId : undefined}
          className="field min-w-0 flex-1 px-3 py-2"
        />
        <button
          type="submit"
          disabled={problem !== null}
          aria-disabled={busy}
          aria-describedby={problem ? reasonId : undefined}
          className="btn-primary px-4 py-2"
        >
          Add
        </button>
        {problem && (
          <p id={reasonId} className={`basis-full ${HINT}`}>
            {problem}
          </p>
        )}
        {circles.failed?.id === NEW_CIRCLE && (
          <p role="alert" className={ALERT}>
            {circles.failed.message}
          </p>
        )}
      </form>

      {circles.error && (
        <p
          role="alert"
          className="mb-4 rounded-lg border border-red-600/50 bg-red-600/10 px-3 py-2.5 text-sm text-red-700 dark:text-red-400"
        >
          {circles.error}
        </p>
      )}

      {circles.circles === null ? (
        !circles.error && <p className="py-9 text-center text-stone-500 dark:text-stone-400">Loading...</p>
      ) : circles.circles.length === 0 ? (
        <p className="py-9 text-center text-stone-500 dark:text-stone-400">
          You aren't in any circles yet. Create one above.
        </p>
      ) : (
        <ul aria-label="Your circles" className="flex flex-col gap-2">
          {circles.circles.map((circle, i, all) => (
            <CircleRow
              key={circle.id}
              circle={circle}
              mine={circle.owner.id === me?.id}
              owned={owned}
              circles={circles}
              nameRef={(el) => {
                if (el) nameButtons.current.set(circle.id, el)
                else nameButtons.current.delete(circle.id)
              }}
              onGone={() => focusAfterGone((all[i + 1] ?? all[i - 1])?.id ?? null)}
            />
          ))}
        </ul>
      )}
    </>
  )
}

/** One circle: its name, which opens it, who owns it, and what you can do with it. */
function CircleRow({
  circle,
  mine,
  owned,
  circles,
  nameRef,
  onGone,
}: {
  circle: Circle
  mine: boolean
  owned: Circle[]
  circles: UseCircles
  nameRef: (el: HTMLButtonElement | null) => void
  /** Called once the circle is deleted or left, after its row has gone. */
  onGone: () => void
}) {
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState<string | null>(null)
  const renameButton = useRef<HTMLButtonElement>(null)
  const reasonId = useId()
  const panelId = useId()
  const busy = circles.pending.has(circle.id)
  const problem = draft === null ? null : nameProblem(draft, owned, circle)

  function stopEditing() {
    setDraft(null)
    // The field that had focus unmounts; hand focus back to Rename once it is back.
    requestAnimationFrame(() => renameButton.current?.focus())
  }

  async function save(e: FormEvent) {
    e.preventDefault()
    if (draft === null || problem || busy) return
    if (draft.trim() === circle.name) return stopEditing()
    if (await circles.rename(circle, draft.trim())) stopEditing()
  }

  function onKeyDown(e: KeyboardEvent) {
    if (e.key === 'Escape') stopEditing()
  }

  async function remove() {
    if (busy || !window.confirm(`Delete ${circle.name}? It goes for everyone in it.`)) return
    if (await circles.remove(circle)) onGone()
  }

  async function leave() {
    if (busy || !window.confirm(`Leave ${circle.name}? Only ${circle.owner.username} can add you back.`)) return
    if (await circles.leave(circle)) onGone()
  }

  return (
    <li className="flex flex-wrap items-center gap-3 rounded-xl border border-stone-200 bg-white px-3.5 py-3 dark:border-stone-800 dark:bg-stone-900">
      {draft === null ? (
        <>
          <button
            ref={nameRef}
            onClick={() => setOpen((o) => !o)}
            aria-expanded={open}
            aria-controls={open ? panelId : undefined}
            className="flex min-w-0 cursor-pointer items-center gap-1.5 text-left font-medium hover:underline"
          >
            {/* Shows that the name opens the circle; aria-expanded says the same to screen readers. */}
            <span aria-hidden="true" className={`inline-block transition-transform ${open ? 'rotate-90' : ''}`}>
              ›
            </span>
            <span className="truncate">{circle.name}</span>
          </button>
          <span className={`flex-1 ${HINT}`}>{mine ? 'yours' : `by ${circle.owner.username}`}</span>
          {mine ? (
            <>
              <button
                ref={renameButton}
                onClick={() => !busy && setDraft(circle.name)}
                aria-disabled={busy}
                aria-label={`Rename ${circle.name}`}
                className="btn px-3 py-1.5 text-sm"
              >
                Rename
              </button>
              <button
                onClick={remove}
                aria-disabled={busy}
                aria-label={`Delete ${circle.name}`}
                className="btn px-3 py-1.5 text-sm hover:border-red-600"
              >
                Delete
              </button>
            </>
          ) : (
            <button
              onClick={leave}
              aria-disabled={busy}
              aria-label={`Leave ${circle.name}`}
              className="btn px-3 py-1.5 text-sm"
            >
              Leave
            </button>
          )}
        </>
      ) : (
        <form onSubmit={save} onKeyDown={onKeyDown} className="flex flex-1 flex-wrap items-center gap-2">
          <input
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            aria-label={`New name for ${circle.name}`}
            aria-describedby={problem ? reasonId : undefined}
            autoFocus
            className="field min-w-0 flex-1 px-3 py-1.5"
          />
          <button
            type="submit"
            disabled={problem !== null}
            aria-disabled={busy}
            aria-describedby={problem ? reasonId : undefined}
            className="btn-primary px-3 py-1.5 text-sm"
          >
            Save
          </button>
          <button type="button" onClick={stopEditing} className="btn px-3 py-1.5 text-sm">
            Cancel
          </button>
          {problem && (
            <p id={reasonId} className={`basis-full ${HINT}`}>
              {problem}
            </p>
          )}
        </form>
      )}
      {circles.failed?.id === circle.id && (
        <p role="alert" className={ALERT}>
          {circles.failed.message}
        </p>
      )}
      {open && (
        <div id={panelId} className="min-w-0 basis-full border-t border-stone-200 pt-3 dark:border-stone-800">
          {mine ? <YourFollowers circle={circle} /> : <Members circle={circle} />}
        </div>
      )}
    </li>
  )
}

/** In a circle you're in: its owner and members, read-only. */
function Members({ circle }: { circle: Circle }) {
  const { members, error } = useCircleMembers(circle.id)

  if (error) {
    return (
      <p role="alert" className={ALERT}>
        {error}
      </p>
    )
  }
  if (members === null) return <p className={HINT}>Loading...</p>

  return (
    <ul aria-label={`In ${circle.name}`} className="flex flex-col gap-2">
      {[circle.owner, ...members].map((person, i) => (
        <li key={person.id} className="flex items-center gap-2">
          {/* Decorative: the username beside it names the person. */}
          <Avatar src={person.imageUrl} alt="" size={24} />
          <span>{person.username}</span>
          {i === 0 && <span className={HINT}>owner</span>}
        </li>
      ))}
    </ul>
  )
}

/** In a circle you own: your followers, each with a box for whether they're in it, filtered by name on screen. */
function YourFollowers({ circle }: { circle: Circle }) {
  const { followers, error: followersError } = useFollowers()
  const { members, error: membersError, pending, failed, setMember } = useCircleMembers(circle.id)
  const [query, setQuery] = useState('')

  const error = followersError ?? membersError
  if (error) {
    return (
      <p role="alert" className={ALERT}>
        {error}
      </p>
    )
  }
  if (followers === null || members === null) return <p className={HINT}>Loading...</p>
  if (followers.length === 0) {
    return <p className={HINT}>No one follows you yet. Only your followers can be added to your circles.</p>
  }

  const text = query.trim().toLowerCase()
  const shown = followers.filter((f) => f.username.toLowerCase().includes(text))
  const memberIds = new Set(members.map((m) => m.id))

  function toggle(person: UserSummary) {
    if (pending.has(person.id)) return
    void setMember(person, !memberIds.has(person.id))
  }

  return (
    <>
      <input
        type="search"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="Search your followers"
        aria-label="Search your followers"
        className="field mb-3 w-full px-3 py-1.5"
      />
      {shown.length === 0 ? (
        <p className={HINT}>No follower matches “{query.trim()}”.</p>
      ) : (
        <ul aria-label={`Your followers, for ${circle.name}`} className="flex flex-col gap-2">
          {shown.map((person) => (
            <li key={person.id} className="flex flex-wrap items-center gap-2">
              {/* Decorative: the username beside it names the person. */}
              <Avatar src={person.imageUrl} alt="" size={24} />
              <span className="min-w-0 flex-1 truncate">{person.username}</span>
              {/* aria-disabled, so focus stays on the box while its request runs. */}
              <label className="flex items-center gap-1.5 text-sm whitespace-nowrap text-stone-600 dark:text-stone-400">
                <input
                  type="checkbox"
                  checked={memberIds.has(person.id)}
                  onChange={() => toggle(person)}
                  aria-disabled={pending.has(person.id)}
                  aria-label={`${person.username} in ${circle.name}`}
                  className="accent-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 dark:accent-emerald-500"
                />
                In {circle.name}
              </label>
              {failed?.id === person.id && (
                <p role="alert" className={ALERT}>
                  {failed.message}
                </p>
              )}
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
