import { useState } from 'react'
import type { SubmitEvent } from 'react'
import { useMe } from '../hooks/useMe'
import Avatar from './Avatar'

interface Draft {
  nickname: string
  s3Url: string
}

/**
 * The signed-in user's profile page. Shows how you appear, with Edit swapping
 * to an inline form for the two things you set yourself: nickname and avatar.
 * Username and email come from your Clerk account and change in its account
 * menu, so they are shown here but not editable. Server state comes from
 * useMe; the draft and whether the form is open are UI state held here.
 */
export default function UserProfile() {
  const { me, loading, error, update } = useMe()
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState<Draft>({ nickname: '', s3Url: '' })

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    const nickname = draft.nickname.trim()
    const s3Url = draft.s3Url.trim()
    // Close the form only if the API accepted it, so a failure keeps the text.
    if (await update({ nickname: nickname || null, s3Url: s3Url || null })) setEditing(false)
  }

  function startEditing() {
    if (!me) return
    setDraft({ nickname: me.nickname ?? '', s3Url: me.s3Url ?? '' })
    setEditing(true)
  }

  const shownName = me?.nickname ?? me?.username ?? ''

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
      ) : me ? (
        <section className="rounded-xl border border-stone-200 bg-white p-5 dark:border-stone-800 dark:bg-stone-900">
          {!editing ? (
            <div className="flex flex-wrap items-center gap-5">
              <Avatar src={me.s3Url} alt={`${shownName}'s profile picture`} size={96} />
              <div className="min-w-48 flex-1">
                <h2 className="text-2xl font-semibold">{shownName}</h2>
                {me.email && (
                  <p className="break-all text-stone-600 dark:text-stone-300">{me.email}</p>
                )}
                <p className="text-sm text-stone-500 dark:text-stone-400">@{me.username}</p>
                {!me.nickname && (
                  <p className="mt-1 text-sm text-stone-500 dark:text-stone-400">
                    Add a nickname to choose how others see you.
                  </p>
                )}
              </div>
              <button onClick={startEditing} className="btn px-3 py-1.5 text-sm">
                Edit
              </button>
            </div>
          ) : (
            <form onSubmit={submit} className="flex flex-col gap-4">
              <div className="flex items-center gap-4">
                <Avatar src={draft.s3Url.trim() || null} alt="Profile picture preview" size={64} />
                <h2 className="text-lg font-semibold">Edit profile</h2>
              </div>

              <div className="flex flex-col gap-1 text-sm">
                <label className="flex flex-col gap-1 font-medium">
                  Nickname (optional)
                  <input
                    value={draft.nickname}
                    onChange={(e) => setDraft({ ...draft, nickname: e.target.value })}
                    maxLength={100}
                    placeholder={me.username}
                    aria-describedby="nickname-hint"
                    className="field px-3 py-2 font-normal"
                  />
                </label>
                <span id="nickname-hint" className="text-stone-500 dark:text-stone-400">
                  Leave blank to show your username.
                </span>
              </div>

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

              <p className="text-sm text-stone-500 dark:text-stone-400">
                Your username and email come from your account. Change them from the account menu
                in the top right.
              </p>

              <div className="flex gap-2">
                <button
                  type="submit"
                  className="cursor-pointer rounded-lg bg-emerald-700 px-5 py-2 font-medium text-white hover:bg-emerald-800"
                >
                  Save
                </button>
                <button type="button" onClick={() => setEditing(false)} className="btn px-4 py-2">
                  Cancel
                </button>
              </div>
            </form>
          )}
        </section>
      ) : null}
    </>
  )
}
