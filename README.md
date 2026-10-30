# Wants

**A social planning app: post something you want to do, and the people who follow you take a seat.**

"Pizza Friday, 4 seats." "Elden Ring co-op tonight, anyone." Friends follow each other and see each other's plans in one feed. Seats go first come, first served, and everyone in the lineup is notified as a plan fills up, changes, starts, ends or runs out of time.

It is a full-stack app deployed on AWS. It was built one feature at a time from a written design doc, and every step went through a pull request, CI and tests.

**Spring Boot 3.5 · Java 21 · PostgreSQL 18 · Flyway · React + TypeScript · Tailwind CSS v4 · TanStack Query · Clerk · Docker · Terraform · AWS (EC2, S3, CloudFront, SSM) · GitHub Actions · Testcontainers**

- [What it does](#what-it-does)
- [Engineering highlights](#engineering-highlights)
- [Architecture](#architecture)
- [How it is tested](#how-it-is-tested)
- [How it was built](#how-it-was-built)
- [Developer guide](#prerequisites): running it, the API, tests, schema, CI and deployment

## What it does

- **Wants.** A plan with a message and a category (food, movie, game or trip). It can have a seat limit or be an **open invite** with no limit, and it can have an optional **deadline**.
- **Following.** Search people by username and follow them. Your feed shows your own wants plus those of everyone you follow, and nobody else's.
- **Circles.** Groups, like group chats: the owner adds people who follow them, and members see the circle and each other. Any member can post a want to the circle, and every member sees it, even without following the poster.
- **Signups.** Take a seat or give it up. The owner always holds the first seat, and a full want refuses more people.
- **A lifecycle.** The owner can **close** a want (the lineup is fixed), **reopen** it, **start** it ("happening now"), **end** it, or **cancel** it. When a deadline passes, the want closes on its own.
- **Notifications.** 16 kinds, such as "dani posted 'Pizza Friday'", "sam took a seat", "dani moved the deadline" and "Pizza Friday has started". A bell shows the unread count.
- **Accounts through Clerk.** Sign-in, username and profile picture all come from Clerk, and a changed picture shows in the app straight away.

Every rule above is drawn as a decision tree in [How status and notifications are decided](#how-status-and-notifications-are-decided).

## Engineering highlights

These are the problems that took real design work. Each one names the code that solves it and the test that proves it.

### 1. The last seat can't be taken twice

When two people press **Sign up** for the last seat at the same moment, both requests count `seatsTaken < capacity`, both see a free seat, and both get in. [`SignupService`](src/main/java/com/example/demo/services/SignupService.java) prevents that by first locking the want's row (`SELECT ... FOR UPDATE`, [`WantRepository.findByIdForUpdate`](src/main/java/com/example/demo/repositories/WantRepository.java)). Only then does it count the seats and insert, so the second request waits, then counts the first one's committed seat.

The tempting one-statement version, `INSERT ... SELECT ... WHERE count < capacity`, **does not work** in PostgreSQL. The wants row is never modified, so a waiting statement is never re-checked, and both inserts succeed. Every other change to a want takes the same lock.

**Proof:** [`WantSignupsIT`](src/test/java/com/example/demo/services/WantSignupsIT.java) holds one signup's transaction open on a latch and shows the second is still blocked after 500 ms. It also sends five signups at once for one seat. Both tests fail every time if the lock is removed.

### 2. A scheduled job that is safe to run twice, with no lock library

Deadlines are enforced the moment they pass, not when a job gets round to them. `Want.getStatus(now)` treats a passed `expires_at` as `CLOSED`, so a signup or edit one second after the deadline is already refused.

[`WantExpiryJob`](src/main/java/com/example/demo/services/WantExpiryJob.java) then runs every minute to record the closure and notify the lineup. That is **one SQL statement**: an `UPDATE ... RETURNING` inside a CTE feeding the `INSERT` into `notifications`, so the want closes and everyone is told atomically. Its `WHERE closed_at IS NULL AND ...` guard is the whole concurrency story. A second run, a second instance, or a request holding the row lock makes the `UPDATE` wait, re-check and skip. There is no ShedLock and no distributed lock.

**Proof:** [`WantExpiryIT`](src/test/java/com/example/demo/services/WantExpiryIT.java) runs the job concurrently and repeatedly, and asserts exactly one `WANT_EXPIRED` per seat. It also checks catch-up after downtime and a want being reopened mid-run. Removing any part of the guard fails it.

### 3. Doing anything twice changes nothing and notifies nobody twice

Every action is idempotent, and every notification is sent exactly once:

- Follows and first sign-ins use `INSERT ... ON CONFLICT DO NOTHING` and act on the returned row count. Catching a unique-constraint exception instead would mark the surrounding transaction rollback-only.
- State changes check the status under the row lock, so closing a closed want is a no-op.
- Each notification is written **in the same transaction** as the event it describes. If the notification fails, the event rolls back too.

**Proof:** [`FollowNotificationsIT`](src/test/java/com/example/demo/services/FollowNotificationsIT.java) makes the notification fail on purpose with a spy and checks the follow is undone. [`WantLifecycleIT`](src/test/java/com/example/demo/services/WantLifecycleIT.java) checks that a repeated close notifies once.

### 4. Security that fails closed

- **Tokens are fully verified.** [`ClerkJwtConfig`](src/main/java/com/example/demo/security/ClerkJwtConfig.java) checks each Clerk JWT against Clerk's published keys: signature, expiry, an exact issuer match, and the authorized party (`azp`, [`AuthorizedPartyValidator`](src/main/java/com/example/demo/security/AuthorizedPartyValidator.java)). **Clerk's secret key is used nowhere.**
- **Who you are comes only from the token.** No endpoint accepts a user id in the body or query string. Ownership is checked in the service layer: 404 for an unknown want, 403 for someone else's.
- **Private data never leaks through another user's response.** Other users are only ever returned as response records that copy public fields (`WantResponse`, `UserSummary`, `PersonResult`), and a controller test guards each one.
- **The API can't be reached around CloudFront.** The firewall admits only CloudFront's address ranges. Those ranges are shared by every CloudFront customer, so [`OriginSecretFilter`](src/main/java/com/example/demo/security/OriginSecretFilter.java) also requires a secret header, and it runs before Spring Security even looks at the token.
- **Missing configuration stops the app.** A blank issuer, an empty party list, or an enabled origin check with no secret stops startup instead of quietly leaving the API open.

### 5. Infrastructure as code, with no long-lived keys

- **Terraform builds everything.** [`terraform/`](terraform/) creates the EC2 instance, a private S3 bucket and CloudFront, the IAM roles and the SSM parameters.
- **CI holds no AWS keys.** GitHub Actions deploys through **OIDC**: each run gets short-lived credentials for a role that only this repository's `master` branch can assume.
- **No SSH in deploys.** The API is redeployed through **SSM Run Command**, then health-checked ([`deploy/redeploy-api.sh`](deploy/redeploy-api.sh)).
- **The database password is in neither the Terraform plan nor its state.** It is a write-only value from an ephemeral variable, stored only in SSM.
- **Sized for the free database plan.** The connection pool fits Aiven's 20-connection limit, with room for a laptop and the admin console. Instances are replaced destroy-first, so two servers never compete for connections.

### 6. A schema that can't drift

Flyway owns the schema (16 migrations, never edited once applied), and Hibernate only *validates* it. Every database test starts a fresh PostgreSQL in Docker and migrates it from empty, so a broken migration fails CI, not production. Time comes from an injected `Clock`, so unit tests pin it with `Clock.fixed` instead of sleeping.

## Architecture

```
Browser ──HTTPS──► CloudFront
                    ├─ /*      → S3 (private)        React + TypeScript SPA
                    └─ /api/*  → + secret header
                                 → EC2: Spring Boot API in Docker (admits CloudFront only)
                                     ├─ verifies Clerk JWTs (JWKS, issuer, azp)
                                     ├─ expiry job, every minute
                                     └──TLS (verify-full)──► Aiven PostgreSQL 18

API layers:  controllers → services → repositories → PostgreSQL
             (dto records in and out; identity from the token, never the request)
Web layers:  components → hooks (TanStack Query / context) → api/ (one function per endpoint)
```

Each layer only calls the one below it. Controllers never touch repositories, and components never call `api/` directly. Notifications are rows in a table, written whether or not the recipient is online, and the browser polls every 30 seconds. The reasoning behind that and every other design choice is in [`docs/design/wants-signups-and-notifications.md`](docs/design/wants-signups-and-notifications.md).

## How it is tested

| Kind | Tools | What it covers |
| --- | --- | --- |
| API unit tests (`*Test`) | JUnit 5, `@WebMvcTest`, Mockito | each endpoint through the real security rules, plus service rules like ownership; no database or network |
| Database tests (`*IT`) | Testcontainers (PostgreSQL + PostGIS) | real SQL and transactions: races for the last seat, the expiry job, rollbacks, migrations from empty |
| Frontend tests | Vitest, React Testing Library | screens and hooks with the `api/` layer stubbed |
| Static checks | `tsc`, oxlint, Prettier | types, lint and formatting |

CI runs all of it on every pull request. A merge to `master` publishes the API image and deploys both halves.

## How it was built

- **Design first.** Signups and notifications were planned in a [design doc](docs/design/wants-signups-and-notifications.md) and built in six steps: notifications, capacity, close and cancel, start and end, open invites, and deadlines. Each step was its own pull request with its tests.
- **AI-assisted, with every decision documented.** Development used [Claude Code](https://claude.com/claude-code) as a pair programmer. The project's [`CLAUDE.md`](CLAUDE.md) records the conventions and the reasons behind them. Custom review agents check UI and accessibility, system design and launch readiness, and pull requests get an automated review.
- **Honest about limits.** What is deliberately missing is listed under [Known gaps](#known-gaps).

**Next:** a deadline picker on the wants screen (the API side is done), then a UI redesign.

---

*Everything below is the developer guide.*

## Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 21 | No Maven wrapper is checked in |
| Maven | 3.9.x | Must be on `PATH` |
| Docker | with Compose v2 | Must be running; the backend starts its local PostgreSQL itself |
| Node | 20+ | For the frontend only |

## Getting started

### 1. Create a Clerk application

Every page and every API route needs a login, so the app does not run without one. At [dashboard.clerk.com](https://dashboard.clerk.com), create an application, choose the sign-in methods you want, then open **Configure → API keys** and copy two values:

| Value | Looks like | Used by |
| --- | --- | --- |
| Publishable key | `pk_test_...` | the frontend, as `VITE_CLERK_PUBLISHABLE_KEY` |
| Frontend API URL | `https://<name>.clerk.accounts.dev` | the backend, as `CLERK_ISSUER` |

Neither is secret. The publishable key is compiled into the JavaScript every visitor downloads, and it only tells Clerk's widget which application to use. The **secret key (`sk_...`) is not used anywhere in this project**: the backend checks tokens against Clerk's published public keys, so it never needs it. Don't paste it into any file here.

### 2. Start Docker

The backend starts its own local database, so Docker must be running.

### 3. Run the backend

```bash
mvn spring-boot:run     # http://localhost:8080
```

The project's Clerk development instance is already set as `app.auth.issuer` in `src/main/resources/application-local.properties`. It isn't secret. If you use your own Clerk application, replace it there or set `CLERK_ISSUER`.

The Frontend API URL is also encoded in the publishable key: everything after `pk_test_` is the hostname in base64, followed by a `$`. If you can't find it in the dashboard, decode the key:

```powershell
$b64 = 'pk_test_...' -replace '^pk_(test|live)_', ''
'https://' + [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b64.PadRight($b64.Length + (4 - $b64.Length % 4) % 4, '='))).TrimEnd('$')
```

To check the result, open `<url>/.well-known/jwks.json`, which should list Clerk's public keys.

This uses the default `local` profile. Spring Boot runs `docker compose up` on `compose.yaml`, which starts PostGIS on PostgreSQL 18 (host port **5433**), and connects to it. Flyway creates the schema on first start and seeds a user, `dani`, with two wants. The container keeps running after the app stops. Stop it with `docker compose stop`, or wipe it with `docker compose down -v`.

### 4. Optional: run against the hosted database

The deployed backend uses an Aiven PostgreSQL database. To run your local backend against it instead:

1. Download the project's CA certificate (`ca.pem`) from the Aiven console and save it outside the repository.
2. Copy the template and fill in the connection details:

   ```bash
   cp config/application-prod.properties.example config/application-prod.properties
   ```

   ```properties
   spring.datasource.url=jdbc:postgresql://<host>:<port>/defaultdb?sslmode=verify-full&sslrootcert=C:/Users/<you>/.aiven/ca.pem
   ```

3. Run with the `prod` profile:

   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=prod
   ```

`config/application-prod.properties` is git-ignored. Keep `app.origin-check.enabled=false` and `app.origin-secret=` in it, as the template does. Requests from the Vite proxy carry no CloudFront header, so that check must be off on a laptop. The template also sets `app.auth.issuer` and `app.auth.authorized-parties=http://localhost:5173`, because tokens on a laptop are issued to the Vite dev server rather than to CloudFront.

The free Aiven plan allows only a few connections, and the `prod` profile caps its pool at 7 (`spring.datasource.hikari.maximum-pool-size`). If startup fails with *"remaining connection slots are reserved for roles with the SUPERUSER attribute"*, other clients, usually the deployed API server, are holding them.

### 5. Run the frontend

```bash
cd frontend
cp .env.example .env.local   # then paste the publishable key into it
npm install
npm run dev             # http://localhost:5173
```

`.env.local` is git-ignored. Without the key the page stays blank, and the console says *"VITE_CLERK_PUBLISHABLE_KEY is not set"*.

The dev server proxies `/api/*` to port 8080, so the browser stays on one origin and the backend needs no CORS configuration.

A signed-out visitor sees only Clerk's sign-in form, which also offers sign-up. Once signed in, the app has two screens, linked from the nav at the top: the wants list at `/` and an about page at `/info`. The header also holds Clerk's account menu, the only account control: it changes your profile picture, username and email, and signs you out.
- **Wants** show each owner's username and Clerk profile picture: their photo, or Clerk's silhouette if they haven't set one. Beside the seat count, a row of small pictures shows everyone going, in the order they signed up.
- **Each want is for a number of people**, set when you add it (1 unless you change it) and raised later with Edit; it can't be lowered. Every row shows its seats taken, such as "2 / 4 seats". Other people's wants have a **Sign up** button, which becomes **Withdraw** once you're in; when every seat is taken it is disabled and the row says "Full". Your own wants have neither, since you always hold a seat on them.
- **An open invite has no seat limit.** Tick **Open invite** when you add a want, or later in Edit; its row then says how many are going, such as "5 going", carries an **Open invite** label, and is never full. Anyone who follows you can still join, as with any want, and the people already in are told it's open so they can bring others. Untick it in Edit to limit it to the people already in; raise the limit in a later edit.
- **A want can have a deadline**, picked when you add it: in 1 hour, tomorrow (at noon), in 1 week, or a date of your choosing, with a time or without one (then noon, or 23:59 if it's today and already past noon). It must be in the future. Its row then says when it closes, such as "Closes Fri 30 Oct, 6:00 pm". When the deadline passes, the want closes and says "Closed at its deadline". Move or remove the deadline with Edit while the want is open; a closed want is reopened first. **Reopen** asks for an optional new deadline: with none, a deadline still ahead is kept and one that has passed is dropped.
- **Notifications** say what happened in a sentence: "alex signed up for 'Pizza Friday'", "You created 'Pizza Friday'", "sam renamed 'Pizza Friday' to 'Pizza Saturday'", "sam opened 'Pizza Friday' to everyone: bring who you like", "dani posted 'Pizza Friday'", or "'Pizza Friday' closed at its deadline".
- **Everything about you comes from Clerk.** The app has no profile page of its own and nothing to edit about yourself.

## Login

[Clerk](https://clerk.com) owns accounts: sign-up, sign-in, passwords, email verification and social logins all happen in Clerk's widget. This app never sees a password.

```
Browser ── signs in through Clerk's widget ──► Clerk
   │  receives a session token: a JWT signed by Clerk, valid ~60 s, refreshed in the background
   └── every API call: Authorization: Bearer <token> ──► Spring
          checks the signature against Clerk's public keys (JWKS, fetched once and cached),
          plus expiry, issuer (CLERK_ISSUER) and azp (CLERK_AUTHORIZED_PARTIES)
          → the token's "sub" (the Clerk user id) is who you are
```

- **Users are created on first sign-in.** The first request carrying a new Clerk user's token creates a row in `users` with `clerk_id` set to the token's `sub`. There is no sign-up endpoint in this API, because Clerk's widget does sign-up.
- **Clerk owns the username, email, last sign-in and profile picture.** The Clerk session token is customised to carry them, under **Sessions → Customize session token** in the Clerk dashboard:

  ```json
  {
    "username": "{{user.username}}",
    "email": "{{user.primary_email_address}}",
    "lastLogin": "{{user.last_sign_in_at}}",
    "imageUrl": "{{user.image_url}}"
  }
  ```

  The backend copies them onto the user's row (`users.username`, `users.email`, `users.last_sign_in_at`, `users.image_url`) whenever they change. A change made in Clerk's account menu while the app is open shows straight away: the wants screen notices it, gets a fresh session token and reloads. Otherwise it reaches the API with the next session token, which Clerk issues about once a minute.
  - Clerk always sends an `image_url`: your photo, or a generated silhouette if you haven't set one. The app shows it as it is, so it matches Clerk's account button.
  - Like the others, a missing `imageUrl` claim never erases a stored picture. Removing your photo in Clerk still shows up, because Clerk then sends its silhouette's URL instead.
  - Without a Clerk username, or without the claim, the username is the Clerk id.
  - If the name is already taken in our table, for example by the seeded `dani`, the user keeps their current one rather than getting an error.
  - A missing email or sign-in claim never erases a stored value.
- **Email stays private.** It sits on `users`, but the API never returns another user's `User`: wants come back as `{id, message, category, userId, username}`, and only `GET /me` shows you your own email. `users.email` is the address to use for notifications.
- **The owner always comes from the token.** Request bodies no longer carry a `userId`; one sent anyway is ignored. You see your own wants and those of people you follow, but only a want's owner can change, close, reopen, start, end or cancel it (`403` otherwise). Your own record is reachable only as `/me`, and there is no route to anyone else's, so nobody can read another user's email.
- **`azp` is checked.** Clerk records the page origin that requested each token in `azp`. The API accepts only its own origins: `http://localhost:5173` locally, the CloudFront URL in production. That stops a token issued to another site on the same Clerk instance being replayed here.
- **No cookies, no CSRF.** The API is stateless and reads credentials only from the `Authorization` header, which a cross-site form cannot set, so CSRF protection is off.
- **The seeded `dani` has no Clerk account**, nor do the test users `alex`, `sam` and `jordan`. Their wants show only to people who follow them, and can be edited by no one. To claim them, sign in once, then in the database set `dani`'s `clerk_id` to your Clerk user id (shown in the Clerk dashboard, or by `GET /me`) and delete the user row your first sign-in created.

## API

All bodies are JSON. **Every route needs `Authorization: Bearer <Clerk session token>`** and returns `401` without a valid one. The only exception is `GET /actuator/health`, which answers `{"status":"UP"}` when the app and its database are up.

To call the API by hand, sign in to the app, run `await Clerk.session.getToken()` in the browser console, and copy the result. It expires in about a minute.

### You

Every field of your record comes from Clerk, so `/me` is read-only: you change these in Clerk's account menu. You can only ever reach your own; there is no route to anyone else's.

| Method | Path | Body | Success | Errors |
| --- | --- | --- | --- | --- |
| `GET` | `/me` | — | `200` you: `id`, `username`, `clerkId`, `email`, `lastSignInAt`, `imageUrl`; created on first call | — |

```bash
curl -H "Authorization: Bearer $TOKEN" localhost:8080/me
```

### Wants

Base path `/wants`.

| Method | Path | Body | Success | Errors |
| --- | --- | --- | --- | --- |
| `GET` | `/wants` | — | `200` your wants, General wants of people you follow, and wants in circles you own or are in, each `{id, message, category, userId, username, imageUrl, capacity, seatsTaken, lineup, signedUp, status, happening, expiresAt, circle}`, `circle` being `{id, name}` or `null` for General, `lineup` being everyone seated as `{id, username, imageUrl}`, the owner first; cancelled wants are left out, ended ones stay | `400` unknown `?category=` |
| `GET` | `/wants?category=FOOD` | — | `200` filtered list | `400` unknown or lowercase value |
| `POST` | `/wants` | `{message, category, capacity?, openInvite?, expiresAt?, circleId?}` | `201` created want, owned by you, with you in its first seat; `openInvite: true` gives it no seat limit; `expiresAt` is its deadline; `circleId` posts it to a circle you own or are in, otherwise it goes to all your followers (General) | `400` invalid body, `capacity` below 1, `capacity` with `openInvite`, or `expiresAt` in the past; `404` a circle you're not in, or a deleted one |
| `PUT` | `/wants/{id}` | `{message, category, capacity?, openInvite?, expiresAt?, noDeadline?, circleId?, allFollowers?}` | `200` updated want; leaving out `capacity`, `expiresAt` or `circleId` keeps it; `circleId` moves it into a circle you own or are in, `allFollowers: true` back to General; `openInvite: true` removes the limit, `false` limits an open want to the people already in; `expiresAt` sets or moves the deadline, `noDeadline: true` removes it | `400` invalid body, capacity lowered, a capacity for an open want, `capacity` with `openInvite`, `expiresAt` in the past, `expiresAt` with `noDeadline`, or `circleId` with `allFollowers`; `403` not yours, `404` a circle you're not in, `404` unknown want, `409` closed (its deadline passed included), ended or cancelled |
| `POST` | `/wants/{id}/close` | — | `200` the want, now `CLOSED`; closing again changes nothing | `403` not yours, `404` unknown want, `409` ended or cancelled |
| `POST` | `/wants/{id}/reopen` | optional `{expiresAt}` | `200` the want, `ACTIVE` again, with `expiresAt` the new deadline if given; without one, a deadline still ahead is kept and one that has passed is cleared; reopening an active want changes nothing | `400` `expiresAt` in the past, `403` not yours, `404` unknown want, `409` ended or cancelled |
| `POST` | `/wants/{id}/start` | — | `200` the want, `happening: true`, its status unchanged; starting again changes nothing | `403` not yours, `404` unknown want, `409` ended or cancelled |
| `POST` | `/wants/{id}/end` | — | `200` the want, now `ENDED`; ending again changes nothing | `403` not yours, `404` unknown want, `409` not happening, or cancelled |
| `DELETE` | `/wants/{id}` | — | `204`; **cancels** the want rather than deleting it, and cancelling again changes nothing | `403` not yours, `404` unknown want, `409` ended |

Categories are `FOOD`, `MOVIE`, `GAME` and `TRIP`, matched by exact name — `food` is a 400. `PUT` replaces the message and category; `capacity` is optional and kept when left out, and can only go up. The owner never changes.

`status` is `ACTIVE`, `CLOSED`, `ENDED` or `CANCELLED`. A full want is still `ACTIVE`; compare `seatsTaken` with `capacity`. Only an active want takes signups, withdrawals and edits:
- **Closing** fixes the lineup. Nobody can sign up or withdraw, and the owner can't edit it until they reopen it.
- **Reopening** makes it active again.
- **Cancelling** is final. The want leaves the feed, but its row, its lineup and the notifications about it stay, so "dani cancelled 'Pizza Friday'" still has something to point at.
- **Starting** says the want is happening now, and sets `happening`. It doesn't change who can join: an active want still takes signups and edits while it happens, a closed one keeps its lineup fixed. You can start an active or a closed want.
- **Ending** is for a happening want, and is final. It becomes `ENDED` and stays in the feed, but nothing about it can change any more, and it can't be cancelled.
- **A deadline** (`expiresAt`, optional, always in the future when set) closes the want when it passes, as if the owner had closed it. It is `CLOSED` from that very moment, so signups, withdrawals and edits are refused at once. Within a minute a job inside the API records when it closed and tells everyone signed up, the owner too (`WANT_EXPIRED`). Changing the deadline in an edit tells everyone signed up (`WANT_DEADLINE_CHANGED`). Reopening takes a new deadline if you send one; otherwise it keeps a deadline still ahead and clears one that has passed.

Each of these tells everyone signed up except the owner, and doing one twice tells nobody twice.

```bash
TOKEN=...   # see above
curl -H "Authorization: Bearer $TOKEN" localhost:8080/wants

curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"Finish Elden Ring","category":"GAME"}' \
  localhost:8080/wants
```

### How status and notifications are decided

The rules above, and those for signups below, as decision trees. 🔔 marks a notification and who gets it; "lineup" means everyone signed up except the owner. Two patterns cover most of it: **doing something twice changes nothing and notifies nobody**, and **a final state (cancelled or ended) answers 409 to everything** except repeating the action that made it final.

**Status** (`Want.getStatus(now)`, checked in this order):

```
cancelled_at set? ──yes──▶ CANCELLED   (final, hidden from the feed)
      │no
ended_at set? ─────yes──▶ ENDED       (final, stays in the feed)
      │no
closed_at set, OR expires_at ≤ now? ──yes──▶ CLOSED   (lineup fixed)
      │no
      ▼
    ACTIVE   (takes signups, withdrawals, edits)

happening (separate flag) = started_at set AND not ended AND not cancelled
```

**The owner's actions** (anyone else gets 403; an unknown want 404):

```
CREATE ──────────────▶ ACTIVE, owner seated ─────────────────────────── 🔔 WANT_CREATED → owner
 └─ a seat free for others? (capacity > 1, or open) ─────────── 🔔 WANT_POSTED → its audience:
        General: followers who haven't muted you · a circle: its owner and members, muted or not

EDIT (PUT)
 ├─ invalid body? (past deadline, expiresAt + noDeadline, capacity + openInvite) ─▶ 400
 ├─ CANCELLED / ENDED / CLOSED (incl. deadline passed) ─▶ 409
 ├─ capacity lowered, or capacity on an open want ─▶ 400
 └─ ACTIVE ─▶ saved, then one 🔔 per kind of change, to the lineup:
       renamed or new category ──▶ WANT_UPDATED
       limit removed ────────────▶ WANT_OPEN_INVITE
       open want limited ────────▶ WANT_LIMITED
       deadline set/moved/removed ▶ WANT_DEADLINE_CHANGED
       nothing changed ──────────▶ (none)
     then, if a seat is free ──▶ 🔔 WANT_POSTED → its audience (as on CREATE), not in the lineup, never told before

CLOSE
 ├─ CANCELLED / ENDED ─▶ 409
 ├─ CLOSED (incl. deadline passed) ─▶ no change, no 🔔
 └─ ACTIVE ─▶ closed_at = now ──────────────────────────────── 🔔 WANT_CLOSED → lineup

REOPEN (optional new deadline)
 ├─ CANCELLED / ENDED ─▶ 409
 ├─ ACTIVE ─▶ no change, no 🔔 (a new deadline is ignored; use EDIT)
 └─ CLOSED ─▶ closed_at cleared, and the deadline:
       new one sent ───────▶ the new one
       none, old one ahead ─▶ kept
       none, old one passed ▶ cleared
     ─────────────────────────────────────────────────────── 🔔 WANT_REOPENED → lineup

START
 ├─ CANCELLED / ENDED ─▶ 409
 ├─ already happening ─▶ no change, no 🔔
 └─ ACTIVE or CLOSED ─▶ happening, status unchanged ──────── 🔔 WANT_STARTED → lineup

END
 ├─ ENDED ─▶ no change, no 🔔
 ├─ CANCELLED ─▶ 409
 ├─ not happening ─▶ 409
 └─ happening ─▶ ENDED (final) ───────────────────────────── 🔔 WANT_ENDED → lineup

CANCEL (DELETE)
 ├─ ENDED ─▶ 409
 ├─ CANCELLED ─▶ no change, no 🔔
 └─ ACTIVE or CLOSED ─▶ CANCELLED (final, row kept) ──────── 🔔 WANT_CANCELLED → lineup
```

**Everyone else's actions:**

```
SIGN UP
 ├─ not ACTIVE?
 │    ├─ you can't see it (not in its lineup, don't follow the owner) ─▶ 404
 │    └─ you can see it ─▶ 409 (closed / ended / cancelled; a passed deadline counts as closed)
 ├─ already in the lineup ─▶ no change, no 🔔
 ├─ you don't follow the owner ─▶ 404
 ├─ full (and not an open invite) ─▶ 409
 └─ seat taken ──────────────────────────────────────────── 🔔 WANT_SIGNUP → owner

WITHDRAW
 ├─ not ACTIVE? ─▶ 404 or 409, as above
 ├─ you're the owner ─▶ 400
 ├─ you held a seat ─▶ seat freed ───────────────────────── 🔔 WANT_WITHDRAWN → owner
 ├─ no seat, can't see it ─▶ 404
 └─ no seat, can see it ─▶ no change, no 🔔

FOLLOW ─▶ first time only ───────────────────────────────── 🔔 FOLLOW → the person followed
```

**Circles** (a member asking for an owner action gets 403; anyone else, or a deleted circle, 404):

```
ADD (owner)
 ├─ they don't follow you (or don't exist) ─▶ 400
 ├─ already in ─▶ no change, no 🔔
 └─ added ──────────────────────────────────────────────── 🔔 CIRCLE_ADDED → the person added

REMOVE (owner removes anyone; a member removes only themselves = LEAVE)
 ├─ the owner leaving ─▶ 400 (delete the circle instead)
 ├─ a member removing someone else ─▶ 403
 └─ removed, or wasn't in ─▶ no 🔔

CREATE / RENAME (owner) ─▶ "General" or a duplicate name ─▶ 400; otherwise no 🔔
DELETE (owner) ─▶ archived, gone for everyone ─▶ no 🔔
UNFOLLOW the owner ─▶ removed from all their circles ─▶ no 🔔
```

**The expiry job** (every minute, inside the API):

```
for each want with: closed_at empty AND not cancelled AND not ended AND expires_at ≤ now
   ─▶ closed_at = now (when the job ran) ─────────────────── 🔔 WANT_EXPIRED → lineup AND owner
   (each want once: a repeat or a second run finds closed_at set and skips it)
```

Keep these trees in step with the code: a new action, status or notification type changes them in the same PR.

### Signups

A want takes up to `capacity` people, its owner included, so a new want starts with one seat taken. People who follow the owner sign up first come, first served, until every seat is taken. In each want of `GET /wants`, `seatsTaken` counts the seats taken and `signedUp` says whether you hold one.

An **open invite** has no seat limit: its `capacity` is `null`, it is never full, and everyone who follows the owner may join. Create one with `openInvite: true`, or open a limited want the same way in an edit. Sending `openInvite: false` in an edit limits it again, to exactly the people already in, so nobody loses a seat; raise the limit afterwards in another edit. An open want takes no `capacity`, since any number would lower its limit.

| Method | Path | Body | Success | Errors |
|---|---|---|---|---|
| `POST` | `/wants/{id}/signups` | — | `200` `{wantId, message, capacity, seatsTaken, lineup, signedUp, status}`; signing up again, or as the owner, changes nothing | `404` not in your feed, `409` full, closed, ended or cancelled |
| `DELETE` | `/wants/{id}/signups` | — | `200` the same shape; withdrawing when you are not signed up changes nothing | `400` you are the owner, `404` not in your feed, `409` closed, ended or cancelled |

On a closed, ended or cancelled want, both answer `409` to everyone who can see it, even someone already in the lineup, since the screen offers neither button there. Anyone who can't see it gets `404`, so the `409` never gives away that it exists. A cancelled want can only be seen by its lineup.

`lineup` lists each person as `{id, username, imageUrl}`, public fields only, in the order people signed up, the owner first, so whoever just joined gets their own picture back with it. If two people go for the last seat at the same moment, exactly one gets it: each signup locks the want's row in the database before counting the seats, so the second waits for the first and then finds the want full.

```bash
curl -X POST   -H "Authorization: Bearer $TOKEN" localhost:8080/wants/7/signups
curl -X DELETE -H "Authorization: Bearer $TOKEN" localhost:8080/wants/7/signups
```

On the screen, your own active wants have **Close** and **Cancel want**, and closed ones **Reopen** and **Cancel want**. Both also have **Start**, which marks the want "Happening now" without changing who can join, so others can still sign up to an active want that is live. Once started, **End** replaces Start. Cancel and End ask you to confirm first, since neither can be undone. Closed wants are labelled "Closed" for everyone, or "Closed at its deadline" when their deadline closed them. Reopen first asks for an optional new deadline, then **Confirm**. Ended ones are labelled "Ended" and have no buttons for anyone. The notifications page says who closed, reopened, started, ended or cancelled which want. The plan is in [docs/design/wants-signups-and-notifications.md](docs/design/wants-signups-and-notifications.md).

### Follows

Your wants list shows your own wants and those of everyone you follow. In the app, the **People** screen (`/people`) lists who you follow, and searching it by username finds anyone else, each with a Follow or Unfollow button. Each person you follow also has a **Tell me about new wants** box, ticked by default: untick it to mute their new wants (no `WANT_POSTED` from them) while still following them and seeing their wants. Following someone again after unfollowing ticks it again. Underneath it are these routes. Base path `/follows`; the follower is always you, from the token.

| Method | Path | Body | Success | Errors |
| --- | --- | --- | --- | --- |
| `GET` | `/follows` | — | `200` who you follow, each `{id, username, imageUrl, following, notifyNewWants}` (`following` always true) | — |
| `PUT` | `/follows/{userId}` | — | `204`; following again changes nothing | `400` yourself, `404` unknown user |
| `DELETE` | `/follows/{userId}` | — | `204`, whether or not you were following | — |
| `DELETE` | `/follows/{userId}/notify` | — | `204`, mutes their new wants; muting again changes nothing | `404` not following them |
| `PUT` | `/follows/{userId}/notify` | — | `204`, unmutes them; wants posted while muted are not announced now | `404` not following them |
| `GET` | `/users?q=al` | — | `200` up to 20 users whose username contains `q`, ignoring case, never you, each `{id, username, imageUrl, following, notifyNewWants}` (`notifyNewWants` false when muted or not followed); an empty `q` finds no one | — |

`V10__seed_test_users.sql` adds three test users, `alex`, `sam` and `jordan`, with two wants each. To try it, search for one on the People screen and press Follow. Or, through the API, look up their ids, then follow one:

```bash
docker exec demo-postgres psql -U wants -c "select id, username from users"

TOKEN=...   # see above
curl -i -X PUT    -H "Authorization: Bearer $TOKEN" localhost:8080/follows/4
curl    -H "Authorization: Bearer $TOKEN" localhost:8080/follows
curl -i -X DELETE -H "Authorization: Bearer $TOKEN" localhost:8080/follows/4
```

Reload the app after each change to see the list follow along.

### Circles

Circles work like group chats. Whoever creates one is its **owner**: they name it, add people who follow them, remove anyone, rename it and delete it. Being added makes you a member at once and tells you ("dani added you to 'Climbing'"). Members see the circle, who owns it and who else is in it, and can leave. Unfollowing the owner takes you out of all their circles, and following again doesn't put you back. Any member can post a want to a circle (`circleId` on `POST /wants`), and every member sees it, even without following the poster; members who follow the poster but aren't in the circle don't. A circle's want is seen only while its poster is still in the circle, so removing someone hides their wants there, and deleting a circle hides its wants from everyone but their poster. People already seated keep their seats either way, and can still withdraw. On the Wants screen, the **Audience** select beside the category picks who sees a new want: **General (all followers)**, the default or one of your circles. Edit moves a want between them, and a want in a circle shows the circle's name on its row. If the circle you picked is deleted, or you leave it, before you post, the screen says you can't post there any more and makes you choose again, rather than posting to all your followers. In the app, the **Circles** screen (`/circles`) lists your circles and the ones you're in. Name a new one and press Add; open one of yours to tick followers in or out, searching them by name, or rename or delete it. Open one you're in to see who else is, or leave it. If a circle was deleted meanwhile, the screen says so and drops it.

A member asking for something only the owner can do gets `403`; anyone else, or anyone asking about a deleted circle, gets `404`, so outsiders learn nothing. Deleting a circle hides it everywhere; the row is kept (archived), because wants will point at it.

| Method | Path | Body | Success | Errors |
| --- | --- | --- | --- | --- |
| `GET` | `/circles` | — | `200` circles you own or are in, oldest first, each `{id, name, owner: {id, username, imageUrl}}` | — |
| `POST` | `/circles` | `{"name"}` | `201` the circle, which you own | `400` blank, over 40 characters, "General" in any case, or the same name as one of your circles, ignoring case |
| `PUT` | `/circles/{id}` | `{"name"}` | `200` the renamed circle | `400` as for create; `403` a member; `404` anyone else |
| `DELETE` | `/circles/{id}` | — | `204`; gone for everyone | `403` a member; `404` anyone else, or already deleted |
| `GET` | `/circles/{id}/members` | — | `200` the members (not the owner), in the order added, each `{id, username, imageUrl}` | `404` not the owner or a member |
| `PUT` | `/circles/{id}/members/{userId}` | — | `204`; adding someone already in changes nothing and tells nobody | `400` they don't follow you; `403` a member; `404` anyone else |
| `DELETE` | `/circles/{id}/members/{userId}` | — | `204`; the owner removes anyone, a member removes themselves (leaving) | `400` the owner leaving (delete instead); `403` a member removing someone else; `404` anyone else |
| `GET` | `/followers` | — | `200` everyone who follows you, each `{id, username, imageUrl}`: who you can add | — |

Two requests that race are settled by locks in the database, never by luck. Every change to a circle locks its row first, so a leave or an add that arrives while the circle is being deleted waits, then gets `404`. Adding someone locks their follow first, so if they unfollow you at the same moment, the add gets a clean `400` instead of a foreign key error. Creating and renaming lock your own user row, so two circles with the same name at once give one circle and a `400`. `CirclesIT` holds each of these races open on a latch and checks the outcome.

### Notifications

When someone follows you, you get a notification. The bell beside your account menu shows how many are unread, and opening it (`/notifications`) lists them, newest first, and marks them read. Ones you had not seen stay highlighted until you leave the page. Notifications are kept in the database whether you are signed in or not, so ones that arrived while you were away are waiting when you come back. The app checks for new ones every 30 seconds while its tab is visible, and again when you return to the tab. Following someone again does not notify them twice.

| Method | Path | Body | Success | Errors |
|---|---|---|---|---|
| `GET` | `/notifications` | — | `200` your 50 newest, each `{id, type, actor: {id, username, imageUrl}, createdAt, read, wantId, wantMessage, previousMessage}` | — |
| `POST` | `/notifications/read` | `{"upTo": 8}` | `204`; marks yours up to and including that id read, so any that arrived after you looked stay unread | `400` no `upTo` |

`type` is one of:

| Type | Sent to | When |
|---|---|---|
| `FOLLOW` | the person followed | someone follows you |
| `WANT_CREATED` | you | you create a want |
| `WANT_SIGNUP` | the owner | someone signs up for your want |
| `WANT_WITHDRAWN` | the owner | someone gives up their seat |
| `WANT_UPDATED` | everyone signed up, not the owner | the owner renames the want or changes its category; `previousMessage` holds the old name when it was renamed |
| `WANT_CLOSED` | everyone signed up, not the owner | the owner closes the want |
| `WANT_REOPENED` | everyone signed up, not the owner | the owner reopens it |
| `WANT_CANCELLED` | everyone signed up, not the owner | the owner cancels it |
| `WANT_STARTED` | everyone signed up, not the owner | the owner starts it: it is happening now |
| `WANT_ENDED` | everyone signed up, not the owner | the owner ends it after it happened |
| `WANT_OPEN_INVITE` | everyone signed up, not the owner | the owner removes the seat limit, so they can bring others |
| `WANT_LIMITED` | everyone signed up, not the owner | the owner limits an open want to the people already in |
| `WANT_DEADLINE_CHANGED` | everyone signed up, not the owner | the owner sets, moves or removes the deadline |
| `WANT_EXPIRED` | everyone signed up, **and** the owner | the want closed at its deadline; the actor is the owner |
| `CIRCLE_ADDED` | the person added | a circle's owner adds you to it; `circleId` and `circleName` say which, the name as it was then |
| `WANT_POSTED` | for a General want, the owner's followers who haven't muted them; for a circle's want, the circle's owner and members (with `circleId` and `circleName`); never anyone already signed up | the owner creates or edits a want with a seat free for others (capacity above 1, or open); each follower hears of a want once at most, so a want first created for one seat is announced when its capacity is raised |

`wantId` and `wantMessage` are null for a follow. `wantMessage` is the want's name when the notification was written, so it still reads correctly after a rename. Each edit is its own notification.

## Layout

```
src/main/java/com/example/demo/
  models/        JPA entities and enums (Want, User, Follow, Notification, Category, WantStatus)
  repositories/  Spring Data interfaces
  services/      business logic and the exceptions that map to HTTP status
  controllers/   HTTP endpoints
  dto/           request bodies and response shapes (WantRequest, WantResponse, UserSummary,
                 NotificationResponse)
  security/      SecurityConfig (who may call what), ClerkJwtConfig (how a token is checked),
                 AuthorizedPartyValidator (the azp check), OriginSecretFilter (CloudFront only)
src/main/resources/db/migration/   Flyway migrations
frontend/src/
  api/           transport, one function per endpoint, no state; http.ts attaches the Clerk token
  hooks/         server state: useWants, MeProvider/useMe, which load you once and share you,
                 and useNotifications and useCircles, on TanStack Query
  types/         shapes mirroring the API
  components/    the screens (Wants.tsx at /, People.tsx at /people, Circles.tsx at /circles,
                 Notifications.tsx at /notifications, Info.tsx at /info), NotificationsBell.tsx in the header,
                 SignInScreen.tsx for signed-out visitors, and Avatar.tsx, the round
                 picture beside each want
  App.tsx        the shell: the sign-in gate, nav and routes (ClerkProvider,
                 QueryClientProvider and BrowserRouter are in main.tsx)
terraform/       single-instance AWS deployment
```

Controllers depend on services, never on repositories directly, and dependencies are injected through constructors. The frontend layers the same way: components call hooks, hooks call `api/`, and nothing else does.

## Tests

```bash
mvn test                                        # unit tests: fast, no Docker
mvn verify                                      # unit tests, then the database tests (needs Docker)
mvn test -Dtest=WantControllerTest              # one class
mvn test -Dtest=WantControllerTest#createsWant  # one method
```

Tests are `@WebMvcTest` slices with a mocked service layer, so **they need no database** and pass on a machine with nothing running. They `@Import(SecurityConfig.class)` so requests go through the real security rules, and they sign in with Spring Security's `jwt()` request post-processor. The `JwtDecoder` is a mock, so **they never contact Clerk**. `WantServiceTest` covers the ownership rule, the feed and that capacity is never lowered, `SignupServiceTest` that the owner cannot withdraw, `FollowServiceTest` who may follow whom and that only a new follow notifies, and `NotificationServiceTest` that you only ever read and mark your own, directly, since the controller tests mock the service away.

**Database tests** (`*IT.java`, run by `mvn verify`) are the other kind. Each run starts a throwaway PostgreSQL with PostGIS in Docker through [Testcontainers](https://testcontainers.com), runs every Flyway migration on it from empty, and calls the real services and repositories, committing for real. They catch what the fakes above cannot: SQL that is wrong, a migration that fails or does not match its entity, a constraint, a transaction that does not roll back. `FollowNotificationsIT` checks that a follow notifies exactly once, that the follow is undone if its notification fails, and how marking read behaves. `WantSignupsIT` checks capacity, signing up and withdrawing, who is notified of what, and the race for the last seat: one test holds the first signup open and shows the second waits for it, another sends five at once for one seat. Both fail every time if the row lock is removed. `WantLifecycleIT` checks closing, reopening and cancelling: what each still allows, who hears about it, and that doing one twice notifies once. `WantHappeningIT` does the same for starting and ending, and checks that a happening active want still takes signups. `WantSignupsIT` also covers open invites: no limit, still only for followers, and a limit taken from the real lineup when one is limited again. `WantExpiryIT` checks deadlines: that a want past its deadline refuses signups and edits before the job has run, that the job closes it and tells the lineup and the owner once, even when two runs race, that it waits for and then skips a want being reopened, and that one run catches up on everything missed during downtime. `WantAnnounceIT` checks announcing new wants: followers with a seat to take hear once, however often the want is edited, people already signed up, non-followers and followers who muted the owner never, a failed announcement undoes the create, and everyone told can see the want, so the SQL that picks them can't drift from the feed's rule. `WantCircleVisibilityIT` checks who sees a want once circles are in play: a member sees and can join another member's circle want without following them, a follower outside the circle and a co-member looking for General wants see nothing, outsiders get 404 even on a closed want, removing the poster or deleting the circle hides its wants (seats kept), posts racing a removal or a delete get 404, and moving a want between audiences announces each person once. `CirclesIT` checks circles: who sees what, leaving, a deleted circle's name being free again, one `CIRCLE_ADDED` however often someone is added, unfollowing removing you from every circle, and three races held open on a latch (two creates with one name, an add against an unfollow, a leave and an add against a delete). The scheduled job is off in tests (`src/test/resources/config/application.properties`), so it can't race them. The first run downloads the image; after that the container starts in a few seconds. They never touch the development database on port 5433.

Frontend checks:

```bash
cd frontend
npm test            # Vitest + React Testing Library, in jsdom
npx tsc --noEmit    # type check
npm run lint        # oxlint
npm run build       # production bundle into frontend/dist
```

Component tests stub the `api/` module rather than `fetch`, so like the backend tests **they need no server and no database**. They live beside the component as `<Name>.test.tsx`. `App.test.tsx` also stubs `@clerk/react`, replacing `<Show>` with a signed-in/signed-out switch, so no test loads Clerk.

For a design review, ask Claude Code to use the **`ui-ux-reviewer`** agent (for example, "have the ui-ux-reviewer look at the Wants screen"). With the dev server running, it opens the page in Playwright, screenshots it at desktop and mobile widths in light and dark mode, tabs through it by keyboard, and reports visual design, UX and accessibility issues with suggested fixes. It does not change code or data.

For an architecture question, ask for the **`system-design-advisor`** agent (for example, "ask the system design advisor how signups should handle the last seat"). It reads `CLAUDE.md`, the design docs in `docs/design/` and the relevant code, then answers with a recommendation, the trade-offs, the failure modes, a sketch and the tests that would prove it, judged against this project's real limits: one API instance, a small connection pool, CloudFront, Clerk tokens and a small budget. It advises only and does not change code.

Before launching to real users, ask for the **`legal-launch-advisor`** agent (for example, "run the legal launch advisor; users would be in the US and EU"). It reads what the app stores and where, then lists what a public launch would be missing: a privacy policy, account deletion, reporting and blocking for user content, processor agreements, age rules, app store requirements and so on, ranked by priority with the evidence in the code. It is not legal advice, and it says which points a lawyer should confirm.

## Database schema

Flyway owns the schema; Hibernate runs with `ddl-auto=validate` and never alters it.

- Migrations are `src/main/resources/db/migration/V<n>__<description>.sql` and run at startup.
- **Adding a field to an entity requires a new migration file.** Without one, startup fails validation.
- **Never edit an applied migration.** Flyway checksums each file and refuses to start if one changes. Add `V3__...sql` instead.

To replay migrations from scratch, which destroys all data:

```bash
docker compose down -v && docker compose up -d
```

`V2__seed_sample_data.sql` inserts the sample user and wants, and `V4__seed_dani_profile.sql` gives that user a profile. Both run in every environment Flyway touches, so delete them before using this schema for anything real. `V3__create_user_profiles.sql` creates the profiles table and stays. `V5__add_users_clerk_id.sql` adds `users.clerk_id`, which links a user to their Clerk account. It is nullable only because `dani` predates logins. `V6__add_users_email_last_sign_in.sql` adds `users.email` and `users.last_sign_in_at`, both copied from the Clerk token. `V7__merge_user_profiles_into_users.sql` moves each profile's nickname and avatar onto its user, then drops `user_profiles`. The profile's hand-typed email is kept only where Clerk hasn't supplied one, as for `dani`. `V8__clerk_picture_drop_nickname.sql` drops `nickname`, renames `s3_url` to `image_url` and empties it, once the profile page went and the picture became Clerk's. `V9__create_follows.sql` creates `follows`, one row per follower and followee, unique per pair, never yourself. `V10__seed_test_users.sql` adds the test users `alex`, `sam` and `jordan` with two wants each; like V2, delete it before real use. `V11__create_notifications.sql` creates `notifications`: who it is for, who did it, what kind, when, and when it was read. `V12__want_capacity_and_signups.sql` adds `wants.capacity` (at least 1, the owner included), creates `want_signups`, one row per seat, unique per want and person, seats each existing want's owner, and gives notifications the want they are about, its message and, for a rename, the old one. `V13__want_close_and_cancel.sql` adds `wants.closed_at` and `wants.cancelled_at`, and the three notification types for them. `V14__want_start_and_end.sql` adds `wants.started_at` and `wants.ended_at`, and `WANT_STARTED` and `WANT_ENDED`. `V15__want_open_invite.sql` lets `wants.capacity` be null, for an open invite, and adds `WANT_OPEN_INVITE` and `WANT_LIMITED`. `V16__want_deadline.sql` adds `wants.expires_at`, nullable for no deadline, and `WANT_DEADLINE_CHANGED` and `WANT_EXPIRED`. `V17__announce_wants.sql` adds `WANT_POSTED`, a partial unique index allowing one per want and follower, and an index for finding someone's followers. `V18__follow_mute.sql` adds `follows.notify_new_wants`, true unless you muted that person's new wants. `V19__circles.sql` creates `circles`, with a name unique per owner among circles not deleted, and `circle_members`, whose key to `follows` both limits members to the owner's followers and removes them when they unfollow; it also adds `CIRCLE_ADDED`, with the circle's id and name, to notifications. `V20__want_circle.sql` adds `wants.circle_id`, null for General. V3 and V4 stay even though their table is gone, because applied migrations must never change.

If you delete a migration that hasn't run yet and replace it with another of the same version, run `mvn clean` first. Otherwise the old file's compiled copy in `target/classes` stays behind, and Flyway refuses to start with *"Found more than one migration with version"*.

## Container image

```bash
docker build -t danrsles/wants-api:dev .
```

The image contains no credentials. `.dockerignore` keeps `config/` and `.env` out of the build context. The image sets `SPRING_PROFILES_ACTIVE=prod`, so a container always uses the hosted-database settings, which arrive as environment variables:

```bash
docker run -p 8080:8080 \
  -e DB_URL='jdbc:postgresql://<host>:<port>/defaultdb?sslmode=verify-full&sslrootcert=/certs/ca.pem' \
  -e DB_USER=<user> -e DB_PASSWORD=<password> \
  -e ORIGIN_SECRET=<secret> \
  -e CLERK_ISSUER=https://<name>.clerk.accounts.dev \
  -e CLERK_AUTHORIZED_PARTIES=https://<id>.cloudfront.net \
  -v /path/to/ca.pem:/certs/ca.pem:ro \
  danrsles/wants-api:dev
```

`CLERK_AUTHORIZED_PARTIES` is a comma-separated list of the page origins allowed to hold tokens.

The CA certificate is mounted at runtime rather than built into the image, so the same image works against any PostgreSQL database.

If `DB_URL` is missing, startup fails with `'url' must start with "jdbc"`. That means the variable isn't set, not that the URL is malformed.

## Continuous integration

`.github/workflows/docker-publish.yml` runs on every push to `master`, on every pull request, and can be triggered manually from the Actions tab. A pull request runs only the two test jobs, so it shows a pass or fail before merging; publishing and deploying happen only from `master`.

| Job | What it does |
|---|---|
| Test API | `mvn verify`: the unit tests, then the database tests in Docker |
| Test and build web | `npm ci`, `lint`, `test`, `build` on Node 22; keeps `dist/` as an artifact, except on pull requests |
| Publish API image | on `master` only, when both test jobs pass, pushes `danrsles/wants-api` tagged `latest` and `sha-<commit>` |
| Deploy API | after the image is published, tells the API server through SSM Run Command to pull it, restart, and pass a health check |
| Deploy web | when both test jobs pass, uploads that tested `dist/` to S3 and refreshes CloudFront |

The deploy job has **no AWS keys**. GitHub issues it a short-lived OIDC token, and AWS exchanges it for temporary credentials for a role that only this repository's `master` branch can assume. The role may only write to the site bucket and invalidate the distribution.

It needs two repository **secrets**, `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` (a Docker Hub access token, not the account password). It also needs three repository **variables** (Settings → Secrets and variables → Actions → Variables), taken from `terraform output` after the first apply:

| Variable | From |
|---|---|
| `AWS_DEPLOY_ROLE_ARN` | `terraform output -raw github_deploy_role_arn` |
| `SITE_BUCKET` | `terraform output -raw site_bucket` |
| `CLOUDFRONT_DISTRIBUTION_ID` | `terraform output -raw cloudfront_distribution_id` |
| `AWS_DEPLOY_API_ROLE_ARN` | `terraform output -raw github_deploy_api_role_arn` |
| `CLERK_PUBLISHABLE_KEY` | the Clerk dashboard, **Configure → API keys** (`pk_test_...`) |

Each deploy job shows as skipped until its variable exists: `SITE_BUCKET` for the site, `AWS_DEPLOY_API_ROLE_ARN` for the API. `CLERK_PUBLISHABLE_KEY` is different. Vite compiles it into the bundle, and a site built without it loads to a blank page, so the web build **fails** rather than skips without it. It is a variable rather than a secret because it is public.

**How the API is redeployed.** The `Deploy API` job:
1. finds the running instance tagged `Name=wants-api`
2. sends it [`deploy/redeploy-api.sh`](deploy/redeploy-api.sh) through **SSM Run Command**: AWS delivers the command to the SSM agent already running on the server, so there's no SSH, no open port and no key in GitHub
3. the script runs `docker compose pull` and `up -d`, then polls `/actuator/health` on the server itself, with the CloudFront header, until it answers. That is the one route that needs no Clerk login.
4. the job waits for the result and fails if the API isn't healthy within two minutes

Requests during the restart get a brief 502 from CloudFront. There is no automatic rollback, because `latest` has already moved; to roll back, re-run the workflow for an earlier commit. Only one API deploy runs at a time.

## Deploying to AWS

`terraform/` builds the whole site. CloudFront is the only public way in: it serves the React app from a private S3 bucket and forwards `/api/*` to the API server. The API server's port 8080 admits only CloudFront's address ranges, and Spring additionally rejects any request without CloudFront's secret header.

```
Browser ──HTTPS──► CloudFront (https://<id>.cloudfront.net)
                    ├─ /*      → S3 bucket (private)       the React build
                    └─ /api/*  → strip /api, add X-Origin-Secret
                                 → API server :8080 (admits CloudFront only)
                                     │ at boot: DB password + origin secret from SSM
                                     └──TLS (verify-full)──► Aiven PostgreSQL
```

Two CloudFront Functions in `terraform/functions/` do what a web server would otherwise do:
- `strip-api-prefix.js` turns `/api/wants` into `/wants`, the same rewrite as the Vite dev proxy.
- `spa-fallback.js` serves `index.html` for any path without a file extension, such as `/info`, so client-side routes survive a refresh.

Approximate cost: the API server is ~$13 a month (instance, Elastic IP, disk). S3 and CloudFront are effectively free at this size, within CloudFront's always-free 1 TB and 10 million requests a month.

**Before the first apply**

1. Download Aiven's CA certificate into `terraform/ca.pem`. It is git-ignored, and `db_ca_cert_path` can point elsewhere.
2. Fill in `terraform.tfvars` from the template. `db_url` must end in `sslrootcert=/certs/ca.pem`, which is the path *inside the container*, not on your machine. `clerk_issuer` defaults to the project's Clerk instance in `variables.tf`, so set it only to use a different one. Every value needs double quotes.
3. Put the database password in your shell, **not** in `terraform.tfvars`. Each `plan` and `apply` needs it:

   ```bash
   read -s -p "DB password: " TF_VAR_db_password && echo && export TF_VAR_db_password   # Git Bash
   ```

   ```powershell
   $env:TF_VAR_db_password = '...'                                                     # PowerShell
   ```

```bash
cd terraform
cp terraform.tfvars.example terraform.tfvars   # then fill it in
terraform init
terraform plan
terraform apply
terraform output site_url
```

Open `site_url` in a browser once CI has uploaded the site (see **Continuous integration**: set the three repository variables, then merge or re-run the workflow). The API is reachable at `<site_url>api/wants`. Port 8080 on the server is closed to everyone else, including you.

**After the first apply,** add the `api_public_ip` output to the Aiven service's **Allowed IP addresses**, if you restrict them. It's the address the API server connects *out* from. It's an Elastic IP, so it stays the same when the instance is replaced. Until it's allowed, the app can't reach the database: the container restarts on its own and connects once the address is added.

Allow two to three minutes after `apply` before the API answers. A new CloudFront distribution also takes a few minutes to reach every edge location. `terraform apply` returns as soon as the instance launches, while cloud-init is still installing Docker and pulling the image.

What the instance gets at boot (`user_data.sh.tftpl`):
- `/opt/wants/ca.pem`, the certificate, sent in the user data. It isn't secret.
- `/opt/wants/.env`, holding `DB_URL`, `DB_USER`, `CLERK_ISSUER` and `CLERK_AUTHORIZED_PARTIES` (the distribution's `https://<id>.cloudfront.net`), plus `DB_PASSWORD` and `ORIGIN_SECRET` fetched from SSM.
- `/opt/wants/compose.yaml`, which runs the image with `ca.pem` mounted read-only at `/certs/ca.pem`.

**The origin secret** is generated by Terraform (`random_password`) and stored in SSM as `/wants/origin-secret`. CloudFront sends it as the `X-Origin-Secret` header, and Spring's `OriginSecretFilter` returns 403 without it. The check is switched on by `app.origin-check.enabled=true` in the `prod` profile, and it fails closed: with the switch on, a missing or empty secret stops the app from starting rather than leaving the API open. It exists because CloudFront's address ranges are shared by every CloudFront customer, so the firewall alone would also admit someone else's distribution pointed at this server. Unlike the database password it *is* in `terraform.tfstate`, because it's also part of the distribution's configuration. To rotate it, run `terraform apply -replace=random_password.origin_secret`.

**The password** goes to SSM Parameter Store as a `SecureString` (`/wants/db-password`), encrypted with the free AWS-managed key. It is a *write-only* value (`value_wo`) from an *ephemeral* variable, so Terraform sends it to AWS but keeps no copy: it is in neither the plan nor `terraform.tfstate`. The instance's IAM role may read that one parameter and nothing else, so the password never appears in the instance's user data either. Because Terraform stores nothing to compare against, it can't detect a password change on its own. After changing the password, set the new one in `TF_VAR_db_password`, raise `db_password_version` in `terraform.tfvars`, apply, and restart the app.

Debugging on the instance:

```bash
ssh -i <key>.pem ec2-user@<api_public_ip>      # or: terraform output ssh_api
sudo cat /var/log/cloud-init-output.log            # the boot script's output
cd /opt/wants && sudo docker compose logs -f app   # the app's logs
```

`terraform destroy` removes the instance, Elastic IP, IAM roles (including both GitHub deploy roles), SSM parameters, the S3 bucket and its files, and the CloudFront distribution. The data lives in Aiven, so destroying or replacing the instance loses nothing. Because `user_data_replace_on_change` is set, changing the image, the certificate or any database setting replaces the instance. Changing only the password updates SSM; restart the app for it to take effect.

## Known gaps

These are deliberate omissions, not oversights:

- **Clerk runs as a development instance.** A Clerk production instance requires a domain you own, with DNS records Clerk can verify, and the site is still on `*.cloudfront.net`. A development instance works there, but shows a "Development mode" badge and has Clerk's development user limits. Moving to production means a custom domain, an ACM certificate in us-east-1, a production Clerk instance, and new `pk_live_` / issuer values.
- **Usernames are optional in Clerk.** Unless usernames are required under **Configure → User & authentication**, a user who signs up with Google alone has none, and shows as their Clerk id (`user_2abc...`) until they set one in the account menu.
- **No roles.** Every signed-in user has the same rights; there is no admin who can moderate other people's wants.
- **Pictures live in Clerk.** The app stores only each picture's URL, so moving off Clerk would mean moving everyone's picture too. Other people see a changed picture the next time they load the list; nothing in the app updates live.
- **Notifications are polled, not pushed.** A new one can take up to 30 seconds to show, and nothing reaches you while the app is closed: no browser or phone notifications. The table works unchanged under either; see the design doc.
- **Other users go out as response types only.** `GET /wants` returns `WantResponse`, which names the owner only by `userId`, `username` and `imageUrl`, `GET /follows` and `GET /users?q=` return `PersonResult`, which adds to those three fields only whether you follow them and hear about their new wants, and `GET /notifications` names each actor by `UserSummary`. `/me` still returns the `User` entity, which is fine only because it is always your own. Any future endpoint listing other users should return `UserSummary` or its own response type.
- **Sample data is seeded everywhere.** `V2` and `V10` insert the users `dani`, `alex`, `sam` and `jordan` and their wants into every database Flyway touches, the hosted one included, so a fresh install has something to look at. Delete them before real users arrive.
- **State in Terraform is local.** `terraform.tfstate` is git-ignored and holds the infrastructure's details in cleartext, including the CloudFront origin secret. The database password is write-only and not stored in it. A team would move it to an encrypted S3 backend with locking.

## Files that must never be committed

Each is git-ignored. `config/application-prod.properties` contains the live database password.

| File | Contains |
| --- | --- |
| `config/application-prod.properties` | hosted database credentials, for running the `prod` profile locally |
| `terraform/terraform.tfvars` | the database URL and user, your IP |
| `terraform/terraform.tfstate` | the deployed infrastructure's details, in cleartext |
| `terraform/tfplan` | a zip archive embedding every variable value |

The Clerk **secret key** (`sk_...`) should never be in this repository at all, ignored or not. Nothing here uses it.
