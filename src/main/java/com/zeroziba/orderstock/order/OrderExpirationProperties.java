package com.zeroziba.orderstock.order;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.orders.expiration")
public record OrderExpirationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30m") Duration paymentWindow,
        @DefaultValue("60s") Duration pollInterval,
        @DefaultValue("100") int batchSize) {

    public OrderExpirationProperties {
        if (paymentWindow == null || paymentWindow.compareTo(Duration.ofSeconds(1)) < 0
                || paymentWindow.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Order payment window must be between 1 second and 24 hours");
        }
        if (pollInterval == null || pollInterval.compareTo(Duration.ofSeconds(1)) < 0
                || pollInterval.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Order expiration poll interval must be between 1 second and 1 hour");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Order expiration batch size must be between 1 and 1000");
        }
    }
}
