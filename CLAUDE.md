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

There is no linter or formatter configured.

Container image:

```bash
docker build -t danrsles/wants-api:0.0.1 .
docker run -p 8080:8080 --network tryclaude_default   -e MYSQL_HOST=mysql -e MYSQL_PORT=3306 -e MYSQL_DATABASE=demo   -e MYSQL_USER=... -e MYSQL_PASSWORD=... danrsles/wants-api:0.0.1
```

The image carries no configuration: `.dockerignore` keeps `.env` and `application-local.properties` out of the build context, so every credential must arrive as an environment variable. `MYSQL_PORT` is **3306** inside the Docker network — the 3307 mapping only exists on the host. The build skips tests (`-DskipTests`); run `mvn test` separately in CI.

## Architecture

A single-module Spring Boot 3.5.6 web application on Java 21. `DemoApplication` sits in the root package `com.example.demo` so component scanning reaches the layers beneath it:

- `models` — JPA entities and enums (`Want`, `User`, `Category`)
- `repositories` — Spring Data interfaces (`WantRepository`, `UserRepository`)
- `services` — business logic, the only layer controllers talk to (`WantService`)
- `controllers` — HTTP endpoints (`WantController`)

Controllers depend on services, never on repositories directly, and all dependencies are injected through constructors rather than fields, which keeps them explicit and testable without a container.

Endpoints:

- `GET /wants` — every want as JSON, narrowed by an optional `?category=` parameter. Spring converts that parameter to the `Category` enum by exact name, so `FOOD` works while `food` or an unknown value yields 400 before the controller runs.
- `POST /wants` — creates one, taking `{"message", "category", "userId"}` and returning 201 with the saved want. `CreateWantRequest` carries the Bean Validation constraints, so a blank message, missing field or unknown category is a 400 before the service is reached; an unknown `userId` is a 404 via `UserNotFoundException`, which carries `@ResponseStatus` rather than needing an exception handler.

Each `Want` belongs to a `User` through a non-null `@ManyToOne` (`user_id`, a real foreign key). The association is deliberately unidirectional: a `User` has no `List<Want>`, which avoids the infinite recursion a bidirectional pair would cause during JSON serialization. Query wants by user through the repository rather than navigating from the user.

`@ManyToOne` defaults to eager fetching, and that matters here because `spring.jpa.open-in-view=false` closes the persistence context before serialization — a lazy association would throw `LazyInitializationException` while Jackson writes the response. If this is ever made lazy for performance, the controller's query must fetch-join the user.

Entities are serialized directly to JSON; there is no DTO layer, so `GET /wants` embeds the full user object in every want. If either entity gains a field that should not be exposed — a password hash on `User`, say — introduce a DTO rather than annotating around the problem.

Tests use `@WebMvcTest` with MockMvc, which loads only the web slice — no datasource, so `mvn test` passes without MySQL running. Collaborators are replaced with `@MockitoBean` (Spring Boot 3.4+ replaced `@MockBean`). New controller tests should follow that pattern and name the controller under test in the annotation.

## Schema migrations

Flyway owns the schema. Migrations live in `src/main/resources/db/migration` as `V<n>__<description>.sql` and run automatically at startup, before Hibernate initializes.

`spring.jpa.hibernate.ddl-auto=validate` — Hibernate never alters the database; it only checks that the tables match the entities and fails startup if they have drifted. **Adding a field to an entity therefore requires a new migration file**, not just the Java change. Never edit an applied migration: Flyway checksums each one and refuses to start if a checksum changes. Write `V3__...sql` instead.

`V2__seed_sample_data.sql` inserts the user `dani` with two wants, so a fresh database is usable while there is no user API. It runs in every environment Flyway touches, so delete it before this schema is used for anything real.

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
