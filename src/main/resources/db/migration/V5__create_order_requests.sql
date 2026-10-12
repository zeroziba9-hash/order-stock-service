CREATE TABLE order_requests (
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    order_id BIGINT NULL,
    PRIMARY KEY (request_key),
    CONSTRAINT uq_order_requests_order UNIQUE (order_id),
    CONSTRAINT fk_order_requests_order FOREIGN KEY (order_id) REFERENCES purchase_orders (id),
    CONSTRAINT chk_order_requests_product CHECK (product_id > 0),
    CONSTRAINT chk_order_requests_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
