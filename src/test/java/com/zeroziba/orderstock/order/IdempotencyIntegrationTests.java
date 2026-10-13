package com.zeroziba.orderstock.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.TestAccounts;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.security.test.context.support.TestExecutionEvent;
import com.zeroziba.orderstock.product.Product;
import com.zeroziba.orderstock.product.ProductRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithUserDetails(value = "order-test@example.test", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class IdempotencyIntegrationTests {

    private long testUserId;

    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderService orderService;
    @Autowired private IdempotentOrderService idempotentOrderService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;
    @MockitoSpyBean private OrderRequestRepository requestRepository;

    @BeforeEach
    void prepareTestDatabase() {
        cleanTestDatabase();
        testUserId = TestAccounts.create(jdbcTemplate, "order-test@example.test");
    }

    @AfterEach
    void cleanTestDatabase() {
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("order_stock_test");
        com.zeroziba.orderstock.TestDatabase.cleanInventory(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM refund_attempts");
        jdbcTemplate.update("DELETE FROM payment_attempts");
        jdbcTemplate.update("DELETE FROM order_requests");
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM purchase_orders");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("DELETE FROM app_users");
    }

    @Test
    void repeatedHttpRequestReturnsSameOrderAndDoesNotDecreaseStockAgain() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("재시도 상품", 15000, 10));
        String body = requestBody(product.getId(), 2);
        var first = mockMvc.perform(post("/orders").with(csrf()).header("Idempotency-Key", "retry-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andReturn().getResponse();
        var second = mockMvc.perform(post("/orders").with(csrf()).header("Idempotency-Key", "retry-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(header().string("Location", first.getHeader("Location")))
                .andReturn().getResponse();
        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(stockOf(product.getId())).isEqualTo(8);
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentProductOrQuantityIsRejected() throws Exception {
        Product firstProduct = productRepository.saveAndFlush(new Product("첫 상품", 1000, 10));
        Product secondProduct = productRepository.saveAndFlush(new Product("다른 상품", 2000, 10));
        idempotentOrderService.create(testUserId, "conflict-key", new CreateOrderRequest(firstProduct.getId(), 2));
        for (String body : List.of(requestBody(firstProduct.getId(), 3), requestBody(secondProduct.getId(), 2))) {
            mockMvc.perform(post("/orders").with(csrf()).header("Idempotency-Key", "conflict-key")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        }
        assertThat(stockOf(firstProduct.getId())).isEqualTo(8);
        assertThat(stockOf(secondProduct.getId())).isEqualTo(10);
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    void differentCaseSensitiveKeysCreateSeparateOrders() {
        Product product = productRepository.saveAndFlush(new Product("키 구분 상품", 1000, 10));
        var first = idempotentOrderService.create(testUserId, "Case-Key", new CreateOrderRequest(product.getId(), 2));
        var second = idempotentOrderService.create(testUserId, "case-key", new CreateOrderRequest(product.getId(), 2));
        assertThat(first.order().orderId()).isNotEqualTo(second.order().orderId());
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isFalse();
        assertThat(stockOf(product.getId())).isEqualTo(6);
        assertThat(requestCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " leading", "trailing ", "has space", "한글키", "bad/key", "_bad", "bad,key"})
    void invalidKeysAreRejectedBeforeAnyWrites(String key) throws Exception {
        Product product = productRepository.saveAndFlush(new Product("키 검증 상품", 1000, 10));
        mockMvc.perform(post("/orders").with(csrf()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody(product.getId(), 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        assertThat(stockOf(product.getId())).isEqualTo(10);
        assertThat(orderRepository.count()).isZero();
        assertThat(requestCount()).isZero();
    }

    @Test
    void keyLengthBoundaryIsEnforced() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("키 길이 상품", 1000, 10));
        String body = requestBody(product.getId(), 1);
        mockMvc.perform(post("/orders").with(csrf()).header("Idempotency-Key", "a".repeat(65))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/orders").with(csrf()).header("Idempotency-Key", "a".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        assertThat(stockOf(product.getId())).isEqualTo(9);
        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    void requestsWithoutKeyKeepCreatingSeparateOrders() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("키 없는 상품", 1000, 10));
        for (int index = 0; index < 2; index++) {
            mockMvc.perform(post("/orders").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody(product.getId(), 2)))
                    .andExpect(status().isCreated());
        }
        assertThat(stockOf(product.getId())).isEqualTo(6);
        assertThat(orderRepository.count()).isEqualTo(2);
        assertThat(requestCount()).isZero();
    }

    @Test
    void insufficientStockRollsBackKeyAndAllowsLaterRetry() {
        Product product = productRepository.saveAndFlush(new Product("실패 재시도 상품", 1000, 1));
        CreateOrderRequest request = new CreateOrderRequest(product.getId(), 2);
        ApiException exception = assertThrows(ApiException.class,
                () -> idempotentOrderService.create(testUserId, "retry-after-restock", request));
        assertThat(exception.getCode()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(requestCount()).isZero();
        assertThat(orderRepository.count()).isZero();
        jdbcTemplate.update("UPDATE products SET stock = 3 WHERE id = ?", product.getId());
        assertThat(idempotentOrderService.create(testUserId, "retry-after-restock", request).replayed()).isFalse();
        assertThat(stockOf(product.getId())).isEqualTo(1);
        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    void completionFailureRollsBackKeyOrderAndStockTogether() {
        Product product = productRepository.saveAndFlush(new Product("키 저장 롤백 상품", 1000, 10));
        CreateOrderRequest request = new CreateOrderRequest(product.getId(), 2);
        doAnswer(invocation -> {
            String key = invocation.getArgument(1);
            long orderId = invocation.getArgument(2);
            jdbcTemplate.update("UPDATE order_requests SET order_id = ? WHERE user_id = ? AND request_key = ?", orderId, testUserId, key);
            assertThat(stockOf(product.getId())).isEqualTo(8);
            assertThat(requestCount()).isEqualTo(1);
            assertThat(orderRepository.count()).isEqualTo(1);
            throw new DataIntegrityViolationException("Simulated key completion failure after SQL writes");
        }).when(requestRepository).complete(anyLong(), anyString(), anyLong());

        assertThrows(DataIntegrityViolationException.class,
                () -> idempotentOrderService.create(testUserId, "rollback-key", request));
        assertThat(stockOf(product.getId())).isEqualTo(10);
        assertThat(orderRepository.count()).isZero();
        assertThat(requestCount()).isZero();
        reset(requestRepository);
        assertThat(idempotentOrderService.create(testUserId, "rollback-key", request).replayed()).isFalse();
        assertThat(stockOf(product.getId())).isEqualTo(8);
    }

    @Test
    void twentyConcurrentRequestsWithSameKeyCreateExactlyOneOrder() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("동시 중복 상품", 1000, 10));
        List<IdempotentOrderService.CreationResult> results = runConcurrently(20,
                index -> idempotentOrderService.create(testUserId, "one-order-key", new CreateOrderRequest(product.getId(), 2)));
        assertThat(results.stream().filter(result -> !result.replayed()).count()).isEqualTo(1);
        assertThat(results.stream().map(result -> result.order().orderId()).distinct().count()).isEqualTo(1);
        assertThat(stockOf(product.getId())).isEqualTo(8);
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    void concurrentDifferentPayloadsUsingSameKeyHaveOneWinner() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("키 충돌 동시 상품", 1000, 10));
        List<IdempotentOrderService.CreationResult> results = runConcurrently(2, index -> {
            try {
                return idempotentOrderService.create(testUserId, "racing-key", new CreateOrderRequest(product.getId(), index + 1));
            } catch (ApiException exception) {
                assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                return null;
            }
        });
        var winners = results.stream().filter(result -> result != null).toList();
        assertThat(winners).hasSize(1);
        assertThat(stockOf(product.getId())).isEqualTo(10 - winners.get(0).order().items().get(0).quantity());
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    void concurrentDifferentKeysPreserveStockAndOnlySuccessfulKeys() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("서로 다른 키 상품", 1000, 10));
        List<Boolean> results = runConcurrently(20, index -> {
            try {
                idempotentOrderService.create(testUserId, "unique-key-" + index, new CreateOrderRequest(product.getId(), 1));
                return true;
            } catch (ApiException exception) {
                assertThat(exception.getCode()).isEqualTo("INSUFFICIENT_STOCK");
                return false;
            }
        });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(10);
        assertThat(stockOf(product.getId())).isZero();
        assertThat(orderRepository.count()).isEqualTo(10);
        assertThat(requestCount()).isEqualTo(10);
    }

    @Test
    void replayAfterCancellationReturnsCancelledOrderWithoutReservingStockAgain() {
        Product product = productRepository.saveAndFlush(new Product("취소 후 재시도 상품", 15000, 10));
        CreateOrderRequest request = new CreateOrderRequest(product.getId(), 2);
        var first = idempotentOrderService.create(testUserId, "cancelled-key", request);
        OrderResponse cancelled = orderService.cancel(testUserId, first.order().orderId());
        var replay = idempotentOrderService.create(testUserId, "cancelled-key", request);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.order()).isEqualTo(cancelled);
        assertThat(stockOf(product.getId())).isEqualTo(10);
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void replayPreservesOrderPriceWhenProductPriceChanges() {
        Product product = productRepository.saveAndFlush(new Product("가격 유지 상품", 15000, 10));
        CreateOrderRequest request = new CreateOrderRequest(product.getId(), 2);
        var original = idempotentOrderService.create(testUserId, "price-key", request);
        jdbcTemplate.update("UPDATE products SET price = 19000 WHERE id = ?", product.getId());
        var replay = idempotentOrderService.create(testUserId, "price-key", request);
        assertThat(replay.order()).isEqualTo(original.order());
        assertThat(replay.order().totalAmount()).isEqualTo(30000);
        assertThat(stockOf(product.getId())).isEqualTo(8);
    }

    private <T> List<T> runConcurrently(int count, IntFunction<T> request) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                final int requestIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Start timeout"); }
                    return request.apply(requestIndex);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) { results.add(future.get(20, TimeUnit.SECONDS)); }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String requestBody(long productId, int quantity) {
        return "{\"productId\":" + productId + ",\"quantity\":" + quantity + "}";
    }

    private int stockOf(long productId) {
        return jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    private long requestCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_requests", Long.class);
    }
}
