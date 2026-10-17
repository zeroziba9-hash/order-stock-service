package com.zeroziba.orderstock.payment;

import jakarta.persistence.*;
import java.time.LocalDateTime;
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

    public PaymentAttempt(long orderId, String requestKey, long amount, MockPaymentRequest.Result result,
            LocalDateTime createdAt) {
        this.orderId = orderId;
        this.requestKey = requestKey;
        this.amount = amount;
        this.status = result == MockPaymentRequest.Result.SUCCESS ? Status.SUCCEEDED : Status.FAILED;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    public static PaymentAttempt approved(long orderId, String key, long amount, LocalDateTime at) {
        var payment = new PaymentAttempt(); payment.orderId=orderId; payment.requestKey=key;
        payment.amount=amount; payment.status=Status.SUCCEEDED; payment.createdAt=at.truncatedTo(ChronoUnit.MICROS); return payment;
    }

    public Long getId() { return id; }
    public long getOrderId() { return orderId; }
    public long getAmount() { return amount; }
    public Status getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
