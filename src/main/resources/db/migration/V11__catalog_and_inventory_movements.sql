ALTER TABLE products
    ADD COLUMN selling BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN catalog_revision BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_catalog_revision CHECK (catalog_revision >= 0);

CREATE TABLE inventory_movements (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    product_name VARCHAR(100) NOT NULL,
    movement_type VARCHAR(16) NOT NULL,
    event_reference VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id BIGINT NULL,
    quantity_delta INT NOT NULL,
    before_stock INT NOT NULL,
    after_stock INT NOT NULL,
    reason VARCHAR(200) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_movement_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT uq_movement_event UNIQUE (product_id, movement_type, event_reference),
    CONSTRAINT chk_movement_type CHECK (movement_type IN ('BASELINE','ORDER','CANCEL','REFUND','EXPIRE','MANUAL')),
    CONSTRAINT chk_movement_stock CHECK (before_stock >= 0 AND after_stock >= 0
        AND CAST(before_stock AS SIGNED) + CAST(quantity_delta AS SIGNED) = after_stock),
    INDEX idx_movement_product_history (product_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- This is a cutover snapshot, not a reconstruction of movements before V11.
INSERT INTO inventory_movements
    (product_id, product_name, movement_type, event_reference, quantity_delta, before_stock, after_stock, reason, created_at)
SELECT id, name, 'BASELINE', 'opening', stock, 0, stock, 'V11 시작 재고', UTC_TIMESTAMP(6) FROM products;

CREATE TABLE catalog_creation_requests (
    admin_id BIGINT NOT NULL,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_name VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    price BIGINT NOT NULL,
    product_id BIGINT NULL,
    PRIMARY KEY (admin_id, request_key),
    CONSTRAINT fk_catalog_request_admin FOREIGN KEY (admin_id) REFERENCES app_users(id),
    CONSTRAINT fk_catalog_request_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT chk_catalog_request_price CHECK (price >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
