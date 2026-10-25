# Wants signups and notifications

**Status: steps 1 to 5 built (notifications for follows; capacity and signups; close, reopen and cancel; happening and ended; open invite); steps 6 to 8 planned.** CLAUDE.md and the README describe what exists; this describes what is coming and why. Update it in the same PR whenever a step changes a decision.

## Goal

1. Tell you when someone follows you.
2. Let a want take a limited number of people. Others sign up for it, first come, first served, until the owner fixes the lineup or the deadline passes. The owner hears about every signup and withdrawal; the lineup hears when the want is closed, reopened, started, ended or cancelled.

## Decisions

| Question | Decision |
|---|---|
| How are signups allocated? | First come, first served. No bidding, no owner approval, for now. |
| Does the owner take a seat? | Yes. Creating a want signs the owner up, so capacity counts the owner and is at least 1. The owner is told the want was created (`WANT_CREATED`, to themselves). A signup by the owner changes nothing, since they already hold the seat. |
| Can the owner withdraw? | No. That is a cancel. |
| Can others withdraw? | Yes, while the want is active, full or not. The seat reopens and the owner is notified. |
| Statuses | `ACTIVE`, `CLOSED`, `ENDED`, `CANCELLED`, worked out from `cancelled_at`, `ended_at` and `closed_at`, in that order, and returned as `status`. Status says who can join. Full is not a status: it is `seatsTaken >= capacity`, and an open invite is never full. |
| Happening | Not a status: a separate `happening` flag, true from `started_at` until the want ends or is cancelled. It says the want is live, while status still says who can join, so an active want can happen and still take signups (and edits), and a closed one can happen with its lineup fixed. This replaced the first sketch of `HAPPENING` as a fourth status, which needed a later setting to stay joinable while live. |
| Starting | The owner starts an active or closed want (`WANT_STARTED` to the lineup). Starting a happening want changes nothing. |
| Ending | Only a happening want can end (409 otherwise); the owner ends it (`WANT_ENDED` to the lineup). Final, like cancelling, but the want stays in the feed as `ENDED`: editing, closing, reopening, starting, cancelling, signing up and withdrawing are all 409. Ending twice changes nothing. Running the same want again is a later idea (repeat a want), not a reopen. |
| Signing up or withdrawing when not active | 409 for everyone who can see the want, even someone already in the lineup repeating a signup, or someone withdrawing who holds no seat. The screen offers neither button there, so the API simply refuses. Anyone who cannot see it gets 404, so the 409 never reveals that it exists. |
| Deadline | Optional. `expires_at` may be null: the want stays open until the owner closes it. |
| What happens at the deadline? | The want closes automatically, exactly as if the owner had closed it, and the lineup is notified. |
| After closing | The lineup is fixed, and so is the want: editing it is a 409 until the owner reopens it. The owner can reopen it, which notifies the lineup, or cancel it. |
| Reopening an expired want | Needs a new future deadline or none, or the expiry job would close it again within a minute. |
| Cancelling | Replaces deleting, from active or closed. The want is hidden from the feed and the lineup is notified. Final: closing, reopening or editing it afterwards is a 409, and only its lineup can still see it. |
| Doing one twice | Closing a closed want, reopening an active one, starting a happening one, ending an ended one or cancelling a cancelled one changes nothing and notifies nobody. |
| Editing | While the want is active, the message and category can change. Either change notifies the lineup (`WANT_UPDATED`), keeping the old name when it was renamed. Each edit is its own notification; grouping is for the screen. Capacity can be raised but never lowered, and raising it notifies nobody. |
| Open invite | A want with no seat limit: `capacity` is null. Who can see it and join doesn't change: the owner's followers, as for any want. `openInvite: true` creates one, or opens a limited want; `openInvite: false` limits an open want to exactly its lineup, so nobody loses a seat and it is full until the owner raises the limit in a later edit. `openInvite` with a capacity, or a capacity alone on an open want, is a 400. Opening and limiting notify the lineup, not the owner (`WANT_OPEN_INVITE`, `WANT_LIMITED`); a repeat that changes nothing notifies nobody. A flag rather than `capacity: null`, because a left-out capacity already means 1 on create and "keep" on update. |
| Notifications per signup | One each. Twenty signups are twenty bell entries; grouping them is a display decision for later. |
| Following twice | Notifies once. |
| Who may withdraw | Anyone in the lineup but the owner, even after unfollowing the owner. Others get 404 if the want is not in their feed. |
| How capacity is enforced | Lock the want's row (`SELECT ... FOR UPDATE`), then count the signups in a separate statement, then insert. Not a `seats_taken` counter, which can drift (a user's deletion cascades away their signups without decrementing it); not SERIALIZABLE, which needs retries; not Redis, which only pays off for timed seat holds or very high signup rates. |

## Model

```
wants          + capacity     INT NULL CHECK (capacity >= 1)       -- includes the owner; NULL = open invite
               + expires_at   TIMESTAMPTZ NULL                      -- NULL = no deadline
               + closed_at    TIMESTAMPTZ NULL
               + cancelled_at TIMESTAMPTZ NULL
               + started_at   TIMESTAMPTZ NULL                      -- set = happening, until ended or cancelled
               + ended_at     TIMESTAMPTZ NULL                      -- set = ENDED, final

want_signups   id, want_id, user_id, created_at
               UNIQUE (want_id, user_id)

notifications  id, recipient_id, actor_id, type, want_id NULL, want_message NULL,
               previous_message NULL,                                -- the old name, for a rename
               created_at, read_at NULL                              -- NULL = unread
               type IN ('FOLLOW', 'WANT_CREATED', 'WANT_SIGNUP', 'WANT_WITHDRAWN', 'WANT_UPDATED',
                        'WANT_CLOSED', 'WANT_REOPENED', 'WANT_CANCELLED', 'WANT_STARTED', 'WANT_ENDED',
                        'WANT_OPEN_INVITE', 'WANT_LIMITED')
               INDEX (recipient_id, read_at)
```

A want is **active** when it is neither cancelled, ended nor closed (from step 6, also when its deadline, if any, is still in the future). It takes signups while it is active and has a free seat.

Following the existing conventions: `BIGINT GENERATED BY DEFAULT AS IDENTITY` ids, `VARCHAR` plus `CHECK` for `type` rather than a native enum, and new migrations from `V11` on.

## Lifecycle

```
          signup / withdraw (owner notified)
ACTIVE ◀────────────────────────────┐
 │  ├─ owner closes ──────▶ CLOSED ─┤ owner reopens (lineup notified)
 │  └─ expires_at passes ─▶ CLOSED  │
 │                                  │
 └──────── owner cancels ──▶ CANCELLED  (lineup notified, hidden from the feed)
                 ▲
          also possible from CLOSED

happening, alongside the status above:

 not started ── owner starts (ACTIVE or CLOSED) ──▶ happening ── owner ends ──▶ ENDED (final, stays in the feed)
                                                      │
                                                      └── owner cancels ──▶ CANCELLED
```

While happening, the status keeps working as before: an active happening want can be closed and reopened, and takes signups while active.

## Who is notified

| Type | Recipient | Written when |
|---|---|---|
| `FOLLOW` | the person followed | `followIfAbsent` actually inserted a row |
| `WANT_CREATED` | the owner (also the actor) | the want is created |
| `WANT_SIGNUP` | the owner | a signup succeeds |
| `WANT_WITHDRAWN` | the owner | a signup is removed |
| `WANT_UPDATED` | the lineup, not the owner | the owner renames or recategorises it |
| `WANT_CLOSED` | the lineup, not the owner | the owner closes it, or the expiry job does |
| `WANT_REOPENED` | the lineup, not the owner | the owner reopens it |
| `WANT_CANCELLED` | the lineup, not the owner | the owner cancels it |
| `WANT_STARTED` | the lineup, not the owner | the owner starts it |
| `WANT_ENDED` | the lineup, not the owner | the owner ends it |
| `WANT_OPEN_INVITE` | the lineup, not the owner | the owner removes the seat limit |
| `WANT_LIMITED` | the lineup, not the owner | the owner limits an open want to the people already in |

Lineup notifications are one `INSERT ... SELECT` from `want_signups`, in the same transaction as the state change, so either everyone is told or the change did not happen.

## Why it is built this way

- **The notifications table is the source of truth, always written.** A live push (later) only nudges an open tab to refetch. Writing to the table only for offline users would mean knowing reliably who is online, which breaks with closed tabs, dropped connections and multiple tabs.
- **One generic table.** A follow and a signup are the same kind of event. New events add a `type`, not a table.
- **The database decides capacity.** Two people taking the last seat at once must not both get in, so a signup first locks the want's row, then counts and inserts. The count must be its own statement after the lock: a single `INSERT ... SELECT ... FOR UPDATE` lets both in, because a signup never changes the `wants` row, so PostgreSQL never re-checks the waiting statement, and its count from before the wait still shows a free seat. A new statement under READ COMMITTED sees the first signup once it commits. Every other change to a want (edit, close, reopen, cancel) takes the same lock. Same reasoning as `followIfAbsent` and `insertIfAbsent`: no catching constraint exceptions, which would mark the transaction rollback-only.
- **Close, reopen and cancel check the status in Java, under that lock.** The row is locked, so a second close waits, then sees `closed_at` set and does nothing, which is what stops a repeat notifying twice. The times come from the application's clock. The expiry job (step 6) runs without the lock, so it uses a guarded `UPDATE` instead.
- **"Active" is checked on read.** Signups test the deadline in the query, so nothing has to close wants on time for correctness.
- **The expiry job exists only for the notification.** A `@Scheduled` task runs `UPDATE wants SET closed_at = now() WHERE closed_at IS NULL AND cancelled_at IS NULL AND expires_at < now() RETURNING id` and notifies each lineup returned. The update flips each want exactly once, so even two instances running the job would not notify twice.
- **Cancel instead of delete.** Notifications point at wants. Deleting the row would take the link with it and leave "Dani cancelled …" with nothing to show.
- **Notifications copy the want's message.** They still read correctly after the message is edited or the want is cancelled.
- **Followers only.** The feed shows your own wants and those of people you follow, so signing up is limited to those wants in the API too, not only in the UI.
- **Polling before Server-Sent Events.** SSE through this stack needs extra work: `EventSource` cannot send the `Authorization` header, CloudFront drops idle origin connections after 30 seconds by default, Clerk tokens expire about every minute, and more than one instance would need Postgres `LISTEN/NOTIFY` to fan pushes out. Polling every ~30 seconds uses none of that, and the table and endpoints stay the same if SSE is added later.

## Endpoints

| Route | Does | Errors |
|---|---|---|
| `GET /notifications` | your notifications, newest first, actor by public fields only | |
| `POST /notifications/read` | marks them read | |
| `POST /wants/{id}/signups` | sign up; idempotent, returns the lineup | 409 full, closed, ended, expired or cancelled; 404 not in your feed |
| `DELETE /wants/{id}/signups` | withdraw; idempotent, returns the lineup | 409 closed, ended or cancelled; 400 owner; 404 not in your feed |
| `POST /wants/{id}/close` | owner fixes the lineup; returns the want | 403 not the owner; 409 ended or cancelled |
| `POST /wants/{id}/reopen` | owner reopens; returns the want. From step 6, with an optional new deadline | 403 not the owner; 409 ended or cancelled; 400 deadline in the past (step 6) |
| `POST /wants/{id}/start` | owner marks it happening; returns the want | 403 not the owner; 409 ended or cancelled |
| `POST /wants/{id}/end` | owner ends a happening want; returns the want | 403 not the owner; 409 not happening, or cancelled |
| `DELETE /wants/{id}` | cancels; 204 | 403 not the owner; 409 ended |
| `PUT /wants/{id}` | edits the message and category, may raise capacity, open it (`openInvite: true`) or limit an open one to its lineup (`false`); a rename, new category, opening or limiting notifies the lineup | 400 capacity lowered, a capacity on an open want, or `capacity` with `openInvite`; 409 closed, ended or cancelled |

Responses that show other people (actors, lineups) need their own records with public fields only, like `UserSummary`, never a `User`.

## Build order

Each step is one PR and works without the ones after it.

1. **Done. Notifications foundation:** the table, `GET /notifications`, `POST /notifications/read`, the bell in the header with an unread count and 30-second polling (on TanStack Query, shared by the bell and the page), and `FOLLOW` (which needs `followIfAbsent` to return the rows it inserted).
2. **Done. Capacity and signups:** `wants.capacity`, `want_signups`, the owner's automatic seat, signing up and withdrawing, seats in the feed, and `WANT_CREATED`, `WANT_SIGNUP`, `WANT_WITHDRAWN` and `WANT_UPDATED`.
3. **Done. Close, reopen and cancel:** `closed_at`, `cancelled_at`, `status` in the responses, `WANT_CLOSED`, `WANT_REOPENED`, `WANT_CANCELLED`; delete becomes cancel. Reopen takes no deadline until step 6.
4. **Done. Happening and ended:** `started_at`, `ended_at`, the `happening` flag and the `ENDED` status, `POST /wants/{id}/start` and `/end` under the same lock, `WANT_STARTED` and `WANT_ENDED`. The screen has Start and End buttons, "Happening now" and "Ended" labels, and texts for both notifications.
5. **Done. Open invite:** a nullable `capacity` (null = no limit, never full), `openInvite` in the request both ways, `WANT_OPEN_INVITE` and `WANT_LIMITED`. The screen has an Open invite checkbox on Add and Edit, "N going" on open rows, and texts for both notifications.
6. **Deadline and expiry job:** `expires_at`, signups refused after it, and automatic closing at the deadline.
7. **Optional: live updates over SSE**, if polling is too slow.
8. **Later: tell followers about new wants,** organised into groups, so people hear about wants they could join without opening the feed.

Later ideas, not yet in the build order: **repeat a want**, running an ended want again as a new one, with the same name and its lineup invited.
