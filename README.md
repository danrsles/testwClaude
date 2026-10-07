# Wants

A small full-stack app for tracking things you want to do, eat, play or visit. Each want has a message, one of four categories, and an owner.

- **Backend** — Spring Boot 3.5.6 on Java 21, MySQL 8.4, Flyway migrations
- **Frontend** — React + TypeScript, Vite, Tailwind CSS v4
- **Infrastructure** — Docker image published to Docker Hub by GitHub Actions, Terraform for a single-instance AWS deployment

## Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 21 | No Maven wrapper is checked in |
| Maven | 3.9.x | Must be on `PATH` |
| Docker | with Compose v2 | Runs MySQL locally |
| Node | 20+ | For the frontend only |

## Getting started

### 1. Create the two config files

Neither is in git, and both are required. Copy the templates beside them and replace every `CHANGEME`:

```bash
cp .env.example .env
cp application-local.properties.example application-local.properties
```

- **`.env`** is read by Docker Compose only. It defines the database name, user and passwords the MySQL container is created with.
- **`application-local.properties`** is read by Spring only. Its credentials must match `.env`, because those are what the container was built with.

The two files are read by different tools and neither sees the other — Spring does not understand the `.env` format, and Compose does not read `.properties`. A value changed in one must be changed in the other by hand.

### 2. Start MySQL

```bash
docker compose up -d
```

The container is published on host port **3307**, not 3306, to avoid colliding with a locally installed MySQL. Inside Docker networks it is still 3306.

### 3. Run the backend

```bash
mvn spring-boot:run     # http://localhost:8080
```

Flyway creates the schema on first start and seeds a user, `dani`, with two wants.

### 4. Run the frontend

```bash
cd frontend
npm install
npm run dev             # http://localhost:5173
```

The dev server proxies `/api/*` to port 8080, so the browser stays on one origin and the backend needs no CORS configuration.

## API

Base path `/wants`. All bodies are JSON.

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

## Layout

```
src/main/java/com/example/demo/
  models/        JPA entities and enums (Want, User, Category)
  repositories/  Spring Data interfaces
  services/      business logic and the exceptions that map to HTTP status
  controllers/   HTTP endpoints and request records
src/main/resources/db/migration/   Flyway migrations
frontend/src/
  api/           transport, one function per endpoint, no state
  hooks/         server state (useWants owns the list and its mutations)
  types/         shapes mirroring the API
  components/    the screens (Wants.tsx)
  App.tsx        the shell, where a router will go
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
npx tsc --noEmit    # type check
npm run lint        # oxlint
npm run build       # production bundle into frontend/dist
```

## Database schema

Flyway owns the schema; Hibernate runs with `ddl-auto=validate` and never alters it.

- Migrations are `src/main/resources/db/migration/V<n>__<description>.sql` and run at startup.
- **Adding a field to an entity requires a new migration file.** Without one, startup fails validation.
- **Never edit an applied migration.** Flyway checksums each file and refuses to start if one changes. Add `V3__...sql` instead.

To replay migrations from scratch, which destroys all data:

```bash
docker compose down -v && docker compose up -d
```

`V2__seed_sample_data.sql` inserts the sample user and wants. It runs in every environment Flyway touches, so delete it before using this schema for anything real.

## Container image

```bash
docker build -t danrsles/wants-api:dev .
```

The image contains no configuration. `.dockerignore` keeps `.env` and `application-local.properties` out of the build context, so credentials must arrive as environment variables:

```bash
docker run -p 8080:8080 \
  -e MYSQL_HOST=<host> -e MYSQL_PORT=3306 -e MYSQL_DATABASE=demo \
  -e MYSQL_USER=<user> -e MYSQL_PASSWORD=<password> \
  danrsles/wants-api:dev
```

`MYSQL_PORT` is **3306** inside a Docker network; the 3307 mapping exists only on a developer's host.

## Continuous integration

`.github/workflows/docker-publish.yml` runs on every push to `master`, and can be triggered manually from the Actions tab. It runs the tests, then builds and pushes the image to Docker Hub tagged `latest` and `sha-<commit>`, so every published image traces back to its commit.

It requires two repository secrets: `DOCKERHUB_USERNAME`, and `DOCKERHUB_TOKEN` — a Docker Hub access token, not an account password.

## Deploying to AWS

`terraform/` provisions one EC2 instance that installs Docker on boot and runs the published image alongside a MySQL container.

```bash
cd terraform
cp terraform.tfvars.example terraform.tfvars   # then fill it in
terraform init
terraform plan
terraform apply
terraform output api_url
```

Allow two to three minutes after `apply` before the API answers — `terraform apply` returns as soon as the instance launches, while cloud-init is still installing Docker and pulling images.

MySQL binds to the instance's loopback and has no security group rule, so reaching it from a GUI client means tunnelling over SSH:

```bash
ssh -i <key>.pem -L 3308:127.0.0.1:3306 ec2-user@<public-ip>
```

Then connect a client to `127.0.0.1:3308`.

`terraform destroy` removes everything it created. The database lives on the instance's disk, so **destroying the instance destroys the data** — and because `user_data_replace_on_change` is set, editing the bootstrap script also replaces the instance.

## Known gaps

These are deliberate omissions, not oversights:

- **No authentication.** Anyone who can reach the API can read and write every want. The Terraform defaults therefore restrict the API port by CIDR.
- **No user API.** `dani` exists only because a migration seeds them, and the frontend attributes every new want to `userId: 1`.
- **No DTO layer.** Entities serialize straight to JSON, so `GET /wants` embeds the full user object. A field that should not be exposed would need a DTO.
- **State in Terraform is local.** `terraform.tfstate` holds credentials in cleartext and is git-ignored. A team would move it to an encrypted S3 backend with locking.

## Files that must never be committed

Each is git-ignored; the first three contain live credentials.

| File | Contains |
| --- | --- |
| `.env` | MySQL container credentials |
| `application-local.properties` | datasource credentials |
| `terraform/terraform.tfvars` | database passwords, your IP |
| `terraform/terraform.tfstate` | everything above, in cleartext |
| `terraform/tfplan` | a zip archive embedding every variable value |
