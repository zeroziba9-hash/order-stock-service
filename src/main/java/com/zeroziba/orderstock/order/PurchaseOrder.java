package com.zeroziba.orderstock.order;

import com.zeroziba.orderstock.product.Product;
import com.zeroziba.orderstock.common.ApiException;
import org.springframework.http.HttpStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {

    public enum Status { CREATED, PAID, CANCELLED, REFUNDED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private long totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private Status status;

    // DB에는 UTC 시각으로 저장하고, API에는 Z가 붙은 ISO-8601 시각을 반환한다.
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime cancelledAt;

    @Column
    private LocalDateTime paidAt;

    @Column
    private LocalDateTime refundedAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column
    private LocalDateTime expiredAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @jakarta.persistence.OrderBy("id ASC")
    private List<OrderItem> items = new ArrayList<>();

    protected PurchaseOrder() { }

    public PurchaseOrder(long userId, Product product, int quantity, long totalAmount,
            LocalDateTime createdAt, LocalDateTime expiresAt) {
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Payment deadline must follow order creation");
        }
        this.userId = userId;
        this.totalAmount = totalAmount;
        this.status = Status.CREATED;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
        this.items.add(new OrderItem(this, product, quantity));
    }

    public Long getId() { return id; }
    public long getTotalAmount() { return totalAmount; }
    public Status getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public LocalDateTime getRefundedAt() { return refundedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getExpiredAt() { return expiredAt; }
    public List<OrderItem> getItems() { return List.copyOf(items); }
    void addItem(Product product,int quantity){items.add(new OrderItem(this,product,quantity));}

    public boolean cancel() {
        if (status == Status.CANCELLED || status == Status.REFUNDED || status == Status.EXPIRED) {
            return false;
        }
        if (status == Status.PAID) {
            throw new ApiException(HttpStatus.CONFLICT, "REFUND_REQUIRED", "결제 완료 주문은 환불 API로 환불과 취소를 함께 처리하세요.");
        }
        status = Status.CANCELLED;
        cancelledAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        return true;
    }

    public boolean expireIfDue(LocalDateTime now) {
        if (status != Status.CREATED || now.isBefore(expiresAt)) {
            return false;
        }
        status = Status.EXPIRED;
        expiredAt = now.truncatedTo(ChronoUnit.MICROS);
        cancelledAt = expiredAt;
        return true;
    }

    public void requirePayable(LocalDateTime now) {
        if (status == Status.EXPIRED || (status == Status.CREATED && !now.isBefore(expiresAt))) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_EXPIRED", "결제 가능 시간이 지났습니다. 새 주문을 만들어 주세요.");
        }
        if (status != Status.CREATED) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_PAYABLE", "결제 대기 중인 주문만 결제할 수 있습니다.");
        }
    }

    public void markPaid(LocalDateTime paidAt) {
        requirePayable(paidAt);
        this.status = Status.PAID;
        this.paidAt = paidAt;
    }

    public void requireRefundable() {
        if (status != Status.PAID) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_REFUNDABLE", "결제 완료 상태의 주문만 환불할 수 있습니다.");
        }
    }

    public void markRefunded(LocalDateTime refundedAt) {
        requireRefundable();
        this.status = Status.REFUNDED;
        this.refundedAt = refundedAt;
        this.cancelledAt = refundedAt;
    }
}
