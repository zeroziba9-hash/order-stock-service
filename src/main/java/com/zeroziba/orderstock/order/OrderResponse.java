package com.zeroziba.orderstock.order;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

public record OrderResponse(long orderId, String status, long totalAmount,
                            Instant createdAt, Instant cancelledAt, Instant paidAt, Instant refundedAt,
                            List<ItemResponse> items) {

    public record ItemResponse(long productId, String productName, long unitPrice, int quantity) { }

    public static OrderResponse from(PurchaseOrder order) {
        return new OrderResponse(order.getId(), order.getStatus().name(), order.getTotalAmount(),
                order.getCreatedAt().toInstant(ZoneOffset.UTC),
                order.getCancelledAt() == null ? null : order.getCancelledAt().toInstant(ZoneOffset.UTC),
                order.getPaidAt() == null ? null : order.getPaidAt().toInstant(ZoneOffset.UTC),
                order.getRefundedAt() == null ? null : order.getRefundedAt().toInstant(ZoneOffset.UTC),
                order.getItems().stream().map(item -> new ItemResponse(item.getProductId(),
                        item.getProductName(), item.getUnitPrice(), item.getQuantity())).toList());
    }
}
