-- Background data for the load test, on top of seed.sql, local database only:
--   docker exec -i demo-postgres psql -U wants -d wants -v ON_ERROR_STOP=1 --single-transaction < loadtest/seed-big.sql
--
-- 99,000 more users (clerk_id bulk_user_<n>, username bulk_<n>) with 10 wants
-- each and their owners' seats: about a million wants nobody in the load test
-- follows. The load users' feeds stay the same size; only the tables around
-- them grow, which shows whether a feed's cost follows the feed or the table.
--
-- It is made once and kept. Running it again adds nothing. It is never
-- deleted by seed.sql, and deleting it by hand is slow: each deleted want
-- cascades to notifications.want_id, which has no full index.
--
-- No tokens are made for these users and they follow nobody.

INSERT INTO users (username, clerk_id)
SELECT 'bulk_' || n, 'bulk_user_' || n
FROM generate_series(1, 99000) AS n
ON CONFLICT (clerk_id) DO NOTHING;

CREATE TEMP TABLE bulk_ids AS
SELECT u.id, substring(u.clerk_id FROM 11)::int AS n
FROM users u
WHERE u.clerk_id ~ '^bulk_user_[0-9]+$'
  AND NOT EXISTS (SELECT 1 FROM wants w WHERE w.user_id = u.id);

-- The same mix as seed.sql: a third for one seat, a third for four, a third
-- open, the last closed. Round by round (k), so each user's wants are spread
-- through the id range as if posted over time.
INSERT INTO wants (message, category, user_id, capacity, closed_at)
SELECT 'Bulk want ' || u.n || '-' || k,
       (ARRAY['FOOD', 'MOVIE', 'GAME', 'TRIP'])[1 + (u.n + k) % 4],
       u.id,
       CASE k % 3 WHEN 0 THEN NULL WHEN 1 THEN 1 ELSE 4 END,
       CASE WHEN k = 10 THEN now() END
FROM bulk_ids u
CROSS JOIN generate_series(1, 10) AS k
ORDER BY k, u.n;

INSERT INTO want_signups (want_id, user_id)
SELECT w.id, w.user_id
FROM wants w JOIN bulk_ids u ON u.id = w.user_id;

ANALYZE users;
ANALYZE wants;
ANALYZE want_signups;

DROP TABLE bulk_ids;
