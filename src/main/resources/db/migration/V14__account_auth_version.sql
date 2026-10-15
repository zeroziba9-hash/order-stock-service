-- Existing serialized principals default the new Java field to zero.
ALTER TABLE app_users
    ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_app_users_auth_version CHECK (auth_version >= 0);
