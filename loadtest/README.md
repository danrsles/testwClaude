# Load test

Fires many requests at a locally running API and checks that the answers stay right, and how fast they come, when hundreds of people use the app at once. The results are in the main README under **Performance**.

It runs against the **local** database only, never Aiven: it writes thousands of rows, and Aiven's free plan has about 17 connections.

## What you need

- Docker running, with the local database (`demo-postgres`, which `mvn spring-boot:run` starts)
- Node, for the token script
- [k6](https://grafana.com/docs/k6/latest/), which plays the users: `winget install GrafanaLabs.k6`

## Running it

```bash
node loadtest/make-tokens.mjs                      # a key pair (first time) and 1,000 tokens, valid 2 h
docker exec -i demo-postgres psql -U wants -d wants -v ON_ERROR_STOP=1 --single-transaction < loadtest/seed.sql
mvn spring-boot:test-run                           # the API on http://localhost:8081, trusting the test key
k6 run loadtest/race.js
k6 run loadtest/feed.js
```

The seed takes about 15 seconds and can be run again at any time; it only replaces the load users' follows, circles and wants. It leaves everyone else's rows, and a normal API on 8080, alone. Run `make-tokens.mjs` again when the tokens expire (401s everywhere). The API's pool has 7 connections, as in production; to try another size:

```bash
mvn spring-boot:test-run -Dspring-boot.run.arguments=--spring.datasource.hikari.maximum-pool-size=10
```

## How signing in works

Every route needs a session token, and real ones come from Clerk, last a minute and can only be made in bulk with Clerk's secret key, which this project never uses. So the load test signs its own:

- `make-tokens.mjs` makes an RSA key pair in `loadtest/keys/` and signs one token per user (`sub` `load_user_<n>`, `username` `load_<n>`, issuer `http://loadtest.invalid`) into `loadtest/tokens.json`. Both are git-ignored.
- `LoadTestApplication` (in `src/test/java`) starts the normal app plus `LoadTestSecurityConfiguration`, whose token decoder trusts that public key instead of Clerk's. The signature, expiry and issuer are still checked, and everything after that runs as it does for real.
- Because it is in `src/test`, it is never in the jar or the Docker image, and it refuses to start with the `prod` profile. A test token sent to the normal API gets 401.

## The data

`seed.sql` makes 1,000 users. Each follows `load_1` and 50 others, and owns 10 wants: a third for one seat, a third for four, a third open, one in a circle (100 circles of 10) and one closed. Owners hold their seats, and wants with room have two more people in them. So each person's feed is about 500 wants. `LoadTestSeedIT` runs it on a freshly migrated database in `mvn verify`, so a migration that breaks it fails the build.

`seed-big.sql` adds a million wants around them: 99,000 `bulk_user_<n>` users nobody follows, with 10 wants each and their owners' seats. The feeds stay the same; only the tables grow, which shows whether a request's cost follows the feed or the table. Run it after `seed.sql`; it takes about 30 seconds, once:

```bash
docker exec -i demo-postgres psql -U wants -d wants -v ON_ERROR_STOP=1 --single-transaction < loadtest/seed-big.sql
```

It is kept, and running it again adds nothing. Deleting it by hand is slow, because each deleted want cascades to `notifications.want_id`, which has no full index. `docker compose down -v` starts the database over instead.

## The scenarios

| Script | What happens | Passes when |
|---|---|---|
| `race.js` | `load_1` posts a want with 6 seats; 200 others sign up at once | exactly 5 get in, 195 get 409, and the want shows 6 seats taken |
| `feed.js` | 10 to 300 users each load `GET /wants` about once a second, over 3 minutes | fewer than 1% of requests fail; the latencies are the result |

To measure one level at a time, as the README's tables do, give k6 stages on the command line, which replace the script's:

```bash
k6 run --stage 5s:100,30s:100 loadtest/feed.js
```

Keep the short ramp. `--vus 300` starts every user in the same instant, and some connections are refused before the server can accept them (`dial: connection refused`), which is the test's doing, not the API's.

k6 prints a summary at the end: requests per second, and how long requests took. p95 is the time 95% of requests beat, which says more than the average, since the slowest requests are the ones people notice.

Numbers from a laptop are not production's: EC2 and Aiven add network time between the API and the database. They show how the design behaves under load, and where it gives way first.
