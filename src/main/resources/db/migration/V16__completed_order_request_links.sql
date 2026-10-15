-- Pending requests must not occupy a nullable unique completion index while waiting for product locks.
CREATE TABLE order_request_completions (
    request_id BIGINT NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    CONSTRAINT uq_completed_request_order UNIQUE (order_id),
    CONSTRAINT fk_completion_request FOREIGN KEY (request_id) REFERENCES order_requests(id) ON DELETE CASCADE,
    CONSTRAINT fk_completion_order FOREIGN KEY (order_id) REFERENCES purchase_orders(id)
) ENGINE=InnoDB;
INSERT INTO order_request_completions(request_id,order_id)
    SELECT id,order_id FROM order_requests WHERE order_id IS NOT NULL;
ALTER TABLE order_requests DROP FOREIGN KEY fk_order_requests_order,
    DROP INDEX uq_order_requests_order, DROP COLUMN order_id;
