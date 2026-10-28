# Wants signups and notifications

**Status: steps 1 to 5 built (notifications for follows; capacity and signups; close, reopen and cancel; happening and ended; open invite); step 6 built (deadline and expiry, with its screen); step 7 optional; step 8 designed (announcing new wants, and circles as groups), with 8a (announcing to all followers), 8b (muting) and 8c's API (circles as groups) built.** CLAUDE.md and the README describe what exists; this describes what is coming and why. Update it in the same PR whenever a step changes a decision.

## Goal

1. Tell you when someone follows you.
2. Let a want take a limited number of people. Others sign up for it, first come, first served, until the owner fixes the lineup or the deadline passes. The owner hears about every signup and withdrawal; the lineup hears when the want is closed, reopened, started, ended or cancelled.
3. Tell your followers when you post a want they could join, and let groups of people (circles) share wants among themselves, like a group chat.

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
| Deadline | Optional. `expires_at` may be null: the want stays open until the owner closes it. It must be in the future when set. The owner can set, move or remove it while the want is active (`PUT`, `noDeadline: true` to remove), which tells the lineup (`WANT_DEADLINE_CHANGED`); sending the same one again tells nobody. The screen offers "in an hour", "in a week" or a date, and sends the instant. |
| What happens at the deadline? | The want is closed from that moment, as if the owner had closed it: signups, withdrawals and edits are refused at once. Within a minute the expiry job records `closed_at` (when it ran) and tells the lineup **and the owner** (`WANT_EXPIRED`), since the owner did not close it. Pressing Close in that gap changes nothing; the job's `WANT_EXPIRED` follows. |
| After closing | The lineup is fixed, and so is the want: editing it is a 409 until the owner reopens it. The owner can reopen it, which notifies the lineup, or cancel it. |
| Reopening | Takes an optional new deadline, which must be in the future (400) and replaces the old one. Without one, a deadline still ahead is kept and one that has passed, even while the want was closed, is cleared, or the want would close again at once. Reopening an expired want the job has not reached yet sends `WANT_REOPENED` with no `WANT_EXPIRED` before it; accepted. |
| Cancelling | Replaces deleting, from active or closed. The want is hidden from the feed and the lineup is notified. Final: closing, reopening or editing it afterwards is a 409, and only its lineup can still see it. |
| Doing one twice | Closing a closed want, reopening an active one, starting a happening one, ending an ended one or cancelling a cancelled one changes nothing and notifies nobody. |
| Editing | While the want is active, the message and category can change. Either change notifies the lineup (`WANT_UPDATED`), keeping the old name when it was renamed. Each edit is its own notification; grouping is for the screen. Capacity can be raised but never lowered, and raising it notifies nobody. |
| Open invite | A want with no seat limit: `capacity` is null. Who can see it and join doesn't change: the owner's followers, as for any want. `openInvite: true` creates one, or opens a limited want; `openInvite: false` limits an open want to exactly its lineup, so nobody loses a seat and it is full until the owner raises the limit in a later edit. `openInvite` with a capacity, or a capacity alone on an open want, is a 400. Opening and limiting notify the lineup, not the owner (`WANT_OPEN_INVITE`, `WANT_LIMITED`); a repeat that changes nothing notifies nobody. A flag rather than `capacity: null`, because a left-out capacity already means 1 on create and "keep" on update. |
| Notifications per signup | One each. Twenty signups are twenty bell entries; grouping them is a display decision for later. |
| Following twice | Notifies once. |
| Who may withdraw | Anyone in the lineup but the owner, even after unfollowing the owner. Others get 404 if the want is not in their feed. |
| How capacity is enforced | Lock the want's row (`SELECT ... FOR UPDATE`), then count the signups in a separate statement, then insert. Not a `seats_taken` counter, which can drift (a user's deletion cascades away their signups without decrementing it); not SERIALIZABLE, which needs retries; not Redis, which only pays off for timed seat holds or very high signup rates. |
| What circles are (step 8) | Groups, like a group chat. A circle has one **owner**, who created it, and **members**. Members see the circle, its name, who else is in it and every want posted to it, and any member can post a want there. A want goes either to all the poster's followers (**General**, the default and what every existing want already is) or to one circle. This replaced a first design in which a circle was the owner's private list of followers, seen and used only by them. |
| How General is stored | As `wants.circle_id IS NULL`, not as a row. A real row would need every new follower added to it, and could drift. The API returns `circle: null`, the screen labels it "General" and lists it first in the picker. "General" is a reserved name for real circles (ignoring case, 400). |
| Who runs a circle | The owner: renames it, adds and removes members, and deletes it. Roles and permissions for members are later. A member asking for an owner action gets 403; anyone else gets 404. |
| Who can be added | Only people who follow the owner. Unfollowing the owner removes you from all their circles, and following again does not put you back. The database enforces both (see Model). |
| Being added | You are a member at once, and told (`CIRCLE_ADDED`, naming the owner and the circle). Adding someone already in tells nobody. No invite to accept: you can leave instead. |
| Leaving | Any member can leave at any time. The owner cannot leave their own circle, only delete it. Leaving or being removed tells nobody. |
| Who can see a want | Its poster; for a General want, anyone who follows the poster; for a circle's want, while the circle is not deleted and its poster is still its owner or a member, the circle's owner and members, **whether or not they follow the poster**. |
| Posting to a circle | Any owner or member of a circle can post a want to it, or move their own active want into it through `PUT` (`circleId`); `allFollowers: true` moves it back to General, and both together is a 400. A circle you are not in, or a deleted one, is a 404. Leaving `circleId` out, or sending the want's current one, keeps the circle without checking membership: membership is checked only when the circle changes, so a poster who has left can still edit their own want. |
| Lineup members who leave the audience | Keep their seat, can still withdraw and still get lineup notifications, but the want leaves their feed. This covers leaving or being removed from a circle, unfollowing its owner, and a want moved to a circle they are not in. |
| A poster who leaves the circle | Their wants in it leave the circle's feed, worked out on read: a circle's want is seen only while its poster is the circle's owner or a member. The poster still sees and runs them and can move them to General. Seated people keep their seats, as for anyone who leaves the audience. This is what makes removing someone a real moderation tool: a removed member can't keep writing into the group through their old wants. It also covers a poster who unfollows the owner, which removes them by cascade. |
| Moving a want between audiences | Tells the lineup nothing. People in the new audience who were never told are announced to (`WANT_POSTED`) if it is joinable, as after any edit. |
| Deleting a circle | Archives it (`archived_at`); the screen and the API call it deleting. It leaves everyone's list of circles, every circle route answers 404, and its wants leave every feed except their poster's. Seated people keep their seats, can withdraw and still get lineup notifications; the poster can move an active one to General. Nobody can be added, removed or leave any more. The row stays because wants point at it: `SET NULL` would widen those wants to all the poster's followers, and a cascade would delete other people's wants. |
| Membership changes | Only being added notifies (`CIRCLE_ADDED`). Removing, leaving, renaming and deleting tell nobody. |
| Circle names | 1 to 40 characters, unique per owner ignoring case among live circles. No limit on how many: rate limiting, when built, guards against a script creating huge numbers. Creating and renaming lock the owner's `users` row first, so two requests with the same name at once give one circle and a 400, not a constraint error. |
| Who sees circle data | Its owner and members see its name and member list (`GET /circles` lists circles you own **or** are in). Its name also appears in `WantResponse`, to people who can see the want, and in `CIRCLE_ADDED` and a circle want's `WANT_POSTED`, to their recipients. Never in `SignupEventDetails`. Anyone else gets 404. Being a member shows the other members that you follow the circle's owner, where today only the owner sees their followers; accepted, as in a group chat. |
| Announcing a want (`WANT_POSTED`) | Sent to the want's audience: for a General want, the poster's followers who have not muted them; for a circle's want, its owner and members other than the poster. Never to anyone already in the lineup or told about it before. Only while the want is active and has a free seat (capacity above 1, or open). Checked after every create and every edit, so a want that later becomes joinable is announced then, once. A circle's want carries the circle's id and name, as it was, so a member who doesn't follow the poster sees why they were told. |
| Announcing again | Never. Limiting a want, opening it again, a withdrawal that frees a seat, or unmuting tells nobody new. |
| Muting | Per person you follow: `follows.notify_new_wants`, on by default. Unfollowing and following again resets it to on. It applies to General wants only: a circle's wants reach all its members, since following has nothing to do with being in a circle. A per-circle mute is later. |

## Model

```
wants          + capacity     INT NULL CHECK (capacity >= 1)       -- includes the owner; NULL = open invite
               + expires_at   TIMESTAMPTZ NULL                      -- NULL = no deadline
               + closed_at    TIMESTAMPTZ NULL
               + cancelled_at TIMESTAMPTZ NULL
               + started_at   TIMESTAMPTZ NULL                      -- set = happening, until ended or cancelled
               + ended_at     TIMESTAMPTZ NULL                      -- set = ENDED, final
               + circle_id    BIGINT NULL REFERENCES circles (id)   -- NULL = General, all followers (step 8)

circles        id, owner_id, name, created_at, archived_at NULL     -- step 8; archived = deleted by the owner
               UNIQUE (id, owner_id)
               UNIQUE (owner_id, lower(name)) WHERE archived_at IS NULL

circle_members circle_id, owner_id, member_id, created_at          -- step 8; the owner is not a row here
               PRIMARY KEY (circle_id, member_id)
               FOREIGN KEY (circle_id, owner_id) REFERENCES circles (id, owner_id)
               FOREIGN KEY (member_id, owner_id) REFERENCES follows (follower_id, followee_id) ON DELETE CASCADE
               INDEX (member_id)                                     -- circles you are in, and who sees a want

follows        + notify_new_wants BOOLEAN NOT NULL DEFAULT TRUE     -- step 8; false = muted
               INDEX (followee_id)

want_signups   id, want_id, user_id, created_at
               UNIQUE (want_id, user_id)

notifications  id, recipient_id, actor_id, type, want_id NULL, want_message NULL,
               previous_message NULL,                                -- the old name, for a rename
               circle_id NULL, circle_name NULL                      -- step 8, for CIRCLE_ADDED and a circle's WANT_POSTED; the name as it was
               created_at, read_at NULL                              -- NULL = unread
               type IN ('FOLLOW', 'WANT_CREATED', 'WANT_SIGNUP', 'WANT_WITHDRAWN', 'WANT_UPDATED',
                        'WANT_CLOSED', 'WANT_REOPENED', 'WANT_CANCELLED', 'WANT_STARTED', 'WANT_ENDED',
                        'WANT_OPEN_INVITE', 'WANT_LIMITED', 'WANT_DEADLINE_CHANGED', 'WANT_EXPIRED',
                        'WANT_POSTED', 'CIRCLE_ADDED')
               INDEX (recipient_id, read_at)
               UNIQUE (want_id, recipient_id) WHERE type = 'WANT_POSTED'   -- notifications_posted_once
```

The `circle_members` → `follows` key is what makes "only the owner's followers, and unfollowing the owner removes them" a constraint rather than code: deleting the follow cascades to the membership. The owner is not a row in `circle_members`, since they cannot follow themselves; "owner or member" is checked as `circles.owner_id = me OR EXISTS (member row)`. Posting to a circle needs membership, which a foreign key can't express once members post too, so `WantService` checks it.

Locks, so that circle changes and posts take turns:
- Owner actions (rename, delete, add, remove) and leaving load the circle with `SELECT ... FOR UPDATE`, as `requireOwned` does for wants.
- Posting or moving a want into a circle takes `FOR SHARE` on the circle, then checks membership. A locked read that waited re-reads the row after the other transaction commits, so a post can't land in a circle deleted, or by a member removed, a moment earlier: it gets a clean 404.
- Adding someone first locks their follow row (`FOR KEY SHARE`), then inserts with `ON CONFLICT (circle_id, member_id) DO NOTHING`, sending `CIRCLE_ADDED` only when that inserted a row. If an unfollow wins, the follow row is gone and the answer is 400, not a foreign key error that would abort the transaction.
- Creating and renaming lock the owner's `users` row, for clean duplicate-name 400s.
- The unfollow cascade removes memberships without the circle lock; reading visibility at query time (a circle's want is seen only while its poster is in the circle) makes that harmless.
- A signup racing the signer's removal can still take a seat, which they would keep anyway; accepted. An announce racing an add may miss the new member; the next edit catches them.

`circles.owner_id` and `wants.circle_id` have no foreign key action, like `wants.user_id`: deleting a user who owns circles is blocked, as it already is for one with wants. Account deletion needs its own design.

A want is **active** when it is neither cancelled, ended nor closed, and its deadline, if any, is still in the future. It takes signups while it is active and has a free seat.

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
| `WANT_CLOSED` | the lineup, not the owner | the owner closes it |
| `WANT_REOPENED` | the lineup, not the owner | the owner reopens it |
| `WANT_CANCELLED` | the lineup, not the owner | the owner cancels it |
| `WANT_STARTED` | the lineup, not the owner | the owner starts it |
| `WANT_ENDED` | the lineup, not the owner | the owner ends it |
| `WANT_OPEN_INVITE` | the lineup, not the owner | the owner removes the seat limit |
| `WANT_LIMITED` | the lineup, not the owner | the owner limits an open want to the people already in |
| `WANT_DEADLINE_CHANGED` | the lineup, not the owner | the owner sets, moves or removes the deadline |
| `WANT_EXPIRED` | the lineup **and** the owner (the actor) | the expiry job closes it at its deadline |
| `WANT_POSTED` | the want's audience (General: followers who haven't muted the poster; a circle: its owner and members), not the poster, not in the lineup, never told before | a create or edit leaves the want active with a free seat (step 8) |
| `CIRCLE_ADDED` | the person added | the circle's owner adds someone who wasn't in it (step 8) |

Lineup notifications are one `INSERT ... SELECT` from `want_signups`, in the same transaction as the state change, so either everyone is told or the change did not happen.

## Why it is built this way

- **The notifications table is the source of truth, always written.** A live push (later) only nudges an open tab to refetch. Writing to the table only for offline users would mean knowing reliably who is online, which breaks with closed tabs, dropped connections and multiple tabs.
- **One generic table.** A follow and a signup are the same kind of event. New events add a `type`, not a table.
- **The database decides capacity.** Two people taking the last seat at once must not both get in, so a signup first locks the want's row, then counts and inserts. The count must be its own statement after the lock: a single `INSERT ... SELECT ... FOR UPDATE` lets both in, because a signup never changes the `wants` row, so PostgreSQL never re-checks the waiting statement, and its count from before the wait still shows a free seat. A new statement under READ COMMITTED sees the first signup once it commits. Every other change to a want (edit, close, reopen, cancel) takes the same lock. Same reasoning as `followIfAbsent` and `insertIfAbsent`: no catching constraint exceptions, which would mark the transaction rollback-only.
- **Close, reopen and cancel check the status in Java, under that lock.** The row is locked, so a second close waits, then sees `closed_at` set and does nothing, which is what stops a repeat notifying twice. The times come from the application's clock. The expiry job (step 6) runs without the lock, so it uses a guarded `UPDATE` instead.
- **"Active" is checked on read.** `Want.getStatus(now)` counts a passed deadline as closed, and every signup, withdrawal and edit checks it in Java under the row lock, so nothing has to close wants on time for correctness. `now` comes from an injected `Clock`, so unit tests can pin it.
- **The expiry job exists only to record it and notify.** `WantExpiryJob` runs every minute (`fixedDelay`, so runs never overlap) inside the API, not as a separate worker: a worker would need its own connections out of Aiven's ~17, its own deploy and a second copy of the SQL, for precision nobody sees behind 30-second polling. It runs one statement, `WITH expired AS (UPDATE wants SET closed_at = now() WHERE closed_at IS NULL AND cancelled_at IS NULL AND ended_at IS NULL AND expires_at <= now() RETURNING ...) INSERT INTO notifications ... FROM expired JOIN want_signups`. The guard flips each want once: a second run, or a second instance, waits on the row lock, re-checks the `WHERE` and skips it, so nobody is told twice and no lock library is needed. A reopen holding the lock is skipped the same way. There is no lower bound, so the first run after downtime catches up. `closed_at` is when it ran, not the deadline, so a late run shows as late.
- **Cancel instead of delete.** Notifications point at wants. Deleting the row would take the link with it and leave "Dani cancelled …" with nothing to show.
- **Notifications copy the want's message.** They still read correctly after the message is edited or the want is cancelled.
- **Followers or a shared circle.** The feed shows your own wants, General wants of people you follow, and wants in circles you own or are in, so signing up is limited to those wants in the API too, not only in the UI. A circle's wants reach members who don't follow the poster; that is the point of a group, and the owner's add rule (only their followers) is what keeps strangers out.
- **The "already told" marker is a partial unique index.** `notifications_posted_once` allows one `WANT_POSTED` per want and recipient, and the announce statement ends in `ON CONFLICT DO NOTHING`. So announcing after every create and edit is safe: only people not yet told get a row, with no extra table and no flag on the want. Same rule as `followIfAbsent`: never catch the constraint exception.
- **One visibility rule, written twice on purpose.** A single `VISIBLE_TO_USER` JPQL fragment is shared by `findFeed`, `findFeedByCategory` and `isInFeed`; the announce statement is native SQL and repeats it. An IT test checks that everyone announced to can see the want, so the two copies can't drift unnoticed. With circles the rule is: you posted it; or it has no circle and you follow the poster; or its circle is not deleted, is yours or has you as a member, and its poster is its owner or a member. Announcing repeats it twice in SQL, once for General and once for a circle, chosen in Java by whether the want has one; the IT check covers both.
- **Announcing runs in the same transaction**, like every other notification. At this size a create fans out to tens of followers in one `INSERT ... SELECT`; a queue would only pay off at thousands.
- **Polling before Server-Sent Events.** SSE through this stack needs extra work: `EventSource` cannot send the `Authorization` header, CloudFront drops idle origin connections after 30 seconds by default, Clerk tokens expire about every minute, and more than one instance would need Postgres `LISTEN/NOTIFY` to fan pushes out. Polling every ~30 seconds uses none of that, and the table and endpoints stay the same if SSE is added later.

## Endpoints

| Route | Does | Errors |
|---|---|---|
| `GET /notifications` | your notifications, newest first, actor by public fields only | |
| `POST /notifications/read` | marks them read | |
| `POST /wants/{id}/signups` | sign up; idempotent, returns the lineup | 409 full, closed, ended, expired or cancelled; 404 not in your feed |
| `DELETE /wants/{id}/signups` | withdraw; idempotent, returns the lineup | 409 closed, ended or cancelled; 400 owner; 404 not in your feed |
| `POST /wants/{id}/close` | owner fixes the lineup; returns the want | 403 not the owner; 409 ended or cancelled |
| `POST /wants/{id}/reopen` | owner reopens, dropping the old deadline, with an optional new one (`{"expiresAt"}`); returns the want | 403 not the owner; 409 ended or cancelled; 400 deadline in the past |
| `POST /wants/{id}/start` | owner marks it happening; returns the want | 403 not the owner; 409 ended or cancelled |
| `POST /wants/{id}/end` | owner ends a happening want; returns the want | 403 not the owner; 409 not happening, or cancelled |
| `DELETE /wants/{id}` | cancels; 204 | 403 not the owner; 409 ended |
| `PUT /wants/{id}` | edits the message and category, may raise capacity, open it (`openInvite: true`) or limit an open one to its lineup (`false`), and set, move or remove the deadline; a rename, new category, opening, limiting or new deadline notifies the lineup | 400 capacity lowered, a capacity on an open want, `capacity` with `openInvite`, a deadline in the past, or `expiresAt` with `noDeadline`; 409 closed (deadline passed included), ended or cancelled |
| `POST /wants`, `PUT /wants/{id}` (step 8) | also take `circleId`, or `allFollowers: true` for General; announce to whoever has not been told | 400 both together; 404 a circle you don't own and aren't in, or a deleted one |
| `GET /circles` (step 8) | live circles you own or are in, `[{id, name, owner}]`, the owner as `UserSummary` | |
| `POST /circles` (step 8) | create one (`{"name"}`); you own it | 400 blank, too long, "General", or a duplicate of one of your live circles |
| `PUT /circles/{id}`, `DELETE /circles/{id}` (step 8) | rename; delete (archives the row) | 400 as for create; 403 a member; 404 anyone else, or deleted |
| `GET /circles/{id}/members` (step 8) | the members, by public fields, in the order they were added | 404 not the owner or a member, or deleted |
| `PUT /circles/{id}/members/{userId}` (step 8) | the owner adds someone, idempotent; `CIRCLE_ADDED` when they weren't in | 400 they don't follow you or don't exist, checked only once you are found to be the owner, so members and outsiders learn nothing; 403 a member; 404 anyone else, or deleted |
| `DELETE /circles/{id}/members/{userId}` (step 8) | the owner removes someone, or a member removes themselves (leaving); idempotent | 400 the owner leaving; 403 a member removing someone else; 404 anyone else, or deleted |
| `GET /followers` (step 8) | people who follow you, by public fields, to pick members from; the screen filters them by name | |
| `PUT` / `DELETE /follows/{userId}/notify` (step 8) | unmute or mute someone you follow's new wants | 404 not following them |

Responses that show other people (actors, lineups) need their own records with public fields only, like `UserSummary`, never a `User`.

## Build order

Each step is one PR and works without the ones after it.

1. **Done. Notifications foundation:** the table, `GET /notifications`, `POST /notifications/read`, the bell in the header with an unread count and 30-second polling (on TanStack Query, shared by the bell and the page), and `FOLLOW` (which needs `followIfAbsent` to return the rows it inserted).
2. **Done. Capacity and signups:** `wants.capacity`, `want_signups`, the owner's automatic seat, signing up and withdrawing, seats in the feed, and `WANT_CREATED`, `WANT_SIGNUP`, `WANT_WITHDRAWN` and `WANT_UPDATED`.
3. **Done. Close, reopen and cancel:** `closed_at`, `cancelled_at`, `status` in the responses, `WANT_CLOSED`, `WANT_REOPENED`, `WANT_CANCELLED`; delete becomes cancel. Reopen takes no deadline until step 6.
4. **Done. Happening and ended:** `started_at`, `ended_at`, the `happening` flag and the `ENDED` status, `POST /wants/{id}/start` and `/end` under the same lock, `WANT_STARTED` and `WANT_ENDED`. The screen has Start and End buttons, "Happening now" and "Ended" labels, and texts for both notifications.
5. **Done. Open invite:** a nullable `capacity` (null = no limit, never full), `openInvite` in the request both ways, `WANT_OPEN_INVITE` and `WANT_LIMITED`. The screen has an Open invite checkbox on Add and Edit, "N going" on open rows, and texts for both notifications.
6. **Done. Deadline and expiry job:** `expires_at`, refused signups and edits after it, `WANT_DEADLINE_CHANGED` and `WANT_EXPIRED`, reopen with a new deadline, and the job. The screen has a deadline picker (presets or a date) on Add, Edit and Reopen, "Closes …" and "Closed at its deadline" on rows, and texts for both notifications.
   - **Follow-up, not built: reload the list when a deadline passes.** The bell polls every 30 s, but the wants list reloads only after a change of yours, so a row keeps "Closes …" and Sign up after its deadline while the bell already shows `WANT_EXPIRED`. The API is right throughout (status is worked out on read); the screen just doesn't read again. Plan: one `setTimeout` in `useWants` (server state, so not in the component) for the soonest `expiresAt` still ahead in the list, plus a second's grace for clock skew, that calls `refresh()`, rescheduled after every load and cleared on change or unmount. A delay beyond `setTimeout`'s ~24.8-day cap waits that long and reschedules. One timer for the list, not one per want: a single `GET /wants` refreshes every row. Tests: `reloads the list when the soonest deadline passes`, `sets no timer when no want has a deadline ahead`. Polling the list on TanStack Query, or step 7, would also cover it, along with other people's changes.
7. **Optional: live updates over SSE**, if polling is too slow.
8. **Designed, not built: tell followers about new wants, with circles,** so people hear about wants they could join without opening the feed. In five PRs, each working without the next:
   1. **Done. 8a, announce to all followers:** `WANT_POSTED`, the `notifications_posted_once` index, an index on `follows (followee_id)`, and the notification's text on the screen.
   2. **Done. 8b, mute:** `follows.notify_new_wants` and `PUT` / `DELETE /follows/{userId}/notify`, with a toggle on the People screen. `GET /follows` and `GET /users` return `PersonResult` with `notifyNewWants`, so the screen knows who is muted; `UserSummary` stays as it is for lineups and actors.
   3. **8c, circles as groups, not yet used by any want:** the tables, `CIRCLE_ADDED` (with `circle_id` and `circle_name` on notifications), the `/circles` routes for owners and members, `/followers`, and a `/circles` screen: circles you own and are in, owner controls, members, leaving, and a by-name filter over your followers when adding. Backend and screen in separate PRs, backend first. The backend PR also adds the `CIRCLE_ADDED` case to `Notifications.tsx`'s `describe()` and updates the README's decision trees.
   4. **8d, circles decide who sees a want:** `wants.circle_id`, `circleId` and `allFollowers` on create and edit for any owner or member, with the feed, signups and announcing changed in the same PR. The feed queries `left join fetch w.circle`, so a want's circle name costs no extra query.
   5. **8e, the picker:** an audience select on Add and Edit ("General" first and the default, then your circles), and a pill naming the circle on rows that aren't General.
   - **Later:** roles and permissions for members; a per-circle mute; invites instead of adding outright; rate limiting on creating circles; grouping on the notifications screen, cursor pagination with a bounded `markRead`.
   - **Failure modes to keep in mind:** the JPQL and SQL copies of the visibility rule drifting (the IT test guards it); a failed announce rolling back the create or edit, which is on purpose; a future retention job deleting `WANT_POSTED` rows, which are also the "already told" markers; and the existing `markRead` hazard, which marks unread rows beyond the 50 shown read; an owner adding and removing someone repeatedly, which spams `CIRCLE_ADDED` until rate limiting exists; and unbounded `GET /circles`, `/members` and `/followers` lists, fine at tens of users and paged later.

Later ideas, not yet in the build order: **repeat a want**, running an ended want again as a new one, with the same name and its lineup invited.
