# Wants

A small full-stack app for tracking things you want to do, eat, play or visit. Each want has a message, one of four categories, and an owner.

- **Backend** — Spring Boot 3.5.6 on Java 21, PostgreSQL 18 (hosted on Aiven, or PostGIS in Docker locally), Flyway migrations
- **Frontend** — React + TypeScript, Vite, React Router, Tailwind CSS v4
- **Login** — [Clerk](https://clerk.com): it hosts sign-up and sign-in, and the API verifies Clerk's session token on every request
- **Infrastructure** — the React app on S3 behind CloudFront (HTTPS), the API on one EC2 instance reachable only through CloudFront, all in Terraform; GitHub Actions tests both halves, publishes the API image and deploys the site

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
- **Wants** show each owner's username and Clerk profile picture: their photo, or Clerk's silhouette if they haven't set one.
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
- **The owner always comes from the token.** Request bodies no longer carry a `userId`; one sent anyway is ignored. You see your own wants and those of people you follow, but only a want's owner can change or delete it (`403` otherwise). Your own record is reachable only as `/me`, and there is no route to anyone else's, so nobody can read another user's email.
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
| `GET` | `/wants` | — | `200` your wants and those of people you follow, each `{id, message, category, userId, username, imageUrl}` | `400` unknown `?category=` |
| `GET` | `/wants?category=FOOD` | — | `200` filtered list | `400` unknown or lowercase value |
| `POST` | `/wants` | `{message, category}` | `201` created want, owned by you | `400` invalid body |
| `PUT` | `/wants/{id}` | `{message, category}` | `200` updated want | `400` invalid body, `403` not yours, `404` unknown want |
| `DELETE` | `/wants/{id}` | — | `204` no content | `403` not yours, `404` unknown want |

Categories are `FOOD`, `MOVIE`, `GAME` and `TRIP`, matched by exact name — `food` is a 400. `PUT` is a full replacement of the message and category; the owner never changes.

```bash
TOKEN=...   # see above
curl -H "Authorization: Bearer $TOKEN" localhost:8080/wants

curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"Finish Elden Ring","category":"GAME"}' \
  localhost:8080/wants
```

### Follows

Your wants list shows your own wants and those of everyone you follow. In the app, the **People** screen (`/people`) lists who you follow, and searching it by username finds anyone else, each with a Follow or Unfollow button. Underneath it are these routes. Base path `/follows`; the follower is always you, from the token.

| Method | Path | Body | Success | Errors |
| --- | --- | --- | --- | --- |
| `GET` | `/follows` | — | `200` who you follow, each `{id, username, imageUrl}` | — |
| `PUT` | `/follows/{userId}` | — | `204`; following again changes nothing | `400` yourself, `404` unknown user |
| `DELETE` | `/follows/{userId}` | — | `204`, whether or not you were following | — |
| `GET` | `/users?q=al` | — | `200` up to 20 users whose username contains `q`, ignoring case, never you, each `{id, username, imageUrl, following}`; an empty `q` finds no one | — |

`V10__seed_test_users.sql` adds three test users, `alex`, `sam` and `jordan`, with two wants each. To try it, search for one on the People screen and press Follow. Or, through the API, look up their ids, then follow one:

```bash
docker exec demo-postgres psql -U wants -c "select id, username from users"

TOKEN=...   # see above
curl -i -X PUT    -H "Authorization: Bearer $TOKEN" localhost:8080/follows/4
curl    -H "Authorization: Bearer $TOKEN" localhost:8080/follows
curl -i -X DELETE -H "Authorization: Bearer $TOKEN" localhost:8080/follows/4
```

Reload the app after each change to see the list follow along.

## Layout

```
src/main/java/com/example/demo/
  models/        JPA entities and enums (Want, User, Follow, Category)
  repositories/  Spring Data interfaces
  services/      business logic and the exceptions that map to HTTP status
  controllers/   HTTP endpoints
  dto/           request bodies and response shapes (WantRequest, WantResponse, UserSummary)
  security/      SecurityConfig (who may call what), ClerkJwtConfig (how a token is checked),
                 AuthorizedPartyValidator (the azp check), OriginSecretFilter (CloudFront only)
src/main/resources/db/migration/   Flyway migrations
frontend/src/
  api/           transport, one function per endpoint, no state; http.ts attaches the Clerk token
  hooks/         server state: useWants, and MeProvider/useMe, which load you once and share you
  types/         shapes mirroring the API
  components/    the screens (Wants.tsx at /, Info.tsx at /info),
                 SignInScreen.tsx for signed-out visitors, and Avatar.tsx, the round
                 picture beside each want
  App.tsx        the shell: the sign-in gate, nav and routes (ClerkProvider and
                 BrowserRouter are in main.tsx)
terraform/       single-instance AWS deployment
```

Controllers depend on services, never on repositories directly, and dependencies are injected through constructors. The frontend layers the same way: components call hooks, hooks call `api/`, and nothing else does.

## Tests

```bash
mvn test                                        # all tests
mvn test -Dtest=WantControllerTest              # one class
mvn test -Dtest=WantControllerTest#createsWant  # one method
```

Tests are `@WebMvcTest` slices with a mocked service layer, so **they need no database** and pass on a machine with nothing running. They `@Import(SecurityConfig.class)` so requests go through the real security rules, and they sign in with Spring Security's `jwt()` request post-processor. The `JwtDecoder` is a mock, so **they never contact Clerk**. `WantServiceTest` covers the ownership rule and the feed, and `FollowServiceTest` who may follow whom, directly, since the controller tests mock the service away.

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

## Database schema

Flyway owns the schema; Hibernate runs with `ddl-auto=validate` and never alters it.

- Migrations are `src/main/resources/db/migration/V<n>__<description>.sql` and run at startup.
- **Adding a field to an entity requires a new migration file.** Without one, startup fails validation.
- **Never edit an applied migration.** Flyway checksums each file and refuses to start if one changes. Add `V3__...sql` instead.

To replay migrations from scratch, which destroys all data:

```bash
docker compose down -v && docker compose up -d
```

`V2__seed_sample_data.sql` inserts the sample user and wants, and `V4__seed_dani_profile.sql` gives that user a profile. Both run in every environment Flyway touches, so delete them before using this schema for anything real. `V3__create_user_profiles.sql` creates the profiles table and stays. `V5__add_users_clerk_id.sql` adds `users.clerk_id`, which links a user to their Clerk account. It is nullable only because `dani` predates logins. `V6__add_users_email_last_sign_in.sql` adds `users.email` and `users.last_sign_in_at`, both copied from the Clerk token. `V7__merge_user_profiles_into_users.sql` moves each profile's nickname and avatar onto its user, then drops `user_profiles`. The profile's hand-typed email is kept only where Clerk hasn't supplied one, as for `dani`. `V8__clerk_picture_drop_nickname.sql` drops `nickname`, renames `s3_url` to `image_url` and empties it, once the profile page went and the picture became Clerk's. `V9__create_follows.sql` creates `follows`, one row per follower and followee, unique per pair, never yourself. `V10__seed_test_users.sql` adds the test users `alex`, `sam` and `jordan` with two wants each; like V2, delete it before real use. V3 and V4 stay even though their table is gone, because applied migrations must never change.

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
| Test API | `mvn test` |
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
- **Other users go out as response types only.** `GET /wants` returns `WantResponse`, which names the owner only by `userId`, `username` and `imageUrl`, `GET /follows` returns `UserSummary`, with the same three fields, and `GET /users?q=` returns `PersonResult`, which adds only whether you follow them. `/me` still returns the `User` entity, which is fine only because it is always your own. Any future endpoint listing other users should return `UserSummary` or its own response type.
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
