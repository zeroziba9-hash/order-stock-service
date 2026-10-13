-- Record future edits only. Historical edits before V12 cannot be reconstructed.
CREATE TABLE catalog_changes (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    before_name VARCHAR(100) NOT NULL,
    after_name VARCHAR(100) NOT NULL,
    before_price BIGINT NOT NULL,
    after_price BIGINT NOT NULL,
    before_selling BOOLEAN NOT NULL,
    after_selling BOOLEAN NOT NULL,
    before_revision BIGINT NOT NULL,
    after_revision BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_catalog_change_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_catalog_change_actor FOREIGN KEY (actor_id) REFERENCES app_users(id),
    CONSTRAINT uq_catalog_change_revision UNIQUE (product_id, after_revision),
    CONSTRAINT chk_catalog_change_prices CHECK (before_price >= 0 AND after_price >= 0),
    CONSTRAINT chk_catalog_change_revision CHECK (before_revision >= 0 AND after_revision = before_revision + 1),
    CONSTRAINT chk_catalog_change_selling CHECK (before_selling IN (0,1) AND after_selling IN (0,1)),
    INDEX idx_catalog_change_history (product_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
