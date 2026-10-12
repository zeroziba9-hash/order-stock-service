ALTER TABLE app_users
    ADD COLUMN user_role VARCHAR(10) NOT NULL DEFAULT 'USER',
    ADD CONSTRAINT chk_users_role CHECK (user_role IN ('USER', 'ADMIN'));

-- Append-only manual adjustments; order/return/expiry movements are outside this ledger.
CREATE TABLE inventory_adjustments (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    admin_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    product_name VARCHAR(100) NOT NULL,
    quantity_delta INT NOT NULL,
    before_stock INT NOT NULL,
    after_stock INT NOT NULL,
    reason VARCHAR(200) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_inventory_admin FOREIGN KEY (admin_id) REFERENCES app_users(id),
    CONSTRAINT fk_inventory_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT chk_inventory_delta CHECK (quantity_delta <> 0),
    CONSTRAINT chk_inventory_stocks CHECK (before_stock >= 0 AND after_stock >= 0
        AND CAST(before_stock AS SIGNED) + CAST(quantity_delta AS SIGNED) = after_stock),
    CONSTRAINT chk_inventory_reason CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 200),
    INDEX idx_inventory_product_history (product_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inventory_adjustment_requests (
    admin_id BIGINT NOT NULL,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_id BIGINT NOT NULL,
    quantity_delta INT NOT NULL,
    reason VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    adjustment_id BIGINT NULL,
    PRIMARY KEY (admin_id, request_key),
    CONSTRAINT uq_inventory_request_adjustment UNIQUE (adjustment_id),
    CONSTRAINT fk_inventory_request_admin FOREIGN KEY (admin_id) REFERENCES app_users(id),
    CONSTRAINT fk_inventory_request_adjustment FOREIGN KEY (adjustment_id) REFERENCES inventory_adjustments(id),
    CONSTRAINT chk_inventory_request_product CHECK (product_id > 0),
    CONSTRAINT chk_inventory_request_delta CHECK (quantity_delta <> 0)
    -- No product FK here: reserving a key must not first take a shared product lock.
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
