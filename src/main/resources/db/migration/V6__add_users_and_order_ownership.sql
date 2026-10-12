CREATE TABLE app_users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) COLLATE utf8mb4_0900_as_cs NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_app_users_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 기존 익명 주문은 소유자 NULL로 보관하며 API에서 회원에게 자동 귀속하지 않는다.
ALTER TABLE purchase_orders
    ADD COLUMN user_id BIGINT NULL,
    ADD CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES app_users (id);

-- NULL 소유자의 기존 키도 보존한다. 신규 키는 인증한 사용자 ID와 함께 저장한다.
ALTER TABLE order_requests
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD COLUMN user_id BIGINT NULL,
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (id),
    ADD CONSTRAINT uq_order_requests_user_key UNIQUE (user_id, request_key),
    ADD CONSTRAINT fk_order_requests_user FOREIGN KEY (user_id) REFERENCES app_users (id);
