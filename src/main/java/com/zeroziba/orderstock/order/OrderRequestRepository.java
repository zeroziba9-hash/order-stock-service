package com.zeroziba.orderstock.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRequestRepository {

    public record StoredRequest(long productId, int quantity, Long orderId,String hash) {
        public String fingerprint(){return hash==null?CartOrderRequest.fingerprint(java.util.List.of(new CreateOrderRequest(productId,quantity))):hash;}
    }

    private final JdbcTemplate jdbcTemplate;

    public OrderRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public StoredRequest reserveAndLock(long userId, String key, CreateOrderRequest request) {
        return reserveAndLock(userId,key,java.util.List.of(request));
    }
    public StoredRequest reserveAndLock(long userId,String key,java.util.List<CreateOrderRequest> items) {
        var request=items.get(0);
        // 신규 키는 삽입하고 기존 키는 내용을 바꾸지 않는다. 같은 키의 요청은 여기서 대기한다.
        // 상품 FK는 두지 않는다. 주문 생성 전 상품의 공유 잠금을 잡으면 쓰기 잠금 승격 시 교착될 수 있다.
        jdbcTemplate.update("""
                INSERT INTO order_requests (user_id, request_key, product_id, quantity,request_hash) VALUES (?, ?, ?, ?,?)
                ON DUPLICATE KEY UPDATE request_key = request_key
                """, userId, key, request.productId(), request.quantity(),CartOrderRequest.fingerprint(items));
        return jdbcTemplate.queryForObject("""
                SELECT product_id, quantity,request_hash,
                    (SELECT order_id FROM order_request_completions c WHERE c.request_id=r.id) order_id
                FROM order_requests r WHERE user_id = ? AND request_key = ? FOR UPDATE
                """, (row, index) -> new StoredRequest(row.getLong("product_id"),
                        row.getInt("quantity"), row.getObject("order_id", Long.class),row.getString("request_hash")), userId, key);
    }

    public void complete(long userId, String key, long orderId) {
        int updated = jdbcTemplate.update("""
                INSERT INTO order_request_completions(order_id,request_id)
                SELECT ?,id FROM order_requests WHERE user_id=? AND request_key=?
                """, orderId, userId, key);
        if (updated != 1) {
            throw new IllegalStateException("The locked order request could not be completed.");
        }
    }
}
