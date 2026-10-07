# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

No Maven wrapper is checked in, so use a system `mvn` (3.9.x) with JDK 21.

```bash
mvn spring-boot:run          # run the app on http://localhost:8080
mvn test                     # run all tests
mvn test -Dtest=HelloControllerTest            # single test class
mvn test -Dtest=HelloControllerTest#helloReturnsHelloWorld   # single test method
mvn package                  # build target/demo-0.0.1-SNAPSHOT.jar
java -jar target/demo-0.0.1-SNAPSHOT.jar       # run the packaged jar
```

No linter or formatter is configured for the Java code. The frontend has oxlint and a Vitest suite — see **Frontend** below.

Container image:

```bash
docker build -t danrsles/wants-api:0.0.1 .
docker run -p 8080:8080 --network tryclaude_default   -e MYSQL_HOST=mysql -e MYSQL_PORT=3306 -e MYSQL_DATABASE=demo   -e MYSQL_USER=... -e MYSQL_PASSWORD=... danrsles/wants-api:0.0.1
```

The image carries no configuration: `.dockerignore` keeps `.env` and `application-local.properties` out of the build context, so every credential must arrive as an environment variable. `MYSQL_PORT` is **3306** inside the Docker network — the 3307 mapping only exists on the host. The build skips tests (`-DskipTests`); run `mvn test` separately in CI.

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
```

The dev server proxies `/api/*` to `http://localhost:8080`, stripping the prefix (see `vite.config.ts`). That keeps the browser on a single origin, so **the backend needs no CORS configuration**. Deploying the frontend on a different origin than the API would change that — then Spring would need CORS, or a reverse proxy would have to serve both under one hostname.

`src/` is organised by role:

```
src/api/http.ts                 the shared `fail` helper that turns a bad response into an Error
src/api/wants.ts                transport: one function per endpoint, no state
src/api/userProfiles.ts         the same for /user/profiles
src/hooks/useWants.ts           server state: the cached list and its mutations
src/hooks/useUserProfile.ts     server state: one user's profile (or null) and its mutations
src/types/wants.ts              the shapes the API returns and accepts
src/types/userProfiles.ts       the same for profiles
src/currentUser.ts              DEFAULT_USER_ID, the user the app acts as until there is a login
src/components/Wants.tsx        the whole wants screen, including its own UI state
src/components/Info.tsx         a static about page, routed at /info
src/components/UserProfile.tsx  the profile page at /profile: view, edit, delete, or create
src/components/ProfileBadge.tsx nickname and avatar in the header, linking to /profile
src/components/Avatar.tsx       round picture with a local placeholder fallback
src/App.tsx               the shell: the page frame, the nav and the routes
```

Imports only ever point down that list: a component calls a hook, a hook calls `api/`, and **nothing but a hook calls `api/` directly**. `App.tsx` holds no state at all, so a second screen means a new component and a route rather than changes to the first one.

Routing is React Router (`react-router`, declarative mode). `main.tsx` wraps `App` in a `BrowserRouter`, and `App.tsx` holds the `<Routes>` and the nav, so `App.test.tsx` can render `App` inside a `MemoryRouter` at any path. Vite's dev server already falls back to `index.html` for unknown paths, so deep links like `/info` work in dev. A static host serving `dist/` would need the same fallback.

Because there is no login, the app acts as `DEFAULT_USER_ID = 1` (`src/currentUser.ts`), the seeded `dani`: `Wants.tsx` attributes new wants to that user, and `App.tsx` passes it to `ProfileBadge` and `UserProfile`. The badge and the profile page each call `useUserProfile`, so they hold separate copies. A nickname changed on `/profile` reaches the header only on the next load. That is the point to reach for TanStack Query.

Avatars use `Avatar.tsx`, which shows `src/assets/avatar-placeholder.svg` when `s3Url` is null and also when the URL fails to load. Vite inlines that SVG as a `data:` URI, so tests compare `src` against the imported asset rather than matching a file name.

Component tests are Vitest with React Testing Library, in a jsdom environment configured in the `test` block of `vite.config.ts` (which is why that file imports `defineConfig` from `vitest/config` rather than `vite`). Tests sit beside the component as `<Name>.test.tsx` and stub the `api/` module with `vi.mock`, never `fetch`, so they survive a change of transport and need no server.

Two things about the setup are easy to trip over:

- **Vitest globals are off**, so a test imports `describe`, `it`, `expect` and `vi` from `vitest` explicitly.
- Because globals are off, Testing Library does not register its own teardown. `src/setupTests.ts` calls `afterEach(cleanup)` by hand. Without it every render stays in the document and queries in later tests match elements left behind by earlier ones — which looks like a mysterious "found multiple elements" failure in every test but the first.

`/component` (see `.claude/skills/component/SKILL.md`) creates a component with tests following these conventions and iterates until the suite passes.

The `ui-ux-reviewer` subagent (`.claude/agents/ui-ux-reviewer.md`) reviews a component in a real browser through the Playwright MCP server: it screenshots desktop and mobile, light and dark, walks the page by keyboard, and reports visual, UX and accessibility findings with file:line fixes. It never edits code, and it is told not to submit, edit or delete wants, because the dev server talks to the real database. Screenshots land in `.playwright-mcp/`, which is git-ignored.

Styling is Tailwind CSS v4, wired through the `@tailwindcss/vite` plugin. v4 has **no `tailwind.config.js`** — everything is declared in CSS. `src/index.css` holds the category colours (an `@theme` block, used as `bg-food`, `bg-movie` and so on) and the shared `field` and `btn` classes (`@utility` blocks, which is why there is no styles constants module). Neither utility sets padding — callers size themselves, so two utilities never fight over the same property. Dark mode uses the `dark:` variant, which follows `prefers-color-scheme` by default.

State is local `useState`. All server data is refetched after each mutation, which is fine at this size; if the UI grows past one screen, reach for TanStack Query (server cache) before reaching for Redux (shared client state).

## Architecture

A single-module Spring Boot 3.5.6 web application on Java 21. `DemoApplication` sits in the root package `com.example.demo` so component scanning reaches the layers beneath it:

- `models` — JPA entities and enums (`Want`, `User`, `UserProfile`, `Category`)
- `repositories` — Spring Data interfaces (`WantRepository`, `UserRepository`, `UserProfileRepository`)
- `services` — business logic, the only layer controllers talk to (`WantService`, `UserProfileService`)
- `controllers` — HTTP endpoints (`WantController`, `UserProfileController`)

Controllers depend on services, never on repositories directly, and all dependencies are injected through constructors rather than fields, which keeps them explicit and testable without a container.

Endpoints:

- `GET /wants` — every want as JSON, narrowed by an optional `?category=` parameter. Spring converts that parameter to the `Category` enum by exact name, so `FOOD` works while `food` or an unknown value yields 400 before the controller runs.
- `POST /wants` — creates one, taking `{"message", "category", "userId"}` and returning 201 with the saved want. `CreateWantRequest` carries the Bean Validation constraints, so a blank message, missing field or unknown category is a 400 before the service is reached; an unknown `userId` is a 404 via `UserNotFoundException`, which carries `@ResponseStatus` rather than needing an exception handler.
- `/user/profiles` — full CRUD for profiles: `GET` (optionally `?userId=`, always a list), `GET /{id}`, `POST` (`CreateUserProfileRequest`: `userId`, `email`, `nickname`, optional `s3Url`), `PUT /{id}` (`UpdateUserProfileRequest`, the same fields without `userId`, because a profile never changes owner) and `DELETE /{id}`. A second profile for a user, or an email already in use, is a **409** via `UserProfileConflictException`. The service checks for these up front, so the client never sees a 500 from the database unique constraints. A blank `s3Url` is stored as null.

`UserProfile` is a separate entity, one-to-one with `User` through a unique `user_id`, rather than columns on `users`. **Keep email off `User`**: every want embeds its user in JSON, so a field on `User` is published on every `GET /wants`. The association runs from profile to user only, and it is eager, for the same reasons as `Want.user` below.

Each `Want` belongs to a `User` through a non-null `@ManyToOne` (`user_id`, a real foreign key). The association is deliberately unidirectional: a `User` has no `List<Want>`, which avoids the infinite recursion a bidirectional pair would cause during JSON serialization. Query wants by user through the repository rather than navigating from the user.

`@ManyToOne` defaults to eager fetching, and that matters here because `spring.jpa.open-in-view=false` closes the persistence context before serialization — a lazy association would throw `LazyInitializationException` while Jackson writes the response. If this is ever made lazy for performance, the controller's query must fetch-join the user.

Entities are serialized directly to JSON; there is no DTO layer, so `GET /wants` embeds the full user object in every want. If either entity gains a field that should not be exposed — a password hash on `User`, say — introduce a DTO rather than annotating around the problem.

Tests use `@WebMvcTest` with MockMvc, which loads only the web slice — no datasource, so `mvn test` passes without MySQL running. Collaborators are replaced with `@MockitoBean` (Spring Boot 3.4+ replaced `@MockBean`). New controller tests should follow that pattern and name the controller under test in the annotation.

## Schema migrations

Flyway owns the schema. Migrations live in `src/main/resources/db/migration` as `V<n>__<description>.sql` and run automatically at startup, before Hibernate initializes.

`spring.jpa.hibernate.ddl-auto=validate` — Hibernate never alters the database; it only checks that the tables match the entities and fails startup if they have drifted. **Adding a field to an entity therefore requires a new migration file**, not just the Java change. Never edit an applied migration: Flyway checksums each one and refuses to start if a checksum changes. Write `V3__...sql` instead.

`V2__seed_sample_data.sql` inserts the user `dani` with two wants, and `V4__seed_dani_profile.sql` gives `dani` a profile, so a fresh database is usable while users can't be created through the API. Both run in every environment Flyway touches, so delete them before this schema is used for anything real. `V3__create_user_profiles.sql` is schema and stays.

Resetting from scratch — the only safe way to replay migrations, and it destroys all data:

```bash
docker compose down -v && docker compose up -d
```

## Database and configuration

MySQL runs in Docker via `compose.yaml` (`docker compose up -d`), mapped to host port **3307** rather than the usual 3306 to avoid clashing with a locally installed MySQL. The app connects through `spring-boot-starter-data-jpa` and `mysql-connector-j`.

Two git-ignored files must exist before anything runs, each copied from the committed `.example` template beside it and filled in (the templates contain only `CHANGEME`):

- `.env` — read by Docker Compose only. Supplies the container's database name, user, password and host port. `compose.yaml` uses `${VAR:?message}` syntax, so `docker compose` fails with a named variable rather than starting a misconfigured container.
- `application-local.properties` — read by Spring only. Its credentials must match `.env`, because those are what the container was created with.

Changing `MYSQL_USER` or `MYSQL_PASSWORD` in `.env` does **not** re-credential an existing container: MySQL applies them only when initializing an empty data directory. Run `docker compose down -v` to discard the volume first, then `up -d`, and update `application-local.properties` to match.

Configuration layers, in increasing precedence:

1. `src/main/resources/application.properties` — committed, and holds **no credential values at all**. The datasource properties are bare `${MYSQL_PORT}` / `${MYSQL_USER}` / `${MYSQL_PASSWORD}` placeholders with no fallbacks, which Spring resolves against its Environment (command-line args, JVM system properties, OS environment variables, imported config files).
2. `application-local.properties` in the project root — git-ignored and required for the app to start, loaded via `spring.config.import=optional:file:./application-local.properties`. Credentials belong here; copy `application-local.properties.example` to create it. Because imported config outranks the importing document, properties set here win.

A checkout without `application-local.properties` fails at startup with `Failed to parse the host:port pair 'localhost:${MYSQL_PORT}'` — that is the intended fail-fast, not a bug. Creating the local file from the example fixes it. `mvn test` is unaffected, because `@WebMvcTest` loads only the web slice and never opens a datasource.

Note that the two files are read by different systems and neither sees the other: Spring does not understand the `.env` format, and Compose does not read `.properties`. A port or password changed in one must be changed in the other by hand.

## Continuous integration

`.github/workflows/docker-publish.yml` runs on every push to `master` and can be triggered manually from the Actions tab. It runs `mvn test` first, then builds the image and pushes it to Docker Hub as `danrsles/wants-api`, tagged `latest` and `sha-<commit>` so any published image traces back to its commit.

It needs two repository secrets, set under Settings → Secrets and variables → Actions:

- `DOCKERHUB_USERNAME` — the Docker Hub account name
- `DOCKERHUB_TOKEN` — a Docker Hub **access token**, not the account password

The test job needs no database, because the tests are `@WebMvcTest` slices. A future test that touches persistence would need a MySQL service container added to that job.

## Copilot app modernization hooks

`.github/modernize/java-upgrade/` belongs to the GitHub Copilot app modernization (Java upgrade) extension, not to the application. Its scripts read a tool-call JSON payload on stdin and append `run_in_terminal` and `appmod-*` calls to a per-session JSONL file under that directory, for the extension to consume. The directory ignores its own contents via a nested `.gitignore` containing `**/*`, so those session logs are intentionally untracked. Leave this tree alone unless the task is explicitly about that extension.


## Further instructions
Update the README.md as we make more features and commits