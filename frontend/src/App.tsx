import { NavLink, Route, Routes } from 'react-router'
import Info from './components/Info'
import ProfileBadge from './components/ProfileBadge'
import UserProfile from './components/UserProfile'
import Wants from './components/Wants'
import { DEFAULT_USER_ID } from './currentUser'

const NAV = [
  { to: '/', label: 'Wants' },
  { to: '/info', label: 'Info' },
  { to: '/profile', label: 'Profile' },
]

/**
 * The app shell: the page frame, the header and the routes, and nothing else.
 * Each screen is its own component, so a new screen means a new component and a
 * Route here rather than changes to an existing one. The BrowserRouter itself
 * lives in main.tsx, so tests can render App inside a MemoryRouter instead.
 */
export default function App() {
  return (
    <main className="mx-auto max-w-3xl px-4 pt-6 pb-16">
      <header className="mb-8 flex flex-wrap items-center justify-between gap-3">
        <nav aria-label="Main" className="flex gap-4 text-sm">
          {NAV.map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              end
              className={({ isActive }) =>
                isActive
                  ? 'font-medium text-emerald-700 dark:text-emerald-400'
                  : 'text-stone-500 hover:text-stone-900 dark:text-stone-400 dark:hover:text-stone-100'
              }
            >
              {n.label}
            </NavLink>
          ))}
        </nav>
        <ProfileBadge userId={DEFAULT_USER_ID} />
      </header>

      <Routes>
        <Route path="/" element={<Wants />} />
        <Route path="/info" element={<Info />} />
        <Route path="/profile" element={<UserProfile userId={DEFAULT_USER_ID} />} />
      </Routes>
    </main>
  )
}
