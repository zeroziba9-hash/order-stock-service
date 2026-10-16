CREATE TABLE product_images (
    product_id BIGINT PRIMARY KEY,
    image_data MEDIUMBLOB NULL,
    image_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    width INT NULL,
    height INT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_product_image FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT ck_product_image_revision CHECK (revision >= 0)
);
CREATE TABLE product_image_changes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    before_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    after_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    revision BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_product_image_revision (product_id, revision),
    CONSTRAINT fk_image_change_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_image_change_actor FOREIGN KEY (actor_id) REFERENCES app_users(id)
);
