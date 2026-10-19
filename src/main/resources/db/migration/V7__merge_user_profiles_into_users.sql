-- Folds user_profiles into users: a user's nickname and avatar are now just
-- optional columns of their own row, edited through PUT /me. The table only
-- existed to keep email out of the user every want embedded, and wants no
-- longer embed the user (they are returned as WantResponse).
ALTER TABLE users ADD COLUMN nickname VARCHAR(100) NULL;
ALTER TABLE users ADD COLUMN s3_url VARCHAR(1024) NULL;

UPDATE users u
SET nickname = p.nickname,
    s3_url   = p.s3_url
FROM user_profiles p
WHERE p.user_id = u.id;

-- The profile email was typed in by hand and never verified. Clerk's email
-- (copied on each request) replaces it; it survives only for users Clerk has
-- told us nothing about, such as the seeded dani.
UPDATE users u
SET email = p.email
FROM user_profiles p
WHERE p.user_id = u.id
  AND u.email IS NULL;

DROP TABLE user_profiles;
