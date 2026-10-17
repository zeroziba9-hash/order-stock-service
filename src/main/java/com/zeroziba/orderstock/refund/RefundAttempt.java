package com.zeroziba.orderstock.refund;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "refund_attempts")
public class RefundAttempt {
    public enum Status { SUCCEEDED, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false)
    private long orderId;
    @Column(nullable = false, updatable = false)
    private long paymentId;
    @Column(nullable = false, updatable = false, length = 64)
    private String requestKey;
    @Column(nullable = false, updatable = false)
    private long amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20, columnDefinition = "varchar(20)")
    private Status status;
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected RefundAttempt() { }

    public RefundAttempt(long orderId, long paymentId, String requestKey, long amount, MockRefundRequest.Result result) {
        this.orderId = orderId;
        this.paymentId = paymentId;
        this.requestKey = requestKey;
        this.amount = amount;
        this.status = result == MockRefundRequest.Result.SUCCESS ? Status.SUCCEEDED : Status.FAILED;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public static RefundAttempt approved(long orderId, long paymentId, String key, long amount, LocalDateTime at) {
        var refund = new RefundAttempt(); refund.orderId=orderId; refund.paymentId=paymentId; refund.requestKey=key;
        refund.amount=amount; refund.status=Status.SUCCEEDED; refund.createdAt=at.truncatedTo(ChronoUnit.MICROS); return refund;
    }

    public Long getId() { return id; }
    public long getOrderId() { return orderId; }
    public long getPaymentId() { return paymentId; }
    public long getAmount() { return amount; }
    public Status getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
