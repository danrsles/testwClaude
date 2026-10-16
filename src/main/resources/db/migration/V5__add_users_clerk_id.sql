-- Links a local user to the Clerk account that signs in as it: the "sub" claim
-- of the session token. Nullable because the seeded dani predates logins;
-- PostgreSQL lets a UNIQUE column hold any number of NULLs.
ALTER TABLE users ADD COLUMN clerk_id VARCHAR(255) NULL;
ALTER TABLE users ADD CONSTRAINT uk_users_clerk_id UNIQUE (clerk_id);
