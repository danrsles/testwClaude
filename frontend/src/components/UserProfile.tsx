import { useState } from 'react'
import type { SubmitEvent } from 'react'
import { useUserProfile } from '../hooks/useUserProfile'
import type { UpdateUserProfileRequest } from '../types/userProfiles'
import Avatar from './Avatar'

interface Props {
  /** The user whose profile this page shows and edits. */
  userId: number
}

interface Draft {
  email: string
  nickname: string
  s3Url: string
}

const EMPTY: Draft = { email: '', nickname: '', s3Url: '' }

function toRequest(draft: Draft): UpdateUserProfileRequest {
  const s3Url = draft.s3Url.trim()
  return { email: draft.email.trim(), nickname: draft.nickname.trim(), s3Url: s3Url || null }
}

/**
 * One user's profile page. Shows the profile with Edit and Delete, swaps to an
 * inline form while editing, and offers a create form when the user has no
 * profile yet. Server state comes from useUserProfile; the draft and whether
 * the form is open are UI state held here.
 */
export default function UserProfile({ userId }: Props) {
  const { profile, loading, error, create, update, remove } = useUserProfile(userId)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState<Draft>(EMPTY)

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    const fields = toRequest(draft)
    if (!fields.email || !fields.nickname) return
    // Close the form only if the API accepted it, so a failure keeps the text.
    const ok = profile ? await update(fields) : await create(fields)
    if (ok) {
      setEditing(false)
      setDraft(EMPTY)
    }
  }

  function startEditing() {
    if (!profile) return
    setDraft({ email: profile.email, nickname: profile.nickname, s3Url: profile.s3Url ?? '' })
    setEditing(true)
  }

  async function confirmDelete() {
    if (!profile) return
    if (!window.confirm(`Delete the profile for ${profile.nickname}?`)) return
    await remove()
  }

  const showForm = !loading && (editing || !profile)
  const canSubmit = draft.email.trim() !== '' && draft.nickname.trim() !== ''

  return (
    <>
      <h1 className="text-3xl font-semibold tracking-tight">Profile</h1>
      <p className="mt-1 mb-7 text-stone-500 dark:text-stone-400">
        How you appear across the app.
      </p>

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
      ) : (
        <section className="rounded-xl border border-stone-200 bg-white p-5 dark:border-stone-800 dark:bg-stone-900">
          {profile && !editing && (
            <div className="flex flex-wrap items-center gap-5">
              <Avatar src={profile.s3Url} alt={`${profile.nickname}'s profile picture`} size={96} />
              <div className="min-w-0 flex-1">
                <h2 className="text-2xl font-semibold">{profile.nickname}</h2>
                <p className="text-stone-600 dark:text-stone-300">{profile.email}</p>
                <p className="text-sm text-stone-500 dark:text-stone-400">
                  @{profile.user.username}
                </p>
              </div>
              <div className="flex gap-1.5">
                <button onClick={startEditing} className="btn px-3 py-1.5 text-sm">
                  Edit
                </button>
                <button
                  onClick={confirmDelete}
                  className="btn px-3 py-1.5 text-sm text-red-700 hover:border-red-600 dark:text-red-400"
                >
                  Delete
                </button>
              </div>
            </div>
          )}

          {showForm && (
            <form onSubmit={submit} className="flex flex-col gap-4">
              <div className="flex items-center gap-4">
                <Avatar
                  src={draft.s3Url.trim() || null}
                  alt="Profile picture preview"
                  size={64}
                />
                <h2 className="text-lg font-semibold">
                  {profile ? 'Edit profile' : 'Create your profile'}
                </h2>
              </div>

              <label className="flex flex-col gap-1 text-sm font-medium">
                Nickname
                <input
                  value={draft.nickname}
                  onChange={(e) => setDraft({ ...draft, nickname: e.target.value })}
                  maxLength={100}
                  required
                  className="field px-3 py-2 font-normal"
                />
              </label>

              <label className="flex flex-col gap-1 text-sm font-medium">
                Email
                <input
                  type="email"
                  value={draft.email}
                  onChange={(e) => setDraft({ ...draft, email: e.target.value })}
                  required
                  className="field px-3 py-2 font-normal"
                />
              </label>

              <div className="flex flex-col gap-1 text-sm">
                {/* The hint sits outside the label so it describes the field rather than naming it. */}
                <label className="flex flex-col gap-1 font-medium">
                  Avatar URL (optional)
                  <input
                    type="url"
                    value={draft.s3Url}
                    onChange={(e) => setDraft({ ...draft, s3Url: e.target.value })}
                    placeholder="https://..."
                    aria-describedby="avatar-url-hint"
                    className="field px-3 py-2 font-normal"
                  />
                </label>
                <span id="avatar-url-hint" className="text-stone-500 dark:text-stone-400">
                  Leave blank to use the placeholder until photo uploads arrive.
                </span>
              </div>

              <div className="flex gap-2">
                <button
                  type="submit"
                  disabled={!canSubmit}
                  className="cursor-pointer rounded-lg bg-emerald-700 px-5 py-2 font-medium text-white hover:bg-emerald-800 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {profile ? 'Save' : 'Create profile'}
                </button>
                {profile && (
                  <button
                    type="button"
                    onClick={() => setEditing(false)}
                    className="btn px-4 py-2"
                  >
                    Cancel
                  </button>
                )}
              </div>
            </form>
          )}
        </section>
      )}
    </>
  )
}
