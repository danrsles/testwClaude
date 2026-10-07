import { CATEGORIES } from '../types/wants'
import type { Category } from '../types/wants'

// Keyed by Category so adding one to CATEGORIES fails the type check until it
// is described here too.
const CATEGORY_INFO: Record<Category, { swatch: string; description: string }> = {
  FOOD: { swatch: 'bg-food', description: 'Restaurants, dishes and recipes to try.' },
  MOVIE: { swatch: 'bg-movie', description: 'Films and shows to watch.' },
  GAME: { swatch: 'bg-game', description: 'Video and board games to play.' },
  TRIP: { swatch: 'bg-trip', description: 'Places to visit.' },
}

const STACK = [
  { name: 'API', detail: 'Spring Boot 3 on Java 21, with MySQL and Flyway migrations.' },
  { name: 'Frontend', detail: 'React and TypeScript, built with Vite and styled with Tailwind CSS.' },
]

/**
 * A static page about the app: what it is for, the categories a want can
 * belong to, and what it is built with. Nothing here comes from the server.
 */
export default function Info() {
  return (
    <>
      <h1 className="text-3xl font-semibold tracking-tight">About Wants</h1>
      <p className="mt-1 mb-7 text-stone-500 dark:text-stone-400">
        A shared list of things worth doing, eating, playing and visiting. Add a want, give it a
        category, and edit or delete it later.
      </p>

      <section aria-labelledby="categories-heading" className="mb-8">
        <h2 id="categories-heading" className="mb-3 text-xl font-semibold">
          Categories
        </h2>
        <ul className="flex flex-col gap-2">
          {CATEGORIES.map((c) => (
            <li
              key={c}
              className="flex items-center gap-3 rounded-xl border border-stone-200 bg-white px-3.5 py-3 dark:border-stone-800 dark:bg-stone-900"
            >
              <span
                className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-semibold tracking-wider text-white ${CATEGORY_INFO[c].swatch}`}
              >
                {c}
              </span>
              <span>{CATEGORY_INFO[c].description}</span>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="stack-heading">
        <h2 id="stack-heading" className="mb-3 text-xl font-semibold">
          How it is built
        </h2>
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2">
          {STACK.map((s) => (
            <div key={s.name} className="contents">
              <dt className="font-medium">{s.name}</dt>
              <dd className="text-stone-600 dark:text-stone-300">{s.detail}</dd>
            </div>
          ))}
        </dl>
      </section>
    </>
  )
}
