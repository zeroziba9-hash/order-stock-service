package com.zeroziba.orderstock.payment;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt {
    public enum Status { SUCCEEDED, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false)
    private long orderId;
    @Column(nullable = false, updatable = false, length = 64)
    private String requestKey;
    @Column(nullable = false, updatable = false)
    private long amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20, columnDefinition = "varchar(20)")
    private Status status;
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected PaymentAttempt() { }

    public PaymentAttempt(long orderId, String requestKey, long amount, MockPaymentRequest.Result result) {
        this.orderId = orderId;
        this.requestKey = requestKey;
        this.amount = amount;
        this.status = result == MockPaymentRequest.Result.SUCCESS ? Status.SUCCEEDED : Status.FAILED;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() { return id; }
    public long getOrderId() { return orderId; }
    public long getAmount() { return amount; }
    public Status getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
