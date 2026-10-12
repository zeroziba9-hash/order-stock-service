-- Flyway runs this version once, so later price/stock changes survive restarts.
INSERT INTO products (name, price, stock) VALUES
    ('한정판 머그컵', 15000, 10),
    ('개발자 스티커 세트', 5000, 30);
