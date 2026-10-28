-- Muting someone you follow (see docs/design/wants-signups-and-notifications.md,
-- step 8b). False stops their new wants being announced to you (WANT_POSTED);
-- you still follow them and still see their wants. Every existing follow
-- starts on, and so does every new one, so unfollowing and following again
-- unmutes.
ALTER TABLE follows ADD COLUMN notify_new_wants BOOLEAN NOT NULL DEFAULT TRUE;
