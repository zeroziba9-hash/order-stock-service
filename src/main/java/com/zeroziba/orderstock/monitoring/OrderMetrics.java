package com.zeroziba.orderstock.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class OrderMetrics {
    public enum Outcome { CREATED, REPLAYED, CANCELLED, EXPIRED }
    private static final Logger log = LoggerFactory.getLogger(OrderMetrics.class);
    private final EnumMap<Outcome, Counter> committed = new EnumMap<>(Outcome.class);
    private final Counter insufficientStock;
    private final Counter expirationFailures;

    public OrderMetrics(MeterRegistry registry) {
        for (Outcome outcome : Outcome.values()) {
            committed.put(outcome, Counter.builder("stockroom.order.events")
                    .description("Committed order operations; replay is a request, not a new order")
                    .tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT)).register(registry));
        }
        insufficientStock = Counter.builder("stockroom.order.rejections")
                .description("HTTP order responses rejected for insufficient stock")
                .tag("reason", "insufficient_stock").register(registry);
        expirationFailures = Counter.builder("stockroom.expiration.failures")
                .description("Rolled back automatic expiration transactions").register(registry);
    }

    public void afterCommit(Outcome outcome) {
        requireTransaction();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { increment(committed.get(outcome)); }
        });
    }

    public void trackExpirationRollback() {
        requireTransaction();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) { increment(expirationFailures); }
            }
        });
    }

    public void insufficientStockResponse() { increment(insufficientStock); }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Order outcome metrics require an active transaction");
        }
    }

    private static void increment(Counter counter) {
        try {
            counter.increment();
        } catch (RuntimeException failure) {
            // Metrics must not turn an already committed operation into a failed HTTP response.
            log.warn("Order metric update failed; error={}", failure.getClass().getSimpleName());
        }
    }
}
