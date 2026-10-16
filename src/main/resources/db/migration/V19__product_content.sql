CREATE TABLE product_content (
    product_id BIGINT PRIMARY KEY,
    description VARCHAR(2000) NOT NULL DEFAULT '',
    specification VARCHAR(1000) NOT NULL DEFAULT '',
    revision BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_product_content_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT chk_product_content_revision CHECK (revision >= 0)
);
CREATE TABLE product_content_changes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    before_description VARCHAR(2000) NOT NULL,
    after_description VARCHAR(2000) NOT NULL,
    before_specification VARCHAR(1000) NOT NULL,
    after_specification VARCHAR(1000) NOT NULL,
    before_revision BIGINT NOT NULL,
    after_revision BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_content_change_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_content_change_actor FOREIGN KEY (actor_id) REFERENCES app_users(id),
    CONSTRAINT uq_content_change_revision UNIQUE (product_id, after_revision),
    CONSTRAINT chk_content_change_revision CHECK (before_revision >= 0 AND after_revision = before_revision + 1),
    INDEX ix_content_changes_product_id (product_id, id)
);
