package com.zeroziba.orderstock.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zeroziba.orderstock.TestAccounts;
import com.zeroziba.orderstock.auth.AuthenticatedUser;
import com.zeroziba.orderstock.order.CreateOrderRequest;
import com.zeroziba.orderstock.order.IdempotentOrderService;
import com.zeroziba.orderstock.order.OrderExpirationProcessor;
import com.zeroziba.orderstock.order.OrderRepository;
import com.zeroziba.orderstock.order.OrderService;
import com.zeroziba.orderstock.product.Product;
import com.zeroziba.orderstock.product.ProductRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OrderMetricsIntegrationTests {
    @Autowired OrderService orders;
    @Autowired IdempotentOrderService idempotency;
    @Autowired OrderExpirationProcessor expiration;
    @Autowired ProductRepository products;
    @Autowired MeterRegistry registry;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @PersistenceContext EntityManager entityManager;
    @MockitoSpyBean OrderRepository repository;
    private final EnumMap<OrderMetrics.Outcome, Double> baseline = new EnumMap<>(OrderMetrics.Outcome.class);
    private double rejected;
    private double failures;
    private long owner;
    private long product;

    @BeforeEach
    void prepare() {
        clean();
        owner = TestAccounts.create(jdbc, "metrics@example.test");
        product = products.saveAndFlush(new Product("지표 검증 상품", 15000, 100)).getId();
        for (var outcome : OrderMetrics.Outcome.values()) { baseline.put(outcome, count(outcome)); }
        rejected = registry.get("stockroom.order.rejections").counter().count();
        failures = registry.get("stockroom.expiration.failures").counter().count();
    }

    @AfterEach
    void clean() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("order_stock_test");
        for (String table : List.of("refund_attempts", "payment_attempts", "order_requests", "order_items",
                "purchase_orders", "products", "app_users")) { jdbc.update("DELETE FROM " + table); }
    }

    @Test
    void successfulFlushIsNotCountedUntilOuterTransactionCommits() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            orders.create(owner, request());
            assertThat(delta(OrderMetrics.Outcome.CREATED)).isZero();
        });
        assertThat(delta(OrderMetrics.Outcome.CREATED)).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void laterRollbackDoesNotCountFlushedOrderOrLeaveReservation() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            idempotency.create(owner, "rollback", request());
            tx.setRollbackOnly();
        });
        assertThat(delta(OrderMetrics.Outcome.CREATED)).isZero();
        assertThat(repository.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_requests", Long.class)).isZero();
        assertThat(stock()).isEqualTo(100);
    }

    @Test
    void replayIsSeparateAndRespectsOuterRollback() {
        idempotency.create(owner, "one-order", request());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(idempotency.create(owner, "one-order", request()).replayed()).isTrue();
            tx.setRollbackOnly();
        });
        assertThat(delta(OrderMetrics.Outcome.REPLAYED)).isZero();
        idempotency.create(owner, "one-order", request());
        assertThat(delta(OrderMetrics.Outcome.REPLAYED)).isEqualTo(1);
        assertThat(delta(OrderMetrics.Outcome.CREATED)).isEqualTo(1);
    }

    @Test
    void rolledBackCancellationIsNotCountedAndRepeatedCancellationCountsOnce() {
        long id = orders.create(owner, request()).orderId();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            orders.cancel(owner, id);
            tx.setRollbackOnly();
        });
        assertThat(delta(OrderMetrics.Outcome.CANCELLED)).isZero();
        assertThat(orders.findById(owner, id).status()).isEqualTo("CREATED");
        assertThat(stock()).isEqualTo(99);
        orders.cancel(owner, id);
        orders.cancel(owner, id);
        assertThat(delta(OrderMetrics.Outcome.CANCELLED)).isEqualTo(1);
        assertThat(stock()).isEqualTo(100);
    }

    @Test
    void failedExpirationFlushRollsBackAndRetryCountsOnlyOneCommittedExpiration() {
        long id = orders.create(owner, request()).orderId();
        LocalDateTime deadline = LocalDateTime.ofInstant(orders.findById(owner, id).expiresAt(), ZoneOffset.UTC);
        doAnswer(invocation -> {
            entityManager.flush();
            assertThat(jdbc.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class, id))
                    .isEqualTo("EXPIRED");
            throw new IllegalStateException("Forced failure after expiration flush");
        }).when(repository).flush();
        assertThrows(IllegalStateException.class, () -> expiration.expire(id, deadline));
        assertThat(delta(OrderMetrics.Outcome.EXPIRED)).isZero();
        assertThat(registry.get("stockroom.expiration.failures").counter().count() - failures).isEqualTo(1);
        assertThat(orders.findById(owner, id).status()).isEqualTo("CREATED");
        assertThat(stock()).isEqualTo(99);
        reset(repository);
        assertThat(expiration.expire(id, deadline)).isTrue();
        assertThat(expiration.expire(id, deadline)).isFalse();
        assertThat(delta(OrderMetrics.Outcome.EXPIRED)).isEqualTo(1);
        assertThat(stock()).isEqualTo(100);
    }

    @Test
    void concurrentSameKeyCountsOneCreationAndSevenReplays() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<IdempotentOrderService.CreationResult>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) { tasks.add(() -> idempotency.create(owner, "concurrent", request())); }
            var results = pool.invokeAll(tasks, 20, TimeUnit.SECONDS);
            for (var result : results) { result.get(1, TimeUnit.SECONDS); }
            assertThat(delta(OrderMetrics.Outcome.CREATED)).isEqualTo(1);
            assertThat(delta(OrderMetrics.Outcome.REPLAYED)).isEqualTo(7);
            assertThat(repository.count()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void stockRejectionsCountHttpResponsesWithoutCountingInvalidRequests() throws Exception {
        var principal = new AuthenticatedUser(owner, "metrics@example.test", null);
        mvc.perform(post("/orders").with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + product + ",\"quantity\":101}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/orders").with(user(principal)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + product + ",\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        assertThat(registry.get("stockroom.order.rejections").counter().count() - rejected).isEqualTo(1);
        assertThat(delta(OrderMetrics.Outcome.CREATED)).isZero();
        assertThat(stock()).isEqualTo(100);
    }

    @Test
    void regularUsersAndForwardedHeadersCannotExposePrometheusOnApplicationPort() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus")
                        .with(user(new AuthenticatedUser(owner, "metrics@example.test", null)))
                        .header("X-Forwarded-Port", "9091").header("Forwarded", "host=localhost:9091"))
                .andExpect(status().isForbidden());
    }

    private CreateOrderRequest request() { return new CreateOrderRequest(product, 1); }
    private int stock() { return products.findById(product).orElseThrow().getStock(); }
    private double count(OrderMetrics.Outcome outcome) {
        return registry.get("stockroom.order.events").tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT)).counter().count();
    }
    private double delta(OrderMetrics.Outcome outcome) { return count(outcome) - baseline.get(outcome); }
}
