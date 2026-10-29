-- Wants in circles (see docs/design/wants-signups-and-notifications.md, step 8d).
-- Null is General: the poster's followers, which every existing want stays.
-- A plain key rather than one to (circle_id, user_id): any member may post to
-- a circle, not only its owner, so WantService checks membership instead.
-- Circles are never deleted (they are archived), so the key needs no action.
ALTER TABLE wants ADD COLUMN circle_id BIGINT REFERENCES circles (id);

-- A circle's wants, for the feed's visibility check.
CREATE INDEX wants_circle_idx ON wants (circle_id) WHERE circle_id IS NOT NULL;
