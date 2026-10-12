package com.zeroziba.orderstock.order;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class OrderExpirationService {
    private static final Logger log = LoggerFactory.getLogger(OrderExpirationService.class);
    private final OrderRepository orders;
    private final OrderExpirationProcessor processor;
    private final OrderExpirationProperties settings;
    private final Clock clock;

    public OrderExpirationService(OrderRepository orders, OrderExpirationProcessor processor,
            OrderExpirationProperties settings, Clock clock) {
        this.orders = orders;
        this.processor = processor;
        this.settings = settings;
        this.clock = clock;
    }

    public int expireDueOrders() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var ids = orders.findDueIds(now, PageRequest.of(0, settings.batchSize()));
        int expired = 0;
        for (long id : ids) {
            try {
                if (processor.expire(id, now)) {
                    expired++;
                }
            } catch (RuntimeException failure) {
                // One bad order rolls back independently; other candidates still get processed.
                log.warn("Order expiration failed; orderId={}, error={}", id, failure.getClass().getSimpleName());
            }
        }
        if (expired > 0) {
            log.info("Expired unpaid orders; count={}", expired);
        }
        return expired;
    }
}
