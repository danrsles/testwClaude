-- Everything about a user now comes from Clerk. The profile page that edited
-- nickname and s3_url is gone: people are shown by their Clerk username, and
-- their profile picture is Clerk's, whose URL arrives in the session token's
-- imageUrl claim and is copied here so the wants list can show it.
ALTER TABLE users DROP COLUMN nickname;

ALTER TABLE users RENAME COLUMN s3_url TO image_url;

-- Old values were typed in by hand, not Clerk's. Each user's own next request
-- fills in their real picture.
UPDATE users SET image_url = NULL;
