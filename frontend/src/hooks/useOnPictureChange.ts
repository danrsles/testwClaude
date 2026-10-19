import { getToken, useUser } from '@clerk/react'
import { useEffect, useRef } from 'react'

/**
 * Calls onChange as soon as the signed-in user changes their picture in
 * Clerk's account menu, after getting a fresh session token that carries it.
 *
 * The API learns a picture only from the token, and Clerk otherwise reissues
 * the token about once a minute, so without this a change would reach the
 * wants list only on a later reload. Clerk tells the page itself straight
 * away (that is how its own button updates), so this listens for that.
 *
 * Only while the app is open: a change made elsewhere arrives with the next
 * request as usual.
 */
export function useOnPictureChange(onChange: () => void) {
  const { user } = useUser()
  const imageUrl = user?.imageUrl
  // The picture the page started with, or last reacted to. Undefined until
  // Clerk has loaded the user, so loading it is not mistaken for a change.
  const known = useRef(imageUrl)

  useEffect(() => {
    if (imageUrl === undefined || imageUrl === known.current) return
    const first = known.current === undefined
    known.current = imageUrl
    if (first) return

    let cancelled = false
    // skipCache mints a new token now; Clerk keeps it, so the next API call
    // (authFetch's own getToken) sends it and the API copies the new picture.
    getToken({ skipCache: true })
      .then(() => {
        if (!cancelled) onChange()
      })
      .catch(() => {
        // No fresh token: the change still arrives with a later request.
      })
    return () => {
      cancelled = true
    }
  }, [imageUrl, onChange])
}
