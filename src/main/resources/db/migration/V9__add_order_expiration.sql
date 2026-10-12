ALTER TABLE purchase_orders
    ADD COLUMN expires_at DATETIME(6) NULL,
    ADD COLUMN expired_at DATETIME(6) NULL;

-- Existing orders receive the original creation time + 30 minutes, regardless of new runtime settings.
UPDATE purchase_orders SET expires_at = DATE_ADD(created_at, INTERVAL 30 MINUTE);

ALTER TABLE purchase_orders
    MODIFY COLUMN expires_at DATETIME(6) NOT NULL,
    DROP CHECK chk_orders_status,
    DROP CHECK chk_orders_cancellation,
    DROP CHECK chk_orders_payment,
    ADD CONSTRAINT chk_orders_status CHECK (status IN ('CREATED', 'PAID', 'CANCELLED', 'REFUNDED', 'EXPIRED')),
    ADD CONSTRAINT chk_orders_cancellation CHECK (
        (status IN ('CREATED', 'PAID') AND cancelled_at IS NULL)
        OR (status IN ('CANCELLED', 'REFUNDED', 'EXPIRED') AND cancelled_at IS NOT NULL)
    ),
    ADD CONSTRAINT chk_orders_payment CHECK (
        (status IN ('PAID', 'REFUNDED') AND paid_at IS NOT NULL)
        OR (status IN ('CREATED', 'CANCELLED', 'EXPIRED') AND paid_at IS NULL)
    ),
    ADD CONSTRAINT chk_orders_deadline CHECK (expires_at > created_at),
    ADD CONSTRAINT chk_orders_expiration CHECK (
        (status = 'EXPIRED' AND expired_at IS NOT NULL AND expired_at = cancelled_at AND expired_at >= expires_at)
        OR (status <> 'EXPIRED' AND expired_at IS NULL)
    ),
    ADD INDEX idx_orders_expiration (status, expires_at, id);
