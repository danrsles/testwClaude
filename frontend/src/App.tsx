import { Show, UserButton } from '@clerk/react'
import { NavLink, Route, Routes } from 'react-router'
import Info from './components/Info'
import Notifications from './components/Notifications'
import NotificationsBell from './components/NotificationsBell'
import People from './components/People'
import SignInScreen from './components/SignInScreen'
import Wants from './components/Wants'
import MeProvider from './hooks/MeProvider'

const NAV = [
  { to: '/', label: 'Wants' },
  { to: '/people', label: 'People' },
  { to: '/info', label: 'Info' },
]

/**
 * The app shell: the sign-in gate, the page frame, the header and the routes,
 * and nothing else. Every API route needs a login, so nothing below the gate
 * renders, or fetches, until Clerk has a session. Show renders nothing while
 * Clerk is still loading.
 *
 * Each screen is its own component, so a new screen means a new component and
 * a Route here rather than changes to an existing one. ClerkProvider, the
 * QueryClientProvider and the BrowserRouter live in main.tsx, so tests can
 * render App inside a MemoryRouter and a fresh query client with Clerk stubbed.
 */
export default function App() {
  return (
    <>
      <Show when="signed-out">
        <SignInScreen />
      </Show>
      <Show when="signed-in">
        {/* One GET /me, which the wants list uses to tell your wants from others'. */}
        <MeProvider>
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
              <div className="flex items-center gap-3">
                {/* Polls for notifications on every screen. */}
                <NotificationsBell />
                {/* Clerk's account menu: picture, username, email, sign out. */}
                <UserButton />
              </div>
            </header>

            <Routes>
              <Route path="/" element={<Wants />} />
              <Route path="/people" element={<People />} />
              <Route path="/notifications" element={<Notifications />} />
              <Route path="/info" element={<Info />} />
            </Routes>
          </main>
        </MeProvider>
      </Show>
    </>
  )
}
