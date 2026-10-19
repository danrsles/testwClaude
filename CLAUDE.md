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
src/api/http.ts                 `fail`, plus `authFetch`, which attaches the Clerk session token
src/api/wants.ts                transport: one function per endpoint, no state
src/api/me.ts                   the same for /me (GET and PUT)
src/hooks/useWants.ts           server state: the cached list and its mutations
src/hooks/MeProvider.tsx        server state: the signed-in user, loaded once and shared
src/hooks/useOnPictureChange.ts calls back when you change your picture in Clerk, after minting a fresh token
src/hooks/useMe.ts              the context MeProvider fills, and the hook that reads it
src/types/wants.ts              the shapes the API returns and accepts
src/components/Wants.tsx        the whole wants screen, including its own UI state
src/components/Info.tsx         a static about page, routed at /info
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

`Wants.tsx` shows edit controls only on rows where `want.userId` matches `me.id`, but that is cosmetic: the API returns 403 regardless. It names each owner by `want.username`, beside `<Avatar src={want.imageUrl} alt="" size={24} />`. The alt is empty because the username already names the person.

**Picture changes show at once.** The API learns your picture only from the session token, which Clerk otherwise reissues about once a minute. `Wants` therefore calls `useOnPictureChange(refresh)`:
- The hook watches `useUser().user.imageUrl`. Clerk updates it in the page as soon as you change your picture in its menu.
- On a change, it calls Clerk's standalone `getToken({ skipCache: true })`, which mints and caches a new token, then calls `refresh`. The reload's `GET /wants` carries that token, and the API syncs your row before listing (see `WantService.findAll`).
- The first value, Clerk finishing loading the user, is not treated as a change.
- `Wants.test.tsx` mocks `@clerk/react` (`useUser`, `getToken`) with a `vi.hoisted` user whose `imageUrl` a test changes between `rerender`s. `App.test.tsx`'s Clerk mock includes both too.

If more state needs sharing, reach for TanStack Query rather than more providers.

`Avatar.tsx` shows `src/assets/avatar-placeholder.svg` when its `src` is null and also when the URL fails to load. Vite inlines that SVG as a `data:` URI, so tests compare `src` against the imported asset rather than matching a file name.

Component tests are Vitest with React Testing Library, in a jsdom environment configured in the `test` block of `vite.config.ts` (which is why that file imports `defineConfig` from `vitest/config` rather than `vite`). Tests sit beside the component as `<Name>.test.tsx` and stub the `api/` module with `vi.mock`, never `fetch`, so they survive a change of transport and need no server. Because `api/` is stubbed, they never reach Clerk's `getToken`. Anything that renders `App` must also `vi.mock('@clerk/react')`, as `App.test.tsx` does with a `vi.hoisted` signed-in flag, because the real `<Show>` and `<SignIn>` load Clerk from the network.

Two things about the setup are easy to trip over:

- **Vitest globals are off**, so a test imports `describe`, `it`, `expect` and `vi` from `vitest` explicitly.
- Because globals are off, Testing Library does not register its own teardown. `src/setupTests.ts` calls `afterEach(cleanup)` by hand. Without it every render stays in the document and queries in later tests match elements left behind by earlier ones — which looks like a mysterious "found multiple elements" failure in every test but the first.

`/component` (see `.claude/skills/component/SKILL.md`) creates a component with tests following these conventions and iterates until the suite passes.

The `ui-ux-reviewer` subagent (`.claude/agents/ui-ux-reviewer.md`) reviews a component in a real browser through the Playwright MCP server: it screenshots desktop and mobile, light and dark, walks the page by keyboard, and reports visual, UX and accessibility findings with file:line fixes. It never edits code, and it is told not to submit, edit or delete wants, because the dev server talks to the real database. Screenshots land in `.playwright-mcp/`, which is git-ignored.

Styling is Tailwind CSS v4, wired through the `@tailwindcss/vite` plugin. v4 has **no `tailwind.config.js`** — everything is declared in CSS. `src/index.css` holds the category colours (an `@theme` block, used as `bg-food`, `bg-movie` and so on) and the shared `field` and `btn` classes (`@utility` blocks, which is why there is no styles constants module). Neither utility sets padding — callers size themselves, so two utilities never fight over the same property. Dark mode uses the `dark:` variant, which follows `prefers-color-scheme` by default.

State is local `useState`. All server data is refetched after each mutation, which is fine at this size; if the UI grows past one screen, reach for TanStack Query (server cache) before reaching for Redux (shared client state).

## Architecture

A single-module Spring Boot 3.5.6 web application on Java 21. `DemoApplication` sits in the root package `com.example.demo` so component scanning reaches the layers beneath it:

- `models` — JPA entities and enums (`Want`, `User`, `Category`)
- `repositories` — Spring Data interfaces (`WantRepository`, `UserRepository`)
- `services` — business logic, the only layer controllers talk to (`WantService`, `UserService`), plus `ClerkIdentity`
- `controllers` — HTTP endpoints (`WantController`, `UserController`), plus `Identities`, which turns a token into a `ClerkIdentity`
- `dto` — request bodies with their Bean Validation constraints (`WantRequest`) and response shapes (`WantResponse`). New request and response records go here, not in `controllers`.
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
- `GET /wants` — every want, from every user, as `WantResponse` (`{id, message, category, userId, username, imageUrl}`). It first runs `UserService.current` for the caller in the same transaction, so a picture or username just changed in Clerk shows beside their own wants in that response rather than racing the page's parallel `GET /me`. The list can be narrowed by an optional `?category=` parameter. Spring converts that parameter to the `Category` enum by exact name, so `FOOD` works while `food` or an unknown value yields 400 before the controller runs.
- `POST /wants`, `PUT /wants/{id}`, `DELETE /wants/{id}` — `WantRequest` is `{"message", "category"}` with Bean Validation constraints, so a blank message or unknown category is a 400 before the service is reached. The owner is the token's user, and an unknown property such as a stale `userId` is ignored. Changing someone else's want is a 403 via `WantForbiddenException`, which carries `@ResponseStatus` rather than needing an exception handler.
- `GET /actuator/health` — the only unauthenticated route. Actuator exposes nothing else by default, and the health details stay hidden.

**Every field of `User` comes from Clerk:** `username`, `clerk_id`, `email`, `last_sign_in_at` and `image_url`, all copied from the token by `UserService`. Nothing about a user is edited through this API.
- There used to be a separate `user_profiles` table, kept apart only so email stayed out of every want; V7 folded it into `users`.
- V8 then dropped the user-edited `nickname` and turned `s3_url` into `image_url`, when the profile page was removed in favour of Clerk's account menu.

**`User` holds private data**, so it is serialized **only for its own user**, by `GET /me`. **Never return a `User`, or an entity that embeds one, for another user.**
- Wants are returned as `WantResponse` (`id`, `message`, `category`, `userId`, `username`, `imageUrl`). The picture URL is public by design.
- `WantControllerTest.neverExposesTheOwnersPrivateDetails` guards that.
- Any new endpoint that lists other people's data needs its own response record copying only public fields.

Each `Want` belongs to a `User` through a non-null `@ManyToOne` (`user_id`, a real foreign key). The association is deliberately unidirectional: a `User` has no `List<Want>`, which avoids the infinite recursion a bidirectional pair would cause during JSON serialization. Query wants by user through the repository rather than navigating from the user.

`@ManyToOne` defaults to eager fetching, and that matters here because `spring.jpa.open-in-view=false` closes the persistence context before serialization — a lazy association would throw `LazyInitializationException` while Jackson writes the response. If this is ever made lazy for performance, the controller's query must fetch-join the user.

Responses are a mix. `WantController` maps every `Want` to `WantResponse` (a record in `dto`, built by `WantResponse.from`). `User` is still serialized as an entity by `/me`, which is safe only because it is always the caller's own row. When a field must stay hidden, add a response record rather than annotating the entity with `@JsonIgnore`.

Tests use `@WebMvcTest` with MockMvc, which loads only the web slice — no datasource, so `mvn test` passes without a database running. Collaborators are replaced with `@MockitoBean` (Spring Boot 3.4+ replaced `@MockBean`). New controller tests should follow that pattern and name the controller under test in the annotation. They also need:
- `@Import(SecurityConfig.class)`. `@WebMvcTest` does not pick up `@Configuration` classes, and without it Boot's default chain would apply, with CSRF on.
- `@MockitoBean JwtDecoder`, so `ClerkJwtConfig` and its `CLERK_ISSUER` placeholder are never needed.
- `.with(jwt().jwt(t -> t.subject("user_...")))` on each authenticated request, plus a test that the route returns 401 without it.

Service-level rules, such as ownership, are tested with plain Mockito (`WantServiceTest`), since the controller tests mock the services.

## Schema migrations

Flyway owns the schema, on **PostgreSQL** (`flyway-database-postgresql`). Migrations live in `src/main/resources/db/migration` as `V<n>__<description>.sql` and run automatically at startup, before Hibernate initializes.

`spring.jpa.hibernate.ddl-auto=validate` — Hibernate never alters the database; it only checks that the tables match the entities and fails startup if they have drifted. **Adding a field to an entity therefore requires a new migration file**, not just the Java change. Never edit an applied migration: Flyway checksums each one and refuses to start if a checksum changes. Write a new `V<n+1>__...sql` instead.

The project moved from MySQL to PostgreSQL once, and V1 and V3 were rewritten in place as PostgreSQL then. That was safe only because no PostgreSQL database had applied them; any MySQL database from before is abandoned rather than migrated. Do not treat it as precedent.

SQL notes for this schema: ids are `BIGINT GENERATED BY DEFAULT AS IDENTITY`, which `GenerationType.IDENTITY` maps to. Enum-like columns such as `wants.category` are `VARCHAR` with a `CHECK` constraint, not native PostgreSQL enum types — `@Enumerated(EnumType.STRING)` is validated as a character column, and a native enum would fail `ddl-auto=validate`. PostGIS and `btree_gist` are available on the hosted database and in the local image; enable them with `CREATE EXTENSION IF NOT EXISTS` in the migration that first needs them.

`V2__seed_sample_data.sql` inserts the user `dani` with two wants, and `V4__seed_dani_profile.sql` gives `dani` a profile, so a fresh database is usable while users can't be created through the API. Both run in every environment Flyway touches, so delete them before this schema is used for anything real. `V3__create_user_profiles.sql` created the profiles table that `V7__merge_user_profiles_into_users.sql` later moves onto `users` and drops. `V8__clerk_picture_drop_nickname.sql` drops `users.nickname` and renames `s3_url` to `image_url`, emptied so each user's next request fills in their Clerk picture. V3 and V4 stay, because they have been applied; deleting or editing them would break Flyway's checksums.

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

`mvn test` needs neither database, because `@WebMvcTest` loads only the web slice.

## Continuous integration

`.github/workflows/docker-publish.yml` runs on every push to `master` and can be triggered manually from the Actions tab.
- `test-api` runs `mvn test`.
- `test-web` runs `npm ci`, `lint`, `test` and `build` in `frontend/` on Node 22, and uploads `dist/` as an artifact. The build reads `vars.CLERK_PUBLISHABLE_KEY` into `VITE_CLERK_PUBLISHABLE_KEY`, and **fails** if it is empty, because a bundle without it is a blank page.
- When both pass, two jobs run:
  - `publish-api` pushes `danrsles/wants-api` tagged `latest` and `sha-<commit>`.
  - `deploy-web` downloads that same `dist/`, so the tested build is the deployed build, and syncs it to S3. It then invalidates `/index.html` and `/` in CloudFront.

**`deploy-web` details:**
- **Upload order is deliberate.** New `assets/` go up first (`immutable`, one year), then everything else with `--delete` and `no-cache`, and only then old assets are deleted. A visitor never gets an `index.html` whose scripts are missing.
- **It authenticates with GitHub OIDC**, not stored keys: `aws-actions/configure-aws-credentials` assumes `vars.AWS_DEPLOY_ROLE_ARN`, and the job needs `permissions: id-token: write`. The role (`terraform/github.tf`) trusts only `<github_oidc_subject_prefix>:ref:refs/heads/master`, so `workflow_dispatch` from any other branch fails to assume it. **This repository uses GitHub's immutable OIDC subject format**, `repo:danrsles@13107885/testwClaude@1380283664:...`, which embeds the owner and repository IDs, not the classic `repo:danrsles/testwClaude:...`. A trust policy written in the classic form fails with "Not authorized to perform sts:AssumeRoleWithWebIdentity". Read the real prefix with `gh api repos/<owner>/<repo>/actions/oidc/customization/sub`.
- **It reads three repository *variables*** (not secrets): `AWS_DEPLOY_ROLE_ARN`, `SITE_BUCKET` and `CLOUDFRONT_DISTRIBUTION_ID`, from `terraform output`. It is skipped (`if: vars.SITE_BUCKET != ''`) until they exist.

**`deploy-api`** (needs `publish-api`) redeploys the instance through **SSM Run Command**:
- It assumes `vars.AWS_DEPLOY_API_ROLE_ARN`, finds exactly one running instance tagged `Name=wants-api`, and sends `deploy/redeploy-api.sh` with the `AWS-RunShellScript` document.
- The script travels **base64-encoded** (`echo <b64> | base64 -d | bash`), because SSM wraps commands in its own shell script and quoting would not survive the CLI, JSON and that shell.
- The script, run as root:
  - runs `docker compose pull app && up -d app`
  - sources `/opt/wants/.env`, so the health check can send `X-Origin-Secret` (the API rejects requests without it)
  - polls `localhost:8080/actuator/health` (the one route that needs no Clerk token) for up to 2 minutes, prunes old images on success, and on failure prints the last 60 log lines and exits 1, which fails the job
  - must never use `set -x`, because of the secrets in `.env`
- The job polls `ssm get-command-invocation` and prints the script's output.
- `concurrency: deploy-api` serializes deploys.
- There is no rollback; re-running an older commit's workflow republishes its image as `latest`.

It needs two repository secrets, set under Settings → Secrets and variables → Actions:

- `DOCKERHUB_USERNAME` — the Docker Hub account name
- `DOCKERHUB_TOKEN` — a Docker Hub **access token**, not the account password

The test job needs no database, because the tests are `@WebMvcTest` slices. A future test that touches persistence would need a PostgreSQL service container added to that job.

## Deployment (Terraform)

`terraform/` runs the API on **one EC2 instance** and the React app on **S3 + CloudFront**. There is no database on AWS.

**Site (`site.tf`):**
- **Bucket:** the S3 bucket `wants-site-<account id>` is fully private (public access block). CloudFront reads it through Origin Access Control, and the bucket policy names this distribution's ARN.
- **Behaviours:**
  - The default serves the bucket with `Managed-CachingOptimized`.
  - `/api/*` goes to the API origin with `Managed-CachingDisabled` and `Managed-AllViewerExceptHostHeader`. Host must be the origin's own name or the request misses the instance.
- **The API origin** must be a DNS name, so it is `aws_eip.app.public_dns`. It is plain HTTP to port 8080, so the edge-to-instance leg is unencrypted.
- **Edge functions** live in `terraform/functions/`, are attached at viewer-request, and run on `cloudfront-js-2.0`.
  - `strip-api-prefix.js` mirrors the Vite proxy's rewrite. Keep the two in step.
  - `spa-fallback.js` rewrites extension-less paths to `/index.html`.
  - SPA fallback is deliberately **not** done with custom error responses. Those apply to every behaviour and would turn the API's real 404s into HTML.
- `price_class = "PriceClass_100"` and the default `*.cloudfront.net` certificate. A custom domain would need an ACM certificate in us-east-1.

**API access:**
- **Firewall:** port 8080 admits only the managed prefix list `com.amazonaws.global.cloudfront.origin-facing`. A prefix list counts against the security group's rule quota by its maximum size (about 55 of the default 60), so there is little room for more rules on that group. The group's description still says "public app port"; it is stale on purpose, because changing a security group's description forces replacement.
- **Origin secret:** `random_password.origin_secret` is sent by CloudFront as `X-Origin-Secret`, and stored in SSM (`/wants/origin-secret`) for the instance.
  - `OriginSecretFilter` compares it in constant time and returns a bare 403 on mismatch.
  - It is switched on by a separate flag, `app.origin-check.enabled` (default `false`), not by the secret being non-empty. That makes it **fail closed**: enabled with a blank secret, the constructor throws and the app does not start. Never fold the switch back into "empty secret means off"; an empty `ORIGIN_SECRET` would then silently open the API.
  - `application-prod.properties` sets `app.origin-check.enabled=true` and `app.origin-secret=${ORIGIN_SECRET}`. `@Value` placeholders are strict, so a missing variable fails startup with "Could not resolve placeholder". That is unlike the datasource placeholders, which only fail later.
  - Running the prod profile from a laptop needs `app.origin-check.enabled=false` and `app.origin-secret=` in `config/application-prod.properties`, as in the template.
  - The secret is in `terraform.tfstate`, because the distribution's config holds it.

**GitHub deploy access (`github.tf`):**
- An OIDC provider for `token.actions.githubusercontent.com` (one per account) and two roles with the same trust (the master branch only):
  - **`wants-github-deploy-site`**: `s3:ListBucket` and `Put`/`DeleteObject` on the site bucket, plus `cloudfront:CreateInvalidation` on the distribution.
  - **`wants-github-deploy-api`**:
    - `ssm:SendCommand` on the `AWS-RunShellScript` document, and on instances whose tag is `ssm:resourceTag/Name = wants-api`. Matching on the tag rather than an instance ID survives instance replacement.
    - `ec2:DescribeInstances` and `ssm:GetCommandInvocation` on `*`. Both are read-only and support no resource-level scoping.
- The API instance role has the managed policy `AmazonSSMManagedInstanceCore` attached, so the preinstalled SSM agent can register and receive commands over outbound HTTPS.
- After that policy is first attached to a running instance, the agent may take a while to re-register. `aws ssm describe-instance-information` shows when it has, and `sudo systemctl restart amazon-ssm-agent` speeds it up.

- **Password:** the database password is an SSM `SecureString` (`aws_ssm_parameter.db_password`, default name `/wants/db-password`) using the AWS-managed `aws/ssm` key.
  - It is set through **`value_wo`, a write-only argument, fed from an `ephemeral = true` variable**, so it never enters the plan or `terraform.tfstate`. Both need Terraform 1.11 or later, hence `required_version = ">= 1.11"`.
  - Supply it as `TF_VAR_db_password` in the shell, never in `terraform.tfvars`. `terraform.tfvars` outranks the environment variable, so a leftover line there would silently override it.
  - Write-only values can't be diffed, so Terraform only re-sends the password when `db_password_version` changes. Raise it whenever the password changes.
  - The instance profile's only permission is `ssm:GetParameter` on the two parameter ARNs, the DB password and the origin secret. No `kms:Decrypt` is needed, because the managed key's policy allows decryption through SSM for principals in the account.
  - The boot script fetches the password with the preinstalled AWS CLI, under IMDSv2 (`http_tokens = "required"`).
- **The boot script must never `set -x`:** it would print the password and the origin secret into `/var/log/cloud-init-output.log`.
- **Certificate:** `ca.pem` is read from `terraform/` (git-ignored, `terraform/*.pem`), passed through `trimspace(file(...))` in the user data, written to `/opt/wants/ca.pem`, and mounted at `/certs/ca.pem`. A validation on `db_url` requires `sslrootcert=/certs/ca.pem` and rejects a laptop path by mistake.
- **`.env` on the instance** quotes every value in single quotes, so Compose doesn't interpret the `&` in the JDBC URL.
- **Clerk settings:** `var.clerk_issuer` (default: the project's development instance; validated: `https://`, no trailing slash) and `CLERK_AUTHORIZED_PARTIES = "https://${aws_cloudfront_distribution.site.domain_name}"` go into `.env` through the user data. The distribution depends only on `aws_eip.app`, never the instance, so this adds no cycle. Changing either replaces the instance.
- **Rollout order for anything the app newly requires from `.env`:** `terraform apply` first, then merge. `deploy-api` only swaps the image; it never rewrites `.env`. An image that needs a variable the instance lacks fails to start, and the health check fails the job.
- **Fixed address:** an Elastic IP is associated separately (`aws_eip_association`), so it survives instance replacement and can be added to Aiven's IP allowlist. `associate_public_ip_address` stays true, so the instance has outbound access before the association happens.
- **What replaces the instance:** any change to the user data (image tag, certificate, URL, user), because `user_data_replace_on_change` is set. A password change only updates SSM, so the app needs a restart to pick it up. The container keeps the password it got at first boot in `/opt/wants/.env`, so restarting means rerunning the fetch-and-write steps, or replacing the instance.
- **AWS account guardrail:** the account has a budget action (`Monthly-50-Max-Budget`) that attaches the deny policy `MyActualBudgetKillSwitchPayload` (`ec2:RunInstances`, `ec2:StartInstances`, `ecs:*`) to the `admin-worker` user at 100% of $50. If `apply` fails with `UnauthorizedOperation ... explicit deny ... MyActualBudgetKillSwitchPayload`, check the budget action's status before anything else. Its automatic reset has failed before (`RESET_FAILURE`), leaving the block on with no spend.

## Copilot app modernization hooks

`.github/modernize/java-upgrade/` belongs to the GitHub Copilot app modernization (Java upgrade) extension, not to the application. Its scripts read a tool-call JSON payload on stdin and append `run_in_terminal` and `appmod-*` calls to a per-session JSONL file under that directory, for the extension to consume. The directory ignores its own contents via a nested `.gitignore` containing `**/*`, so those session logs are intentionally untracked. Leave this tree alone unless the task is explicitly about that extension.


## Further instructions
Update the README.md as we make more features and commits