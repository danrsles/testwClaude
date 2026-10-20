import { useState } from 'react'
import { usePeople } from '../hooks/usePeople'
import type { Person } from '../types/people'
import Avatar from './Avatar'

/**
 * Finding people to follow. With the search box empty it lists who you
 * already follow; typing searches every user by name. Following someone puts
 * their wants in your list on the Wants screen.
 */
export default function People() {
  const [query, setQuery] = useState('')
  // What the last follow or unfollow did, until the search changes.
  const [notice, setNotice] = useState<string | null>(null)
  const { people, shownFor, loading, error, pendingId, failed, toggleFollow } = usePeople(query)
  // Headings and counts describe the rows on screen, which lag the box while
  // a search is in flight.
  const searching = shownFor !== null && shownFor !== ''

  const noun = people?.length === 1 ? 'person' : 'people'
  const count =
    people === null ? '' : searching ? `${people.length} ${noun} found` : `You follow ${people.length} ${noun}`

  async function toggle(person: Person) {
    if (!(await toggleFollow(person))) return
    setNotice(
      person.following
        ? `Unfollowed ${person.username}. Their wants are no longer in your list.`
        : `Now following ${person.username}. Their wants are in your list.`,
    )
  }

  return (
    <>
      <title>People · Wants</title>
      <h1 className="text-3xl font-semibold tracking-tight">People</h1>
      <p className="mt-1 mb-7 text-stone-500 dark:text-stone-400">
        Find people to follow. Their wants show up in your list.
      </p>

      <input
        type="search"
        value={query}
        onChange={(e) => {
          setQuery(e.target.value)
          setNotice(null)
        }}
        placeholder="Search by username"
        aria-label="Search by username"
        className="field mb-5 w-full px-3 py-2"
      />

      <div className="mb-3 flex items-baseline justify-between gap-3">
        <h2 className="text-lg font-medium">{searching ? 'Results' : 'You follow'}</h2>
        {/* Announced to screen readers as results arrive. */}
        <p role="status" className="text-sm text-stone-500 dark:text-stone-400">
          {count}
        </p>
      </div>

      {/* A live region of its own, so the count above is not replaced. */}
      <p role="status" className="mb-3 text-sm text-emerald-700 empty:hidden dark:text-emerald-400">
        {notice}
      </p>

      {error && (
        <p
          role="alert"
          className="mb-4 rounded-lg border border-red-600/50 bg-red-600/10 px-3 py-2.5 text-sm text-red-700 dark:text-red-400"
        >
          {error}
        </p>
      )}

      {people === null ? (
        <p className="py-9 text-center text-stone-500 dark:text-stone-400">Loading...</p>
      ) : people.length === 0 ? (
        // The alert above already says why the list is empty.
        !error && (
          <p className="py-9 text-center text-stone-500 dark:text-stone-400">
            {searching
              ? `No one matches “${shownFor}”.`
              : "You don't follow anyone yet. Search for someone above."}
          </p>
        )
      ) : (
        // Earlier rows stay, dimmed, while the next search loads, rather than
        // blanking the list on every keystroke.
        <ul
          aria-busy={loading}
          className={`flex flex-col gap-2 transition-opacity motion-reduce:transition-none ${loading ? 'opacity-60' : ''}`}
        >
          {people.map((person) => {
            const pending = pendingId === person.id
            return (
              <li
                key={person.id}
                className="flex flex-wrap items-center gap-3 rounded-xl border border-stone-200 bg-white px-3.5 py-3 dark:border-stone-800 dark:bg-stone-900"
              >
                {/* Decorative: the username beside it names the person. */}
                <Avatar src={person.imageUrl} alt="" size={32} />
                <span className="min-w-0 flex-1 truncate">{person.username}</span>
                {/*
                  One button whose label swaps, so focus stays on it after a
                  toggle. aria-disabled rather than disabled while the request
                  runs: a disabled button drops focus to the top of the page.
                */}
                <button
                  onClick={() => !pending && toggle(person)}
                  aria-disabled={pending}
                  aria-label={`${person.following ? 'Unfollow' : 'Follow'} ${person.username}`}
                  className={`min-w-20 px-3 py-1.5 text-sm ${
                    // A neutral hover, so Unfollow never shows Follow's green.
                    person.following ? 'btn hover:border-stone-500' : 'btn-primary'
                  }`}
                >
                  {person.following ? 'Unfollow' : 'Follow'}
                </button>
                {failed?.id === person.id && (
                  <p role="alert" className="basis-full text-sm text-red-700 dark:text-red-400">
                    {failed.message}
                  </p>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </>
  )
}
