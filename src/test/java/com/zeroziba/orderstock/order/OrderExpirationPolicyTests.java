package com.zeroziba.orderstock.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.product.Product;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OrderExpirationPolicyTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Settings.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OrderExpirationProperties.class)
    static class Settings { }

    @Test
    void defaultsAndExplicitDurationUnitsBind() {
        context.run(c -> {
            assertThat(c).hasNotFailed();
            var settings = c.getBean(OrderExpirationProperties.class);
            assertThat(settings.enabled()).isTrue();
            assertThat(settings.paymentWindow()).isEqualTo(Duration.ofMinutes(30));
            assertThat(settings.pollInterval()).isEqualTo(Duration.ofSeconds(60));
            assertThat(settings.batchSize()).isEqualTo(100);
        });
        context.withPropertyValues("app.orders.expiration.payment-window=PT5S", "app.orders.expiration.poll-interval=1s",
                "app.orders.expiration.enabled=false", "app.orders.expiration.batch-size=2").run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(OrderExpirationProperties.class).paymentWindow()).isEqualTo(Duration.ofSeconds(5));
            assertThat(c.getBean(OrderExpirationProperties.class).enabled()).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment-window=0s", "payment-window=-1s", "payment-window=25h",
            "poll-interval=0s", "poll-interval=2h", "batch-size=0", "batch-size=1001"})
    void invalidSettingsFailStartup(String setting) {
        context.withPropertyValues("app.orders.expiration." + setting).run(c -> assertThat(c).hasFailed());
    }

    @Test
    void paymentDeadlineIsExclusiveAndPaidOrderCannotExpire() {
        var now = LocalDateTime.of(2026, 1, 1, 0, 0);
        var order = new PurchaseOrder(1, new Product("상품", 1000, 1), 1, 1000, now, now.plusMinutes(30));
        order.requirePayable(now.plusMinutes(30).minusNanos(1000));
        assertThat(assertThrows(ApiException.class, () -> order.requirePayable(now.plusMinutes(30))).getCode())
                .isEqualTo("ORDER_EXPIRED");
        order.markPaid(now.plusMinutes(30).minusNanos(1000));
        assertThat(order.expireIfDue(now.plusMinutes(30))).isFalse();
        assertThat(order.getStatus()).isEqualTo(PurchaseOrder.Status.PAID);
    }
}
