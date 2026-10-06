import { useEffect, useState } from 'react'
import { createWant, deleteWant, listWants, updateWant } from './api'
import { CATEGORIES } from './types'
import type { Category, Want } from './types'

// There is no user API yet, so every want is attributed to the seeded user.
const DEFAULT_USER_ID = 1

const field =
  'rounded-lg border border-stone-300 bg-white px-3 py-2 text-stone-900 ' +
  'focus:border-emerald-600 focus:outline-none focus:ring-1 focus:ring-emerald-600 ' +
  'dark:border-stone-700 dark:bg-stone-900 dark:text-stone-100'

const button =
  'cursor-pointer rounded-lg border border-stone-300 bg-white px-2.5 py-1.5 text-sm ' +
  'hover:border-emerald-600 disabled:cursor-not-allowed disabled:opacity-50 ' +
  'dark:border-stone-700 dark:bg-stone-900'

const TAG: Record<Category, string> = {
  FOOD: 'bg-food',
  MOVIE: 'bg-movie',
  GAME: 'bg-game',
  TRIP: 'bg-trip',
}

export default function App() {
  const [wants, setWants] = useState<Want[]>([])
  const [filter, setFilter] = useState<Category | ''>('')
  const [message, setMessage] = useState('')
  const [category, setCategory] = useState<Category>('FOOD')
  const [editingId, setEditingId] = useState<number | null>(null)
  const [draft, setDraft] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  async function refresh(next: Category | '' = filter) {
    setLoading(true)
    try {
      setWants(await listWants(next))
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Request failed')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    refresh(filter)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filter])

  async function run(action: () => Promise<unknown>) {
    try {
      await action()
      setError(null)
      await refresh()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Request failed')
    }
  }

  function add(event: React.FormEvent) {
    event.preventDefault()
    if (!message.trim()) return
    run(async () => {
      await createWant({ message: message.trim(), category, userId: DEFAULT_USER_ID })
      setMessage('')
    })
  }

  function saveEdit(want: Want) {
    run(async () => {
      await updateWant(want.id, {
        message: draft.trim(),
        category: want.category,
        userId: want.user.id,
      })
      setEditingId(null)
    })
  }

  function changeCategory(want: Want, next: Category) {
    run(() =>
      updateWant(want.id, { message: want.message, category: next, userId: want.user.id }),
    )
  }

  return (
    <main className="mx-auto max-w-3xl px-4 pt-12 pb-16">
      <h1 className="text-3xl font-semibold tracking-tight">Wants</h1>
      <p className="mt-1 mb-7 text-stone-500 dark:text-stone-400">
        Things worth doing, eating, playing and visiting.
      </p>

      <form onSubmit={add} className="mb-5 flex flex-wrap gap-2">
        <input
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          placeholder="What do you want?"
          aria-label="Want description"
          className={`${field} min-w-0 flex-1`}
        />
        <select
          value={category}
          onChange={(e) => setCategory(e.target.value as Category)}
          aria-label="Category"
          className={field}
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
                  className={`${field} flex-1 basis-40 px-2 py-1`}
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
                    <button onClick={() => saveEdit(want)} className={button}>
                      Save
                    </button>
                    <button onClick={() => setEditingId(null)} className={button}>
                      Cancel
                    </button>
                  </>
                ) : (
                  <>
                    <select
                      value={want.category}
                      onChange={(e) => changeCategory(want, e.target.value as Category)}
                      aria-label="Change category"
                      className={`${field} px-2 py-1.5 text-sm`}
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
                      className={button}
                    >
                      Edit
                    </button>
                    <button
                      onClick={() => run(() => deleteWant(want.id))}
                      className={`${button} text-red-700 hover:border-red-600 dark:text-red-400`}
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
    </main>
  )
}
