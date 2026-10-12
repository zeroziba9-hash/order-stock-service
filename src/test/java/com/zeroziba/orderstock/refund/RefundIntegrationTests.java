package com.zeroziba.orderstock.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.zeroziba.orderstock.TestAccounts;
import com.zeroziba.orderstock.auth.AuthenticatedUser;
import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.order.*;
import com.zeroziba.orderstock.payment.MockPaymentRequest;
import com.zeroziba.orderstock.payment.PaymentResponse;
import com.zeroziba.orderstock.payment.PaymentService;
import com.zeroziba.orderstock.product.Product;
import com.zeroziba.orderstock.product.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.TestExecutionEvent;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithUserDetails(value = "refund@example.test", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class RefundIntegrationTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ProductRepository products;
    @Autowired private OrderService orders;
    @Autowired private PaymentService payments;
    @Autowired private IdempotentOrderService idempotentOrders;
    @Autowired private RefundService refunds;
    @MockitoSpyBean private RefundRepository refundRepository;
    @PersistenceContext private EntityManager entityManager;
    private long ownerId;
    private long otherId;
    private long productId;
    private long orderId;
    private PaymentResponse originalPayment;

    @BeforeEach
    void prepare() {
        clean();
        ownerId = TestAccounts.create(jdbc, "refund@example.test");
        otherId = TestAccounts.create(jdbc, "other.refund@example.test");
        productId = products.saveAndFlush(new Product("환불 테스트", 15000, 10)).getId();
        orderId = orders.create(ownerId, new CreateOrderRequest(productId, 2)).orderId();
        originalPayment = payments.pay(ownerId, orderId, "payment", paid()).payment();
    }

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
    void successfulRefundUsesOriginalPaymentAndCompletesCancellation() throws Exception {
        jdbc.update("UPDATE products SET price = 1 WHERE id = ?", productId);
        mvc.perform(refundRequest("refund", "{\"result\":\"SUCCESS\",\"amount\":1,\"paymentId\":0,\"userId\":" + otherId + "}"))
                .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(header().string("Location", "/orders/" + orderId + "/refunds"))
                .andExpect(jsonPath("$.paymentId").value(originalPayment.paymentId()))
                .andExpect(jsonPath("$.amount").value(30000)).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        var order = orders.findById(ownerId, orderId);
        var refund = refunds.history(ownerId, orderId).get(0);
        assertThat(order.status()).isEqualTo("REFUNDED");
        assertThat(order.paidAt()).isEqualTo(originalPayment.createdAt());
        assertThat(order.refundedAt()).isEqualTo(refund.createdAt()).isEqualTo(order.cancelledAt());
        assertThat(stock()).isEqualTo(10);
        assertThat(payments.history(ownerId, orderId)).containsExactly(originalPayment);
    }

    @Test
    void declinedRefundCommitsHistoryWithoutRestoringStockAndNewKeyCanRetry() throws Exception {
        mvc.perform(refundRequest("decline", "{\"result\":\"FAILURE\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(orders.findById(ownerId, orderId).status()).isEqualTo("PAID");
        assertThat(orders.findById(ownerId, orderId).refundedAt()).isNull();
        assertThat(orders.findById(ownerId, orderId).cancelledAt()).isNull();
        assertThat(stock()).isEqualTo(8);
        mvc.perform(refundRequest("retry", "{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        mvc.perform(get("/orders/" + orderId + "/refunds"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[1].status").value("SUCCEEDED"));
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void identicalRefundReplaysImmutableResultAndCancelIsNoOp() throws Exception {
        var first = refunds.refund(ownerId, orderId, "same", success()).refund();
        var completed = orders.findById(ownerId, orderId);
        mvc.perform(refundRequest("same", "{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.refundId").value(first.refundId()));
        assertThat(refunds.refund(ownerId, orderId, "same", success()).refund()).isEqualTo(first);
        assertThat(orders.cancel(ownerId, orderId)).isEqualTo(completed);
        assertThat(stock()).isEqualTo(10);
        assertThat(refundRepository.count()).isEqualTo(1);
    }

    @Test
    void failedKeyStillReplaysFailureAfterSuccessfulRefund() {
        var failed = refunds.refund(ownerId, orderId, "failed", failure()).refund();
        refunds.refund(ownerId, orderId, "success", success());
        var replay = refunds.refund(ownerId, orderId, "failed", failure());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.refund()).isEqualTo(failed);
        assertThat(stock()).isEqualTo(10);
        assertThat(refundRepository.count()).isEqualTo(2);
    }

    @Test
    void sameKeyCannotChangeSelectedResult() throws Exception {
        refunds.refund(ownerId, orderId, "same", failure());
        mvc.perform(refundRequest("same", "{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(stock()).isEqualTo(8);
        assertThat(refundRepository.count()).isEqualTo(1);
    }

    @Test
    void refundedOrderRejectsNewRefundAndNewPaymentButPaymentReplaySurvives() throws Exception {
        refunds.refund(ownerId, orderId, "first", success());
        mvc.perform(refundRequest("second", "{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_REFUNDABLE"));
        var conflict = assertThrows(ApiException.class, () -> payments.pay(ownerId, orderId, "new", paid()));
        assertThat(conflict.getCode()).isEqualTo("ORDER_NOT_PAYABLE");
        var replay = payments.pay(ownerId, orderId, "payment", paid());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.payment()).isEqualTo(originalPayment);
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void createdAndCancelledOrdersCannotRefund() {
        var unpaid = orders.create(ownerId, new CreateOrderRequest(productId, 1));
        assertThat(assertThrows(ApiException.class,
                () -> refunds.refund(ownerId, unpaid.orderId(), "before", success())).getCode()).isEqualTo("ORDER_NOT_REFUNDABLE");
        orders.cancel(ownerId, unpaid.orderId());
        assertThat(assertThrows(ApiException.class,
                () -> refunds.refund(ownerId, unpaid.orderId(), "after", success())).getCode()).isEqualTo("ORDER_NOT_REFUNDABLE");
        assertThat(refundRepository.count()).isZero();
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void refundKeysAreCaseSensitiveScopedToOrderAndSeparateFromPaymentKeys() {
        refunds.refund(ownerId, orderId, "Case", failure());
        assertThat(refunds.refund(ownerId, orderId, "case", failure()).replayed()).isFalse();
        // 결제 키와 동일 문자열도 별개의 환불 요청이다.
        assertThat(refunds.refund(ownerId, orderId, "payment", success()).replayed()).isFalse();
        var second = orders.create(ownerId, new CreateOrderRequest(productId, 1));
        payments.pay(ownerId, second.orderId(), "payment", paid());
        assertThat(refunds.refund(ownerId, second.orderId(), "Case", success()).replayed()).isFalse();
        assertThat(refundRepository.count()).isEqualTo(4);
        assertThat(stock()).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad key", "한글", "-prefix", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void rejectsInvalidKey(String key) throws Exception {
        mvc.perform(refundRequest(key, "{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        assertThat(refundRepository.count()).isZero();
    }

    @Test
    void requiresKey() throws Exception {
        mvc.perform(post("/orders/" + orderId + "/refunds").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"result\":null}", "{\"result\":\"UNKNOWN\"}", "{\"result\":0}", "{\"result\":true}", "{"})
    void rejectsInvalidResult(String body) throws Exception {
        mvc.perform(refundRequest("valid", body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(refundRepository.count()).isZero();
    }

    @Test
    void requiresLoginAndCsrf() throws Exception {
        mvc.perform(refundRequest("valid", "{\"result\":\"SUCCESS\"}").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/orders/" + orderId + "/refunds").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(post("/orders/" + orderId + "/refunds").header("Idempotency-Key", "valid")
                .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isForbidden());
        assertThat(refundRepository.count()).isZero();
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void anotherMemberCannotRefundReadOrReplay() throws Exception {
        refunds.refund(ownerId, orderId, "shared", failure());
        var foreign = new AuthenticatedUser(otherId, "other.refund@example.test", null);
        mvc.perform(refundRequest("shared", "{\"result\":\"FAILURE\"}").with(user(foreign)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mvc.perform(refundRequest("new", "{\"result\":\"SUCCESS\"}").with(user(foreign)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/orders/" + orderId + "/refunds").with(user(foreign))).andExpect(status().isNotFound());
        assertThat(refundRepository.count()).isEqualTo(1);
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void missingOrderAndInvalidIdDoNotExposeHistory() throws Exception {
        mvc.perform(get("/orders/" + orderId + "/refunds")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/orders/9223372036854775807/refunds")).andExpect(status().isNotFound());
        mvc.perform(post("/orders/9223372036854775807/refunds").with(csrf()).header("Idempotency-Key", "valid")
                .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/orders/0/refunds")).andExpect(status().isBadRequest());
    }

    @Test
    void actualDatabaseWritesRollBackTogetherAndSameKeyCanRetry() {
        doAnswer(invocation -> {
            RefundAttempt refund = invocation.getArgument(0);
            entityManager.persist(refund);
            entityManager.flush();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_attempts", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class, orderId)).isEqualTo("REFUNDED");
            assertThat(stock()).isEqualTo(10);
            throw new DataIntegrityViolationException("Injected after actual refund, order, and inventory SQL");
        }).when(refundRepository).saveAndFlush(any(RefundAttempt.class));
        assertThrows(DataIntegrityViolationException.class, () -> refunds.refund(ownerId, orderId, "retry", success()));
        assertThat(refundRepository.count()).isZero();
        var order = orders.findById(ownerId, orderId);
        assertThat(order.status()).isEqualTo("PAID");
        assertThat(order.refundedAt()).isNull();
        assertThat(order.cancelledAt()).isNull();
        assertThat(stock()).isEqualTo(8);
        assertThat(payments.history(ownerId, orderId)).containsExactly(originalPayment);
        reset(refundRepository);
        assertThat(refunds.refund(ownerId, orderId, "retry", success()).replayed()).isFalse();
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void stockOverflowDoesNotPartiallyRefundAndCanRetryAfterCorrection() throws Exception {
        jdbc.update("UPDATE products SET stock = ? WHERE id = ?", Integer.MAX_VALUE, productId);
        mvc.perform(refundRequest("retry", "{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STOCK_CAPACITY_EXCEEDED"));
        assertThat(refundRepository.count()).isZero();
        assertThat(orders.findById(ownerId, orderId).status()).isEqualTo("PAID");
        assertThat(orders.findById(ownerId, orderId).refundedAt()).isNull();
        assertThat(stock()).isEqualTo(Integer.MAX_VALUE);
        jdbc.update("UPDATE products SET stock = 8 WHERE id = ?", productId);
        assertThat(refunds.refund(ownerId, orderId, "retry", success()).replayed()).isFalse();
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void missingOrMismatchedPaymentPreventsRefund() {
        jdbc.update("UPDATE payment_attempts SET amount = 1 WHERE id = ?", originalPayment.paymentId());
        assertThat(assertThrows(ApiException.class,
                () -> refunds.refund(ownerId, orderId, "mismatch", success())).getCode()).isEqualTo("PAYMENT_STATE_CONFLICT");
        jdbc.update("DELETE FROM payment_attempts WHERE id = ?", originalPayment.paymentId());
        assertThat(assertThrows(ApiException.class,
                () -> refunds.refund(ownerId, orderId, "missing", success())).getCode()).isEqualTo("PAYMENT_STATE_CONFLICT");
        assertThat(refundRepository.count()).isZero();
        assertThat(orders.findById(ownerId, orderId).status()).isEqualTo("PAID");
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void concurrentIdenticalRefundsRestoreInventoryOnce() throws Exception {
        var results = concurrently(20, i -> refunds.refund(ownerId, orderId, "same", success()));
        assertThat(results.stream().filter(result -> !result.replayed()).count()).isEqualTo(1);
        assertThat(results.stream().map(result -> result.refund().refundId()).distinct().count()).isEqualTo(1);
        assertThat(refundRepository.count()).isEqualTo(1);
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void differentConcurrentKeysCanRefundOnlyOnce() throws Exception {
        var results = concurrently(20, i -> {
            try { refunds.refund(ownerId, orderId, "key-" + i, success()); return true; }
            catch (ApiException exception) {
                assertThat(exception.getCode()).isEqualTo("ORDER_NOT_REFUNDABLE"); return false;
            }
        });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(refundRepository.count()).isEqualTo(1);
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void concurrentRefundCancelAndPaymentKeepOneRestock() throws Exception {
        var results = concurrently(20, i -> {
            try {
                if (i % 3 == 0) { refunds.refund(ownerId, orderId, "refund", success()); return "REFUND"; }
                if (i % 3 == 1) { return orders.cancel(ownerId, orderId).status(); }
                payments.pay(ownerId, orderId, "new-payment", paid()); return "UNEXPECTED_PAYMENT";
            } catch (ApiException exception) { return exception.getCode(); }
        });
        assertThat(results).allMatch(result -> List.of("REFUND", "REFUNDED", "REFUND_REQUIRED", "ORDER_NOT_PAYABLE").contains(result));
        assertThat(orders.findById(ownerId, orderId).status()).isEqualTo("REFUNDED");
        assertThat(refundRepository.count()).isEqualTo(1);
        assertThat(stock()).isEqualTo(10);
        assertThat(payments.history(ownerId, orderId)).containsExactly(originalPayment);
    }

    @Test
    void concurrentRefundsOfDifferentOrdersPreserveSharedProductInventory() throws Exception {
        var second = orders.create(ownerId, new CreateOrderRequest(productId, 3));
        payments.pay(ownerId, second.orderId(), "payment", paid());
        assertThat(stock()).isEqualTo(5);
        concurrently(2, i -> refunds.refund(ownerId, i == 0 ? orderId : second.orderId(), "refund", success()));
        assertThat(stock()).isEqualTo(10);
        assertThat(refundRepository.count()).isEqualTo(2);
    }

    @Test
    void creationKeyReplaysCurrentRefundedOrder() {
        var request = new CreateOrderRequest(productId, 1);
        var created = idempotentOrders.create(ownerId, "create", request).order();
        payments.pay(ownerId, created.orderId(), "pay", paid());
        refunds.refund(ownerId, created.orderId(), "refund", success());
        var replay = idempotentOrders.create(ownerId, "create", request);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.order().status()).isEqualTo("REFUNDED");
        assertThat(replay.order().refundedAt()).isNotNull();
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void databaseRejectsDuplicateSuccessfulRefundAndCrossOrderPayment() {
        refunds.refund(ownerId, orderId, "first", success());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO refund_attempts(order_id, payment_id, request_key, amount, status, created_at)
                VALUES (?, ?, 'duplicate', 30000, 'SUCCEEDED', UTC_TIMESTAMP(6))
                """, orderId, originalPayment.paymentId()));
        var otherOrder = orders.create(ownerId, new CreateOrderRequest(productId, 1));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO refund_attempts(order_id, payment_id, request_key, amount, status, created_at)
                VALUES (?, ?, 'foreign-payment', 30000, 'FAILED', UTC_TIMESTAMP(6))
                """, otherOrder.orderId(), originalPayment.paymentId()));
        assertThat(refundRepository.count()).isEqualTo(1);
    }

    @Test
    void databaseRejectsRefundedStatusWithoutRequiredTimestamps() {
        var exception = assertThrows(UncategorizedSQLException.class,
                () -> jdbc.update("UPDATE purchase_orders SET status = 'REFUNDED', cancelled_at = UTC_TIMESTAMP(6) WHERE id = ?", orderId));
        assertThat(exception.getSQLException().getErrorCode()).isEqualTo(3819);
        assertThat(orders.findById(ownerId, orderId).status()).isEqualTo("PAID");
    }

    @Test
    void zeroAmountPaidOrderCanBeRefundedAndRestocked() {
        long freeProduct = products.saveAndFlush(new Product("무료 상품", 0, 1)).getId();
        var freeOrder = orders.create(ownerId, new CreateOrderRequest(freeProduct, 1));
        payments.pay(ownerId, freeOrder.orderId(), "zero-pay", paid());
        assertThat(refunds.refund(ownerId, freeOrder.orderId(), "zero-refund", success()).refund().amount()).isZero();
        assertThat(products.findById(freeProduct).orElseThrow().getStock()).isEqualTo(1);
    }

    private MockHttpServletRequestBuilder refundRequest(String key, String body) {
        return post("/orders/" + orderId + "/refunds").with(csrf()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
    private MockRefundRequest success() { return new MockRefundRequest(MockRefundRequest.Result.SUCCESS); }
    private MockRefundRequest failure() { return new MockRefundRequest(MockRefundRequest.Result.FAILURE); }
    private MockPaymentRequest paid() { return new MockPaymentRequest(MockPaymentRequest.Result.SUCCESS); }
    private int stock() { return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId); }

    private <T> List<T> concurrently(int count, IntFunction<T> task) throws Exception {
        var executor = Executors.newFixedThreadPool(count);
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Start timeout"); }
                    return task.apply(index);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) { results.add(future.get(20, TimeUnit.SECONDS)); }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
