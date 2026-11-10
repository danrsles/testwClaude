import { Show, UserButton } from '@clerk/react'
import { NavLink, Route, Routes } from 'react-router'
import Circles from './components/Circles'
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
  { to: '/circles', label: 'Circles' },
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
          {/* Sticky and translucent, so the page scrolls under it. */}
          <header className="sticky top-0 z-30 border-b border-stone-200/60 bg-stone-50/75 backdrop-blur-md dark:border-stone-800/60 dark:bg-stone-950/70">
            <div className="mx-auto flex max-w-6xl items-center gap-3 px-4 py-2.5 sm:px-6 lg:px-8">
              {/* The app's name. Decorative: the page title already says it, and the nav names every screen. */}
              <span
                aria-hidden="true"
                className="hidden bg-gradient-to-r from-violet-600 to-emerald-700 bg-clip-text text-lg font-bold tracking-tight text-transparent sm:inline dark:from-violet-400 dark:to-emerald-400"
              >
                Yuwant
              </span>
              {/* Scrolls sideways on a phone rather than wrapping under the icons. */}
              <nav
                aria-label="Main"
                className="-mx-1 -my-1 flex min-w-0 flex-1 gap-1 overflow-x-auto px-1 py-1 text-sm"
              >
                {NAV.map((n) => (
                  <NavLink
                    key={n.to}
                    to={n.to}
                    end
                    className={({ isActive }) =>
                      `shrink-0 rounded-full px-3 py-1.5 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-violet-500 ${
                        isActive
                          ? 'bg-stone-900 font-medium text-white dark:bg-stone-100 dark:text-stone-900'
                          : 'text-stone-600 hover:bg-stone-200/60 hover:text-stone-900 dark:text-stone-400 dark:hover:bg-stone-800 dark:hover:text-stone-100'
                      }`
                    }
                  >
                    {n.label}
                  </NavLink>
                ))}
              </nav>
              <div className="flex shrink-0 items-center gap-3">
                {/* Polls for notifications on every screen. */}
                <NotificationsBell />
                {/* Clerk's account menu: picture, username, email, sign out. */}
                <UserButton
                  appearance={{
                    elements: {
                      userButtonTrigger:
                        'focus-visible:outline-2! focus-visible:outline-offset-2! focus-visible:outline-violet-500! focus-visible:shadow-none!',
                    },
                  }}
                />
              </div>
            </div>
          </header>

          {/* Wide for the wants cards; screens with prose or short rows cap themselves at max-w-3xl. */}
          <main className="mx-auto max-w-6xl px-4 pt-8 pb-16 sm:px-6 lg:px-8">
            <Routes>
              <Route path="/" element={<Wants />} />
              <Route path="/people" element={<People />} />
              <Route path="/circles" element={<Circles />} />
              <Route path="/notifications" element={<Notifications />} />
              <Route path="/info" element={<Info />} />
            </Routes>
          </main>
        </MeProvider>
      </Show>
    </>
  )
}
