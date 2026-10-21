import { NavLink } from 'react-router'
import { useNotifications } from '../hooks/useNotifications'

/**
 * The bell in the header: a link to /notifications with a count of unread
 * ones. Mounted on every signed-in screen, so its hook is what keeps polling.
 */
export default function NotificationsBell() {
  const { unread } = useNotifications()
  // The badge is decorative; the link's name carries the count instead.
  const label = unread === 0 ? 'Notifications' : `Notifications, ${unread} unread`

  return (
    <NavLink
      to="/notifications"
      aria-label={label}
      title={label}
      className={({ isActive }) =>
        `relative rounded-full p-1.5 ${
          isActive
            ? 'text-emerald-700 dark:text-emerald-400'
            : 'text-stone-500 hover:text-stone-900 dark:text-stone-400 dark:hover:text-stone-100'
        }`
      }
    >
      <svg aria-hidden="true" viewBox="0 0 24 24" className="size-6 fill-none stroke-current stroke-[1.75]">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.94 1.94 0 0 0 3.4 0"
        />
      </svg>
      {unread > 0 && (
        <span
          aria-hidden="true"
          className="absolute -top-0.5 -right-0.5 min-w-5 rounded-full bg-red-600 px-1 text-center text-xs leading-5 font-medium text-white"
        >
          {unread > 9 ? '9+' : unread}
        </span>
      )}
    </NavLink>
  )
}
