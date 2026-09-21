-- Sample data so a fresh database is not empty while there is no user API.
-- Delete this migration (and reset the database) before using this schema for
-- anything real: Flyway runs it in every environment, production included.
INSERT INTO users (username)
VALUES ('dani');

INSERT INTO wants (message, category, user_id)
VALUES ('Ramen at that place downtown', 'FOOD', (SELECT id FROM users WHERE username = 'dani')),
       ('Watch Dune Part Two', 'MOVIE', (SELECT id FROM users WHERE username = 'dani'));
