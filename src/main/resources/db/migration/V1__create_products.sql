CREATE TABLE products (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    price BIGINT NOT NULL,
    stock INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_products_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_products_price CHECK (price >= 0),
    CONSTRAINT chk_products_stock CHECK (stock >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
