-- Load-test data for the local database, never Aiven:
--   docker exec -i demo-postgres psql -U wants -d wants -v ON_ERROR_STOP=1 --single-transaction < loadtest/seed.sql
--
-- 1,000 users (clerk_id load_user_<n>, username load_<n>, as make-tokens.mjs
-- signs them), each following load_1 (the race host) and 50 others, 100
-- circles of 10, and 10 wants each, with their owners' seats and a few more.
-- It is not a Flyway migration, which would run everywhere. LoadTestSeedIT
-- runs it on a freshly migrated database, so a schema change that breaks it
-- fails the build.
--
-- It starts by clearing the previous run's follows, circles and wants, so
-- it can run again. The users themselves are kept: deleting them makes
-- PostgreSQL check every foreign key to users row by row, several of them on
-- columns with no index, which takes far longer than the rest of the script.
-- Nobody else's rows are touched.

DROP TABLE IF EXISTS load_users, load_circles, load_ids;

CREATE TEMP TABLE load_users AS
SELECT id FROM users WHERE clerk_id ~ '^load_user_[0-9]+$';

CREATE TEMP TABLE load_circles AS
SELECT id FROM circles WHERE owner_id IN (SELECT id FROM load_users);

DELETE FROM notifications
WHERE recipient_id IN (SELECT id FROM load_users)
   OR actor_id IN (SELECT id FROM load_users)
   OR circle_id IN (SELECT id FROM load_circles);

DELETE FROM want_signups WHERE user_id IN (SELECT id FROM load_users);

-- The remaining signups and notifications cascade from wants.
DELETE FROM wants
WHERE user_id IN (SELECT id FROM load_users)
   OR circle_id IN (SELECT id FROM load_circles);

DELETE FROM circle_members WHERE owner_id IN (SELECT id FROM load_users);

DELETE FROM circles WHERE id IN (SELECT id FROM load_circles);

DELETE FROM follows
WHERE follower_id IN (SELECT id FROM load_users)
   OR followee_id IN (SELECT id FROM load_users);

INSERT INTO users (username, clerk_id)
SELECT 'load_' || n, 'load_user_' || n
FROM generate_series(1, 1000) AS n
ON CONFLICT (clerk_id) DO UPDATE SET username = EXCLUDED.username;

-- n, the number in the Clerk id, for the statements below.
CREATE TEMP TABLE load_ids AS
SELECT id, substring(clerk_id FROM 11)::int AS n
FROM users WHERE clerk_id ~ '^load_user_[0-9]+$';

-- Without these the follows below join a thousand rows to a thousand, 50 times.
CREATE INDEX ON load_ids (n);
ANALYZE load_ids;

-- Everyone follows load_1, so all of them can see the race want.
INSERT INTO follows (follower_id, followee_id)
SELECT id, (SELECT id FROM load_ids WHERE n = 1)
FROM load_ids WHERE n <> 1;

-- And 50 others each. 19 and 1000 share no factor, so the 50 are distinct
-- and never yourself.
INSERT INTO follows (follower_id, followee_id)
SELECT follower.id, followee.id
FROM load_ids follower
CROSS JOIN generate_series(1, 50) AS k
JOIN load_ids followee ON followee.n = (follower.n - 1 + 19 * k) % 1000 + 1
ON CONFLICT DO NOTHING;

-- load_2 to load_101 own a circle each, with 10 of their followers in it.
INSERT INTO circles (owner_id, name)
SELECT id, 'Load circle'
FROM load_ids WHERE n BETWEEN 2 AND 101;

INSERT INTO circle_members (circle_id, owner_id, member_id)
SELECT c.id, c.owner_id, f.follower_id
FROM circles c
JOIN load_ids owner ON owner.id = c.owner_id
CROSS JOIN LATERAL (
    SELECT follower_id FROM follows WHERE followee_id = c.owner_id ORDER BY follower_id LIMIT 10
) f;

-- 10 wants each: a third for one seat, a third for four, a third open; the
-- first in your circle if you have one, the last closed. No deadlines, so
-- the expiry job never picks them up.
INSERT INTO wants (message, category, user_id, capacity, circle_id, closed_at)
SELECT 'Load want ' || u.n || '-' || k,
       (ARRAY['FOOD', 'MOVIE', 'GAME', 'TRIP'])[1 + (u.n + k) % 4],
       u.id,
       CASE k % 3 WHEN 0 THEN NULL WHEN 1 THEN 1 ELSE 4 END,
       CASE WHEN k = 1 THEN c.id END,
       CASE WHEN k = 10 THEN now() END
FROM load_ids u
CROSS JOIN generate_series(1, 10) AS k
LEFT JOIN circles c ON c.owner_id = u.id;

-- The owner's seat, as WantService.create gives it.
INSERT INTO want_signups (want_id, user_id)
SELECT w.id, w.user_id
FROM wants w JOIN load_ids u ON u.id = w.user_id;

-- Two followers on each General want with room, so lineups have people in them.
INSERT INTO want_signups (want_id, user_id)
SELECT w.id, f.follower_id
FROM wants w
JOIN load_ids u ON u.id = w.user_id
CROSS JOIN LATERAL (
    SELECT follower_id FROM follows WHERE followee_id = w.user_id ORDER BY follower_id LIMIT 2
) f
WHERE w.circle_id IS NULL AND (w.capacity IS NULL OR w.capacity >= 4);

-- Fresh statistics, so the planner sees these tables as they now are.
ANALYZE users;
ANALYZE follows;
ANALYZE circles;
ANALYZE circle_members;
ANALYZE wants;
ANALYZE want_signups;

DROP TABLE load_users, load_circles, load_ids;
