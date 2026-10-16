CREATE TABLE password_resets (
    user_id BIGINT PRIMARY KEY,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
    auth_version BIGINT NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NULL,
    window_started_at DATETIME(6) NOT NULL,
    request_count INT NOT NULL,
    INDEX ix_reset_cleanup (issued_at),
    CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT ck_reset_count CHECK (request_count BETWEEN 1 AND 5)
);
