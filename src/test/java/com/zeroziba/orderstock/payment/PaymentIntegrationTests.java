package com.zeroziba.orderstock.payment;

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
@WithUserDetails(value = "payment@example.test", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class PaymentIntegrationTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ProductRepository products;
    @Autowired private OrderService orders;
    @Autowired private IdempotentOrderService idempotentOrders;
    @Autowired private PaymentService payments;
    @MockitoSpyBean private PaymentRepository paymentRepository;
    @PersistenceContext private EntityManager entityManager;
    private long ownerId;
    private long otherId;
    private long productId;
    private long orderId;

    @BeforeEach
    void prepare() {
        clean();
        ownerId = TestAccounts.create(jdbc, "payment@example.test");
        otherId = TestAccounts.create(jdbc, "other.payment@example.test");
        productId = products.saveAndFlush(new Product("결제 테스트", 15000, 10)).getId();
        orderId = orders.create(ownerId, new CreateOrderRequest(productId, 2)).orderId();
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
    void successUsesOrderAmountAndPreservesInventory() throws Exception {
        jdbc.update("UPDATE products SET price = 1 WHERE id = ?", productId);
        mvc.perform(payRequest("success", "{\"result\":\"SUCCESS\",\"amount\":1,\"userId\":" + otherId + "}"))
                .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(header().string("Location", "/orders/" + orderId + "/payments"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.amount").value(30000)).andExpect(jsonPath("$.createdAt").isString());
        var order = orders.findById(ownerId, orderId);
        assertThat(order.status()).isEqualTo("PAID");
        assertThat(order.paidAt()).isNotNull();
        assertThat(order.cancelledAt()).isNull();
        assertThat(payments.history(ownerId, orderId).get(0).createdAt()).isEqualTo(order.paidAt());
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void declinedAttemptCommitsAndNewKeyCanSucceed() throws Exception {
        mvc.perform(payRequest("declined", "{\"result\":\"FAILURE\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(orders.findById(ownerId, orderId).status()).isEqualTo("CREATED");
        assertThat(orders.findById(ownerId, orderId).paidAt()).isNull();
        mvc.perform(payRequest("retry", "{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        mvc.perform(get("/orders/" + orderId + "/payments"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[1].status").value("SUCCEEDED"));
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void identicalRequestReplaysImmutableResult() throws Exception {
        var first = payments.pay(ownerId, orderId, "same", success()).payment();
        mvc.perform(payRequest("same", "{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.paymentId").value(first.paymentId()));
        assertThat(payments.pay(ownerId, orderId, "same", success()).payment()).isEqualTo(first);
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void declinedReplayStillReturnsFailureAfterOrderCancellation() {
        var first = payments.pay(ownerId, orderId, "declined", failure()).payment();
        orders.cancel(ownerId, orderId);
        var replay = payments.pay(ownerId, orderId, "declined", failure());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.payment()).isEqualTo(first);
        assertThat(stock()).isEqualTo(10);
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    void keyCannotChangeSelectedResult() throws Exception {
        payments.pay(ownerId, orderId, "same", failure());
        mvc.perform(payRequest("same", "{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(orders.findById(ownerId, orderId).status()).isEqualTo("CREATED");
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    void paidOrderRejectsAnotherKeyAndCancellation() throws Exception {
        payments.pay(ownerId, orderId, "first", success());
        mvc.perform(payRequest("second", "{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"));
        mvc.perform(post("/orders/" + orderId + "/cancel").with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_REQUIRED"));
        assertThat(stock()).isEqualTo(8);
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(orders.findById(ownerId, orderId).status()).isEqualTo("PAID");
    }

    @Test
    void cancelledOrderRejectsNewPayment() throws Exception {
        orders.cancel(ownerId, orderId);
        mvc.perform(payRequest("late", "{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"));
        assertThat(stock()).isEqualTo(10);
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void requestKeysAreScopedToOrderAndCaseSensitive() {
        payments.pay(ownerId, orderId, "Case", failure());
        assertThat(payments.pay(ownerId, orderId, "case", failure()).replayed()).isFalse();
        var second = orders.create(ownerId, new CreateOrderRequest(productId, 1));
        assertThat(payments.pay(ownerId, second.orderId(), "Case", success()).replayed()).isFalse();
        assertThat(paymentRepository.count()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad key", "한글", "-prefix", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void rejectsInvalidKey(String key) throws Exception {
        mvc.perform(payRequest(key, "{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void paymentRequiresKey() throws Exception {
        mvc.perform(post("/orders/" + orderId + "/payments").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"result\":null}", "{\"result\":\"UNKNOWN\"}", "{\"result\":0}", "{\"result\":true}", "{"})
    void rejectsInvalidResult(String body) throws Exception {
        mvc.perform(payRequest("valid", body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void requiresSessionAndCsrf() throws Exception {
        mvc.perform(payRequest("valid", "{\"result\":\"SUCCESS\"}").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/orders/" + orderId + "/payments").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/orders/" + orderId + "/payments").header("Idempotency-Key", "valid")
                .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isForbidden());
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void anotherUserCannotPayReadOrReplay() throws Exception {
        payments.pay(ownerId, orderId, "shared", failure());
        var foreign = new AuthenticatedUser(otherId, "other.payment@example.test", null);
        mvc.perform(payRequest("shared", "{\"result\":\"FAILURE\"}").with(user(foreign)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mvc.perform(get("/orders/" + orderId + "/payments").with(user(foreign)))
                .andExpect(status().isNotFound());
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    void missingOrderReturns404AndEmptyHistoryReturnsArray() throws Exception {
        mvc.perform(get("/orders/" + orderId + "/payments")).andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mvc.perform(post("/orders/9223372036854775807/payments").with(csrf()).header("Idempotency-Key", "valid")
                .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/orders/0/payments")).andExpect(status().isBadRequest());
    }

    @Test
    void databaseFailureRollsBackWrittenPaymentAndOrderThenSameKeyCanRetry() {
        doAnswer(invocation -> {
            PaymentAttempt payment = invocation.getArgument(0);
            entityManager.persist(payment);
            entityManager.flush();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class, orderId)).isEqualTo("PAID");
            throw new DataIntegrityViolationException("Injected after actual payment and status SQL");
        }).when(paymentRepository).saveAndFlush(any(PaymentAttempt.class));
        assertThrows(DataIntegrityViolationException.class, () -> payments.pay(ownerId, orderId, "retry", success()));
        assertThat(paymentRepository.count()).isZero();
        assertThat(orders.findById(ownerId, orderId).status()).isEqualTo("CREATED");
        assertThat(orders.findById(ownerId, orderId).paidAt()).isNull();
        assertThat(stock()).isEqualTo(8);
        reset(paymentRepository);
        assertThat(payments.pay(ownerId, orderId, "retry", success()).replayed()).isFalse();
    }

    @Test
    void concurrentIdenticalPaymentsCreateOneAttempt() throws Exception {
        var results = concurrently(20, i -> payments.pay(ownerId, orderId, "same", success()));
        assertThat(results.stream().filter(result -> !result.replayed()).count()).isEqualTo(1);
        assertThat(results.stream().map(result -> result.payment().paymentId()).distinct().count()).isEqualTo(1);
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void concurrentDifferentKeysCanOnlyPayOnce() throws Exception {
        var results = concurrently(20, i -> {
            try { payments.pay(ownerId, orderId, "key-" + i, success()); return true; }
            catch (ApiException exception) {
                assertThat(exception.getCode()).isEqualTo("ORDER_NOT_PAYABLE"); return false;
            }
        });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void concurrentPaymentAndCancellationHaveOneConsistentWinner() throws Exception {
        // 여러 번 경쟁시켜 최종 상태·결제 이력·재고의 조합을 검증한다.
        for (int round = 0; round < 8; round++) {
            long raceOrderId = orders.create(ownerId, new CreateOrderRequest(productId, 1)).orderId();
            int before = stock();
            var results = concurrently(2, i -> {
                try {
                    if (i == 0) { payments.pay(ownerId, raceOrderId, "race", success()); return "PAID"; }
                    orders.cancel(ownerId, raceOrderId); return "CANCELLED";
                } catch (ApiException exception) { return exception.getCode(); }
            });
            var order = orders.findById(ownerId, raceOrderId);
            if (order.status().equals("PAID")) {
                assertThat(results).containsExactlyInAnyOrder("PAID", "REFUND_REQUIRED");
                assertThat(payments.history(ownerId, raceOrderId)).hasSize(1);
                assertThat(stock()).isEqualTo(before);
            } else {
                assertThat(order.status()).isEqualTo("CANCELLED");
                assertThat(results).containsExactlyInAnyOrder("ORDER_NOT_PAYABLE", "CANCELLED");
                assertThat(payments.history(ownerId, raceOrderId)).isEmpty();
                assertThat(stock()).isEqualTo(before + 1);
            }
        }
    }

    @Test
    void creationKeyReplaysCurrentPaidOrder() {
        var request = new CreateOrderRequest(productId, 1);
        var created = idempotentOrders.create(ownerId, "create", request).order();
        payments.pay(ownerId, created.orderId(), "pay", success());
        var replay = idempotentOrders.create(ownerId, "create", request);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.order().status()).isEqualTo("PAID");
        assertThat(replay.order().paidAt()).isNotNull();
        assertThat(stock()).isEqualTo(7);
    }

    @Test
    void databaseAlsoRejectsSecondSuccessfulPayment() {
        payments.pay(ownerId, orderId, "first", success());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO payment_attempts(order_id, request_key, amount, status, created_at)
                VALUES (?, 'other', 30000, 'SUCCEEDED', UTC_TIMESTAMP(6))
                """, orderId));
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    private MockHttpServletRequestBuilder payRequest(String key, String body) {
        return post("/orders/" + orderId + "/payments").with(csrf()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
    private MockPaymentRequest success() { return new MockPaymentRequest(MockPaymentRequest.Result.SUCCESS); }
    private MockPaymentRequest failure() { return new MockPaymentRequest(MockPaymentRequest.Result.FAILURE); }
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
