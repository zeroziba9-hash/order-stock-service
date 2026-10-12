ALTER TABLE purchase_orders
    ADD COLUMN refunded_at DATETIME(6) NULL,
    DROP CHECK chk_orders_status,
    DROP CHECK chk_orders_cancellation,
    DROP CHECK chk_orders_payment,
    ADD CONSTRAINT chk_orders_status CHECK (status IN ('CREATED', 'PAID', 'CANCELLED', 'REFUNDED')),
    ADD CONSTRAINT chk_orders_cancellation CHECK (
        (status IN ('CREATED', 'PAID') AND cancelled_at IS NULL)
        OR (status IN ('CANCELLED', 'REFUNDED') AND cancelled_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_orders_payment CHECK (
        (status IN ('PAID', 'REFUNDED') AND paid_at IS NOT NULL)
        OR (status IN ('CREATED', 'CANCELLED') AND paid_at IS NULL)
    ),
    ADD CONSTRAINT chk_orders_refund CHECK (
        (status = 'REFUNDED' AND refunded_at IS NOT NULL AND refunded_at = cancelled_at)
        OR (status <> 'REFUNDED' AND refunded_at IS NULL)
    );

-- 복합 외래 키로 같은 주문의 결제만 환불 대상으로 연결할 수 있게 한다.
ALTER TABLE payment_attempts
    ADD CONSTRAINT uk_payment_order_id UNIQUE (order_id, id);

CREATE TABLE refund_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    payment_id BIGINT NOT NULL,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    successful_order_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN status = 'SUCCEEDED' THEN order_id ELSE NULL END
    ) STORED,
    CONSTRAINT fk_refund_order FOREIGN KEY (order_id) REFERENCES purchase_orders(id),
    CONSTRAINT fk_refund_payment FOREIGN KEY (order_id, payment_id) REFERENCES payment_attempts(order_id, id),
    CONSTRAINT uk_refund_request UNIQUE (order_id, request_key),
    CONSTRAINT uk_refund_success UNIQUE (successful_order_id),
    CONSTRAINT chk_refund_amount CHECK (amount >= 0),
    CONSTRAINT chk_refund_status CHECK (status IN ('SUCCEEDED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
