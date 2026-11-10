import { useEffect, useId, useReducer, useRef, useState } from 'react'
import { Link } from 'react-router'
import type { SubmitEvent } from 'react'
import { useCircles } from '../hooks/useCircles'
import { useMe } from '../hooks/useMe'
import { useOnPictureChange } from '../hooks/useOnPictureChange'
import { useWants } from '../hooks/useWants'
import LoadMore from './LoadMore'
import type { AudienceChange, DeadlineChange, SeatsChange } from '../hooks/useWants'
import { CATEGORIES, CATEGORY_LABEL } from '../types/wants'
import type { Category, Want } from '../types/wants'
import Avatar from './Avatar'
import DeadlinePicker from './DeadlinePicker'
import RowMenu from './RowMenu'
import type { MenuGroup, MenuItem } from './RowMenu'
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

/** The id of a card's title, which names its buttons and takes focus when a neighbour is cancelled. */
function titleId(want: Want): string {
  return `want-${want.id}-title`
}

/** The id of the line saying why a card's edit can't be saved. */
function editProblemId(want: Want): string {
  return `want-${want.id}-edit-problem`
}

/** "1 / 1 seat", "2 / 4 seats". */
function seatsLabel(taken: number, capacity: number): string {
  return `${taken} / ${capacity} ${capacity === 1 ? 'seat' : 'seats'}`
}

/** The top of the calendar leaf beside a deadline, such as "Nov". */
const leafMonth = new Intl.DateTimeFormat(undefined, { month: 'short' })

/**
 * The lineup by name, Luma-style: "dani and sam", or "dani, sam and 3 others".
 * Null for the owner alone, whose name is already on the card.
 */
function lineupNames({ lineup }: Want): string | null {
  if (lineup.length < 2) return null
  const [first, second] = lineup.map((p) => p.username)
  const rest = lineup.length - 2
  if (rest === 0) return `${first} and ${second}`
  return `${first}, ${second} and ${rest} ${rest === 1 ? 'other' : 'others'}`
}

/** What Reopen does without a new deadline: keeps one still ahead, or leaves none. */
function keepLabel(want: Want): string {
  return want.expiresAt !== null && new Date(want.expiresAt).getTime() > Date.now()
    ? `Keep ${closesAt.format(new Date(want.expiresAt))}`
    : 'No deadline'
}

/** A select's value for an audience: '' for General, else the circle's id. */
function audienceValue(circleId: number | null): string {
  return circleId === null ? '' : String(circleId)
}

function audienceOf(value: string): number | null {
  return value === '' ? null : Number(value)
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
  const {
    wants,
    loading,
    error,
    refresh,
    hasMore,
    loadMore,
    loadingMore,
    add,
    update,
    close,
    reopen,
    start,
    end,
    cancel,
    signUp,
    withdraw,
    refusalFor,
    isPending,
  } = useWants(filter)
  // Your picture beside your wants comes from the API, so reload the list as
  // soon as you change it in Clerk's account menu.
  useOnPictureChange(refresh)
  // Only the owner may change a want. The API enforces that; this just hides
  // controls that would fail.
  const { me } = useMe()
  const isMine = (want: Want) => me !== null && want.userId === me.id
  // The circles you own or are in, any of which you may post to. Until they
  // load, or if they fail to, General is the only audience offered.
  const { circles } = useCircles()

  const [message, setMessage] = useState('')
  const [category, setCategory] = useState<Category>('FOOD')
  // Kept as typed, so the field can be cleared on the way to a new number.
  const [capacity, setCapacity] = useState('1')
  const [openInvite, setOpenInvite] = useState(false)
  const [deadline, setDeadline] = useState<DeadlineDraft>(NO_DEADLINE)
  // The circle to post to, or null for General.
  const [audience, setAudience] = useState<number | null>(null)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [draft, setDraft] = useState('')
  const [draftCapacity, setDraftCapacity] = useState('')
  const [draftOpen, setDraftOpen] = useState(false)
  const [draftDeadline, setDraftDeadline] = useState<DeadlineDraft>(NO_DEADLINE)
  const [draftAudience, setDraftAudience] = useState<number | null>(null)
  // Reopen asks for an optional new deadline first, in place of the row's buttons.
  const [reopeningId, setReopeningId] = useState<number | null>(null)
  const [reopenDeadline, setReopenDeadline] = useState<DeadlineDraft>(NO_DEADLINE)
  // Leaving edit mode unmounts the field that had focus, so the row's Edit
  // button takes it back when it mounts again.
  const refocusId = useRef<number | null>(null)
  const openInviteHint = useId()
  const audienceHint = useId()
  // Nothing re-renders as the clock passes a deadline, so a pick can go stale
  // with its button still enabled. A refused submit re-renders, and the picker
  // then says why.
  const [, recheck] = useReducer((n: number) => n + 1, 0)
  // Read out by a live region after a signup or withdrawal, whose button
  // changes under the focus without saying what happened.
  const [announcement, setAnnouncement] = useState('')

  async function join(want: Want) {
    if (await signUp(want.id)) setAnnouncement(`You're going to ${want.message}.`)
  }

  async function leave(want: Want) {
    if (await withdraw(want.id)) setAnnouncement(`You withdrew from ${want.message}.`)
  }

  // The row's Edit or Reopen button, whichever mounts, takes focus back.
  const refocus = (id: number) => (button: HTMLButtonElement | null) => {
    if (button && refocusId.current === id) {
      refocusId.current = null
      button.focus()
    }
  }

  // The circle picked on Add has since gone from your list, deleted or left.
  // Never fall back to General, the widest audience: Add waits for a choice.
  const audienceGone = audience !== null && circles !== null && !circles.some((c) => c.id === audience)

  // The audience picked in Edit, or the want's own if the circle picked has
  // since gone, so a failure never widens who sees it. General is always
  // there, and its own circle counts even after you leave it.
  const draftTo = (want: Want) =>
    draftAudience === null ||
    draftAudience === (want.circle?.id ?? null) ||
    circles?.some((c) => c.id === draftAudience)
      ? draftAudience
      : (want.circle?.id ?? null)

  // Add turns disabled when its circle goes, which drops its focus to the
  // page. The select that needs changing takes it instead, and a screen
  // reader reads why. Only from Add or nowhere, so typing elsewhere is never
  // interrupted, as on a refetch when the window regains focus.
  const audienceSelect = useRef<HTMLSelectElement>(null)
  const messageInput = useRef<HTMLInputElement>(null)
  const addButton = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    const focused = document.activeElement
    if (audienceGone && (focused === addButton.current || focused === document.body)) {
      audienceSelect.current?.focus()
    }
  }, [audienceGone])

  // A want that closed while its row was open, such as at its deadline, can't
  // be edited, so its row leaves edit mode once the reload says so.
  const isEditing = (want: Want) => editingId === want.id && want.status !== 'CLOSED' && want.status !== 'ENDED'

  // Why an edit can't be saved, or null if it can: Save is disabled and says so.
  function editProblem(want: Want): { field: 'message' | 'capacity'; text: string } | null {
    if (!draft.trim()) return { field: 'message', text: 'Write what you want first.' }
    if (want.capacity !== null && !draftOpen && seats(draftCapacity, want.capacity) === null) {
      return { field: 'capacity', text: `Capacity can't go below ${want.capacity}.` }
    }
    return null
  }

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
    if (!text || (!openInvite && count === null) || audienceGone) return
    // Only clear the inputs if the API accepted them, so a failure keeps them.
    if (await add(text, category, count, closes?.toISOString(), audience ?? undefined)) {
      setMessage('')
      setCapacity('1')
      setOpenInvite(false)
      setDeadline(NO_DEADLINE)
      setAudience(null)
      // Add turns disabled once the box is empty, which would drop focus to
      // the page; the box takes it instead, ready for the next want.
      messageInput.current?.focus()
      setAnnouncement(`Added ${text}.`)
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
    // Sent only when it changed: left out, the want keeps its audience, even a
    // circle you have since left.
    let audienceChange: AudienceChange | undefined
    const to = draftTo(want)
    if (to !== (want.circle?.id ?? null)) {
      audienceChange = to === null ? { allFollowers: true } : { circleId: to }
    }
    // Set first: if the save is refused because the want closed, the reload
    // ends edit mode and Reopen, mounting, takes focus. If it is still open,
    // the row stays open so the edit is not silently lost.
    refocusId.current = want.id
    if (await update(want.id, text, want.category, change, deadlineChange, audienceChange)) stopEditing(want.id)
  }

  async function confirmReopen(want: Want) {
    const closes = deadlineOf(reopenDeadline)
    if (closes === 'invalid') return recheck()
    if (await reopen(want.id, closes?.toISOString())) stopReopening(want.id)
  }

  // Unlike closing, which Reopen undoes, cancelling is final and tells the
  // whole lineup, so it asks first. Not "Cancel …?": the dialog's own Cancel
  // button would then mean "don't".
  async function confirmCancel(want: Want) {
    const others = want.seatsTaken - 1
    const told = others > 0 ? ` The ${others} other ${others === 1 ? 'person' : 'people'} signed up will be told.` : ''
    if (!window.confirm(`Call off '${want.message}' for good?${told} This can't be undone.`)) return
    // The card leaves the feed, taking focus with it, so the next card's
    // title takes it, or the previous one's, or the Add box when none is left.
    const at = wants.findIndex((w) => w.id === want.id)
    const neighbour = wants[at + 1] ?? wants[at - 1]
    if (await cancel(want.id)) {
      const title = neighbour && document.getElementById(titleId(neighbour))
      if (title) title.focus()
      else messageInput.current?.focus()
    }
  }

  // Ending is final too: an ended want can't be started again.
  function confirmEnd(want: Want) {
    if (window.confirm(`End '${want.message}'? This can't be undone.`)) {
      end(want.id)
    }
  }

  // The less common owner actions, in the row's ⋯ menu so the card shows only
  // its main one. A closed want can be started, ended or cancelled, not edited.
  function menuFor(want: Want): MenuGroup[] {
    const live: MenuItem = want.happening
      ? { label: 'End', onSelect: () => confirmEnd(want) }
      : { label: 'Start', onSelect: () => start(want.id) }
    const groups: MenuGroup[] =
      want.status === 'CLOSED'
        ? [{ items: [live] }]
        : [
            { items: [{ label: 'Close', onSelect: () => close(want.id) }, live] },
            {
              label: 'Category',
              items: CATEGORIES.map((c) => ({
                label: CATEGORY_LABEL[c],
                checked: want.category === c,
                onSelect: () => {
                  if (c === want.category) return
                  void update(want.id, want.message, c).then((ok) => {
                    if (ok) setAnnouncement(`Moved ${want.message} to ${CATEGORY_LABEL[c]}.`)
                  })
                },
              })),
            },
          ]
    return [...groups, { items: [{ label: 'Cancel want', danger: true, onSelect: () => confirmCancel(want) }] }]
  }

  return (
    <>
      <title>Wants · Yuwant</title>
      <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">Wants</h1>
      <p className="mt-1 mb-6 text-stone-500 dark:text-stone-400">Things worth doing, eating, playing and visiting.</p>

      {/* The composer: what you want first and largest, the details beneath. */}
      <form onSubmit={submit} className="card mb-6 flex flex-col gap-3 p-3 sm:p-4">
        <input
          ref={messageInput}
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          placeholder="What do you want?"
          aria-label="Want description"
          className="field w-full px-4 py-3 text-base sm:text-lg"
        />
        {/* Two even columns on a phone, one row from sm up. */}
        <div className="grid grid-cols-2 items-center gap-2 text-sm sm:flex sm:flex-wrap">
          <select
            value={category}
            onChange={(e) => setCategory(e.target.value as Category)}
            aria-label="Category"
            className="field w-full px-2.5 py-1.5 sm:w-auto"
          >
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {CATEGORY_LABEL[c]}
              </option>
            ))}
          </select>
          {/* Beside Category on a phone, or its own row once a date and time are
              showing. Right after Category in the page too, so Tab follows the eye. */}
          <span
            className={`flex flex-wrap items-center gap-2 sm:contents ${deadline.choice === 'date' ? 'col-span-2' : ''} [&>select]:w-full sm:[&>select]:w-auto`}
          >
            <DeadlinePicker label="Deadline" value={deadline} onChange={setDeadline} />
          </span>
          {/* General, everyone who follows you, first and the default. Capped in
              width, so a long circle name can't push the form off a phone. */}
          <select
            ref={audienceSelect}
            value={audienceValue(audience)}
            onChange={(e) => setAudience(audienceOf(e.target.value))}
            aria-label="Audience"
            aria-describedby={audienceGone ? audienceHint : undefined}
            title="Who sees it"
            className="field col-span-2 w-full max-w-full min-w-0 px-2.5 py-1.5 sm:w-auto sm:max-w-48"
          >
            {/* Shown in place of a gone circle, so even General is a change, and a deliberate one. */}
            {audienceGone && (
              <option value={audience} disabled>
                Choose who sees it
              </option>
            )}
            <option value="">General (all followers)</option>
            {circles?.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
          {/* Capacity and Open invite belong together: ticking one disables the other. */}
          <span className="col-span-2 flex items-center gap-3">
            {/* A visible label: a bare "1" in a box doesn't say what it counts. */}
            <label className="flex items-center gap-1.5 text-stone-600 dark:text-stone-300">
              Capacity
              <input
                type="number"
                min={1}
                // Empty while open, so a greyed-out number doesn't look like a limit.
                value={openInvite ? '' : capacity}
                placeholder={openInvite ? 'Any' : undefined}
                disabled={openInvite}
                onChange={(e) => setCapacity(e.target.value)}
                aria-describedby={openInvite ? openInviteHint : undefined}
                title="How many people, you included"
                className="field w-16 px-2.5 py-1.5 text-stone-900 disabled:opacity-50 dark:text-stone-100"
              />
            </label>
            <span id={openInviteHint} className="sr-only">
              No limit: anyone who can see it may join
            </span>
            <label
              className="flex min-h-6 items-center gap-1.5 whitespace-nowrap"
              title="No limit on how many can join"
            >
              <input
                type="checkbox"
                checked={openInvite}
                onChange={(e) => setOpenInvite(e.target.checked)}
                className="accent-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 dark:accent-emerald-500"
              />
              Open invite
            </label>
          </span>
          <button
            ref={addButton}
            type="submit"
            disabled={
              !message.trim() ||
              (!openInvite && seats(capacity, 1) === null) ||
              deadlineOf(deadline) === 'invalid' ||
              audienceGone
            }
            className="btn-primary col-span-2 w-full px-6 py-2 sm:ml-auto sm:w-auto"
          >
            Add
          </button>
          {audienceGone && (
            <p id={audienceHint} className="col-span-2 basis-full text-sm text-red-700 dark:text-red-400">
              That circle is gone. Choose who sees it.
            </p>
          )}
        </div>
      </form>

      {/* Pill chips, each with its category's colour, like the tag on every card. */}
      {/* A group of toggles, not a navigation landmark: nothing here leaves the page. */}
      <div role="group" aria-label="Filter by category" className="mb-5 flex flex-wrap gap-2">
        {(['', ...CATEGORIES] as const).map((c) => (
          <button
            key={c || 'all'}
            onClick={() => setFilter(c)}
            aria-pressed={filter === c}
            className={`flex cursor-pointer items-center gap-1.5 rounded-full border px-3.5 py-1.5 text-sm transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-violet-500 ${
              filter === c
                ? 'border-stone-900 bg-stone-900 font-medium text-white dark:border-stone-100 dark:bg-stone-100 dark:text-stone-900'
                : 'border-stone-200 bg-white text-stone-600 hover:border-stone-300 dark:border-stone-700 dark:bg-stone-900 dark:text-stone-300 dark:hover:border-stone-600'
            }`}
          >
            {c && <span aria-hidden="true" className={`size-2 rounded-full ${TAG[c]}`} />}
            {c ? CATEGORY_LABEL[c] : 'All'}
          </button>
        ))}
      </div>

      {/* Always mounted, so screen readers hear each new message. */}
      <p role="status" className="sr-only">
        {announcement}
      </p>

      {error && (
        <p
          role="alert"
          className="mb-4 rounded-xl border border-red-600/50 bg-red-600/10 px-3 py-2.5 text-sm text-red-700 dark:text-red-400"
        >
          {error}
        </p>
      )}

      {/* Only the first load replaces the list, so a reload keeps rows, and focus, in place. */}
      {loading && wants.length === 0 ? (
        <div aria-busy="true">
          <p className="sr-only">Loading...</p>
          {/* Skeleton cards in the shape of the real ones, so the page doesn't jump when they arrive. */}
          <div aria-hidden="true" className="grid gap-3 lg:grid-cols-2">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="card flex flex-col gap-3 p-4 motion-safe:animate-pulse">
                <div className="h-4 w-16 rounded-full bg-stone-200 dark:bg-stone-800" />
                <div className="h-5 w-2/3 rounded-lg bg-stone-200 dark:bg-stone-800" />
                <div className="h-4 w-1/3 rounded-lg bg-stone-100 dark:bg-stone-800/60" />
              </div>
            ))}
          </div>
        </div>
      ) : wants.length === 0 ? (
        <p className="card px-4 py-10 text-center text-stone-500 dark:text-stone-400">
          Nothing here yet. Add a want above, or{' '}
          <Link to="/people" className="text-emerald-700 underline dark:text-emerald-400">
            find people to follow
          </Link>
          .
        </p>
      ) : (
        <ul className="grid items-start gap-3 lg:grid-cols-2">
          {wants.map((want) => (
            <li
              key={want.id}
              // A row being edited or reopened takes the full width, for its extra fields.
              className={`card flex flex-col gap-3 p-4 ${
                isEditing(want) || reopeningId === want.id ? 'lg:col-span-2' : ''
              } ${want.status === 'ENDED' ? 'opacity-75' : ''}`}
            >
              {/* Labels first: what kind of thing, for whom, and whether it is still on. */}
              <div className="flex flex-wrap items-center gap-1.5">
                <span className={`pill px-2 py-0.5 text-white uppercase ${TAG[want.category]}`}>
                  {CATEGORY_LABEL[want.category]}
                </span>

                {/* General wants get no pill: that is the default audience. */}
                {want.circle && (
                  <span className="pill bg-violet-100 px-2 py-0.5 text-violet-800 dark:bg-violet-950 dark:text-violet-300">
                    <span className="sr-only">Circle: </span>
                    {want.circle.name}
                  </span>
                )}

                {/* Filled and borderless like the category tag, so they read as a status, not a button. */}
                {/* Only while people can join: on a closed or ended want there is no one left to invite. */}
                {want.capacity === null && want.status !== 'CLOSED' && want.status !== 'ENDED' && (
                  <span className="pill bg-sky-100 px-2 py-0.5 text-sky-800 dark:bg-sky-950 dark:text-sky-300">
                    Open invite
                  </span>
                )}
                {want.happening && (
                  <span className="pill bg-emerald-100 px-2 py-0.5 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300">
                    <span
                      aria-hidden="true"
                      className="size-1.5 rounded-full bg-emerald-600 motion-safe:animate-pulse"
                    />
                    Happening now
                  </span>
                )}
                {(want.status === 'CLOSED' || want.status === 'ENDED') && (
                  <span className="pill bg-stone-200 px-2 py-0.5 text-stone-700 dark:bg-stone-800 dark:text-stone-300">
                    {want.status === 'ENDED' ? 'Ended' : closedAtDeadline(want) ? 'Closed at its deadline' : 'Closed'}
                  </span>
                )}

                {/* Every change to an ended want is a 409, so it gets no menu; nor
                    does a row whose own buttons are Save or Confirm. */}
                {isMine(want) && want.status !== 'ENDED' && !isEditing(want) && reopeningId !== want.id && (
                  <span className="-my-1.5 ml-auto">
                    {/* Pulled into the row's height, so titles line up across cards with and without one. */}
                    <RowMenu label={`More actions for ${want.message}`} groups={menuFor(want)} />
                  </span>
                )}
              </div>

              {isEditing(want) ? (
                <span
                  className="flex min-w-0 flex-wrap items-center gap-1.5 text-sm"
                  onKeyDown={(e) => {
                    // Not from Save or Cancel, whose own click already handles Enter.
                    if (e.key === 'Enter' && !(e.target instanceof HTMLButtonElement)) saveEdit(want)
                    if (e.key === 'Escape') stopEditing(want.id)
                  }}
                >
                  <input
                    value={draft}
                    autoFocus
                    onChange={(e) => setDraft(e.target.value)}
                    aria-label="Edit want"
                    aria-describedby={editProblem(want)?.field === 'message' ? editProblemId(want) : undefined}
                    className="field min-w-0 flex-1 basis-full px-3 py-2 text-base"
                  />
                  {/* Disabled on an open want even once unticked: limiting it
                      takes exactly the lineup, shown here, and a bigger limit a second save. */}
                  <label className="flex items-center gap-1.5 text-stone-600 dark:text-stone-300">
                    Capacity
                    <input
                      type="number"
                      min={want.capacity ?? undefined}
                      value={draftCapacity}
                      disabled={draftOpen || want.capacity === null}
                      onChange={(e) => setDraftCapacity(e.target.value)}
                      aria-label="Edit capacity"
                      aria-describedby={editProblem(want) ? editProblemId(want) : undefined}
                      aria-invalid={editProblem(want)?.field === 'capacity' ? true : undefined}
                      className="field w-16 px-2 py-1.5 text-stone-900 disabled:opacity-50 dark:text-stone-100"
                    />
                  </label>
                  <label className="flex min-h-6 items-center gap-1.5 whitespace-nowrap">
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
                  <select
                    value={audienceValue(draftTo(want))}
                    onChange={(e) => setDraftAudience(audienceOf(e.target.value))}
                    aria-label="Edit audience"
                    title="Who sees it"
                    className="field max-w-full min-w-0 px-2 py-1.5"
                  >
                    <option value="">General (all followers)</option>
                    {/* Its own circle, even one you have left, so saving keeps it. */}
                    {want.circle && !circles?.some((c) => c.id === want.circle?.id) && (
                      <option value={want.circle.id}>{want.circle.name}</option>
                    )}
                    {circles?.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name}
                      </option>
                    ))}
                  </select>
                  {/* Why Save is disabled, since a disabled button can't say. */}
                  {editProblem(want) && (
                    <span id={editProblemId(want)} className="basis-full text-sm text-red-700 dark:text-red-400">
                      {editProblem(want)?.text}
                    </span>
                  )}
                  {/* Right under the fields, not below the lineup, so the action is beside what it saves. */}
                  <span className="flex basis-full justify-end gap-1.5">
                    <button
                      onClick={() => saveEdit(want)}
                      disabled={
                        editProblem(want) !== null ||
                        (!sameDraft(draftDeadline, draftOf(want.expiresAt)) && deadlineOf(draftDeadline) === 'invalid')
                      }
                      className="btn-primary px-3.5 py-1.5 text-sm"
                    >
                      Save
                    </button>
                    <button onClick={() => stopEditing(want.id)} className="btn px-3.5 py-1.5 text-sm">
                      Cancel
                    </button>
                  </span>
                </span>
              ) : (
                // A heading, so screen reader users can jump card to card. It
                // describes the card's buttons, and takes focus when a neighbour is cancelled.
                <h2
                  id={titleId(want)}
                  tabIndex={-1}
                  className="rounded-md text-lg leading-snug font-medium break-words focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-violet-500"
                >
                  {want.message}
                </h2>
              )}

              {/* Who posted it and, Luma-style, when it closes, with a small calendar leaf. */}
              <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-sm text-stone-500 dark:text-stone-400">
                {/* The picture is decorative: the username beside it names the owner. */}
                <span className="flex items-center gap-1.5">
                  <Avatar src={want.imageUrl} alt="" size={24} />
                  {want.username}
                </span>

                {/* Only while people can join; once closed, the pill above says why.
                    Hidden while editing, where the picker shows the deadline being chosen. */}
                {want.expiresAt !== null && want.status !== 'CLOSED' && want.status !== 'ENDED' && !isEditing(want) && (
                  <span className="flex items-center gap-2">
                    <span
                      aria-hidden="true"
                      className="flex w-9 flex-col overflow-hidden rounded-md border border-stone-200 text-center leading-none dark:border-stone-700"
                    >
                      <span className="bg-stone-100 py-0.5 text-[0.65rem] font-semibold text-stone-600 uppercase dark:bg-stone-800 dark:text-stone-300">
                        {leafMonth.format(new Date(want.expiresAt))}
                      </span>
                      <span className="py-1 text-sm font-medium text-stone-800 dark:text-stone-200">
                        {new Date(want.expiresAt).getDate()}
                      </span>
                    </span>
                    <span>
                      Closes <time dateTime={want.expiresAt}>{closesAt.format(new Date(want.expiresAt))}</time>
                    </span>
                  </span>
                )}
              </div>

              {/* Who is going, then the one action that matters most here. */}
              <div className="flex flex-wrap items-center gap-3 border-t border-stone-100 pt-3 dark:border-stone-800">
                <div className="flex min-w-0 flex-1 basis-60 items-center gap-2.5">
                  {/* Unlike the owner's, these pictures stand alone, so each is named by its alt text. */}
                  <ul aria-label={`Going to ${want.message}`} className="flex shrink-0 -space-x-2">
                    {want.lineup.map((person) => (
                      <li
                        key={person.id}
                        title={person.username}
                        className="rounded-full ring-2 ring-white dark:ring-stone-900"
                      >
                        <Avatar src={person.imageUrl} alt={person.username} size={26} />
                      </li>
                    ))}
                  </ul>
                  <div className="flex min-w-0 flex-col gap-1">
                    <span className="text-sm font-medium whitespace-nowrap text-stone-700 tabular-nums dark:text-stone-300">
                      {want.capacity === null ? `${want.seatsTaken} going` : seatsLabel(want.seatsTaken, want.capacity)}
                    </span>
                    {/* The names above already say this to a screen reader. */}
                    {lineupNames(want) && (
                      <span aria-hidden="true" className="truncate text-xs text-stone-500 dark:text-stone-400">
                        {lineupNames(want)}
                      </span>
                    )}
                    {want.capacity !== null && (
                      <span
                        aria-hidden="true"
                        className="h-1 w-24 overflow-hidden rounded-full bg-stone-200 dark:bg-stone-800"
                      >
                        <span
                          className={`block h-full rounded-full ${isFull(want) || want.status === 'CLOSED' || want.status === 'ENDED' ? 'bg-stone-500' : 'bg-emerald-600'}`}
                          style={{ width: `${Math.min(100, (want.seatsTaken / want.capacity) * 100)}%` }}
                        />
                      </span>
                    )}
                  </div>
                </div>

                {/* Wraps rather than spilling out of the card on narrow phones. */}
                <span className="ml-auto flex flex-wrap items-center justify-end gap-1.5 whitespace-nowrap">
                  {/* Every change to an ended want is a 409, the owner's included. */}
                  {want.status === 'ENDED' ? null : reopeningId === want.id ? (
                    // Its own element, so React never reuses Confirm or Cancel as another button.
                    <span
                      className="flex flex-wrap items-center justify-end gap-1.5 text-sm"
                      onKeyDown={(e) => {
                        if (e.key === 'Escape') stopReopening(want.id)
                      }}
                    >
                      {/* Reopen can keep or replace a deadline, not remove one: Edit does that once it is open.
                          Its own line on a phone, so Confirm and Cancel stay together. */}
                      <span className="flex basis-full flex-wrap items-center justify-end gap-1.5 sm:basis-auto">
                        {/* The select's own name says the same, so this is not read twice. */}
                        <span aria-hidden="true" className="text-stone-500 dark:text-stone-400">
                          Reopen until
                        </span>
                        <DeadlinePicker
                          label="Reopen until"
                          noneLabel={keepLabel(want)}
                          value={reopenDeadline}
                          onChange={setReopenDeadline}
                          autoFocus
                        />
                      </span>
                      <button
                        onClick={() => confirmReopen(want)}
                        disabled={deadlineOf(reopenDeadline) === 'invalid'}
                        className="btn-primary px-3 py-1.5 text-sm"
                      >
                        Confirm
                      </button>
                      <button onClick={() => stopReopening(want.id)} className="btn px-3 py-1.5 text-sm">
                        Cancel
                      </button>
                    </span>
                  ) : isEditing(want) ? null : isMine(want) ? (
                    // A closed want can't be edited until it is reopened. Tested
                    // as CLOSED, not "not ACTIVE", so an unknown status keeps the controls.
                    want.status === 'CLOSED' ? (
                      <button
                        ref={refocus(want.id)}
                        onClick={() => {
                          setReopeningId(want.id)
                          setReopenDeadline(NO_DEADLINE)
                          // A row that closed mid-edit must not come back in edit mode once reopened.
                          setEditingId(null)
                        }}
                        aria-describedby={titleId(want)}
                        className="btn px-3.5 py-1.5 text-sm"
                      >
                        Reopen
                      </button>
                    ) : (
                      <button
                        ref={refocus(want.id)}
                        onClick={() => {
                          setEditingId(want.id)
                          setDraft(want.message)
                          setDraftCapacity(String(want.capacity ?? want.seatsTaken))
                          setDraftOpen(want.capacity === null)
                          setDraftDeadline(draftOf(want.expiresAt))
                          setDraftAudience(want.circle?.id ?? null)
                        }}
                        aria-describedby={titleId(want)}
                        className="btn px-3.5 py-1.5 text-sm"
                      >
                        Edit
                      </button>
                    )
                  ) : want.status === 'CLOSED' ? null : want.signedUp ? (
                    <button
                      onClick={() => leave(want)}
                      aria-disabled={isPending(want.id)}
                      aria-describedby={titleId(want)}
                      className="btn px-3.5 py-1.5 text-sm"
                    >
                      Withdraw
                    </button>
                  ) : isFull(want) ? (
                    // Status text, not a button shape: there is nothing to press until someone withdraws.
                    <span className="text-sm font-medium text-stone-500 dark:text-stone-400">Full</span>
                  ) : (
                    <button
                      onClick={() => join(want)}
                      aria-disabled={isPending(want.id)}
                      aria-describedby={titleId(want)}
                      className="btn-primary px-4 py-1.5 text-sm"
                    >
                      Sign up
                    </button>
                  )}
                </span>
              </div>

              {/* Where the person is looking, not at the top of a long feed. */}
              {refusalFor(want.id) && (
                <p
                  role="alert"
                  className="rounded-xl border border-red-600/50 bg-red-600/10 px-3 py-2 text-sm text-red-700 dark:text-red-400"
                >
                  {refusalFor(want.id)}
                </p>
              )}
            </li>
          ))}
        </ul>
      )}

      {hasMore && wants.length > 0 && <LoadMore onLoad={loadMore} loading={loadingMore} />}
    </>
  )
}
