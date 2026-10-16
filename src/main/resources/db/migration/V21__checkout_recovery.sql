ALTER TABLE checkout_quotes
    ADD COLUMN request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN checkout_kind VARCHAR(6) NULL,
    ADD COLUMN cart_revision BIGINT NULL,
    ADD COLUMN acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    ADD INDEX ix_quote_recovery (user_id, acknowledged, expires_at);
