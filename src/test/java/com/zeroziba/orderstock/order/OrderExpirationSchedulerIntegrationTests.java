package com.zeroziba.orderstock.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.zeroziba.orderstock.TestAccounts;
import com.zeroziba.orderstock.product.Product;
import com.zeroziba.orderstock.product.ProductRepository;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = {"app.orders.expiration.enabled=true", "app.orders.expiration.payment-window=PT2S",
        "app.orders.expiration.poll-interval=PT1S"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderExpirationSchedulerIntegrationTests {
    @Autowired OrderService orders;
    @Autowired ProductRepository products;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("order_stock_test");
        jdbc.update("DELETE FROM refund_attempts");
        jdbc.update("DELETE FROM payment_attempts");
        jdbc.update("DELETE FROM order_requests");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM purchase_orders");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM app_users");
    }

    @Test
    void realScheduledJobExpiresOrderWithoutManualInvocation() throws Exception {
        long user = TestAccounts.create(jdbc, "scheduler@example.test");
        long product = products.saveAndFlush(new Product("예약 만료", 1000, 1)).getId();
        var order = orders.create(user, new CreateOrderRequest(product, 1));
        long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!orders.findById(user, order.orderId()).status().equals("EXPIRED") && System.nanoTime() < timeout) {
            TimeUnit.MILLISECONDS.sleep(75);
        }
        var expired = orders.findById(user, order.orderId());
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThat(expired.expiredAt()).isAfterOrEqualTo(order.expiresAt());
        assertThat(products.findById(product).orElseThrow().getStock()).isEqualTo(1);
    }
}
