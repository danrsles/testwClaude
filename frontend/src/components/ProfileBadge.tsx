import { Link } from 'react-router'
import { useUserProfile } from '../hooks/useUserProfile'
import Avatar from './Avatar'

/**
 * The signed-in user's nickname and picture, shown in the app header and
 * linking to their profile page. Renders nothing while loading or if the
 * request fails, so a profile problem never breaks the shell; the profile page
 * shows the error.
 */
export default function ProfileBadge() {
  const { profile, loading, error } = useUserProfile()

  if (loading || error) return null

  return (
    <Link
      to="/profile"
      className="flex items-center gap-2 rounded-full py-1 pr-3 pl-1 text-sm hover:bg-stone-200/60 dark:hover:bg-stone-800"
    >
      <Avatar src={profile?.s3Url ?? null} alt="" size={28} />
      <span className="font-medium">{profile ? profile.nickname : 'Set up profile'}</span>
    </Link>
  )
}
