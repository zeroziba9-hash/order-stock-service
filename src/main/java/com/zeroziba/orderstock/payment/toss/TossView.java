package com.zeroziba.orderstock.payment.toss;

public record TossView(String checkoutId, long orderId, String status, long amount, boolean pending, boolean reviewRequired) {
    static TossView from(TossStore.Row row) {
        return new TossView(row.id(), row.orderId(), row.status(), row.amount(), row.pending(), row.outboundDisabled());
    }
    static TossView none(long order) { return new TossView(null, order, "NONE", 0, false, false); }
}
