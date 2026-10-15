CREATE INDEX ix_orders_user_status_created_id ON purchase_orders(user_id,status,created_at,id);
CREATE INDEX ix_products_selling_price_id ON products(selling,price,id);
