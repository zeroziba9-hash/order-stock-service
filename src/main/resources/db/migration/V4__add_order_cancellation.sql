ALTER TABLE purchase_orders
    ADD COLUMN cancelled_at DATETIME(6) NULL,
    DROP CHECK chk_orders_status,
    ADD CONSTRAINT chk_orders_status CHECK (status IN ('CREATED', 'CANCELLED')),
    ADD CONSTRAINT chk_orders_cancellation CHECK (
        (status = 'CREATED' AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
    );
