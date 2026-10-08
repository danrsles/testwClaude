# Wants

A small full-stack app for tracking things you want to do, eat, play or visit. Each want has a message, one of four categories, and an owner.

- **Backend** — Spring Boot 3.5.6 on Java 21, PostgreSQL 18 (hosted on Aiven, or PostGIS in Docker locally), Flyway migrations
- **Frontend** — React + TypeScript, Vite, React Router, Tailwind CSS v4
- **Infrastructure** — Docker image published to Docker Hub by GitHub Actions, Terraform for a single-instance AWS deployment

## Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 21 | No Maven wrapper is checked in |
| Maven | 3.9.x | Must be on `PATH` |
| Docker | with Compose v2 | Must be running; the backend starts its local PostgreSQL itself |
| Node | 20+ | For the frontend only |

## Getting started

### 1. Start Docker

The backend starts its own local database, so Docker must be running. There are no config files to create for local development.

### 2. Run the backend

```bash
mvn spring-boot:run     # http://localhost:8080
```

This uses the default `local` profile. Spring Boot runs `docker compose up` on `compose.yaml`, which starts PostGIS on PostgreSQL 18 (host port **5433**), and connects to it. Flyway creates the schema on first start and seeds a user, `dani`, with two wants. The container keeps running after the app stops. Stop it with `docker compose stop`, or wipe it with `docker compose down -v`.

### 3. Optional: run against the hosted database

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

`config/application-prod.properties` is git-ignored. `verify-full` encrypts the connection and checks the server's certificate and host name. Use forward slashes in the path, even on Windows. **This is the real database**, so anything you do appears there too.

### 4. Run the frontend

```bash
cd frontend
npm install
npm run dev             # http://localhost:5173
```

The dev server proxies `/api/*` to port 8080, so the browser stays on one origin and the backend needs no CORS configuration.

The app has three screens, linked from the nav at the top: the wants list at `/`, an about page at `/info`, and the user's profile at `/profile`. The header on every screen also shows the user's nickname and picture, linking to the profile. Until S3 uploads exist, a profile without an avatar URL shows a local placeholder picture.

## API

All bodies are JSON.

### Wants

Base path `/wants`.

| Method | Path | Body | Success | Errors |
| --- | --- | --- | --- | --- |
| `GET` | `/wants` | — | `200` list of wants | `400` unknown `?category=` |
| `GET` | `/wants?category=FOOD` | — | `200` filtered list | `400` unknown or lowercase value |
| `POST` | `/wants` | `{message, category, userId}` | `201` created want | `400` invalid body, `404` unknown user |
| `PUT` | `/wants/{id}` | `{message, category, userId}` | `200` updated want | `400` invalid body, `404` unknown want or user |
| `DELETE` | `/wants/{id}` | — | `204` no content | `404` unknown want |

Categories are `FOOD`, `MOVIE`, `GAME` and `TRIP`, matched by exact name — `food` is a 400. `PUT` is a full replacement, so every field is required and the owner can be reassigned.

```bash
curl localhost:8080/wants

curl -X POST -H 'Content-Type: application/json' \
  -d '{"message":"Finish Elden Ring","category":"GAME","userId":1}' \
  localhost:8080/wants
```

### User profiles

Base path `/user/profiles`. A profile holds a user's `email`, `nickname` and an optional `s3Url` for their avatar. Each user has at most one profile, and an email can belong to only one profile.

| Method | Path | Body | Success | Errors |
| --- | --- | --- | --- | --- |
| `GET` | `/user/profiles` | — | `200` list of profiles | — |
| `GET` | `/user/profiles?userId=1` | — | `200` list with that user's profile, or empty | — |
| `GET` | `/user/profiles/{id}` | — | `200` profile | `404` unknown profile |
| `POST` | `/user/profiles` | `{userId, email, nickname, s3Url?}` | `201` created profile | `400` invalid body, `404` unknown user, `409` user already has a profile or email taken |
| `PUT` | `/user/profiles/{id}` | `{email, nickname, s3Url?}` | `200` updated profile | `400` invalid body, `404` unknown profile, `409` email taken |
| `DELETE` | `/user/profiles/{id}` | — | `204` no content | `404` unknown profile |

A blank or missing `s3Url` is stored as `null`. `PUT` takes no `userId`, because a profile cannot change owner. Profiles live in their own table rather than on `users`, so email does not appear inside the user object that every want embeds.

```bash
curl localhost:8080/user/profiles?userId=1

curl -X PUT -H 'Content-Type: application/json'   -d '{"email":"dani@example.com","nickname":"Dani","s3Url":null}'   localhost:8080/user/profiles/1
```

## Layout

```
src/main/java/com/example/demo/
  models/        JPA entities and enums (Want, User, UserProfile, Category)
  repositories/  Spring Data interfaces
  services/      business logic and the exceptions that map to HTTP status
  controllers/   HTTP endpoints and request records
src/main/resources/db/migration/   Flyway migrations
frontend/src/
  api/           transport, one function per endpoint, no state
  hooks/         server state (useWants, useUserProfile)
  types/         shapes mirroring the API
  components/    the screens (Wants.tsx at /, Info.tsx at /info, UserProfile.tsx at /profile),
                 plus ProfileBadge.tsx in the header and the shared Avatar.tsx
  currentUser.ts DEFAULT_USER_ID, the user the app acts as until there is a login
  App.tsx        the shell: nav and routes (BrowserRouter is in main.tsx)
terraform/       single-instance AWS deployment
```

Controllers depend on services, never on repositories directly, and dependencies are injected through constructors. The frontend layers the same way: components call hooks, hooks call `api/`, and nothing else does.

## Tests

```bash
mvn test                                        # all tests
mvn test -Dtest=WantControllerTest              # one class
mvn test -Dtest=WantControllerTest#createsWant  # one method
```

Tests are `@WebMvcTest` slices with a mocked service layer, so **they need no database** and pass on a machine with nothing running.

Frontend checks:

```bash
cd frontend
npm test            # Vitest + React Testing Library, in jsdom
npx tsc --noEmit    # type check
npm run lint        # oxlint
npm run build       # production bundle into frontend/dist
```

Component tests stub the `api/` module rather than `fetch`, so like the backend tests **they need no server and no database**. They live beside the component as `<Name>.test.tsx`.

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

`V2__seed_sample_data.sql` inserts the sample user and wants, and `V4__seed_dani_profile.sql` gives that user a profile. Both run in every environment Flyway touches, so delete them before using this schema for anything real. `V3__create_user_profiles.sql` creates the profiles table and stays.

## Container image

```bash
docker build -t danrsles/wants-api:dev .
```

The image contains no credentials. `.dockerignore` keeps `config/` and `.env` out of the build context. The image sets `SPRING_PROFILES_ACTIVE=prod`, so a container always uses the hosted-database settings, which arrive as environment variables:

```bash
docker run -p 8080:8080 \
  -e DB_URL='jdbc:postgresql://<host>:<port>/defaultdb?sslmode=verify-full&sslrootcert=/certs/ca.pem' \
  -e DB_USER=<user> -e DB_PASSWORD=<password> \
  -v /path/to/ca.pem:/certs/ca.pem:ro \
  danrsles/wants-api:dev
```

The CA certificate is mounted at runtime rather than built into the image, so the same image works against any PostgreSQL database.

If `DB_URL` is missing, startup fails with `'url' must start with "jdbc"`. That means the variable isn't set, not that the URL is malformed.

## Continuous integration

`.github/workflows/docker-publish.yml` runs on every push to `master`, and can be triggered manually from the Actions tab. It runs the tests, then builds and pushes the image to Docker Hub tagged `latest` and `sha-<commit>`, so every published image traces back to its commit.

It requires two repository secrets: `DOCKERHUB_USERNAME`, and `DOCKERHUB_TOKEN` — a Docker Hub access token, not an account password.

## Deploying to AWS

`terraform/` provisions one EC2 instance for the API. It installs Docker on boot and runs the published image, which connects to the hosted Aiven PostgreSQL database. There is no database on the instance.

```
Browser / curl ──► EC2 (Elastic IP, :8080)  wants-api container, prod profile
                     │  at boot: reads the DB password from SSM Parameter Store
                     └──TLS (verify-full)──► Aiven PostgreSQL
```

**Before the first apply**

1. Download Aiven's CA certificate into `terraform/ca.pem`. It is git-ignored, and `db_ca_cert_path` can point elsewhere.
2. Fill in `terraform.tfvars` from the template. `db_url` must end in `sslrootcert=/certs/ca.pem`, which is the path *inside the container*, not on your machine. Every value needs double quotes.
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
terraform output public_ip
terraform output api_url
```

**After the first apply,** add the `public_ip` output to the Aiven service's **Allowed IP addresses**, if you restrict them. It's an Elastic IP, so it stays the same when the instance is replaced. Until it's allowed, the app can't reach the database: the container restarts on its own and connects once the address is added.

Allow two to three minutes after `apply` before the API answers. `terraform apply` returns as soon as the instance launches, while cloud-init is still installing Docker and pulling the image.

What the instance gets at boot (`user_data.sh.tftpl`):
- `/opt/wants/ca.pem`, the certificate, sent in the user data. It isn't secret.
- `/opt/wants/.env`, holding `DB_URL` and `DB_USER`, plus `DB_PASSWORD` fetched from SSM.
- `/opt/wants/compose.yaml`, which runs the image with `ca.pem` mounted read-only at `/certs/ca.pem`.

**The password** goes to SSM Parameter Store as a `SecureString` (`/wants/db-password`), encrypted with the free AWS-managed key. It is a *write-only* value (`value_wo`) from an *ephemeral* variable, so Terraform sends it to AWS but keeps no copy: it is in neither the plan nor `terraform.tfstate`. The instance's IAM role may read that one parameter and nothing else, so the password never appears in the instance's user data either. Because Terraform stores nothing to compare against, it can't detect a password change on its own. After changing the password, set the new one in `TF_VAR_db_password`, raise `db_password_version` in `terraform.tfvars`, apply, and restart the app.

Debugging on the instance:

```bash
ssh -i <key>.pem ec2-user@<public-ip>
sudo cat /var/log/cloud-init-output.log            # the boot script's output
cd /opt/wants && sudo docker compose logs -f app   # the app's logs
```

`terraform destroy` removes the instance, Elastic IP, IAM role and SSM parameter. The data lives in Aiven, so destroying or replacing the instance loses nothing. Because `user_data_replace_on_change` is set, changing the image, the certificate or any database setting replaces the instance. Changing only the password updates SSM; restart the app for it to take effect.

## Known gaps

These are deliberate omissions, not oversights:

- **No authentication.** Anyone who can reach the API can read and write every want. The Terraform defaults therefore restrict the API port by CIDR.
- **No user accounts.** Profiles can be managed through `/user/profiles`, but users themselves can't be created through the API. `dani` exists only because a migration seeds them, and the frontend acts as `userId: 1` (`DEFAULT_USER_ID` in `frontend/src/currentUser.ts`).
- **No avatar uploads.** `s3Url` is a plain URL field until S3 is set up.
- **The header badge does not refresh on edit.** The badge and the profile page each fetch the profile, so a nickname changed on `/profile` reaches the header on the next page load. A shared server cache such as TanStack Query would fix this.
- **No DTO layer.** Entities serialize straight to JSON, so `GET /wants` embeds the full user object. A field that should not be exposed would need a DTO.
- **State in Terraform is local.** `terraform.tfstate` is git-ignored and holds the infrastructure's details in cleartext. The database password is write-only and not stored in it. A team would move it to an encrypted S3 backend with locking.

## Files that must never be committed

Each is git-ignored. `config/application-prod.properties` contains the live database password.

| File | Contains |
| --- | --- |
| `config/application-prod.properties` | hosted database credentials, for running the `prod` profile locally |
| `terraform/terraform.tfvars` | the database URL and user, your IP |
| `terraform/terraform.tfstate` | the deployed infrastructure's details, in cleartext |
| `terraform/tfplan` | a zip archive embedding every variable value |
