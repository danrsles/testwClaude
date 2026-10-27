import { useId, useReducer, useRef, useState } from 'react'
import { Link } from 'react-router'
import type { SubmitEvent } from 'react'
import { useMe } from '../hooks/useMe'
import { useOnPictureChange } from '../hooks/useOnPictureChange'
import { useWants } from '../hooks/useWants'
import type { DeadlineChange, SeatsChange } from '../hooks/useWants'
import { CATEGORIES } from '../types/wants'
import type { Category, Want } from '../types/wants'
import Avatar from './Avatar'
import DeadlinePicker from './DeadlinePicker'
import { NO_DEADLINE, deadlineOf, draftOf, sameDraft } from './deadline'
import type { DeadlineDraft } from './deadline'

const TAG: Record<Category, string> = {
  FOOD: 'bg-food',
  MOVIE: 'bg-movie',
  GAME: 'bg-game',
  TRIP: 'bg-trip',
}

/** A whole number of at least `min`, or null for anything else in the field. */
function seats(text: string, min: number): number | null {
  const n = Number(text)
  return Number.isInteger(n) && n >= min ? n : null
}

/** An open invite has no limit, so it is never full. */
function isFull(want: Want): boolean {
  return want.capacity !== null && want.seatsTaken >= want.capacity
}

/** Closed by its deadline rather than by hand: the API says CLOSED, and the deadline is behind us. */
function closedAtDeadline(want: Want): boolean {
  return want.status === 'CLOSED' && want.expiresAt !== null && new Date(want.expiresAt).getTime() <= Date.now()
}

const closesAt = new Intl.DateTimeFormat(undefined, {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  hour: 'numeric',
  minute: '2-digit',
})

/** What Reopen does without a new deadline: keeps one still ahead, or leaves none. */
function keepLabel(want: Want): string {
  return want.expiresAt !== null && new Date(want.expiresAt).getTime() > Date.now()
    ? `Keep ${closesAt.format(new Date(want.expiresAt))}`
    : 'No deadline'
}

/**
 * The whole wants screen: the add form, the category filter, and the list,
 * where the signed-in user's own rows can be edited, closed, reopened,
 * started, ended and cancelled, and everyone else's active ones signed up
 * for. Server state comes from useWants; everything held here is UI state
 * belonging to this screen.
 */
export default function Wants() {
  const [filter, setFilter] = useState<Category | ''>('')
  const { wants, loading, error, refresh, add, update, close, reopen, start, end, cancel, signUp, withdraw } =
    useWants(filter)
  // Your picture beside your wants comes from the API, so reload the list as
  // soon as you change it in Clerk's account menu.
  useOnPictureChange(refresh)
  // Only the owner may change a want. The API enforces that; this just hides
  // controls that would fail.
  const { me } = useMe()
  const isMine = (want: Want) => me !== null && want.userId === me.id

  const [message, setMessage] = useState('')
  const [category, setCategory] = useState<Category>('FOOD')
  // Kept as typed, so the field can be cleared on the way to a new number.
  const [capacity, setCapacity] = useState('1')
  const [openInvite, setOpenInvite] = useState(false)
  const [deadline, setDeadline] = useState<DeadlineDraft>(NO_DEADLINE)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [draft, setDraft] = useState('')
  const [draftCapacity, setDraftCapacity] = useState('')
  const [draftOpen, setDraftOpen] = useState(false)
  const [draftDeadline, setDraftDeadline] = useState<DeadlineDraft>(NO_DEADLINE)
  // Reopen asks for an optional new deadline first, in place of the row's buttons.
  const [reopeningId, setReopeningId] = useState<number | null>(null)
  const [reopenDeadline, setReopenDeadline] = useState<DeadlineDraft>(NO_DEADLINE)
  // Leaving edit mode unmounts the field that had focus, so the row's Edit
  // button takes it back when it mounts again.
  const refocusId = useRef<number | null>(null)
  const openInviteHint = useId()
  // Nothing re-renders as the clock passes a deadline, so a pick can go stale
  // with its button still enabled. A refused submit re-renders, and the picker
  // then says why.
  const [, recheck] = useReducer((n: number) => n + 1, 0)

  // The row's Edit or Reopen button, whichever mounts, takes focus back.
  const refocus = (id: number) => (button: HTMLButtonElement | null) => {
    if (button && refocusId.current === id) {
      refocusId.current = null
      button.focus()
    }
  }

  // A want that closed while its row was open, such as at its deadline, can't
  // be edited, so its row leaves edit mode once the reload says so.
  const isEditing = (want: Want) => editingId === want.id && want.status !== 'CLOSED' && want.status !== 'ENDED'

  function stopEditing(id: number) {
    refocusId.current = id
    setEditingId(null)
  }

  function stopReopening(id: number) {
    refocusId.current = id
    setReopeningId(null)
  }

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    const text = message.trim()
    const count = openInvite ? null : seats(capacity, 1)
    const closes = deadlineOf(deadline)
    if (closes === 'invalid') return recheck()
    if (!text || (!openInvite && count === null)) return
    // Only clear the inputs if the API accepted them, so a failure keeps them.
    if (await add(text, category, count, closes?.toISOString())) {
      setMessage('')
      setCapacity('1')
      setOpenInvite(false)
      setDeadline(NO_DEADLINE)
    }
  }

  async function saveEdit(want: Want) {
    const text = draft.trim()
    if (!text) return
    // Compared as fields, not moments, so seconds the picker can't show don't
    // count as a change, and an untouched deadline is never sent.
    let deadlineChange: DeadlineChange | undefined
    if (!sameDraft(draftDeadline, draftOf(want.expiresAt))) {
      const closes = deadlineOf(draftDeadline)
      if (closes === 'invalid') return recheck()
      deadlineChange = closes === null ? { noDeadline: true } : { expiresAt: closes.toISOString() }
    }
    let change: SeatsChange | undefined
    if (draftOpen !== (want.capacity === null)) {
      // Opening, or limiting to the people already in. Never with a capacity:
      // a bigger limit takes a second save.
      change = { openInvite: draftOpen }
    } else if (want.capacity !== null) {
      // The API refuses to lower the capacity, so the field goes no lower either.
      const count = seats(draftCapacity, want.capacity)
      if (count === null) return
      // Send the capacity only when it changed; left out, it stays as it is.
      if (count !== want.capacity) change = { capacity: count }
    }
    // Set first: if the save is refused because the want closed, the reload
    // ends edit mode and Reopen, mounting, takes focus. If it is still open,
    // the row stays open so the edit is not silently lost.
    refocusId.current = want.id
    if (await update(want.id, text, want.category, change, deadlineChange)) stopEditing(want.id)
  }

  async function confirmReopen(want: Want) {
    const closes = deadlineOf(reopenDeadline)
    if (closes === 'invalid') return recheck()
    if (await reopen(want.id, closes?.toISOString())) stopReopening(want.id)
  }

  // Unlike closing, which Reopen undoes, cancelling is final and tells the
  // whole lineup, so it asks first. Not "Cancel …?": the dialog's own Cancel
  // button would then mean "don't".
  function confirmCancel(want: Want) {
    const others = want.seatsTaken - 1
    const told = others > 0 ? ` The ${others} other ${others === 1 ? 'person' : 'people'} signed up will be told.` : ''
    if (window.confirm(`Call off '${want.message}' for good?${told} This can't be undone.`)) {
      cancel(want.id)
    }
  }

  // Ending is final too: an ended want can't be started again.
  function confirmEnd(want: Want) {
    if (window.confirm(`End '${want.message}'? This can't be undone.`)) {
      end(want.id)
    }
  }

  return (
    <>
      <title>Wants</title>
      <h1 className="text-3xl font-semibold tracking-tight">Wants</h1>
      <p className="mt-1 mb-7 text-stone-500 dark:text-stone-400">Things worth doing, eating, playing and visiting.</p>

      <form onSubmit={submit} className="mb-5 flex flex-wrap gap-2">
        <input
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          placeholder="What do you want?"
          aria-label="Want description"
          className="field min-w-0 flex-1 basis-full px-3 py-2 sm:basis-48"
        />
        <select
          value={category}
          onChange={(e) => setCategory(e.target.value as Category)}
          aria-label="Category"
          className="field px-3 py-2"
        >
          {CATEGORIES.map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>
        <input
          type="number"
          min={1}
          // Empty while open, so a greyed-out number doesn't look like a limit.
          value={openInvite ? '' : capacity}
          placeholder={openInvite ? 'Any' : undefined}
          disabled={openInvite}
          onChange={(e) => setCapacity(e.target.value)}
          aria-label="Capacity"
          aria-describedby={openInvite ? openInviteHint : undefined}
          title="How many people, you included"
          className="field w-20 px-3 py-2 disabled:opacity-50"
        />
        <span id={openInviteHint} className="sr-only">
          No limit: anyone who can see it may join
        </span>
        <label className="flex items-center gap-1.5 text-sm" title="No limit on how many can join">
          <input
            type="checkbox"
            checked={openInvite}
            onChange={(e) => setOpenInvite(e.target.checked)}
            className="accent-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 dark:accent-emerald-500"
          />
          Open invite
        </label>
        <DeadlinePicker label="Deadline" value={deadline} onChange={setDeadline} />
        <button
          type="submit"
          disabled={
            !message.trim() || (!openInvite && seats(capacity, 1) === null) || deadlineOf(deadline) === 'invalid'
          }
          className="btn-primary px-5 py-2"
        >
          Add
        </button>
      </form>

      <nav className="mb-5 flex flex-wrap gap-1.5">
        {(['', ...CATEGORIES] as const).map((c) => (
          <button
            key={c || 'all'}
            onClick={() => setFilter(c)}
            className={`cursor-pointer rounded-lg border px-3 py-1.5 text-sm ${
              filter === c
                ? 'border-emerald-600 font-medium text-stone-900 dark:text-stone-100'
                : 'border-stone-300 text-stone-500 dark:border-stone-700 dark:text-stone-400'
            }`}
          >
            {c || 'All'}
          </button>
        ))}
      </nav>

      {error && (
        <p
          role="alert"
          className="mb-4 rounded-lg border border-red-600/50 bg-red-600/10 px-3 py-2.5 text-sm text-red-700 dark:text-red-400"
        >
          {error}
        </p>
      )}

      {/* Only the first load replaces the list, so a reload keeps rows, and focus, in place. */}
      {loading && wants.length === 0 ? (
        <p className="py-9 text-center text-stone-500 dark:text-stone-400">Loading...</p>
      ) : wants.length === 0 ? (
        <p className="py-9 text-center text-stone-500 dark:text-stone-400">
          Nothing here yet. Add a want above, or{' '}
          <Link to="/people" className="text-emerald-700 underline dark:text-emerald-400">
            find people to follow
          </Link>
          .
        </p>
      ) : (
        <ul className="flex flex-col gap-2">
          {wants.map((want) => (
            <li
              key={want.id}
              className="flex flex-wrap items-center gap-3 rounded-xl border border-stone-200 bg-white px-3.5 py-3 dark:border-stone-800 dark:bg-stone-900"
            >
              <span
                className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-semibold tracking-wider text-white ${TAG[want.category]}`}
              >
                {want.category}
              </span>

              {isEditing(want) ? (
                <span
                  className="flex min-w-0 flex-1 basis-40 flex-wrap gap-1.5"
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') saveEdit(want)
                    if (e.key === 'Escape') stopEditing(want.id)
                  }}
                >
                  <input
                    value={draft}
                    autoFocus
                    onChange={(e) => setDraft(e.target.value)}
                    aria-label="Edit want"
                    className="field min-w-0 flex-1 basis-full px-2 py-1 sm:basis-40"
                  />
                  {/* Disabled on an open want even once unticked: limiting it
                      takes exactly the lineup, shown here, and a bigger limit a second save. */}
                  <input
                    type="number"
                    min={want.capacity ?? undefined}
                    value={draftCapacity}
                    disabled={draftOpen || want.capacity === null}
                    onChange={(e) => setDraftCapacity(e.target.value)}
                    aria-label="Edit capacity"
                    className="field w-16 px-2 py-1 disabled:opacity-50"
                  />
                  <label className="flex items-center gap-1.5 text-sm whitespace-nowrap">
                    <input
                      type="checkbox"
                      checked={draftOpen}
                      onChange={(e) => setDraftOpen(e.target.checked)}
                      aria-label="Edit open invite"
                      className="accent-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 dark:accent-emerald-500"
                    />
                    Open invite
                  </label>
                  <DeadlinePicker label="Edit deadline" value={draftDeadline} onChange={setDraftDeadline} />
                </span>
              ) : (
                <span className="flex-1 basis-40">{want.message}</span>
              )}

              {/* The picture is decorative: the username beside it names the owner. */}
              <span className="flex items-center gap-1.5 text-sm text-stone-500 dark:text-stone-400">
                <Avatar src={want.imageUrl} alt="" size={24} />
                {want.username}
              </span>

              <span className="text-sm text-stone-500 tabular-nums dark:text-stone-400">
                {want.capacity === null ? `${want.seatsTaken} going` : `${want.seatsTaken} / ${want.capacity} seats`}
              </span>

              {/* Unlike the owner's, these pictures stand alone, so each is named by its alt text. */}
              <ul aria-label={`Going to ${want.message}`} className="flex -space-x-1.5">
                {want.lineup.map((person) => (
                  <li key={person.id} title={person.username}>
                    <Avatar src={person.imageUrl} alt={person.username} size={20} />
                  </li>
                ))}
              </ul>

              {/* Only while people can join; once closed, the pill below says why. */}
              {want.expiresAt !== null && want.status !== 'CLOSED' && want.status !== 'ENDED' && (
                <span className="text-sm text-stone-500 dark:text-stone-400">
                  Closes <time dateTime={want.expiresAt}>{closesAt.format(new Date(want.expiresAt))}</time>
                </span>
              )}

              {/* Wraps rather than spilling out of the card on narrow phones. */}
              <span className="ml-auto flex flex-wrap justify-end gap-1.5 whitespace-nowrap">
                {/* Filled and borderless like the category tag, so they read as a status, not a button. */}
                {/* Only while people can join: on a closed or ended want there is no one left to invite. */}
                {want.capacity === null && want.status !== 'CLOSED' && want.status !== 'ENDED' && (
                  <span className="self-center rounded-full bg-sky-100 px-2 py-0.5 text-xs font-semibold tracking-wider text-sky-800 dark:bg-sky-950 dark:text-sky-300">
                    Open invite
                  </span>
                )}
                {want.happening && (
                  <span className="self-center rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-semibold tracking-wider text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300">
                    Happening now
                  </span>
                )}
                {(want.status === 'CLOSED' || want.status === 'ENDED') && (
                  <span className="self-center rounded-full bg-stone-200 px-2 py-0.5 text-xs font-semibold tracking-wider text-stone-700 dark:bg-stone-800 dark:text-stone-300">
                    {want.status === 'ENDED' ? 'Ended' : closedAtDeadline(want) ? 'Closed at its deadline' : 'Closed'}
                  </span>
                )}
                {/* Every change to an ended want is a 409, the owner's included. */}
                {want.status === 'ENDED' ? null : reopeningId === want.id ? (
                  // Its own element, so React never reuses Confirm or Cancel as Start or Cancel want.
                  <span
                    className="flex flex-wrap justify-end gap-1.5"
                    onKeyDown={(e) => {
                      if (e.key === 'Escape') stopReopening(want.id)
                    }}
                  >
                    {/* Reopen can keep or replace a deadline, not remove one: Edit does that once it is open. */}
                    <DeadlinePicker
                      label="Reopen deadline"
                      noneLabel={keepLabel(want)}
                      value={reopenDeadline}
                      onChange={setReopenDeadline}
                      autoFocus
                    />
                    <button
                      onClick={() => confirmReopen(want)}
                      disabled={deadlineOf(reopenDeadline) === 'invalid'}
                      className="btn-primary px-2.5 py-1.5 text-sm"
                    >
                      Confirm
                    </button>
                    <button onClick={() => stopReopening(want.id)} className="btn px-2.5 py-1.5 text-sm">
                      Cancel
                    </button>
                  </span>
                ) : isEditing(want) ? (
                  <>
                    <button
                      onClick={() => saveEdit(want)}
                      disabled={
                        !sameDraft(draftDeadline, draftOf(want.expiresAt)) && deadlineOf(draftDeadline) === 'invalid'
                      }
                      className="btn px-2.5 py-1.5 text-sm"
                    >
                      Save
                    </button>
                    <button onClick={() => stopEditing(want.id)} className="btn px-2.5 py-1.5 text-sm">
                      Cancel
                    </button>
                  </>
                ) : isMine(want) ? (
                  <>
                    {/* A closed want can't be edited until it is reopened. Tested
                        as CLOSED, not "not ACTIVE", so an unknown status keeps the controls. */}
                    {want.status === 'CLOSED' ? (
                      <button
                        ref={refocus(want.id)}
                        onClick={() => {
                          setReopeningId(want.id)
                          setReopenDeadline(NO_DEADLINE)
                          // A row that closed mid-edit must not come back in edit mode once reopened.
                          setEditingId(null)
                        }}
                        className="btn px-2.5 py-1.5 text-sm"
                      >
                        Reopen
                      </button>
                    ) : (
                      <>
                        <select
                          value={want.category}
                          onChange={(e) => update(want.id, want.message, e.target.value as Category)}
                          aria-label="Change category"
                          className="field px-2 py-1.5 text-sm"
                        >
                          {CATEGORIES.map((c) => (
                            <option key={c} value={c}>
                              {c}
                            </option>
                          ))}
                        </select>
                        <button
                          ref={refocus(want.id)}
                          onClick={() => {
                            setEditingId(want.id)
                            setDraft(want.message)
                            setDraftCapacity(String(want.capacity ?? want.seatsTaken))
                            setDraftOpen(want.capacity === null)
                            setDraftDeadline(draftOf(want.expiresAt))
                          }}
                          className="btn px-2.5 py-1.5 text-sm"
                        >
                          Edit
                        </button>
                        <button onClick={() => close(want.id)} className="btn px-2.5 py-1.5 text-sm">
                          Close
                        </button>
                      </>
                    )}
                    {/* Starting doesn't change who can join, so it sits beside Close or Reopen. */}
                    {want.happening ? (
                      <button onClick={() => confirmEnd(want)} className="btn px-2.5 py-1.5 text-sm">
                        End
                      </button>
                    ) : (
                      <button onClick={() => start(want.id)} className="btn px-2.5 py-1.5 text-sm">
                        Start
                      </button>
                    )}
                    <button
                      onClick={() => confirmCancel(want)}
                      className="btn ml-2 border-red-300 px-2.5 py-1.5 text-sm text-red-700 hover:border-red-600 dark:border-red-900/60 dark:text-red-400 dark:hover:border-red-600"
                    >
                      Cancel want
                    </button>
                  </>
                ) : want.status === 'CLOSED' ? null : want.signedUp ? (
                  <button onClick={() => withdraw(want.id)} className="btn px-2.5 py-1.5 text-sm">
                    Withdraw
                  </button>
                ) : (
                  <>
                    {isFull(want) && (
                      <span className="self-center text-sm font-medium text-stone-500 dark:text-stone-400">Full</span>
                    )}
                    <button
                      onClick={() => signUp(want.id)}
                      disabled={isFull(want)}
                      className="btn-primary px-2.5 py-1.5 text-sm"
                    >
                      Sign up
                    </button>
                  </>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
