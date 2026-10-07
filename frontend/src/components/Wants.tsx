import { useState } from 'react'
import type { SubmitEvent } from 'react'
import { DEFAULT_USER_ID } from '../currentUser'
import { useWants } from '../hooks/useWants'
import { CATEGORIES } from '../types/wants'
import type { Category, Want } from '../types/wants'

const TAG: Record<Category, string> = {
  FOOD: 'bg-food',
  MOVIE: 'bg-movie',
  GAME: 'bg-game',
  TRIP: 'bg-trip',
}

/**
 * The whole wants screen: the add form, the category filter, and the list with
 * each row editable in place. Server state comes from useWants; everything held
 * here is UI state belonging to this screen.
 */
export default function Wants() {
  const [filter, setFilter] = useState<Category | ''>('')
  const { wants, loading, error, add, update, remove } = useWants(filter)

  const [message, setMessage] = useState('')
  const [category, setCategory] = useState<Category>('FOOD')
  const [editingId, setEditingId] = useState<number | null>(null)
  const [draft, setDraft] = useState('')

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    const text = message.trim()
    if (!text) return
    // Only clear the input if the API accepted it, so a failure keeps the text.
    if (await add(text, category, DEFAULT_USER_ID)) setMessage('')
  }

  async function saveEdit(want: Want) {
    const text = draft.trim()
    if (!text) return
    // Leave the row open on failure so the edit is not silently lost.
    if (await update(want.id, text, want.category, want.user.id)) setEditingId(null)
  }

  return (
    <>
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
        <button
          type="submit"
          disabled={!message.trim()}
          className="cursor-pointer rounded-lg bg-emerald-700 px-5 py-2 font-medium text-white hover:bg-emerald-800 disabled:cursor-not-allowed disabled:opacity-50"
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
        <p className="mb-4 rounded-lg border border-red-600/50 bg-red-600/10 px-3 py-2.5 text-sm text-red-700 dark:text-red-400">
          {error}
        </p>
      )}

      {loading ? (
        <p className="py-9 text-center text-stone-500 dark:text-stone-400">Loading...</p>
      ) : wants.length === 0 ? (
        <p className="py-9 text-center text-stone-500 dark:text-stone-400">Nothing here yet.</p>
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
                <input
                  value={draft}
                  autoFocus
                  onChange={(e) => setDraft(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') saveEdit(want)
                    if (e.key === 'Escape') setEditingId(null)
                  }}
                  aria-label="Edit want"
                  className="field flex-1 basis-40 px-2 py-1"
                />
              ) : (
                <span className="flex-1 basis-40">{want.message}</span>
              )}

              <span className="text-sm text-stone-500 dark:text-stone-400">
                {want.user.username}
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
                ) : (
                  <>
                    <select
                      value={want.category}
                      onChange={(e) =>
                        update(want.id, want.message, e.target.value as Category, want.user.id)
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
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
