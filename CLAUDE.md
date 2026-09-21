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

## Architecture

A single-module Spring Boot 3.5.6 web application on Java 21, in package `com.example.demo`. It is a scaffold: `DemoApplication` is the stock `@SpringBootApplication` entry point and `HelloController` exposes one endpoint, `GET /hello`, returning a plain string. There is no service, persistence, or configuration layer, and `application.properties` sets only the application name — so the app relies entirely on Spring Boot defaults, including port 8080.

Tests use `@WebMvcTest` with MockMvc, which loads only the web slice rather than the full context. New controller tests should follow that pattern and name the controller under test in the annotation.

## Database and configuration

MySQL runs in Docker via `compose.yaml` (`docker compose up -d`), mapped to host port **3307** rather than the usual 3306 to avoid clashing with a locally installed MySQL. The app connects through `spring-boot-starter-data-jpa` and `mysql-connector-j`.

Configuration layers, in increasing precedence:

1. `src/main/resources/application.properties` — committed, and holds **no credential values at all**. The datasource properties are bare `${MYSQL_PORT}` / `${MYSQL_USER}` / `${MYSQL_PASSWORD}` placeholders with no fallbacks, which Spring resolves against its Environment (command-line args, JVM system properties, OS environment variables, imported config files).
2. `application-local.properties` in the project root — git-ignored and required for the app to start, loaded via `spring.config.import=optional:file:./application-local.properties`. Credentials belong here; copy `application-local.properties.example` to create it. Because imported config outranks the importing document, properties set here win.

A checkout without `application-local.properties` fails at startup with `Failed to parse the host:port pair 'localhost:${MYSQL_PORT}'` — that is the intended fail-fast, not a bug. Creating the local file from the example fixes it. `mvn test` is unaffected, because `@WebMvcTest` loads only the web slice and never opens a datasource.

Note that `.env` is read by Docker Compose only — Spring does not understand that format, so a port or password changed there does not reach the application. Changing the container's port means updating `application-local.properties` to match.

## Copilot app modernization hooks

`.github/modernize/java-upgrade/` belongs to the GitHub Copilot app modernization (Java upgrade) extension, not to the application. Its scripts read a tool-call JSON payload on stdin and append `run_in_terminal` and `appmod-*` calls to a per-session JSONL file under that directory, for the extension to consume. The directory ignores its own contents via a nested `.gitignore` containing `**/*`, so those session logs are intentionally untracked. Leave this tree alone unless the task is explicitly about that extension.
