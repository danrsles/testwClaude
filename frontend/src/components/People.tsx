import { useState } from 'react'
import { usePeople } from '../hooks/usePeople'
import Avatar from './Avatar'

/**
 * Finding people to follow. With the search box empty it lists who you
 * already follow; typing searches every user by name. Following someone puts
 * their wants in your list on the Wants screen.
 */
export default function People() {
  const [query, setQuery] = useState('')
  const { people, loading, error, pendingId, toggleFollow } = usePeople(query)
  const searching = query.trim() !== ''

  const noun = people.length === 1 ? 'person' : 'people'
  const count = searching ? `${people.length} ${noun} found` : `You follow ${people.length} ${noun}`

  return (
    <>
      <h1 className="text-3xl font-semibold tracking-tight">People</h1>
      <p className="mt-1 mb-7 text-stone-500 dark:text-stone-400">
        Find people to follow. Their wants show up in your list.
      </p>

      <input
        type="search"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="Search by username"
        aria-label="Search by username"
        className="field mb-5 w-full px-3 py-2"
      />

      <div className="mb-3 flex items-baseline justify-between gap-3">
        <h2 className="text-lg font-medium">{searching ? 'Results' : 'You follow'}</h2>
        {/* Announced to screen readers as results arrive. */}
        <p role="status" className="text-sm text-stone-500 dark:text-stone-400">
          {loading ? '' : count}
        </p>
      </div>

      {error && (
        <p
          role="alert"
          className="mb-4 rounded-lg border border-red-600/50 bg-red-600/10 px-3 py-2.5 text-sm text-red-700 dark:text-red-400"
        >
          {error}
        </p>
      )}

      {loading ? (
        <p className="py-9 text-center text-stone-500 dark:text-stone-400">Loading...</p>
      ) : people.length === 0 ? (
        <p className="py-9 text-center text-stone-500 dark:text-stone-400">
          {searching
            ? `No one matches “${query.trim()}”.`
            : "You don't follow anyone yet. Search for someone above."}
        </p>
      ) : (
        <ul className="flex flex-col gap-2">
          {people.map((person) => (
            <li
              key={person.id}
              className="flex items-center gap-3 rounded-xl border border-stone-200 bg-white px-3.5 py-3 dark:border-stone-800 dark:bg-stone-900"
            >
              {/* Decorative: the username beside it names the person. */}
              <Avatar src={person.imageUrl} alt="" size={32} />
              <span className="min-w-0 flex-1 truncate">{person.username}</span>
              {person.following ? (
                <button
                  onClick={() => toggleFollow(person)}
                  disabled={pendingId === person.id}
                  aria-label={`Unfollow ${person.username}`}
                  className="btn px-3 py-1.5 text-sm"
                >
                  Unfollow
                </button>
              ) : (
                <button
                  onClick={() => toggleFollow(person)}
                  disabled={pendingId === person.id}
                  aria-label={`Follow ${person.username}`}
                  className="cursor-pointer rounded-lg bg-emerald-700 px-3 py-1.5 text-sm font-medium text-white hover:bg-emerald-800 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  Follow
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
