import { useState } from 'react'
import { Link } from 'react-router'
import type { SubmitEvent } from 'react'
import { useMe } from '../hooks/useMe'
import { useOnPictureChange } from '../hooks/useOnPictureChange'
import { useWants } from '../hooks/useWants'
import { CATEGORIES } from '../types/wants'
import type { Category, Want } from '../types/wants'
import Avatar from './Avatar'

const TAG: Record<Category, string> = {
  FOOD: 'bg-food',
  MOVIE: 'bg-movie',
  GAME: 'bg-game',
  TRIP: 'bg-trip',
}

/** A whole number of at least `min`, or null for anything else in the field. */
function seats(text: string, min: number): number | null {
  const n = Number(text)
  return Number.isInteger(n) && n >= min ? n : null
}

/**
 * The whole wants screen: the add form, the category filter, and the list,
 * where the signed-in user's own rows are editable in place and everyone
 * else's can be signed up for. Server state comes from useWants; everything
 * held here is UI state belonging to this screen.
 */
export default function Wants() {
  const [filter, setFilter] = useState<Category | ''>('')
  const { wants, loading, error, refresh, add, update, remove, signUp, withdraw } = useWants(filter)
  // Your picture beside your wants comes from the API, so reload the list as
  // soon as you change it in Clerk's account menu.
  useOnPictureChange(refresh)
  // Only the owner may change a want. The API enforces that; this just hides
  // controls that would fail.
  const { me } = useMe()
  const isMine = (want: Want) => me !== null && want.userId === me.id

  const [message, setMessage] = useState('')
  const [category, setCategory] = useState<Category>('FOOD')
  // Kept as typed, so the field can be cleared on the way to a new number.
  const [capacity, setCapacity] = useState('1')
  const [editingId, setEditingId] = useState<number | null>(null)
  const [draft, setDraft] = useState('')
  const [draftCapacity, setDraftCapacity] = useState('')

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    const text = message.trim()
    const count = seats(capacity, 1)
    if (!text || count === null) return
    // Only clear the inputs if the API accepted them, so a failure keeps them.
    if (await add(text, category, count)) {
      setMessage('')
      setCapacity('1')
    }
  }

  async function saveEdit(want: Want) {
    const text = draft.trim()
    // The API refuses to lower the capacity, so the field goes no lower either.
    const count = seats(draftCapacity, want.capacity)
    if (!text || count === null) return
    // Send the capacity only when it changed; left out, it stays as it is.
    const raised = count === want.capacity ? undefined : count
    // Leave the row open on failure so the edit is not silently lost.
    if (await update(want.id, text, want.category, raised)) setEditingId(null)
  }

  return (
    <>
      <title>Wants</title>
      <h1 className="text-3xl font-semibold tracking-tight">Wants</h1>
      <p className="mt-1 mb-7 text-stone-500 dark:text-stone-400">
        Things worth doing, eating, playing and visiting.
      </p>

      <form onSubmit={submit} className="mb-5 flex flex-wrap gap-2">
        <input
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          placeholder="What do you want?"
          aria-label="Want description"
          className="field min-w-0 flex-1 px-3 py-2"
        />
        <select
          value={category}
          onChange={(e) => setCategory(e.target.value as Category)}
          aria-label="Category"
          className="field px-3 py-2"
        >
          {CATEGORIES.map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>
        <input
          type="number"
          min={1}
          value={capacity}
          onChange={(e) => setCapacity(e.target.value)}
          aria-label="Capacity"
          title="How many people, you included"
          className="field w-20 px-3 py-2"
        />
        <button
          type="submit"
          disabled={!message.trim() || seats(capacity, 1) === null}
          className="btn-primary px-5 py-2"
        >
          Add
        </button>
      </form>

      <nav className="mb-5 flex flex-wrap gap-1.5">
        {(['', ...CATEGORIES] as const).map((c) => (
          <button
            key={c || 'all'}
            onClick={() => setFilter(c)}
            className={`cursor-pointer rounded-lg border px-3 py-1.5 text-sm ${
              filter === c
                ? 'border-emerald-600 font-medium text-stone-900 dark:text-stone-100'
                : 'border-stone-300 text-stone-500 dark:border-stone-700 dark:text-stone-400'
            }`}
          >
            {c || 'All'}
          </button>
        ))}
      </nav>

      {error && (
        <p
          role="alert"
          className="mb-4 rounded-lg border border-red-600/50 bg-red-600/10 px-3 py-2.5 text-sm text-red-700 dark:text-red-400">
          {error}
        </p>
      )}

      {loading ? (
        <p className="py-9 text-center text-stone-500 dark:text-stone-400">Loading...</p>
      ) : wants.length === 0 ? (
        <p className="py-9 text-center text-stone-500 dark:text-stone-400">
          Nothing here yet. Add a want above, or{' '}
          <Link to="/people" className="text-emerald-700 underline dark:text-emerald-400">
            find people to follow
          </Link>
          .
        </p>
      ) : (
        <ul className="flex flex-col gap-2">
          {wants.map((want) => (
            <li
              key={want.id}
              className="flex flex-wrap items-center gap-3 rounded-xl border border-stone-200 bg-white px-3.5 py-3 dark:border-stone-800 dark:bg-stone-900"
            >
              <span
                className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-semibold tracking-wider text-white ${TAG[want.category]}`}
              >
                {want.category}
              </span>

              {editingId === want.id ? (
                <span
                  className="flex flex-1 basis-40 gap-1.5"
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') saveEdit(want)
                    if (e.key === 'Escape') setEditingId(null)
                  }}
                >
                  <input
                    value={draft}
                    autoFocus
                    onChange={(e) => setDraft(e.target.value)}
                    aria-label="Edit want"
                    className="field min-w-0 flex-1 px-2 py-1"
                  />
                  <input
                    type="number"
                    min={want.capacity}
                    value={draftCapacity}
                    onChange={(e) => setDraftCapacity(e.target.value)}
                    aria-label="Edit capacity"
                    className="field w-16 px-2 py-1"
                  />
                </span>
              ) : (
                <span className="flex-1 basis-40">{want.message}</span>
              )}

              {/* The picture is decorative: the username beside it names the owner. */}
              <span className="flex items-center gap-1.5 text-sm text-stone-500 dark:text-stone-400">
                <Avatar src={want.imageUrl} alt="" size={24} />
                {want.username}
              </span>

              <span className="text-sm text-stone-500 tabular-nums dark:text-stone-400">
                {want.seatsTaken} / {want.capacity} seats
              </span>

              <span className="ml-auto flex gap-1.5">
                {editingId === want.id ? (
                  <>
                    <button onClick={() => saveEdit(want)} className="btn px-2.5 py-1.5 text-sm">
                      Save
                    </button>
                    <button
                      onClick={() => setEditingId(null)}
                      className="btn px-2.5 py-1.5 text-sm"
                    >
                      Cancel
                    </button>
                  </>
                ) : isMine(want) ? (
                  <>
                    <select
                      value={want.category}
                      onChange={(e) =>
                        update(want.id, want.message, e.target.value as Category)
                      }
                      aria-label="Change category"
                      className="field px-2 py-1.5 text-sm"
                    >
                      {CATEGORIES.map((c) => (
                        <option key={c} value={c}>
                          {c}
                        </option>
                      ))}
                    </select>
                    <button
                      onClick={() => {
                        setEditingId(want.id)
                        setDraft(want.message)
                        setDraftCapacity(String(want.capacity))
                      }}
                      className="btn px-2.5 py-1.5 text-sm"
                    >
                      Edit
                    </button>
                    <button
                      onClick={() => remove(want.id)}
                      className="btn px-2.5 py-1.5 text-sm text-red-700 hover:border-red-600 dark:text-red-400"
                    >
                      Delete
                    </button>
                  </>
                ) : want.signedUp ? (
                  <button onClick={() => withdraw(want.id)} className="btn px-2.5 py-1.5 text-sm">
                    Withdraw
                  </button>
                ) : (
                  <>
                    {want.seatsTaken >= want.capacity && (
                      <span className="self-center text-sm font-medium text-stone-500 dark:text-stone-400">
                        Full
                      </span>
                    )}
                    <button
                      onClick={() => signUp(want.id)}
                      disabled={want.seatsTaken >= want.capacity}
                      className="btn-primary px-2.5 py-1.5 text-sm"
                    >
                      Sign up
                    </button>
                  </>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
