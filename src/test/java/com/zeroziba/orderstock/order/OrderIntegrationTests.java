package com.zeroziba.orderstock.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithUserDetails(value = "order-test@example.test", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class OrderIntegrationTests {

    private long testUserId;

    @Autowired private ProductRepository productRepository;
    @Autowired private OrderService orderService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;
    @PersistenceContext private EntityManager entityManager;
    @MockitoSpyBean private OrderRepository orderRepository;

    // 테스트를 트랜잭션으로 감싸지 않고 서비스의 실제 커밋/롤백을 검증한다.
    @BeforeEach
    void prepareTestDatabase() {
        cleanTestDatabase();
        testUserId = TestAccounts.create(jdbcTemplate, "order-test@example.test");
    }

    @AfterEach
    void cleanTestDatabase() {
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo("order_stock_test");
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
    void listsOnlyOwnOrdersWithBoundedCursorPages() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("목록 테스트", 1000, 30));
        long otherId = TestAccounts.create(jdbcTemplate, "other-list@example.test");
        orderService.create(otherId, new CreateOrderRequest(product.getId(), 1));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 22; i++) {
            ids.add(orderService.create(testUserId, new CreateOrderRequest(product.getId(), 1)).orderId());
        }
        orderService.create(otherId, new CreateOrderRequest(product.getId(), 1));
        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(20))
                .andExpect(jsonPath("$.orders[0].orderId").value(ids.get(21)))
                .andExpect(jsonPath("$.orders[19].orderId").value(ids.get(2)))
                .andExpect(jsonPath("$.orders[0].items[0].productName").value("목록 테스트"))
                .andExpect(jsonPath("$.nextBeforeId").value(ids.get(2)));
        mockMvc.perform(get("/orders").param("beforeId", ids.get(2).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].orderId").value(ids.get(1)))
                .andExpect(jsonPath("$.orders[1].orderId").value(ids.get(0)))
                .andExpect(jsonPath("$.nextBeforeId").isEmpty());
        assertThat(orderService.findRecent(otherId, null).orders()).hasSize(2);
    }

    @Test
    void emptyOrderPageAndInvalidCursorAreHandled() throws Exception {
        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orders").isEmpty())
                .andExpect(jsonPath("$.nextBeforeId").isEmpty());
        for (String cursor : new String[]{"0", "-1", "abc", "9223372036854775808"}) {
            mockMvc.perform(get("/orders").param("beforeId", cursor)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void createsOrderWithServerPriceAndDecreasesStock() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("주문 테스트 머그컵", 15000, 10));
        var result = mockMvc.perform(post("/orders").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(product.getId(), 2)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.totalAmount").value(30000))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.items[0].productId").value(product.getId()))
                .andExpect(jsonPath("$.items[0].productName").value("주문 테스트 머그컵"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(15000))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andReturn();
        assertThat(stockOf(product.getId())).isEqualTo(8);
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(itemCount()).isEqualTo(1);

        // 상품 가격/이름이 바뀌어도 주문 시점의 값을 유지한다.
        jdbcTemplate.update("UPDATE products SET price = 19000, name = ? WHERE id = ?", "변경된 상품", product.getId());
        mockMvc.perform(get(result.getResponse().getHeader("Location")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(30000))
                .andExpect(jsonPath("$.items[0].unitPrice").value(15000))
                .andExpect(jsonPath("$.items[0].productName").value("주문 테스트 머그컵"));
    }

    @Test
    void rejectsInsufficientStockWithoutSavingOrder() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("재고 부족 상품", 15000, 1));
        mockMvc.perform(post("/orders").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(product.getId(), 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(stockOf(product.getId())).isEqualTo(1);
        assertNoOrders();
    }

    @Test
    void canBuyExactlyRemainingStockThenRejectsAnotherOrder() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("마지막 상품", 5000, 2));
        mockMvc.perform(post("/orders").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(product.getId(), 2)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/orders").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(product.getId(), 1)))
                .andExpect(status().isConflict());
        assertThat(stockOf(product.getId())).isZero();
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"productId\":1}", "{\"quantity\":1}",
            "{\"productId\":null,\"quantity\":1}",
            "{\"productId\":0,\"quantity\":1}",
            "{\"productId\":1,\"quantity\":0}",
            "{\"productId\":1,\"quantity\":-1}",
            "{\"productId\":1,\"quantity\":1.5}",
            "{\"productId\":1,\"quantity\":2147483648}",
            "{\"productId\":1,\"quantity\":\"invalid\"}", "{"
    })
    void rejectsInvalidRequests(String body) throws Exception {
        mockMvc.perform(post("/orders").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertNoOrders();
    }

    @Test
    void returnsNotFoundForMissingProductAndOrder() throws Exception {
        mockMvc.perform(post("/orders").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Long.MAX_VALUE, 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(get("/orders/" + Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(get("/orders/0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/orders/abc")).andExpect(status().isBadRequest());
        assertNoOrders();
    }

    @Test
    void rejectsTotalAmountOverflowWithoutChangingStock() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("고액 상품", Long.MAX_VALUE, 2));
        mockMvc.perform(post("/orders").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(product.getId(), 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_AMOUNT"));
        assertThat(stockOf(product.getId())).isEqualTo(2);
        assertNoOrders();
    }

    @Test
    void rollsBackActualFlushedStockAndOrderWritesWhenStorageFails() {
        Product product = productRepository.saveAndFlush(new Product("롤백 확인 상품", 15000, 10));
        doAnswer(invocation -> {
            // 새 엔티티 저장과 flush를 실제 MySQL에 실행한 다음 저장 실패를 주입한다.
            entityManager.persist(invocation.getArgument(0, PurchaseOrder.class));
            entityManager.flush();
            // 실제 INSERT/UPDATE가 실행된 트랜잭션 안에서 실패를 주입한다.
            assertThat(stockOf(product.getId())).isEqualTo(8);
            assertThat(itemCount()).isEqualTo(1);
            throw new DataIntegrityViolationException("Simulated storage failure after SQL flush");
        }).when(orderRepository).saveAndFlush(any(PurchaseOrder.class));

        assertThrows(DataIntegrityViolationException.class,
                () -> orderService.create(testUserId, new CreateOrderRequest(product.getId(), 2)));
        assertThat(stockOf(product.getId())).isEqualTo(10);
        assertNoOrders();
    }

    @Test
    void twentyConcurrentOrdersSellOnlyTenItems() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("동시 주문 상품", 1000, 10));
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> requests = new ArrayList<>();
        try {
            for (int index = 0; index < 20; index++) {
                requests.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Start timeout"); }
                    try {
                        orderService.create(testUserId, new CreateOrderRequest(product.getId(), 1));
                        return true;
                    } catch (ApiException exception) {
                        assertThat(exception.getCode()).isEqualTo("INSUFFICIENT_STOCK");
                        return false;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successes = 0;
            for (Future<Boolean> request : requests) {
                if (request.get(20, TimeUnit.SECONDS)) { successes++; }
            }
            assertThat(successes).isEqualTo(10);
            assertThat(stockOf(product.getId())).isZero();
            assertThat(orderRepository.count()).isEqualTo(10);
            assertThat(itemCount()).isEqualTo(10);
            assertThat(jdbcTemplate.queryForObject("SELECT SUM(quantity) FROM order_items", Long.class)).isEqualTo(10);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cancellationRestoresStockAndPreservesOrderHistory() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("취소 테스트 상품", 15000, 10));
        OrderResponse order = orderService.create(testUserId, new CreateOrderRequest(product.getId(), 2));
        assertThat(stockOf(product.getId())).isEqualTo(8);

        mockMvc.perform(post("/orders/" + order.orderId() + "/cancel").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order.orderId()))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isString())
                .andExpect(jsonPath("$.totalAmount").value(30000))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
        mockMvc.perform(get("/orders/" + order.orderId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isString());
        assertThat(stockOf(product.getId())).isEqualTo(10);
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(itemCount()).isEqualTo(1);
    }

    @Test
    void repeatingCancellationKeepsOriginalTimestampAndRestoresStockOnlyOnce() {
        Product product = productRepository.saveAndFlush(new Product("중복 취소 상품", 5000, 10));
        OrderResponse order = orderService.create(testUserId, new CreateOrderRequest(product.getId(), 3));
        OrderResponse first = orderService.cancel(testUserId, order.orderId());
        OrderResponse second = orderService.cancel(testUserId, order.orderId());
        assertThat(first.status()).isEqualTo("CANCELLED");
        assertThat(second).isEqualTo(first);
        assertThat(orderService.findById(testUserId, order.orderId())).isEqualTo(first);
        assertThat(stockOf(product.getId())).isEqualTo(10);
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void cancellationRejectsMissingAndInvalidOrderIds() throws Exception {
        mockMvc.perform(post("/orders/" + Long.MAX_VALUE + "/cancel").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(post("/orders/0/cancel").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/orders/abc/cancel").with(csrf())).andExpect(status().isBadRequest());
        assertNoOrders();
    }

    @Test
    void failedCancellationRollsBackActualStockAndStatusWrites() {
        Product product = productRepository.saveAndFlush(new Product("취소 롤백 상품", 5000, 10));
        OrderResponse order = orderService.create(testUserId, new CreateOrderRequest(product.getId(), 2));
        doAnswer(invocation -> {
            entityManager.flush();
            assertThat(stockOf(product.getId())).isEqualTo(10);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM purchase_orders WHERE id = ?",
                    String.class, order.orderId())).isEqualTo("CANCELLED");
            throw new DataIntegrityViolationException("Simulated storage failure after cancellation flush");
        }).when(orderRepository).flush();

        assertThrows(DataIntegrityViolationException.class, () -> orderService.cancel(testUserId, order.orderId()));
        OrderResponse unchanged = orderService.findById(testUserId, order.orderId());
        assertThat(unchanged.status()).isEqualTo("CREATED");
        assertThat(unchanged.cancelledAt()).isNull();
        assertThat(stockOf(product.getId())).isEqualTo(8);
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(itemCount()).isEqualTo(1);
    }

    @Test
    void twentyConcurrentCancellationsRestoreStockOnlyOnce() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("동시 중복 취소 상품", 5000, 10));
        OrderResponse order = orderService.create(testUserId, new CreateOrderRequest(product.getId(), 4));
        List<OrderResponse> results = runConcurrently(20, index -> orderService.cancel(testUserId, order.orderId()));
        assertThat(results).allMatch(result -> result.status().equals("CANCELLED"));
        assertThat(results.stream().map(OrderResponse::cancelledAt).distinct().count()).isEqualTo(1);
        assertThat(stockOf(product.getId())).isEqualTo(10);
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentCancellationsOfDifferentOrdersDoNotLoseRestockedItems() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("여러 주문 취소 상품", 5000, 10));
        List<Long> orderIds = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            orderIds.add(orderService.create(testUserId, new CreateOrderRequest(product.getId(), 2)).orderId());
        }
        assertThat(stockOf(product.getId())).isZero();
        List<OrderResponse> results = runConcurrently(5, index -> orderService.cancel(testUserId, orderIds.get(index)));
        assertThat(results).allMatch(result -> result.status().equals("CANCELLED"));
        assertThat(stockOf(product.getId())).isEqualTo(10);
        assertThat(orderRepository.count()).isEqualTo(5);
    }

    @Test
    void concurrentOrderingAndCancellationPreserveInventoryBalance() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("주문과 취소 혼합 상품", 1000, 10));
        OrderResponse original = orderService.create(testUserId, new CreateOrderRequest(product.getId(), 4));
        List<Boolean> results = runConcurrently(20, index -> {
            if (index % 2 == 0) {
                orderService.cancel(testUserId, original.orderId());
                return false;
            }
            try {
                orderService.create(testUserId, new CreateOrderRequest(product.getId(), 1));
                return true;
            } catch (ApiException exception) {
                assertThat(exception.getCode()).isEqualTo("INSUFFICIENT_STOCK");
                return false;
            }
        });
        long successes = results.stream().filter(Boolean::booleanValue).count();
        assertThat(successes).isBetween(6L, 10L);
        assertThat(stockOf(product.getId())).isEqualTo((int) (10 - successes));
        assertThat(orderService.findById(testUserId, original.orderId()).status()).isEqualTo("CANCELLED");
        assertThat(orderRepository.count()).isEqualTo(successes + 1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(i.quantity), 0) FROM order_items i
                JOIN purchase_orders o ON o.id = i.order_id WHERE o.status = 'CREATED'
                """, Long.class)).isEqualTo(successes);
    }

    @Test
    void stockOverflowRejectsCancellationWithoutChangingOrder() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("재고 범위 상품", 1000, 1));
        OrderResponse order = orderService.create(testUserId, new CreateOrderRequest(product.getId(), 1));
        // 외부 재입고로 재고가 저장 범위 끝까지 찬 경우에도 취소를 부분 반영하지 않는다.
        jdbcTemplate.update("UPDATE products SET stock = ? WHERE id = ?", Integer.MAX_VALUE, product.getId());
        mockMvc.perform(post("/orders/" + order.orderId() + "/cancel").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_CAPACITY_EXCEEDED"));
        assertThat(stockOf(product.getId())).isEqualTo(Integer.MAX_VALUE);
        assertThat(orderService.findById(testUserId, order.orderId()).status()).isEqualTo("CREATED");
        assertThat(orderService.findById(testUserId, order.orderId()).cancelledAt()).isNull();
    }

    @Test
    void databaseRejectsCancelledStatusWithoutCancellationTimestamp() {
        Product product = productRepository.saveAndFlush(new Product("상태 제약 상품", 1000, 1));
        OrderResponse order = orderService.create(testUserId, new CreateOrderRequest(product.getId(), 1));
        UncategorizedSQLException exception = assertThrows(UncategorizedSQLException.class,
                () -> jdbcTemplate.update("UPDATE purchase_orders SET status = 'CANCELLED' WHERE id = ?", order.orderId()));
        assertThat(exception.getSQLException().getErrorCode()).isEqualTo(3819);
        assertThat(exception.getSQLException().getMessage()).contains("chk_orders_cancellation");
        assertThat(orderService.findById(testUserId, order.orderId()).status()).isEqualTo("CREATED");
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

    private long itemCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_items", Long.class);
    }

    private void assertNoOrders() {
        assertThat(orderRepository.count()).isZero();
        assertThat(itemCount()).isZero();
    }
}
