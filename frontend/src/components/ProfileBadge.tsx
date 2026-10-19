import { Link } from 'react-router'
import { useMe } from '../hooks/useMe'
import Avatar from './Avatar'

/**
 * The signed-in user's nickname and picture, shown in the app header and
 * linking to their profile page. Renders nothing while loading or if the
 * request fails, so a problem never breaks the shell; the profile page shows
 * the error.
 */
export default function ProfileBadge() {
  const { me, loading, error } = useMe()

  if (loading || error || !me) return null

  return (
    <Link
      to="/profile"
      className="flex items-center gap-2 rounded-full py-1 pr-3 pl-1 text-sm hover:bg-stone-200/60 dark:hover:bg-stone-800"
    >
      <Avatar src={me.s3Url} alt="" size={28} />
      <span className="font-medium">{me.nickname ?? 'Set up profile'}</span>
    </Link>
  )
}
