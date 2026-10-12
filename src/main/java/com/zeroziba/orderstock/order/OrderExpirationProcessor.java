package com.zeroziba.orderstock.order;

import java.time.LocalDateTime;
import java.util.Comparator;
import com.zeroziba.orderstock.product.ProductRepository;
import com.zeroziba.orderstock.monitoring.OrderMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderExpirationProcessor {
    private final OrderRepository orders;
    private final ProductRepository products;
    private final OrderMetrics metrics;

    public OrderExpirationProcessor(OrderRepository orders, ProductRepository products, OrderMetrics metrics) {
        this.orders = orders;
        this.products = products;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expire(long orderId, LocalDateTime now) {
        metrics.trackExpirationRollback();
        var order = orders.findByIdForExpiration(orderId).orElse(null);
        // Candidate IDs can become stale while a payment, cancellation or another worker commits.
        if (order == null || !order.expireIfDue(now)) {
            return false;
        }
        for (var item : order.getItems().stream().sorted(Comparator.comparing(OrderItem::getProductId)).toList()) {
            var product = products.findByIdForUpdate(item.getProductId())
                    .orElseThrow(() -> new IllegalStateException("Expiration product is missing"));
            product.restoreStock(item.getQuantity());
        }
        orders.flush();
        metrics.afterCommit(OrderMetrics.Outcome.EXPIRED);
        return true;
    }
}
