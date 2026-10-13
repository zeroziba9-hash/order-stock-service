package com.zeroziba.orderstock.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zeroziba.orderstock.TestAccounts;
import com.zeroziba.orderstock.auth.AuthenticatedUser;
import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.payment.MockPaymentRequest;
import com.zeroziba.orderstock.payment.PaymentService;
import com.zeroziba.orderstock.product.Product;
import com.zeroziba.orderstock.product.ProductRepository;
import com.zeroziba.orderstock.refund.RefundService;
import com.zeroziba.orderstock.refund.MockRefundRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderExpirationIntegrationTests {
    @Autowired OrderService orders;
    @Autowired IdempotentOrderService idempotency;
    @Autowired OrderExpirationProcessor processor;
    @Autowired OrderExpirationService expiration;
    @Autowired ProductRepository products;
    @Autowired PaymentService payments;
    @Autowired RefundService refunds;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean OrderRepository repository;
    @PersistenceContext EntityManager entityManager;
    long owner;
    long product;

    @BeforeEach
    void prepare() {
        clean();
        owner = TestAccounts.create(jdbc, "expiration@example.test");
        product = products.saveAndFlush(new Product("만료 검증 상품", 1000, 100)).getId();
    }

    @AfterEach
    void clean() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("order_stock_test");
        com.zeroziba.orderstock.TestDatabase.cleanInventory(jdbc);
        jdbc.update("DELETE FROM refund_attempts");
        jdbc.update("DELETE FROM payment_attempts");
        jdbc.update("DELETE FROM order_requests");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM purchase_orders");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM app_users");
    }

    @Test
    void creationPersistsThirtyMinuteDeadlineAndApiExposesUtcTimes() throws Exception {
        var order = create();
        assertThat(Duration.between(order.createdAt(), order.expiresAt())).isEqualTo(Duration.ofMinutes(30));
        assertThat(order.expiredAt()).isNull();
        mvc.perform(get("/orders/" + order.orderId()).with(user(principal())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expiresAt").value(order.expiresAt().toString()))
                .andExpect(jsonPath("$.expiredAt").isEmpty());
    }

    @Test
    void exactDeadlineExpiresAndRepeatedCancellationDoesNotRestoreAgain() {
        var order = create();
        var deadline = LocalDateTime.ofInstant(order.expiresAt(), ZoneOffset.UTC);
        assertThat(processor.expire(order.orderId(), deadline.minusNanos(1000))).isFalse();
        assertThat(processor.expire(order.orderId(), deadline)).isTrue();
        var expired = orders.findById(owner, order.orderId());
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThat(expired.expiredAt()).isEqualTo(order.expiresAt());
        assertThat(expired.cancelledAt()).isEqualTo(expired.expiredAt());
        assertThat(stock()).isEqualTo(100);
        assertThat(processor.expire(order.orderId(), deadline.plusSeconds(1))).isFalse();
        assertThat(orders.cancel(owner, order.orderId())).isEqualTo(expired);
        assertThat(stock()).isEqualTo(100);
    }

    @Test
    void deadlineRejectsNewPaymentBeforeWorkerRunsButPreservesOldFailureReplay() {
        var order = create();
        var failed = payments.pay(owner, order.orderId(), "failure", failure()).payment();
        due(order.orderId());
        var error = assertThrows(ApiException.class, () -> payments.pay(owner, order.orderId(), "new", success()));
        assertThat(error.getCode()).isEqualTo("ORDER_EXPIRED");
        assertThat(orders.findById(owner, order.orderId()).status()).isEqualTo("CREATED");
        assertThat(payments.pay(owner, order.orderId(), "failure", failure()).payment()).isEqualTo(failed);
        assertThat(expiration.expireDueOrders()).isEqualTo(1);
        assertThat(payments.pay(owner, order.orderId(), "failure", failure()).payment()).isEqualTo(failed);
        assertThat(payments.history(owner, order.orderId())).hasSize(1);
        assertThat(stock()).isEqualTo(100);
    }

    @Test
    void staleCandidateCannotExpirePaidOrCancelledOrRefundedOrder() {
        var paid = create();
        payments.pay(owner, paid.orderId(), "pay", success());
        var cancelled = create();
        orders.cancel(owner, cancelled.orderId());
        var refunded = create();
        payments.pay(owner, refunded.orderId(), "pay", success());
        refunds.refund(owner, refunded.orderId(), "refund", new MockRefundRequest(MockRefundRequest.Result.SUCCESS));
        var future = LocalDateTime.ofInstant(paid.expiresAt(), ZoneOffset.UTC).plusSeconds(1);
        assertThat(processor.expire(paid.orderId(), future)).isFalse();
        assertThat(processor.expire(cancelled.orderId(), future)).isFalse();
        assertThat(processor.expire(refunded.orderId(), future)).isFalse();
        assertThat(orders.findById(owner, paid.orderId()).status()).isEqualTo("PAID");
        assertThat(stock()).isEqualTo(99);
    }

    @Test
    void sameCreationKeyReplaysExpiredOrderWithoutReservingStockAgain() {
        var request = new CreateOrderRequest(product, 1);
        var first = idempotency.create(owner, "same-order", request).order();
        due(first.orderId());
        expiration.expireDueOrders();
        var replay = idempotency.create(owner, "same-order", request);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.order().orderId()).isEqualTo(first.orderId());
        assertThat(replay.order().status()).isEqualTo("EXPIRED");
        assertThat(repository.count()).isEqualTo(1);
        assertThat(stock()).isEqualTo(100);
    }

    @Test
    void batchIsBoundedAndProcessesOldestDeadlineFirst() {
        var first = create();
        var oldest = create();
        var last = create();
        var now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        setDeadline(first.orderId(), now.minusSeconds(2));
        setDeadline(oldest.orderId(), now.minusSeconds(3));
        setDeadline(last.orderId(), now.minusSeconds(1));
        var limited = new OrderExpirationService(repository, processor,
                new OrderExpirationProperties(false, Duration.ofMinutes(30), Duration.ofSeconds(60), 1),
                Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        assertThat(limited.expireDueOrders()).isEqualTo(1);
        assertThat(orders.findById(owner, oldest.orderId()).status()).isEqualTo("EXPIRED");
        assertThat(orders.findById(owner, first.orderId()).status()).isEqualTo("CREATED");
        assertThat(limited.expireDueOrders()).isEqualTo(1);
        assertThat(limited.expireDueOrders()).isEqualTo(1);
        assertThat(stock()).isEqualTo(100);
    }

    @Test
    void storageFailureAfterRealFlushRollsBackExpirationAndRestock() {
        var order = create();
        due(order.orderId());
        doAnswer(invocation -> {
            entityManager.flush();
            assertThat(stock()).isEqualTo(100);
            assertThat(jdbc.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class, order.orderId()))
                    .isEqualTo("EXPIRED");
            throw new DataIntegrityViolationException("Injected expiration failure after real writes");
        }).when(repository).flush();
        assertThrows(DataIntegrityViolationException.class,
                () -> processor.expire(order.orderId(), LocalDateTime.now(ZoneOffset.UTC)));
        assertThat(orders.findById(owner, order.orderId()).status()).isEqualTo("CREATED");
        assertThat(orders.findById(owner, order.orderId()).expiredAt()).isNull();
        assertThat(stock()).isEqualTo(99);
    }

    @Test
    void oneFailedOrderDoesNotRollBackOtherOrdersAndIsRetriedNextRun() {
        var broken = create();
        long healthyProduct = products.saveAndFlush(new Product("다른 상품", 1000, 1)).getId();
        var healthy = orders.create(owner, new CreateOrderRequest(healthyProduct, 1));
        due(broken.orderId());
        due(healthy.orderId());
        jdbc.update("UPDATE products SET stock = ? WHERE id = ?", Integer.MAX_VALUE, product);
        assertThat(expiration.expireDueOrders()).isEqualTo(1);
        assertThat(orders.findById(owner, broken.orderId()).status()).isEqualTo("CREATED");
        assertThat(orders.findById(owner, healthy.orderId()).status()).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, healthyProduct)).isEqualTo(1);
        jdbc.update("UPDATE products SET stock = 99 WHERE id = ?", product);
        assertThat(expiration.expireDueOrders()).isEqualTo(1);
        assertThat(stock()).isEqualTo(100);
    }

    @Test
    void twentyWorkersExpireSameOrderAndRestoreStockExactlyOnce() throws Exception {
        var order = create();
        due(order.orderId());
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var results = concurrently(20, index -> processor.expire(order.orderId(), now));
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(stock()).isEqualTo(100);
    }

    @Test
    void expirationAndManualCancellationRaceRestoreStockExactlyOnce() throws Exception {
        var order = create();
        due(order.orderId());
        var now = LocalDateTime.now(ZoneOffset.UTC);
        concurrently(20, index -> {
            if (index % 2 == 0) processor.expire(order.orderId(), now);
            else orders.cancel(owner, order.orderId());
            return true;
        });
        assertThat(orders.findById(owner, order.orderId()).status()).isIn("EXPIRED", "CANCELLED");
        assertThat(stock()).isEqualTo(100);
    }

    @Test
    void expirationAndLatePaymentsRaceNeverChargeOrRestoreTwice() throws Exception {
        var order = create();
        due(order.orderId());
        var now = LocalDateTime.now(ZoneOffset.UTC);
        concurrently(20, index -> {
            if (index % 2 == 0) processor.expire(order.orderId(), now);
            else {
                var error = assertThrows(ApiException.class,
                        () -> payments.pay(owner, order.orderId(), "late-" + index, success()));
                assertThat(error.getCode()).isEqualTo("ORDER_EXPIRED");
            }
            return true;
        });
        assertThat(orders.findById(owner, order.orderId()).status()).isEqualTo("EXPIRED");
        assertThat(payments.history(owner, order.orderId())).isEmpty();
        assertThat(stock()).isEqualTo(100);
    }

    @Test
    void expiredOrderStillRequiresOwnerForDetailAndHistory() throws Exception {
        var order = create();
        due(order.orderId());
        expiration.expireDueOrders();
        long other = TestAccounts.create(jdbc, "other-expiration@example.test");
        var otherUser = new AuthenticatedUser(other, "other-expiration@example.test", "unused");
        for (String suffix : List.of("", "/payments", "/refunds")) {
            mvc.perform(get("/orders/" + order.orderId() + suffix).with(user(otherUser)))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/orders/" + order.orderId()).with(user(principal())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.expiredAt").isString());
    }

    @Test
    void databaseRejectsInconsistentExpirationAndInvalidDeadline() {
        var order = create();
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "UPDATE purchase_orders SET status = 'EXPIRED', cancelled_at = expires_at WHERE id = ?", order.orderId()));
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "UPDATE purchase_orders SET expires_at = created_at WHERE id = ?", order.orderId()));
        assertThat(orders.findById(owner, order.orderId()).status()).isEqualTo("CREATED");
    }

    OrderResponse create() { return orders.create(owner, new CreateOrderRequest(product, 1)); }
    AuthenticatedUser principal() { return new AuthenticatedUser(owner, "expiration@example.test", "unused"); }
    int stock() { return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, product); }
    void due(long id) { setDeadline(id, LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1).truncatedTo(ChronoUnit.MICROS)); }
    void setDeadline(long id, LocalDateTime deadline) {
        // Use production's ORM binding; raw JDBC and ORM timestamp conversion differ on a non-UTC JVM.
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> entityManager.createQuery(
                "update PurchaseOrder o set o.createdAt = :created, o.expiresAt = :expires where o.id = :id")
                .setParameter("created", deadline.minusMinutes(30)).setParameter("expires", deadline)
                .setParameter("id", id).executeUpdate());
        assertThat(orders.findById(owner, id).expiresAt()).isEqualTo(deadline.toInstant(ZoneOffset.UTC));
    }
    MockPaymentRequest success() { return new MockPaymentRequest(MockPaymentRequest.Result.SUCCESS); }
    MockPaymentRequest failure() { return new MockPaymentRequest(MockPaymentRequest.Result.FAILURE); }

    <T> List<T> concurrently(int count, IntFunction<T> operation) throws Exception {
        var pool = Executors.newFixedThreadPool(count);
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                    return operation.apply(index);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
