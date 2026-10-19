-- Test users for the follow feature, while there is no way to find a user.
-- They have no Clerk account, so nobody can sign in as them and their wants
-- are read-only, like dani's. Their wants appear in your list only once you
-- follow them: PUT /follows/{id}.
--
-- Like V2, delete this migration (and reset the database) before using this
-- schema for anything real: Flyway runs it in every environment, production
-- included.
INSERT INTO users (username)
VALUES ('alex'),
       ('sam'),
       ('jordan');

INSERT INTO wants (message, category, user_id)
VALUES ('Hand-pulled noodles in Chinatown', 'FOOD', (SELECT id FROM users WHERE username = 'alex')),
       ('Hike the Dolomites', 'TRIP', (SELECT id FROM users WHERE username = 'alex')),
       ('Finish Hollow Knight: Silksong', 'GAME', (SELECT id FROM users WHERE username = 'sam')),
       ('Rewatch Spirited Away', 'MOVIE', (SELECT id FROM users WHERE username = 'sam')),
       ('Proper Neapolitan pizza', 'FOOD', (SELECT id FROM users WHERE username = 'jordan')),
       ('A week in Lisbon', 'TRIP', (SELECT id FROM users WHERE username = 'jordan'));
