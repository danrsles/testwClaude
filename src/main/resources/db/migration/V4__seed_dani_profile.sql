-- Sample profile for the seeded user, so the profile screen has something to
-- show. Like V2, delete this migration (and reset the database) before using
-- this schema for anything real: Flyway runs it in every environment.
INSERT INTO user_profiles (user_id, email, nickname, s3_url)
VALUES ((SELECT id FROM users WHERE username = 'dani'), 'dani@example.com', 'Dani', NULL);
