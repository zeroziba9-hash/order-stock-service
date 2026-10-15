CREATE TABLE saved_carts (
    user_id BIGINT PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_saved_cart_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
);
CREATE TABLE saved_cart_items (
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    PRIMARY KEY (user_id, product_id),
    CONSTRAINT fk_saved_cart_items FOREIGN KEY (user_id) REFERENCES saved_carts(user_id) ON DELETE CASCADE
);
CREATE TABLE checkout_quotes (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    order_id BIGINT NULL,
    INDEX ix_quotes_expiry (expires_at),
    INDEX ix_quotes_user_expiry (user_id, expires_at),
    CONSTRAINT fk_checkout_quote_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
);
CREATE TABLE checkout_quote_items (
    quote_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price BIGINT NOT NULL,
    catalog_revision BIGINT NOT NULL,
    PRIMARY KEY (quote_id, product_id),
    CONSTRAINT fk_checkout_quote_items FOREIGN KEY (quote_id) REFERENCES checkout_quotes(id) ON DELETE CASCADE
);
