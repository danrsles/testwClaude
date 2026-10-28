# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

No Maven wrapper is checked in, so use a system `mvn` (3.9.x) with JDK 21.

```bash
mvn spring-boot:run          # run the app on http://localhost:8080
mvn test                     # unit tests only: fast, no Docker
mvn verify                   # unit tests, then the database tests (*IT), which need Docker
mvn verify -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false   # database tests only
mvn test -Dtest=HelloControllerTest            # single test class
mvn test -Dtest=HelloControllerTest#helloReturnsHelloWorld   # single test method
mvn package                  # build target/demo-0.0.1-SNAPSHOT.jar
java -jar target/demo-0.0.1-SNAPSHOT.jar       # run the packaged jar
```

No linter or formatter is configured for the Java code. The frontend has oxlint, Prettier and a Vitest suite — see **Frontend** below.

Container image:

```bash
docker build -t danrsles/wants-api:0.0.1 .
docker run -p 8080:8080 \
  -e DB_URL='jdbc:postgresql://<host>:<port>/defaultdb?sslmode=verify-full&sslrootcert=/certs/ca.pem' \
  -e DB_USER=... -e DB_PASSWORD=... \
  -v /path/to/ca.pem:/certs/ca.pem:ro \
  danrsles/wants-api:0.0.1
```

The image carries no credentials: `.dockerignore` keeps `config/` and `.env` out of the build context. The Dockerfile sets `ENV SPRING_PROFILES_ACTIVE=prod`, so every container runs the `prod` profile and needs the `DB_*` variables; `mvn spring-boot:run` is unaffected and stays on `local`. The Docker Compose support is not in the image. That is the Spring Boot Maven plugin's doing, not the Dockerfile's: `repackage` drops `spring-boot-docker-compose` from the jar by default (`excludeDockerCompose=true`). If someone overrode the profile back to `local`, the container would have no datasource at all.

## Frontend

A React + TypeScript app built with Vite lives in `frontend/`, in the same repository as the backend so an API change and its UI change land in one commit.

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
npm run build      # production bundle into frontend/dist
npm test           # vitest run — the whole suite once
npm run test:watch # vitest in watch mode
npm run lint       # oxlint
npm run format     # prettier --write .
npm run format:check
```

**Formatting is Prettier** (`.prettierrc.json`: no semicolons, single quotes, 120 columns). `endOfLine` is `auto` because `core.autocrlf` checks files out as CRLF on Windows; with Prettier's default of LF, every file reads as unformatted there. A project hook (`.claude/settings.json` → `.claude/hooks/prettier-format.mjs`) formats each file under `frontend/` after Claude writes it, so a file read straight after an edit may have been reflowed. The formatting commit is listed in `.git-blame-ignore-revs`; run `git config blame.ignoreRevsFile .git-blame-ignore-revs` once per clone so `git blame` skips it.

The dev server proxies `/api/*` to `http://localhost:8080`, stripping the prefix (see `vite.config.ts`). That keeps the browser on a single origin, so **the backend needs no CORS configuration**. Deploying the frontend on a different origin than the API would change that — then Spring would need CORS, or a reverse proxy would have to serve both under one hostname.

`src/` is organised by role:

```
src/api/http.ts                 `fail`, which throws an `HttpError` carrying the status, plus `authFetch`, which attaches the Clerk session token
src/api/wants.ts                transport: one function per endpoint, no state
src/api/me.ts                   the same for /me (GET and PUT)
src/api/people.ts               the same for /users?q=, /follows and /follows/{id}/notify
src/api/notifications.ts        the same for /notifications and /notifications/read
src/api/circles.ts              the same for /circles and /circles/{id}/members
src/api/followers.ts            the same for /followers, the people you can add to a circle
src/hooks/useWants.ts           server state: the cached list and its mutations
src/hooks/usePeople.ts          server state: who you follow, or search results, and follow/unfollow
src/hooks/useNotifications.ts   server state on TanStack Query: your notifications, polled, and markRead
src/hooks/useCircles.ts         server state on TanStack Query: your circles, a circle's members and your followers, and their changes
src/hooks/MeProvider.tsx        server state: the signed-in user, loaded once and shared
src/hooks/useOnPictureChange.ts calls back when you change your picture in Clerk, after minting a fresh token
src/hooks/useMe.ts              the context MeProvider fills, and the hook that reads it
src/types/wants.ts              the shapes the API returns and accepts
src/types/people.ts             Person, a search result or someone you follow
src/types/notifications.ts      Notification and its Actor
src/types/circles.ts            Circle and UserSummary, its owner or a member
src/components/Wants.tsx        the whole wants screen, including its own UI state
src/components/People.tsx       search users by name and follow or unfollow them, or mute their new wants, routed at /people
src/components/Circles.tsx      your circles and the ones you're in, routed at /circles
src/components/Notifications.tsx your notifications, routed at /notifications; opening it marks them read
src/components/NotificationsBell.tsx the header's bell: unread count, link to /notifications
src/components/Info.tsx         a static about page, routed at /info
src/components/DeadlinePicker.tsx the deadline select (presets or a date and optional time), on Add, Edit and Reopen
src/components/deadline.ts      the picker's fields and `deadlineOf`, which turns them into a moment
src/components/Avatar.tsx       round picture with a local placeholder fallback, beside each want
src/components/SignInScreen.tsx Clerk's <SignIn>, shown instead of the app when signed out
src/App.tsx               the shell: the sign-in gate, the page frame, the nav and the routes
```

Imports only ever point down that list: a component calls a hook, a hook calls `api/`, and **nothing but a hook calls `api/` directly**. `App.tsx` holds no state at all, so a second screen means a new component and a route rather than changes to the first one.

Routing is React Router (`react-router`, declarative mode). `main.tsx` wraps `App` in `ClerkProvider` and a `BrowserRouter`, and `App.tsx` holds the `<Routes>` and the nav, so `App.test.tsx` can render `App` inside a `MemoryRouter` at any path. Vite's dev server already falls back to `index.html` for unknown paths, so deep links like `/info` work in dev. A static host serving `dist/` would need the same fallback.

**Login is Clerk** (`@clerk/react` v6, Clerk Core 3; the old `@clerk/clerk-react` is deprecated). Core 3 replaced `<SignedIn>`/`<SignedOut>` with `<Show when="signed-in">`/`<Show when="signed-out">`, which `App.tsx` uses to gate the whole shell, so nothing fetches before there is a session. `api/http.ts` gets the token from Clerk's standalone `getToken()` export, which works outside React, so the transport needs no hook and the layering rule holds. `main.tsx` throws if `VITE_CLERK_PUBLISHABLE_KEY` is unset (`frontend/.env.local`, git-ignored by `*.local`; template in `.env.example`). The publishable key is public by design; **the Clerk secret key is used nowhere and must never be added**.

Which user the app acts as comes from the token, so no component passes a user id. The signed-in user is the one piece of state shared across screens:
- `App.tsx` wraps the signed-in shell in **`MeProvider`**, which makes a single `GET /me` and exposes it through React context.
- `Wants` reads it with `useMe()`; any later screen shares the same copy. It is read-only: everything about a user comes from Clerk and changes in Clerk's account menu (`<UserButton />`), so there is no profile page and no `PUT /me`.
- `useMe()` throws outside the provider, so a component test that renders one of them wraps it in `<MeProvider>`.
- `useMe.ts` holds the context and hook, and `MeProvider.tsx` the component. They are split because oxlint wants component files to export components only.

`Wants.tsx` shows edit controls only on rows where `want.userId` matches `me.id`, but that is cosmetic: the API returns 403 regardless. It names each owner by `want.username`, beside `<Avatar src={want.imageUrl} alt="" size={24} />`. The alt is empty because the username already names the person. After the seat count, a `<ul aria-label="Going to …">` shows `want.lineup` as 20px avatars whose alt is the username, since nothing else names them.

**Signups in the UI.** Each row shows `seatsTaken / capacity seats`, or `seatsTaken going` for an open invite (`capacity` null). Other people's rows get **Sign up**, or **Withdraw** when `want.signedUp`; full (`isFull`: `seatsTaken >= capacity`, never for an open invite) disables Sign up and shows "Full". Your own rows get neither, as you always hold a seat.
- `useWants` exposes `signUp` and `withdraw` through the same `mutate` as the rest, so both reload the list and a 409 (someone took the last seat) lands in the error alert. The feed, not the `SignupEventDetails` they return, is what the screen shows.
- The capacity fields hold the typed text, so they can be cleared on the way to a new number; `seats()` turns it into a whole number or null, and null disables Add or Save.
- Editing offers capacity with `min={want.capacity}`, since the API refuses to lower it, and `saveEdit` sends `capacity` only when it changed. Left out, the API keeps it.
- **Open invite** is a checkbox beside capacity on Add and in Edit; ticked, it disables the capacity field. On Add the field then shows "Any" instead of a number, with a screen-reader hint through `aria-describedby`. Add sends `openInvite: true` and no capacity (`add` with a null capacity). An open row gets an "Open invite" pill while people can still join, so not when closed or ended.
  - Edit starts it ticked for an open want. `saveEdit` sends `openInvite` only when the box ends up different from where it started: `true` opens, `false` limits an open want to the people already in. It never sends `capacity` with it, which the API refuses, so `useWants`' `SeatsChange` is either one or the other.
  - On an open want the capacity field stays disabled even unticked, showing `seatsTaken`, the limit it will get; a bigger one takes a second edit. Saving an open want unchanged sends neither, since a bare capacity is a 400.
  - Anyone who can see the want may join, as before; only the lineup hears of opening or limiting (`WANT_OPEN_INVITE`, `WANT_LIMITED`).
- `Notifications.tsx`'s `describe()` needs a case for every `NotificationType`, or that type's rows show a name and nothing else. `who()` says "You" for `WANT_CREATED`, whose actor is you, and nothing for `WANT_EXPIRED`: the clock closed it, so the row reads "'Pizza Friday' closed at its deadline" for the owner and the lineup alike, although the API stores the owner as its actor.

**Deadlines in the UI.** `DeadlinePicker` is a select (**No deadline**, **In 1 hour**, **Tomorrow**, **In 1 week**, **Pick a date**); the last shows a date field and an optional time field. It holds only the fields (`DeadlineDraft`); `deadlineOf` in `deadline.ts` turns them into a `Date` in local time, sent as `toISOString()`, or null for none, or `'invalid'` for a missing date or a moment not in the future, which disables Add, Save or Confirm. The picker then says why ("Choose a date", "Pick a time that hasn't passed"), linked to its date and time fields by `aria-describedby`, since a disabled button can't. The API checks again (400).
- Nothing re-renders as the clock passes a deadline, so a pick can go stale with its button enabled. `submit`, `saveEdit` and `confirmReopen` re-check it and, refused, call `recheck` (a `useReducer` counter) to re-render, so the reason shows instead of nothing happening.
- Tomorrow is noon tomorrow. In 1 week is the same clock time seven calendar days on, not 168 hours, so a clock change keeps the time of day. A date without a time is noon, or 23:59 when it is today and noon has passed.
- Separate date and time inputs, not `datetime-local`, so a date alone is possible. They have no time zone: `deadlineOf` builds the `Date` from local parts.
- An active row with a deadline shows "Closes" and a `<time dateTime>`. A closed row whose deadline has passed says "Closed at its deadline" (`closedAtDeadline`); one closed by hand says "Closed". The API already reports a passed deadline as `CLOSED`, before the job runs, so the screen trusts `status` and needs no timer.
- Edit starts from `draftOf(want.expiresAt)` and `saveEdit` compares the fields, not the moments, with `sameDraft`, so seconds the picker can't show never count as a change. Changed, it sends `expiresAt`, or `noDeadline: true` for No deadline (`useWants`' `DeadlineChange`, one or the other).
- **Reopen** on a closed row swaps the row's buttons for the picker, **Confirm** and **Cancel**. Without a new deadline the API keeps one still ahead and clears one that passed, so the none option says which (`keepLabel`): "Keep <time>" or "No deadline". It can't remove a deadline still ahead; Edit does that once the want is open. With none, `reopenWant` sends no body at all.
  - The step is its own `<span>`, so React never reuses its Confirm or Cancel node as the Start or Cancel want that follow; Escape leaves it. Leaving goes through `stopReopening`, which, like `stopEditing`, hands focus back through `refocus`: to Reopen after Cancel, to Edit after a successful Confirm.
- A refused change reloads the list as well (`mutate`'s `catch`), then shows the error. An edit refused because the want closed meanwhile, such as at its deadline (409), therefore leaves edit mode: `isEditing` is false for a closed or ended want, and Reopen takes focus. Pressing Reopen clears `editingId`, so that row never comes back in edit mode.
- Tests pin the clock with `vi.useFakeTimers({ toFake: ['Date'] })`, so promises and user-event's timers stay real, and build every expected moment from local parts, so they pass in any time zone. Date and time inputs are set with `fireEvent.change`, as typing into them in jsdom is unreliable.

**Focus stays put.** "Loading..." replaces the list only on the first load (`loading && wants.length === 0`), so the reload after every change keeps the rows mounted and keyboard focus where it was. Leaving edit mode (Save, Enter, Cancel or Escape) goes through `stopEditing`, which puts focus back on that row's Edit button through a callback ref, because the field that had it unmounts. A failed save keeps the row open and focus in it, unless the reload shows the want closed, when Reopen takes focus. The Reopen step does the same through `stopReopening`.

**Close, reopen, start, end and cancel in the UI.** `want.status` (can people join?) and `want.happening` (is it live?) drive each row; cancelled wants never reach the feed, so only `ACTIVE`, `CLOSED` and `ENDED` are drawn.
- Your active wants get the category menu, **Edit**, **Close** and **Cancel want**. Your closed ones get **Reopen** and **Cancel want** only, since editing a closed want is a 409.
- Both also get **Start**, or **End** once happening. Starting doesn't change who can join, so an active happening want still offers Edit to you and Sign up or Withdraw to others. **End** asks first with `window.confirm`, because it is final.
- A happening row shows "Happening now". A closed row shows a "Closed" label, and other people get neither Sign up nor Withdraw on it. An ended row shows "Ended" and no buttons for anyone, since every change to it is a 409.
- **Cancel want** asks first with `window.confirm`, because it is final and tells the lineup; Close needs no confirmation, as Reopen undoes it. It is not just "Cancel", which already leaves edit mode. Tests stub it with `vi.spyOn(window, 'confirm')`.
- `useWants` exposes `close`, `reopen`, `start`, `end` and `cancel` through `mutate`, like the rest. `api/wants.ts`'s `cancelWant` is the `DELETE`.

**Picture changes show at once.** The API learns your picture only from the session token, which Clerk otherwise reissues about once a minute. `Wants` therefore calls `useOnPictureChange(refresh)`:
- The hook watches `useUser().user.imageUrl`. Clerk updates it in the page as soon as you change your picture in its menu.
- On a change, it calls Clerk's standalone `getToken({ skipCache: true })`, which mints and caches a new token, then calls `refresh`. The reload's `GET /wants` carries that token, and the API syncs your row before listing (see `WantService.findAll`).
- The first value, Clerk finishing loading the user, is not treated as a change.
- `Wants.test.tsx` mocks `@clerk/react` (`useUser`, `getToken`) with a `vi.hoisted` user whose `imageUrl` a test changes between `rerender`s. `App.test.tsx`'s Clerk mock includes both too.

**Circles in the UI** (`Circles.tsx`, `useCircles.ts`, on TanStack Query like notifications):
- Query keys `['circles', me.id]`, `['circles', me.id, circleId, 'members']` and `['followers', me.id]`, disabled until `/me` loads. Each change writes its result into the cache, then refetches. Pending and failed state is per row (`useRowChanges`), keyed by circle id, or by person id for members; `NEW_CIRCLE` (0) is the create form.
- The screen checks names itself (`nameProblem`: blank, over 40, "General", a duplicate among circles you own) and says why Add or Save is disabled, because the API's 400 has no message.
- Refusals are worded from the status (`explain`, through `HttpError`), never the raw body, which is Spring's JSON. A 404 refetches the list, so a circle deleted elsewhere leaves it; on Delete or Leave a 404 counts as done (`unlessMissing`).
- Opening a circle you own lists your followers with an "In <circle>" box each, filtered by name on screen. A circle you're in shows its owner and members, and Leave. Delete and Leave confirm with `window.confirm`, then move focus to the neighbouring circle, or the "New circle" field when none is left (`focusAfterGone`).

If more state needs sharing, reach for TanStack Query rather than more providers. **Notifications already do** (`@tanstack/react-query`, the `QueryClientProvider` in `main.tsx`):
- `useNotifications` is called by both `NotificationsBell` and `Notifications`; the cache serves both from one request, so marking read on the page clears the badge in the same render.
- It polls with `refetchInterval` (`POLL_MS`, 30 s), which pauses in background tabs, and refetches on window focus. The bell is in the header of every signed-in screen, so polling never stops while the app is open.
- The query key is `['notifications', me.id]`, from `useMe`, so after signing in as someone else the previous person's cached list is never shown. It is disabled until `/me` has loaded.
- `markRead` updates the cache optimistically, then refetches. Its `mutationFn` wraps the `api/` call because TanStack v5 passes a context object as a second argument.
- `Notifications.tsx` marks up to the newest id it shows (`upTo`), not "all", so one that arrived after the page rendered stays unread. A ref remembers the last id sent, so a failed mark is not retried on every render.
- Tests render inside a fresh `QueryClient` with `retry: false`; `App.test.tsx` wraps `App` in one too and stubs `api/notifications`.

`Avatar.tsx` shows `src/assets/avatar-placeholder.svg` when its `src` is null and also when the URL fails to load. Vite inlines that SVG as a `data:` URI, so tests compare `src` against the imported asset rather than matching a file name.

Component tests are Vitest with React Testing Library, in a jsdom environment configured in the `test` block of `vite.config.ts` (which is why that file imports `defineConfig` from `vitest/config` rather than `vite`). Tests sit beside the component as `<Name>.test.tsx` and stub the `api/` module with `vi.mock`, never `fetch`, so they survive a change of transport and need no server. Because `api/` is stubbed, they never reach Clerk's `getToken`. Anything that renders `App` must also `vi.mock('@clerk/react')`, as `App.test.tsx` does with a `vi.hoisted` signed-in flag, because the real `<Show>` and `<SignIn>` load Clerk from the network.

Two things about the setup are easy to trip over:

- **Vitest globals are off**, so a test imports `describe`, `it`, `expect` and `vi` from `vitest` explicitly.
- Because globals are off, Testing Library does not register its own teardown. `src/setupTests.ts` calls `afterEach(cleanup)` by hand. Without it every render stays in the document and queries in later tests match elements left behind by earlier ones — which looks like a mysterious "found multiple elements" failure in every test but the first.

`/component` (see `.claude/skills/component/SKILL.md`) creates a component with tests following these conventions and iterates until the suite passes.

`/ship` (see `.claude/skills/ship/SKILL.md`) takes the current work to master: it shows the files and commit message once for approval, runs the checks that match the diff, commits, pushes, opens the PR, waits for CI and merges. Only the user can start it (`disable-model-invocation`). It squash-merges by default, but uses a merge commit when commit ids must survive, as for `.git-blame-ignore-revs`.
- master has two GitHub rulesets. **Checks** binds everyone, with no bypass: a PR, "Test API" and "Test and build web" green, the branch up to date with master, no force-push or deletion, squash or merge commits only.
- **Review** requires one approval given after the last push, with every conversation resolved, and lets the Admin role bypass it when merging a PR. The owner is the only Admin; collaborators have Write, so their PRs wait for the owner's review.
- `/ship` therefore merges the owner's own PR with `--admin` once Checks passes. It never does so for anyone else's PR. A branch that is behind or conflicting is brought up to date by merging master in, never by rebasing.

The `ui-ux-reviewer` subagent (`.claude/agents/ui-ux-reviewer.md`) reviews a component in a real browser through the Playwright MCP server: it screenshots desktop and mobile, light and dark, walks the page by keyboard, and reports visual, UX and accessibility findings with file:line fixes. It never edits code. It may press buttons that write only after proving the API is on the `local` profile, by finding a want from the page in the `demo-postgres` container (a seeded want proves nothing, since seeds are in every database), because with the `prod` profile the same port writes to Aiven. It then tests on wants it creates itself and checks each write with a read-only `select`. Screenshots land in `.playwright-mcp/`, which is git-ignored.

The `system-design-advisor` subagent (`.claude/agents/system-design-advisor.md`) advises on architecture before something is built: a planned feature, a design doc in `docs/design/`, or a choice between approaches. It reads `CLAUDE.md`, the design docs and the relevant code, weighs the options against this project's real limits (one API instance, Aiven's ~17 connections, CloudFront's 30 s idle timeout, one-minute Clerk tokens, a $50 budget), and answers with a recommendation, trade-offs, failure modes, a sketch and the tests that would prove it. Its practices are written from general system design knowledge, not copied from any course. It never edits code. Keep its constraints section in step when the deployment changes.

The `legal-launch-advisor` subagent (`.claude/agents/legal-launch-advisor.md`) audits launch readiness: privacy law (GDPR, CCPA), children's privacy, terms of service, user-generated content and moderation, security and breach duties, accessibility, app store rules and dependency licences. It builds a data inventory from the entities, migrations and infrastructure, then lists what is missing as blockers, should-fix and later, each with `path:line` evidence. It may use web search to check current rules and cites what it checks. It is not a lawyer, says so, and says when one is needed. It never edits code. Rerun it when a feature collects new personal data or user content.

Styling is Tailwind CSS v4, wired through the `@tailwindcss/vite` plugin. v4 has **no `tailwind.config.js`** — everything is declared in CSS. `src/index.css` holds the category colours (an `@theme` block, used as `bg-food`, `bg-movie` and so on) and the shared `field`, `btn` and `btn-primary` classes (`@utility` blocks, which is why there is no styles constants module). `btn-primary` is the green main action (Add, Follow). `btn` and `btn-primary` style `aria-disabled` like `disabled`, for buttons that must keep focus while blocked, as on People. No utility sets padding — callers size themselves, so two utilities never fight over the same property. Dark mode uses the `dark:` variant, which follows `prefers-color-scheme` by default. The `body` rule also sets `scheme-light dark:scheme-dark`, so native controls such as checkboxes and selects turn dark too; checkboxes take `accent-emerald-*` and an emerald `focus-visible` outline.

State is local `useState`. All server data is refetched after each mutation, which is fine at this size; if the UI grows past one screen, reach for TanStack Query (server cache) before reaching for Redux (shared client state).

## Architecture

**Planned, partly built:** the design for notifications and for signups on wants with a capacity is in `docs/design/wants-signups-and-notifications.md`. Steps 1 (notifications for follows), 2 (capacity and signups, with their screen) and 3 (close, reopen and cancel, with their buttons) are built and described below, and so is step 4's start and end, with their buttons. Step 5's open invite is built too, with its screen, and so is step 6's deadline and expiry job, with its screen. Of step 8, 8a (announcing new wants to all followers) and 8b (muting, with its checkbox) are built, and so is 8c, circles with their API and screen; wants in circles (8d, 8e) are not. Everything below describes the code as it is; move facts here as each step lands.

A single-module Spring Boot 3.5.6 web application on Java 21. `DemoApplication` sits in the root package `com.example.demo` so component scanning reaches the layers beneath it:

- `models` — JPA entities and enums (`Want`, `User`, `Follow`, `Circle`, `WantSignup`, `Notification`, `NotificationType`, `Category`, `WantStatus`)
- `repositories` — Spring Data interfaces (`WantRepository`, `UserRepository`, `FollowRepository`, `CircleRepository`, `WantSignupRepository`, `NotificationRepository`)
- `services` — business logic, the only layer controllers talk to (`WantService`, `UserService`, `FollowService`, `CircleService`, `SignupService`, `NotificationService`), plus `ClerkIdentity` and `WantExpiryJob`
- `controllers` — HTTP endpoints (`WantController`, `UserController`, `FollowController`, `FollowerController`, `PeopleController`, `CircleController`, `SignupController`, `NotificationController`), plus `Identities`, which turns a token into a `ClerkIdentity`
- `dto` — request bodies with their Bean Validation constraints (`WantRequest`, `MarkReadRequest`, `CircleRequest`) and response shapes (`WantResponse`, `UserSummary`, `PersonResult`, `CircleResponse`, `SignupEventDetails`, `NotificationResponse`). New request and response records go here, not in `controllers`.
- `security` — `SecurityConfig` (the filter chain), `ClerkJwtConfig` (the `JwtDecoder`), `AuthorizedPartyValidator` (the `azp` check) and `OriginSecretFilter`, which rejects requests that did not come through CloudFront

**Authentication.** Every route needs a Clerk session token in `Authorization: Bearer`, except `/actuator/health` and `/error`. Spring Security runs as an OAuth2 resource server. It is stateless, and CSRF is off, because nothing reads cookies.
- `ClerkJwtConfig` builds the decoder from `${app.auth.issuer}/.well-known/jwks.json`. Startup makes no network call; keys are fetched on the first token. It validates expiry, the issuer (an exact match, so no trailing slash), and `azp` against `app.auth.authorized-parties`.
- Both properties are strict `${CLERK_ISSUER}` / `${CLERK_AUTHORIZED_PARTIES}` placeholders in `application.properties`. `application-local.properties` sets both: the parties to `http://localhost:5173`, and the issuer to the Clerk development instance `https://enabled-walleye-998.clerk.accounts.dev` (not secret, and the host the frontend's `pk_test_` key encodes), so `mvn spring-boot:run` needs no variables. A blank issuer, or an empty party list, throws at startup: **fail closed**, like the origin check.
- `OriginSecretFilter` carries `@Order(SecurityProperties.DEFAULT_FILTER_ORDER - 1)`, so it runs before Spring Security and a request that skipped CloudFront gets 403 before its token is looked at.
- The backend never uses Clerk's secret key. Don't add it.

**Identity.** A controller takes `@AuthenticationPrincipal Jwt jwt` and passes `Identities.of(jwt)` to the service. That is a `ClerkIdentity(clerkId, username)` built from the token's `sub` and its custom `username` claim, so services never see Spring Security types. **Never accept a user id from the request body or the query string.**
- `UserService.current(who)` finds the user by `users.clerk_id`. If there is none, it creates one through `insertIfAbsent`, a native `INSERT ... ON CONFLICT DO NOTHING`, always with the Clerk id as the username, which cannot clash. Two simultaneous first requests are safe that way. Catching a unique-constraint exception instead would mark the surrounding transaction rollback-only.
- It then syncs from the claims onto the managed `User`, so an unchanged user is never written:
  - `username`: on creation, and whenever the name changes in Clerk. It skips the sync when the claim is missing or blank, or when the name belongs to another row here (`existsByUsernameAndIdNot`), such as the seeded `dani`.
  - `email` and `lastSignInAt`: a missing claim never erases the stored value.
  - `imageUrl`, the Clerk profile picture: the same rule. Removing a photo in Clerk doesn't empty the claim; Clerk sends its silhouette's URL instead, which is copied like any change.
- The claims exist only because the Clerk session token is customised (dashboard → **Sessions → Customize session token**):

  ```json
  {"username": "{{user.username}}", "email": "{{user.primary_email_address}}", "lastLogin": "{{user.last_sign_in_at}}", "imageUrl": "{{user.image_url}}"}
  ```

  Without them, everyone stays named by Clerk id, with no email and no picture.
- Clerk always fills `image_url`, with a generated silhouette when the user has no photo. `Identities.pictureOf` uses it as it is (blank counts as none), so the app matches Clerk's own account button; there is deliberately no `hasImage` claim. So `imageUrl` is null only for users Clerk has told us nothing about, such as the seeded `dani`, and `Avatar.tsx` shows the placeholder for them. Session tokens are reissued about every minute, so a picture changed in Clerk reaches the API within that.
- `lastLogin` arrives as Unix seconds. Spring converts only the standard time claims to `Instant`, so `Identities.epochSeconds` does it for this one.
- Renaming a claim in the dashboard means changing `Identities.of` as well. Ownership checks live in services: `WantService.requireOwned` gives 404 for an unknown want and 403 (`WantForbiddenException`) for someone else's. Wants whose owner has no `clerk_id`, such as the seeded `dani`'s, are therefore read-only.

Controllers depend on services, never on repositories directly, and all dependencies are injected through constructors rather than fields, which keeps them explicit and testable without a container.

Endpoints:

- `GET /me` — the signed-in user (`id`, `username`, `clerkId`, `email`, `lastSignInAt`, `imageUrl`), created on first call. Read-only: every field comes from Clerk, so there is no `PUT /me` (it answers 405). **There is deliberately no route to another user's record.**
- `GET /wants` — **your feed**: your own wants plus those of everyone you follow, never anyone else's, as `WantResponse` (`{id, message, category, userId, username, imageUrl, capacity, seatsTaken, lineup, signedUp, status, happening, expiresAt}`), ordered by want id. Cancelled wants are left out, ended ones are not, as they are by `isInFeed`. `WantService` builds the responses, because `lineup` (everyone seated, as `UserSummary`s in signup order, the owner first) comes from `want_signups` in one query for the whole list (`findLineups`), never one per want; `seatsTaken` is its size and `signedUp` whether your id is in it. `WantRepository.findFeed` / `findFeedByCategory` are JPQL with a `Follow` subquery; Spring Data parses them at startup, so a mistake there fails `mvn spring-boot:run` but not `mvn test`. It first runs `UserService.current` for the caller in the same transaction, so a picture or username just changed in Clerk shows beside their own wants in that response rather than racing the page's parallel `GET /me`. The list can be narrowed by an optional `?category=` parameter. Spring converts that parameter to the `Category` enum by exact name, so `FOOD` works while `food` or an unknown value yields 400 before the controller runs.
- `POST /wants`, `PUT /wants/{id}`, `DELETE /wants/{id}` — `WantRequest` is `{"message", "category", "capacity", "openInvite", "expiresAt", "noDeadline"}` with Bean Validation constraints, so a blank message, unknown category, capacity below 1 or a deadline not in the future (`@Future`) is a 400 before the service is reached. `capacity` is optional so older clients keep working: 1 on create (`capacityOnCreate`), unchanged on update. Creating seats the owner and sends them `WANT_CREATED`.
  - **Announcing (`WANT_POSTED`).** After a create, and after every edit, a want with a seat free for others (capacity above 1, or open) is announced to the owner's followers through `NotificationRepository.announce`: one native `INSERT ... SELECT` from `follows`, skipping anyone in the lineup, ending in `ON CONFLICT DO NOTHING` against the partial unique index `notifications_posted_once`, so each follower hears of a want once at most and no flag on the want is needed. A want created for one seat is therefore announced when its capacity is raised, and someone who followed since hears on the next edit. The free-seat check is in Java, under the row lock on edits. The SQL repeats the feed's visibility rule; `WantAnnounceIT.everyoneToldCanSeeTheWant` fails if the two drift. A failed announce rolls back the create or edit, on purpose. Lowering capacity is a 400 (`CapacityLoweredException`).
  - **Open invite** is a null `capacity`: no limit, never full (`SignupService` skips the count), still only for people who can see it. `openInvite` is three-way, a `Boolean` through to `WantService.update`: `true` opens (or creates open), `false` limits an open want to exactly its lineup, counted with `countByWantId` under the row lock `requireOwned` holds, and null keeps it. It is a flag because a left-out `capacity` already means 1 or "keep". Sending a capacity with it, either way, fails `isCapacityOrOpenInvite` (400); a bare capacity on an open want is a `CapacityLoweredException`. Opening and limiting notify the lineup (`WANT_OPEN_INVITE`, `WANT_LIMITED`); a repeat that changes nothing does not. A new message or category notifies the lineup (`WANT_UPDATED`, through `notifyLineup`); `update` uses `saveAndFlush` first, because that native insert copies the message from the table. The owner is the token's user, and an unknown property such as a stale `userId` is ignored. Changing someone else's want is a 403 via `WantForbiddenException`, which carries `@ResponseStatus` rather than needing an exception handler. Editing a closed, ended or cancelled want is a 409 (`WantClosedException`, `WantEndedException`, `WantCancelledException`): a closed one is reopened first. A happening want that is still active can be edited. **`DELETE` cancels** (`WantService.cancel`); it never deletes the row.
- `POST /wants/{id}/close`, `POST /wants/{id}/reopen`, `POST /wants/{id}/start`, `POST /wants/{id}/end`, and `DELETE /wants/{id}` as cancel — owner only (403), each 409 on a cancelled or ended want (`requireNotFinal`) except a repeat of the same one. All but cancel return the `WantResponse`.
  - `status` (`WantStatus`) is `Want.getStatus(now)`, worked out from `cancelled_at`, `ended_at`, then `closed_at` **or a passed `expires_at`**. Full is not a status. Cancelling and ending are final; reopening clears `closed_at`. An ended want stays in the feed.
  - **Time comes from a `Clock` bean** (`DemoApplication.clock()`, `Clock.systemUTC()`), injected into `WantService` and `SignupService`, which pass `clock.instant()` to `getStatus` and `WantResponse.from`. Never call `Instant.now()` there. Their unit tests build the service by hand with `Clock.fixed(...)`, since `@InjectMocks` would pass null for it.
  - **Happening is not a status.** `happening` is `Want.isHappening()`: `started_at` set, and neither ended nor cancelled. `status` still says who can join, so an `ACTIVE` happening want takes signups and a `CLOSED` one does not. Start works from either; end needs a happening want (409, `WantNotHappeningException`).
  - Each goes through `requireOwned`, so it holds the row lock, and checks the status in Java. A repeat therefore changes nothing and notifies nobody (`closingTwiceNotifiesOnce`). The change is written by dirty checking at commit, with no `save`, and the times come from the `Clock`.
  - Each notifies the lineup but not the owner, through `notifyLineup` (`WANT_CLOSED`, `WANT_REOPENED`, `WANT_CANCELLED`, `WANT_STARTED`, `WANT_ENDED`).
  - Cancel keeps the row, its lineup and its notifications, which point at it.
- **Deadlines** (`wants.expires_at`, nullable). A want past its deadline is `CLOSED` from that moment, through `getStatus(now)`, so every check under the row lock refuses signups, withdrawals and edits at once. Correctness never waits for the job.
  - `PUT` sets or moves it (`expiresAt`), removes it (`noDeadline: true`, 400 with `expiresAt`), or keeps it when both are left out. A change tells the lineup (`WANT_DEADLINE_CHANGED`); the same one again tells nobody.
  - `POST /wants/{id}/reopen` takes an optional `ReopenRequest` body (`{expiresAt}`, `@Future`). A new deadline replaces the old one; without one, a deadline still ahead is kept and one that has passed (even while the want was closed) is cleared, so it doesn't close again at once (`Want.reopen(newDeadline, now)`). Reopening an active want still changes nothing.
  - Close on an expired want the job hasn't reached is a no-op, as it is already `CLOSED`; the lineup gets `WANT_EXPIRED` from the job, not `WANT_CLOSED`.
- **`WantExpiryJob`** (`@Scheduled(fixedDelay = 1 minute)`, `@EnableScheduling` on `DemoApplication`) calls `WantService.expireDue`, which runs `WantRepository.expireDue`: one native statement, an `UPDATE ... RETURNING` in a CTE feeding the `INSERT` into `notifications`, so the want closes and everyone seated, the owner included, gets `WANT_EXPIRED` together.
  - It takes no row lock first. Its `WHERE closed_at IS NULL AND cancelled_at IS NULL AND ended_at IS NULL AND expires_at <= now()` guard is the whole concurrency story: a second run or instance, or a request holding the lock, makes the `UPDATE` wait and re-check, so each want flips once. No ShedLock. Removing any part of the guard fails `WantExpiryIT`.
  - `closed_at` is the database's `now()`, when the job ran, not the deadline, on purpose: a late run should look late.
  - It is in the API, not a separate worker, because a worker would need its own Aiven connections and deploy; see the design doc. It logs only when it sent something.
  - Switched by `app.expiry.enabled` (`havingValue = "true"`, so missing means off, which is safe). `application.properties` sets it true; `src/test/resources/config/application.properties`, which Spring reads after the classpath root and only the test classpath has, sets it false, so the job never races the `*IT` tests, which call `expireDue` themselves.
- `PUT /follows/{userId}`, `DELETE /follows/{userId}` — follow or unfollow, both 204 and idempotent. The follower is the token's user. Following yourself is a 400 (`CannotFollowYourselfException`), an unknown user a 404 (`UserNotFoundException`). Following again is a no-op through `FollowRepository.followIfAbsent`, a native `INSERT ... ON CONFLICT DO NOTHING`, for the same rollback-only reason as `insertIfAbsent`. The **People** screen (`People.tsx`) is the UI for both.
- `POST /wants/{id}/signups`, `DELETE /wants/{id}/signups` — take or give up your seat, both 200 with `SignupEventDetails` (`{wantId, message, capacity, seatsTaken, lineup, signedUp, status}`, the lineup as `UserSummary`s in signup order, from `WantSignupRepository.findLineup`, a JPQL constructor expression) and both idempotent. Full is a 409 (`WantFullException`); the owner withdrawing is a 400 (`OwnerCannotWithdrawException`); a want not in your feed is a 404, unless you are already in its lineup. Only an active want's lineup changes: `requireActive` runs right after the lock and answers 409 on a closed, ended or cancelled want to everyone who can see it, even a member repeating a signup, and 404 to anyone else, so the 409 never reveals the want. New signups and withdrawals notify the owner (`WANT_SIGNUP`, `WANT_WITHDRAWN`) only when `signUpIfAbsent` or `withdraw` changed a row.
  - **Capacity is enforced by locking, then counting.** `SignupService` calls `WantRepository.findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`, i.e. `SELECT ... FOR UPDATE`) first, then checks membership, the feed and `countByWantId` in separate statements, then inserts. A second signup for the last seat waits on the lock and its count sees the first one's commit. Never fold the count into the insert (`INSERT ... SELECT ... WHERE count < capacity`): the wants row is not modified, so PostgreSQL does not re-check a waiting statement, and both get in. Every change to a want, including `WantService.update`, `close`, `reopen`, `start`, `end` and `cancel` through `requireOwned`, takes the same lock. `WantSignupsIT`'s two race tests fail if `@Lock` is removed.
- **Circles** (`CircleController` → `CircleService`), groups like group chats. `GET /circles` lists circles you own or are in as `CircleResponse` (`{id, name, owner}`, the owner a `UserSummary`); `POST /circles` (201) and `PUT /circles/{id}` take `CircleRequest` (`@NotBlank @Size(max = 40)`); `DELETE /circles/{id}` archives (`archived_at`); `GET /circles/{id}/members` returns `UserSummary`s, not the owner; `PUT` / `DELETE /circles/{id}/members/{userId}` add (owner) and remove (owner, or yourself to leave). `GET /followers` (`FollowerController` → `FollowService.followers`) lists who follows you, to add from.
  - Roles: owner may do anything; a member asking for an owner action gets 403 (`CircleForbiddenException`); anyone else, or any route on a deleted circle, 404 (`CircleNotFoundException`). `requireOwned` checks owner, then member, under the circle's row lock. Adding a non-follower (or unknown user) is 400 (`NotYourFollowerException`), decided only once you are known to be the owner; the owner leaving is 400 (`OwnerCannotLeaveException`). Names are trimmed; "General" in any case (`ReservedCircleNameException`) and a duplicate among your live circles, ignoring case (`DuplicateCircleNameException`), are 400. No cap on how many.
  - Locks: every change loads the circle with `findLiveForUpdate` (`@Lock(PESSIMISTIC_WRITE)`, with `archivedAt is null` in the query, so one that waited behind a delete re-checks and gets 404). Create and rename also lock the owner's `users` row (`UserRepository.lockForCircleNames`, native `FOR NO KEY UPDATE`, so inserts that only reference the user don't wait). Adding locks the follow row first (`FollowRepository.lockFollow`, `FOR KEY SHARE`): an unfollow that won has deleted it (400); one that comes later waits. Then `addMemberIfAbsent` (`ON CONFLICT DO NOTHING`) and `CIRCLE_ADDED` only when it returned 1.
  - `circle_members` has no entity; `CircleRepository` reaches it with native SQL (`findMine`, `isMember`, `findMembers`, `addMemberIfAbsent`, `removeMember`). The owner is never a row in it. Its key `(member_id, owner_id)` → `follows (follower_id, followee_id) ON DELETE CASCADE` limits members to the owner's followers and removes them on unfollow.
  - Membership changes notify nobody except being added (`CIRCLE_ADDED`, through `NotificationRepository.notifyAboutCircle`, which copies the circle's name in SQL like `notifyAboutWant`).
  - Hibernate runs a derived delete's `DELETE` at flush, normally commit. `CirclesIT`'s add-versus-unfollow race calls `followRepository.flush()` so the follow row is locked while the test holds the unfollow open.
- `GET /follows` — the users you follow, as `PersonResult` (`{id, username, imageUrl, following, notifyNewWants}`, `following` always true). `FollowService.following` returns the `Follow` rows and the controller maps them, so `FollowControllerTest` still starts from a `User` with private details.
- `DELETE /follows/{userId}/notify` mutes, `PUT` unmutes: both 204, idempotent, 404 (`NotFollowingException`) when you don't follow them. Each is one JPQL `UPDATE` (`FollowRepository.setNotifyNewWants`) whose matched-row count is the 404 check. `follows.notify_new_wants` defaults to true and `Follow` maps it read-only, like `created_at`; unfollowing deletes the row, so following again unmutes. `announce` skips muted follows (`AND f.notify_new_wants`). Unmuting announces nothing by itself; a want posted meanwhile reaches you only if its owner edits it later. The People screen shows the box only for people you follow; `usePeople`'s `toggleFollow` and `toggleNotify` share `change`, so one request per person is in flight and a failure shows in that row.
- `GET /users?q=` — people search (`PeopleController` → `FollowService.search`): up to 20 users whose username contains `q`, ignoring case, never the caller, as `PersonResult` (`{id, username, imageUrl, following, notifyNewWants}`). A blank or missing `q` returns `[]` rather than everyone. `following` comes from `FollowRepository.findFolloweeIds` and `notifyNewWants` from `findMutedFolloweeIds` (false too for people you don't follow), JPQL parsed at startup like the feed queries. This is the only way to reach another user, and only by public fields.
- `GET /notifications` — your 50 newest notifications, newest first, as `NotificationResponse` (`{id, type, actor, createdAt, read, wantId, wantMessage, previousMessage}`), the actor a `UserSummary`. Types: `FOLLOW`, `WANT_CREATED`, `WANT_SIGNUP`, `WANT_WITHDRAWN`, `WANT_UPDATED`, `WANT_CLOSED`, `WANT_REOPENED`, `WANT_CANCELLED`, `WANT_STARTED`, `WANT_ENDED`, `WANT_OPEN_INVITE`, `WANT_LIMITED`, `WANT_DEADLINE_CHANGED`, `WANT_EXPIRED`, `WANT_POSTED`, `CIRCLE_ADDED` (with `circleId` and `circleName` in the response). Guarded by `NotificationControllerTest.listsYourNotificationsWithTheActorByPublicDetailsOnly`. `POST /notifications/read` with `{"upTo": id}` marks yours up to that id read (204; 400 without `upTo`). Both act only on the token's user; there is no route to anyone else's.
  - The table is the letterbox: a row is written for every event whether or not the recipient is online, in the same transaction as the event. The frontend polls; there is no push.
  - `FollowService.follow` notifies only when `followIfAbsent` returns 1, the rows it inserted, so following again does not notify twice.
  - `NotificationRepository.notify` and `markRead` are native SQL, so the database fills in `created_at` and `read_at`; the entity maps both read-only. `markRead` never overwrites an existing `read_at`.
  - `notifyAboutWant` and `notifyLineup` copy the want's current message in SQL (`INSERT ... SELECT`), so a notification keeps the name it was written with. `notifyLineup` writes one row per signup except the owner's, in one statement. Its `previousMessage` is `CAST(... AS VARCHAR)`, because PostgreSQL cannot type a null parameter.
  - A new kind of notification needs a `NotificationType` constant **and** a migration widening `notifications_type_check`, or the insert fails.
  - The README's decision trees (**How status and notifications are decided**) show every action's outcome and notification. A new action, status or notification type changes them in the same PR.
- `GET /actuator/health` — the only unauthenticated route. Actuator exposes nothing else by default, and the health details stay hidden.

**Every field of `User` comes from Clerk:** `username`, `clerk_id`, `email`, `last_sign_in_at` and `image_url`, all copied from the token by `UserService`. Nothing about a user is edited through this API.
- There used to be a separate `user_profiles` table, kept apart only so email stayed out of every want; V7 folded it into `users`.
- V8 then dropped the user-edited `nickname` and turned `s3_url` into `image_url`, when the profile page was removed in favour of Clerk's account menu.

**`User` holds private data**, so it is serialized **only for its own user**, by `GET /me`. **Never return a `User`, or an entity that embeds one, for another user.**
- Wants are returned as `WantResponse` (`id`, `message`, `category`, `userId`, `username`, `imageUrl`). The picture URL is public by design.
- `WantControllerTest.neverExposesTheOwnersPrivateDetails` guards that.
- Any new endpoint that lists other people's data needs its own response record copying only public fields. `UserSummary` is that record for users in lineups and notifications, and `PersonResult` the one for `GET /follows`, guarded by `FollowControllerTest.listsWhoYouFollowByPublicDetailsOnly`, and for search (`GET /users`), guarded by `PeopleControllerTest`.

Each `Want` belongs to a `User` through a non-null `@ManyToOne` (`user_id`, a real foreign key). The association is deliberately unidirectional: a `User` has no `List<Want>`, which avoids the infinite recursion a bidirectional pair would cause during JSON serialization. Query wants by user through the repository rather than navigating from the user.

`Follow` (`follows` table) is the same shape: two non-null eager `@ManyToOne`s to `User`, `follower` and `followee`, and no collections on `User`. The table has `UNIQUE (follower_id, followee_id)` and `CHECK (follower_id <> followee_id)`. `created_at` is filled by the database, so the entity maps it read-only.

`@ManyToOne` defaults to eager fetching, and that matters here because `spring.jpa.open-in-view=false` closes the persistence context before serialization — a lazy association would throw `LazyInitializationException` while Jackson writes the response. If this is ever made lazy for performance, the controller's query must fetch-join the user.

Responses are a mix. `WantController` maps every `Want` to `WantResponse` (a record in `dto`, built by `WantResponse.from`). `User` is still serialized as an entity by `/me`, which is safe only because it is always the caller's own row. When a field must stay hidden, add a response record rather than annotating the entity with `@JsonIgnore`.

There are two kinds of backend test, split the standard Maven way:
- **Unit tests**, `*Test.java`, run by Surefire in `mvn test`. They need no database and no Docker, as described below.
- **Database tests**, `*IT.java`, run by Failsafe in `mvn verify` after the unit tests. They `@Import(TestcontainersConfiguration.class)`, which starts the same `postgis/postgis:18-3.6` image as `compose.yaml` in Docker and points the datasource at it with `@ServiceConnection`. The whole context starts, so Flyway migrates an empty database and Hibernate validates every entity: a broken migration fails here, not only at `mvn spring-boot:run`. The container is a bean, so every IT class sharing that setup shares one container through Spring's context cache. Docker Compose support is skipped in tests by default, so the dev database on 5433 is never touched.
  - They are **not** wrapped in test transactions, so service calls commit as in the app and a rollback can be observed. Each test signs up its own users with fresh Clerk ids instead of cleaning tables.
  - `FollowNotificationsIT` covers what the fakes cannot: `followIfAbsent` returning 1 then 0, a new follow starting unmuted and following again unmuting, one notification per new follow, the follow rolling back when its notification fails (`@MockitoSpyBean NotificationService`), `markRead`'s `upTo` bound and first-read time, and the cascade on user delete.
  - `WantSignupsIT` covers capacity, signing up and withdrawing, the feed's seat counts, who is notified of what, the migration's backfill, and the last-seat race. One race test holds the first signup's transaction open on a latch and asserts the second is still blocked after 500 ms; the other sends five at once for one seat, with fewer threads than the connection pool so they wait on the lock, not for a connection.
  - `WantLifecycleIT` covers close, reopen and cancel: what each still allows, the 409s and the 404 for people who can't see the want, who is notified, that a repeat notifies once, and that cancel keeps the row and hides it from the feed.
  - `WantExpiryIT` covers deadlines: refused signups, withdrawals and edits before the job runs, one `WANT_EXPIRED` per seat however often or concurrently `expireDue` runs, the job waiting for and then skipping a want being reopened, and catching up after downtime. It moves deadlines into the past with SQL (`backdate`), since the API refuses them.
  - `CirclesIT` covers circles: owner and member views, leaving, reusing a deleted circle's name, one `CIRCLE_ADDED` per add, the unfollow cascade, and three races on latches (same-name creates, add against unfollow, leave and add against delete). Removing `lockFollow` fails the second with a foreign key error.
  - `WantAnnounceIT` covers `WANT_POSTED`: who is told (followers, not the owner, non-followers, the lineup or followers who muted the owner, and muting one owner leaves others alone), once however often the want is edited, on a later capacity raise or to a later follower, that everyone told can see the want, and that a failed announce rolls back the create.
  - `WantHappeningIT` covers start and end: that a happening active want still takes signups and edits while a closed one keeps its lineup fixed, that only a happening want ends, that an ended one stays in the feed and refuses everything, and who is notified, once.
  - Removing `@Transactional` from a service method that runs a `@Modifying` query fails them with `TransactionRequiredException`; the unit tests cannot see that.

Unit tests use `@WebMvcTest` with MockMvc, which loads only the web slice — no datasource, so `mvn test` passes without a database running. Collaborators are replaced with `@MockitoBean` (Spring Boot 3.4+ replaced `@MockBean`). New controller tests should follow that pattern and name the controller under test in the annotation. They also need:
- `@Import(SecurityConfig.class)`. `@WebMvcTest` does not pick up `@Configuration` classes, and without it Boot's default chain would apply, with CSRF on.
- `@MockitoBean JwtDecoder`, so `ClerkJwtConfig` and its `CLERK_ISSUER` placeholder are never needed.
- `.with(jwt().jwt(t -> t.subject("user_...")))` on each authenticated request, plus a test that the route returns 401 without it.

Service-level rules, such as ownership, are tested with plain Mockito (`WantServiceTest`), since the controller tests mock the services. Anything that depends on SQL, constraints or transactions belongs in an `*IT` test instead.

## Schema migrations

Flyway owns the schema, on **PostgreSQL** (`flyway-database-postgresql`). Migrations live in `src/main/resources/db/migration` as `V<n>__<description>.sql` and run automatically at startup, before Hibernate initializes.

`spring.jpa.hibernate.ddl-auto=validate` — Hibernate never alters the database; it only checks that the tables match the entities and fails startup if they have drifted. **Adding a field to an entity therefore requires a new migration file**, not just the Java change. Never edit an applied migration: Flyway checksums each one and refuses to start if a checksum changes. Write a new `V<n+1>__...sql` instead.

The project moved from MySQL to PostgreSQL once, and V1 and V3 were rewritten in place as PostgreSQL then. That was safe only because no PostgreSQL database had applied them; any MySQL database from before is abandoned rather than migrated. Do not treat it as precedent.

SQL notes for this schema: ids are `BIGINT GENERATED BY DEFAULT AS IDENTITY`, which `GenerationType.IDENTITY` maps to. Enum-like columns such as `wants.category` are `VARCHAR` with a `CHECK` constraint, not native PostgreSQL enum types — `@Enumerated(EnumType.STRING)` is validated as a character column, and a native enum would fail `ddl-auto=validate`. PostGIS and `btree_gist` are available on the hosted database and in the local image; enable them with `CREATE EXTENSION IF NOT EXISTS` in the migration that first needs them.

`V2__seed_sample_data.sql` inserts the user `dani` with two wants, and `V4__seed_dani_profile.sql` gives `dani` a profile, so a fresh database is usable while users can't be created through the API. Both run in every environment Flyway touches, so delete them before this schema is used for anything real. `V3__create_user_profiles.sql` created the profiles table that `V7__merge_user_profiles_into_users.sql` later moves onto `users` and drops. `V8__clerk_picture_drop_nickname.sql` drops `users.nickname` and renames `s3_url` to `image_url`, emptied so each user's next request fills in their Clerk picture. `V9__create_follows.sql` creates `follows`. `V11__create_notifications.sql` creates `notifications`. `V12__want_capacity_and_signups.sql` adds `wants.capacity` (default 1, `CHECK >= 1`), creates `want_signups` (`UNIQUE (want_id, user_id)`, both foreign keys `ON DELETE CASCADE`), seats every existing want's owner, adds `want_id`, `want_message` and `previous_message` to `notifications`, and widens its type check. `V13__want_close_and_cancel.sql` adds `wants.closed_at` and `wants.cancelled_at`, both nullable, and widens the type check again. `V14__want_start_and_end.sql` adds `wants.started_at` and `wants.ended_at`, both nullable, and widens it for `WANT_STARTED` and `WANT_ENDED`. `V15__want_open_invite.sql` drops `NOT NULL` from `wants.capacity` (the V12 check already passes null) and widens the type check for `WANT_OPEN_INVITE` and `WANT_LIMITED`. `V16__want_deadline.sql` adds `wants.expires_at`, nullable, and widens it for `WANT_DEADLINE_CHANGED` and `WANT_EXPIRED`. `V17__announce_wants.sql` widens it for `WANT_POSTED`, adds the partial unique index `notifications_posted_once` on `(want_id, recipient_id) WHERE type = 'WANT_POSTED'` (the "already told" marker, so a future retention job must keep those rows), and an index on `follows (followee_id)`. `V18__follow_mute.sql` adds `follows.notify_new_wants BOOLEAN NOT NULL DEFAULT TRUE`. `V19__circles.sql` creates `circles` (`UNIQUE (id, owner_id)`, a partial unique index on `(owner_id, lower(name)) WHERE archived_at IS NULL`, no action on `owner_id`) and `circle_members` (`PRIMARY KEY (circle_id, member_id)`, keys to `circles (id, owner_id)` and to `follows` with cascade, an index on `member_id`), adds `notifications.circle_id` and `circle_name`, and widens the type check for `CIRCLE_ADDED`. `V10__seed_test_users.sql` adds `alex`, `sam` and `jordan` with two wants each and no `clerk_id`, so they are read-only like `dani` and appear in your list only once you follow them. Like V2, V10 runs in every environment, so delete it before real use. Your own follows can't be seeded: your row only exists after your first sign-in. V3 and V4 stay, because they have been applied; deleting or editing them would break Flyway's checksums.

Resetting from scratch — the only safe way to replay migrations, and it destroys all data:

```bash
docker compose down -v && docker compose up -d
```

## Database and configuration

The app uses PostgreSQL through `spring-boot-starter-data-jpa` and the `org.postgresql` driver. Which database it uses is chosen by the **Spring profile**, not by editing a file:

| Profile | When | Database | Settings come from |
|---|---|---|---|
| `local` (default) | `mvn spring-boot:run` with no options | PostGIS on PostgreSQL 18 in Docker, started automatically from `compose.yaml` | Spring Boot's Docker Compose support reads the container's credentials itself |
| `prod` | any container from the image (the Dockerfile sets `SPRING_PROFILES_ACTIVE=prod`), or `-Dspring-boot.run.profiles=prod` locally | Aiven PostgreSQL 18 (free plan, DigitalOcean NYC) | `DB_URL`, `DB_USER`, `DB_PASSWORD` environment variables, or a git-ignored `config/application-prod.properties` |

Configuration files:

- `src/main/resources/application.properties` — committed. Shared settings only, plus `spring.profiles.default=local`. No datasource settings.
- `src/main/resources/application-local.properties` — committed, no secrets. Sets `spring.docker.compose.lifecycle-management=start-only`, so the container keeps running after the app stops.
- `src/main/resources/application-prod.properties` — committed, no secrets. The datasource is bare `${DB_URL}` / `${DB_USER}` / `${DB_PASSWORD}` placeholders with no fallbacks. `DB_URL` is a whole JDBC URL so it can carry TLS parameters. If `DB_URL` is missing, Spring leaves the literal `${DB_URL}` in place and startup fails with `'url' must start with "jdbc"` from Hikari. That message means the variable is not set, not that the URL is malformed. The file also sets `spring.docker.compose.enabled=false`.
- `config/application-prod.properties` — **git-ignored** (`/config/*`, with `!/config/*.example` keeping the template tracked), copied from `config/application-prod.properties.example`. It lets a laptop run the `prod` profile against Aiven. Spring reads `./config/` automatically as an external config location, and it outranks the classpath file. There is no `spring.config.import`. `.dockerignore` excludes `config/`, so it never reaches an image.

**Local database.** `spring-boot-docker-compose` is a `runtime`, `optional` dependency. On `mvn spring-boot:run` it runs `docker compose up` on `compose.yaml`, waits for the health check and builds the datasource from the running container, so Docker must be running. The Spring Boot Maven plugin leaves it out of the repackaged jar, and Spring skips it in tests, so neither the image nor `mvn test` touches Docker.
- Spring recognises the official `postgres` image by name but not `postgis/postgis`, so the service carries the label `org.springframework.boot.service-connection: postgres`. Without it, startup fails for lack of a datasource.
- The container's credentials (`wants` / `wants` / `wants`) are committed on purpose. It is a throwaway database listening only on the developer's machine, so there is no `.env` to maintain.
- It is published on host port **5433**, rather than 5432, to avoid clashing with a locally installed PostgreSQL, and so a database client can connect. Spring finds the port itself.
- PostgreSQL 18 images keep data under `/var/lib/postgresql/<version>`, which is why the volume mounts at `/var/lib/postgresql` and not `.../data`.

**Connection limit.** The free Aiven plan allows `max_connections = 20`, a few of them reserved for superusers (`avnadmin` is not one), and has no PgBouncer. The `prod` profile sets `spring.datasource.hikari.maximum-pool-size=7`, down from Hikari's default of 10, so the deployed server and a laptop run of the `prod` profile (7 + 7) still leave room for the Aiven console within the roughly 17 usable slots. When slots run out, startup fails in Flyway with *"remaining connection slots are reserved for roles with the SUPERUSER attribute"*. Terraform replaces the API instance destroy-first (no `create_before_destroy`), so old and new servers do not overlap. Switching to create-before-destroy would make them overlap, so budget for both.

**Hosted database.** Aiven requires TLS; use `sslmode=verify-full&sslrootcert=<path to Aiven's ca.pem>` in the JDBC URL. `ca.pem` is not secret, but is kept outside the repository. In a `.properties` file the path must use forward slashes even on Windows, because backslashes are escapes. Running the `prod` profile locally writes to the real database.

`mvn test` needs neither database, because `@WebMvcTest` loads only the web slice. `mvn verify` needs Docker running, for the Testcontainers database.

## Deployment and CI

CI is `.github/workflows/docker-publish.yml`; the infrastructure is `terraform/` (one EC2 instance for the API, S3 + CloudFront for the site, the database on Aiven). The README describes what is built. **`.github/CLAUDE.md` and `terraform/CLAUDE.md` hold their gotchas**, and are loaded when working in those folders.

Three of them matter when changing the application itself:
- **Anything the app newly requires from `.env`** needs `terraform apply` first, then the merge. `deploy-api` only swaps the image; an image needing a variable the instance lacks fails to start.
- **The origin check fails closed.** `OriginSecretFilter` is switched on by `app.origin-check.enabled`, not by the secret being non-empty; enabled with a blank secret, the app does not start. Never fold the switch back into "empty secret means off", which would silently open the API. Running the `prod` profile from a laptop needs `app.origin-check.enabled=false` and `app.origin-secret=` in `config/application-prod.properties`, as in the template.
- **The Vite proxy's `/api` rewrite** (`frontend/vite.config.ts`) is mirrored in production by `terraform/functions/strip-api-prefix.js`. Keep the two in step.

## Further instructions
Update the README.md as we make more features and commits