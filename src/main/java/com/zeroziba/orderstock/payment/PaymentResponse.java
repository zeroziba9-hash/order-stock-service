package com.zeroziba.orderstock.payment;

import java.time.Instant;
import java.time.ZoneOffset;

public record PaymentResponse(long paymentId, long orderId, long amount, String status, Instant createdAt) {
    public static PaymentResponse from(PaymentAttempt payment) {
        return new PaymentResponse(payment.getId(), payment.getOrderId(), payment.getAmount(),
                payment.getStatus().name(), payment.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
