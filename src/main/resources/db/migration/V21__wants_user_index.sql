-- A user's wants, for the feed (see WantRepository.FEED_SOURCES). Without it
-- the feed reads every want in the table to find those of the people you
-- follow: 5 ms at 10,000 wants, 490 ms at a million (README, Performance).
CREATE INDEX wants_user_idx ON wants (user_id);
