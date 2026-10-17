package com.zeroziba.orderstock.payment.toss;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Read-only projection for typed order candidate queries; transitions stay in TossTransactions. */
@Entity
@Immutable
@Table(name="toss_payments")
public class TossPaymentIndex {
    @Id @Column(length=36) private String id;
    @Column(name="order_id",nullable=false) private long orderId;
    @Column(nullable=false,length=20) private String status;
    protected TossPaymentIndex() { }
}
