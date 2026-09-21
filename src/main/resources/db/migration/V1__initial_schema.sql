CREATE TABLE users (
    id       BIGINT       NOT NULL AUTO_INCREMENT,
    username VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username)
) ENGINE = InnoDB;

CREATE TABLE wants (
    id       BIGINT                                   NOT NULL AUTO_INCREMENT,
    message  VARCHAR(255)                             NOT NULL,
    category ENUM ('FOOD', 'GAME', 'MOVIE', 'TRIP')   NOT NULL,
    user_id  BIGINT                                   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_wants_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;
