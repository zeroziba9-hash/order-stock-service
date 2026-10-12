ALTER TABLE purchase_orders
    ADD COLUMN paid_at DATETIME(6) NULL,
    DROP CHECK chk_orders_status,
    DROP CHECK chk_orders_cancellation,
    ADD CONSTRAINT chk_orders_status CHECK (status IN ('CREATED', 'PAID', 'CANCELLED')),
    ADD CONSTRAINT chk_orders_cancellation CHECK (
        (status IN ('CREATED', 'PAID') AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_orders_payment CHECK (
        (status = 'PAID' AND paid_at IS NOT NULL)
        OR (status IN ('CREATED', 'CANCELLED') AND paid_at IS NULL)
    );

CREATE TABLE payment_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    -- NULL은 여러 행에 허용된다. 성공 결제만 주문당 최대 한 건으로 제한한다.
    successful_order_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN status = 'SUCCEEDED' THEN order_id ELSE NULL END
    ) STORED,
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES purchase_orders(id),
    CONSTRAINT uk_payment_request UNIQUE (order_id, request_key),
    CONSTRAINT uk_payment_success UNIQUE (successful_order_id),
    CONSTRAINT chk_payment_amount CHECK (amount >= 0),
    CONSTRAINT chk_payment_status CHECK (status IN ('SUCCEEDED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
