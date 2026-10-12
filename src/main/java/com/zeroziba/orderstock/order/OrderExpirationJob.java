package com.zeroziba.orderstock.order;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.orders.expiration", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderExpirationJob {
    private final OrderExpirationService expiration;

    public OrderExpirationJob(OrderExpirationService expiration) {
        this.expiration = expiration;
    }

    @Scheduled(fixedDelayString = "${app.orders.expiration.poll-interval:PT60S}",
            initialDelayString = "${app.orders.expiration.poll-interval:PT60S}")
    public void run() {
        expiration.expireDueOrders();
    }
}
