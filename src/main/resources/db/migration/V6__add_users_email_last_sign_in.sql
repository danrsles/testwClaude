-- Copied from the Clerk session token on each request (see UserService): the
-- primary email, to send notifications to, and when the user last signed in.
-- Safe on users only because no endpoint serializes another user's User: wants
-- are returned as WantResponse, which carries just userId and username.
--
-- Not unique on email: Clerk already keeps emails unique, and a stale copy here
-- must never block another user's request.
ALTER TABLE users ADD COLUMN email VARCHAR(320) NULL;
ALTER TABLE users ADD COLUMN last_sign_in_at TIMESTAMPTZ NULL;
