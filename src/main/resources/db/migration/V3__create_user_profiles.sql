-- One profile per user. Kept apart from users so email does not travel inside
-- every want, which embeds its user when serialized.
CREATE TABLE user_profiles (
    id       BIGINT        NOT NULL AUTO_INCREMENT,
    user_id  BIGINT        NOT NULL,
    email    VARCHAR(255)  NOT NULL,
    nickname VARCHAR(100)  NOT NULL,
    s3_url   VARCHAR(1024) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_profiles_user UNIQUE (user_id),
    CONSTRAINT uk_user_profiles_email UNIQUE (email),
    CONSTRAINT fk_user_profiles_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;
