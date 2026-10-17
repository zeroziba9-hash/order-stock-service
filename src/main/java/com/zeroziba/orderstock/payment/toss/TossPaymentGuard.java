package com.zeroziba.orderstock.payment.toss;

import com.zeroziba.orderstock.common.ApiException;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TossPaymentGuard {
    private final JdbcTemplate jdbc;
    public TossPaymentGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    // Call only after acquiring the parent order lock, as every external transition does.
    public boolean pending(long order) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM toss_payments WHERE order_id=? AND status IN ('CONFIRMING','UNKNOWN','CANCELING')", Long.class, order) > 0;
    }
    public void requireCancelable(long order) {
        if (pending(order)) throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_PENDING", "결제 결과를 확인 중이에요. 결과가 확정된 뒤 취소해 주세요.");
    }
    public void requireMockAllowed(long order) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM toss_payments WHERE active_order_id=?", Long.class, order) > 0)
            throw new ApiException(HttpStatus.CONFLICT, "GATEWAY_PAYMENT_EXISTS", "카드 테스트 결제의 결과 확인 또는 환불을 이용하세요.");
    }
    public void closeUnused(long order, LocalDateTime now) {
        jdbc.update("UPDATE toss_payments SET status='FAILED' WHERE order_id=? AND status='READY'", order);
    }
}
