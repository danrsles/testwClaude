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

Tests use `@WebMvcTest` with MockMvc, which loads only the web slice — no datasource, so `mvn test` passes without a database running. Collaborators are replaced with `@MockitoBean` (Spring Boot 3.4+ replaced `@MockBean`). New controller tests should follow that pattern and name the controller under test in the annotation.

## Schema migrations

Flyway owns the schema, on **PostgreSQL** (`flyway-database-postgresql`). Migrations live in `src/main/resources/db/migration` as `V<n>__<description>.sql` and run automatically at startup, before Hibernate initializes.

`spring.jpa.hibernate.ddl-auto=validate` — Hibernate never alters the database; it only checks that the tables match the entities and fails startup if they have drifted. **Adding a field to an entity therefore requires a new migration file**, not just the Java change. Never edit an applied migration: Flyway checksums each one and refuses to start if a checksum changes. Write a new `V<n+1>__...sql` instead.

The project moved from MySQL to PostgreSQL once, and V1 and V3 were rewritten in place as PostgreSQL then. That was safe only because no PostgreSQL database had applied them; any MySQL database from before is abandoned rather than migrated. Do not treat it as precedent.

SQL notes for this schema: ids are `BIGINT GENERATED BY DEFAULT AS IDENTITY`, which `GenerationType.IDENTITY` maps to. Enum-like columns such as `wants.category` are `VARCHAR` with a `CHECK` constraint, not native PostgreSQL enum types — `@Enumerated(EnumType.STRING)` is validated as a character column, and a native enum would fail `ddl-auto=validate`. PostGIS and `btree_gist` are available on the hosted database and in the local image; enable them with `CREATE EXTENSION IF NOT EXISTS` in the migration that first needs them.

`V2__seed_sample_data.sql` inserts the user `dani` with two wants, and `V4__seed_dani_profile.sql` gives `dani` a profile, so a fresh database is usable while users can't be created through the API. Both run in every environment Flyway touches, so delete them before this schema is used for anything real. `V3__create_user_profiles.sql` is schema and stays.

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

**Hosted database.** Aiven requires TLS; use `sslmode=verify-full&sslrootcert=<path to Aiven's ca.pem>` in the JDBC URL. `ca.pem` is not secret, but is kept outside the repository. In a `.properties` file the path must use forward slashes even on Windows, because backslashes are escapes. Running the `prod` profile locally writes to the real database.

`mvn test` needs neither database, because `@WebMvcTest` loads only the web slice.

## Continuous integration

`.github/workflows/docker-publish.yml` runs on every push to `master` and can be triggered manually from the Actions tab. It runs `mvn test` first, then builds the image and pushes it to Docker Hub as `danrsles/wants-api`, tagged `latest` and `sha-<commit>` so any published image traces back to its commit.

It needs two repository secrets, set under Settings → Secrets and variables → Actions:

- `DOCKERHUB_USERNAME` — the Docker Hub account name
- `DOCKERHUB_TOKEN` — a Docker Hub **access token**, not the account password

The test job needs no database, because the tests are `@WebMvcTest` slices. A future test that touches persistence would need a PostgreSQL service container added to that job.

## Deployment (Terraform)

`terraform/` runs **one API instance** against the hosted Aiven database; there is no database on AWS. The two-instance layout (a public nginx UI server in front of a private API) is planned but not built.

- **Password:** the database password is an SSM `SecureString` (`aws_ssm_parameter.db_password`, default name `/wants/db-password`) using the AWS-managed `aws/ssm` key.
  - It is set through **`value_wo`, a write-only argument, fed from an `ephemeral = true` variable**, so it never enters the plan or `terraform.tfstate`. Both need Terraform 1.11 or later, hence `required_version = ">= 1.11"`.
  - Supply it as `TF_VAR_db_password` in the shell, never in `terraform.tfvars`. `terraform.tfvars` outranks the environment variable, so a leftover line there would silently override it.
  - Write-only values can't be diffed, so Terraform only re-sends the password when `db_password_version` changes. Raise it whenever the password changes.
  - The instance profile's only permission is `ssm:GetParameter` on that one ARN. No `kms:Decrypt` is needed, because the managed key's policy allows decryption through SSM for principals in the account.
  - The boot script fetches the password with the preinstalled AWS CLI, under IMDSv2 (`http_tokens = "required"`).
- **The boot script must never `set -x`:** it would print the password into `/var/log/cloud-init-output.log`.
- **Certificate:** `ca.pem` is read from `terraform/` (git-ignored, `terraform/*.pem`), passed through `trimspace(file(...))` in the user data, written to `/opt/wants/ca.pem`, and mounted at `/certs/ca.pem`. A validation on `db_url` requires `sslrootcert=/certs/ca.pem` and rejects a laptop path by mistake.
- **`.env` on the instance** quotes every value in single quotes, so Compose doesn't interpret the `&` in the JDBC URL.
- **Fixed address:** an Elastic IP is associated separately (`aws_eip_association`), so it survives instance replacement and can be added to Aiven's IP allowlist. `associate_public_ip_address` stays true, so the instance has outbound access before the association happens.
- **What replaces the instance:** any change to the user data (image tag, certificate, URL, user), because `user_data_replace_on_change` is set. A password change only updates SSM, so the app needs a restart to pick it up. The container keeps the password it got at first boot in `/opt/wants/.env`, so restarting means rerunning the fetch-and-write steps, or replacing the instance.
- **AWS account guardrail:** the account has a budget action (`Monthly-50-Max-Budget`) that attaches the deny policy `MyActualBudgetKillSwitchPayload` (`ec2:RunInstances`, `ec2:StartInstances`, `ecs:*`) to the `admin-worker` user at 100% of $50. If `apply` fails with `UnauthorizedOperation ... explicit deny ... MyActualBudgetKillSwitchPayload`, check the budget action's status before anything else. Its automatic reset has failed before (`RESET_FAILURE`), leaving the block on with no spend.

## Copilot app modernization hooks

`.github/modernize/java-upgrade/` belongs to the GitHub Copilot app modernization (Java upgrade) extension, not to the application. Its scripts read a tool-call JSON payload on stdin and append `run_in_terminal` and `appmod-*` calls to a per-session JSONL file under that directory, for the extension to consume. The directory ignores its own contents via a nested `.gitignore` containing `**/*`, so those session logs are intentionally untracked. Leave this tree alone unless the task is explicitly about that extension.


## Further instructions
Update the README.md as we make more features and commits