package com.zeroziba.orderstock.payment.toss;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TossStore {
    record Row(String id, long orderId, String requestKey, long amount, String customerKey, String status,
               String paymentKey, String confirmKey, String refundKey, Long paymentId, LocalDateTime createdAt,
               LocalDateTime confirmStartedAt, LocalDateTime refundStartedAt, LocalDateTime nextCheckAt,
               String claimId, int attempts, boolean outboundDisabled) {
        boolean pending() { return List.of("CONFIRMING", "UNKNOWN", "CANCELING").contains(status); }
    }
    private static final RowMapper<Row> MAPPER = (r, index) -> new Row(r.getString("id"), r.getLong("order_id"), r.getString("request_key"),
            r.getLong("amount"), r.getString("customer_key"), r.getString("status"), r.getString("payment_key"),
            r.getString("confirm_key"), r.getString("refund_key"), r.getObject("payment_id", Long.class),
            r.getObject("created_at", LocalDateTime.class), r.getObject("confirm_started_at", LocalDateTime.class),
            r.getObject("refund_started_at", LocalDateTime.class), r.getObject("next_check_at", LocalDateTime.class),
            r.getString("claim_id"), r.getInt("attempts"), r.getBoolean("outbound_disabled"));
    private final JdbcTemplate jdbc;
    public TossStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public JdbcTemplate jdbc() { return jdbc; }
    Optional<Row> find(String id) { return jdbc.query("SELECT * FROM toss_payments WHERE id=?", MAPPER, id).stream().findFirst(); }
    Optional<Row> request(long order, String key) { return jdbc.query("SELECT * FROM toss_payments WHERE order_id=? AND request_key=?", MAPPER, order, key).stream().findFirst(); }
    Optional<Row> active(long order) { return jdbc.query("SELECT * FROM toss_payments WHERE active_order_id=?", MAPPER, order).stream().findFirst(); }
    Optional<Row> latest(long order) { return jdbc.query("SELECT * FROM toss_payments WHERE order_id=? ORDER BY sequence DESC LIMIT 1", MAPPER, order).stream().findFirst(); }
    List<String> due(LocalDateTime now) { return jdbc.queryForList("SELECT id FROM toss_payments WHERE next_check_at<=? ORDER BY next_check_at,id LIMIT 10", String.class, now); }
}
