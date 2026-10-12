package com.zeroziba.orderstock.refund;

import java.time.Instant;
import java.time.ZoneOffset;

public record RefundResponse(long refundId, long orderId, long paymentId, long amount, String status, Instant createdAt) {
    public static RefundResponse from(RefundAttempt refund) {
        return new RefundResponse(refund.getId(), refund.getOrderId(), refund.getPaymentId(), refund.getAmount(),
                refund.getStatus().name(), refund.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
