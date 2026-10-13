package com.zeroziba.orderstock.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.zeroziba.orderstock.TestAccounts;
import com.zeroziba.orderstock.auth.AuthenticatedUser;
import com.zeroziba.orderstock.auth.UserRole;
import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.order.CreateOrderRequest;
import com.zeroziba.orderstock.order.OrderService;
import com.zeroziba.orderstock.product.Product;
import com.zeroziba.orderstock.product.ProductRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class InventoryIntegrationTests {
    @Autowired InventoryService inventory;
    @Autowired ProductRepository products;
    @Autowired OrderService orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean InventoryRepository repository;
    private long admin;
    private long otherAdmin;
    private long customer;
    private long product;

    @BeforeEach void prepare() {
        clean();
        admin = TestAccounts.create(jdbc, "admin@example.test");
        otherAdmin = TestAccounts.create(jdbc, "other-admin@example.test");
        customer = TestAccounts.create(jdbc, "customer@example.test");
        jdbc.update("UPDATE app_users SET user_role='ADMIN' WHERE id IN (?, ?)", admin, otherAdmin);
        product = products.saveAndFlush(new Product("재고 관리 머그컵", 15000, 10)).getId();
    }

    @AfterEach void clean() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("order_stock_test");
        com.zeroziba.orderstock.TestDatabase.cleanInventory(jdbc);
        for (String table : List.of("inventory_adjustment_requests", "inventory_adjustments", "refund_attempts",
                "payment_attempts", "order_requests", "order_items", "purchase_orders", "products", "app_users")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test void realLoginLoadsAdminRoleAndOrdinaryRegistrationCannotGrantIt() throws Exception {
        mvc.perform(post("/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"no-promotion@example.test\",\"password\":\"TestPass123!\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("USER"));
        var session = login("admin@example.test");
        mvc.perform(get("/auth/me").session(session)).andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(get("/admin.html").session(session)).andExpect(status().isOk());
        mvc.perform(get("/admin/inventory-adjustments").session(session)).andExpect(status().isOk());
    }

    @Test void guestsAndCustomersCannotReadWriteOrOpenAdminPage() throws Exception {
        mvc.perform(get("/admin/inventory-adjustments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin.html")).andExpect(status().isUnauthorized());
        mvc.perform(write(1, "key", 1, "입고").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/inventory-adjustments").with(user(principal(customer, UserRole.USER))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin.html").with(user(principal(customer, UserRole.USER))))
                .andExpect(status().isForbidden());
        mvc.perform(write(product, "key", 1, "입고").with(user(principal(customer, UserRole.USER))).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(stock()).isEqualTo(10);
        assertThat(historyCount()).isZero();
    }

    @Test void adminWritesStillRequireCsrf() throws Exception {
        mvc.perform(write(product, "no-csrf", 1, "입고").with(user(principal(admin, UserRole.ADMIN))))
                .andExpect(status().isForbidden());
        assertThat(historyCount()).isZero();
    }

    @Test void revokedAdminSessionAndDirectServiceCallsAreRejected() throws Exception {
        var session = login("admin@example.test");
        jdbc.update("UPDATE app_users SET user_role='USER' WHERE id=?", admin);
        mvc.perform(get("/admin/inventory-adjustments").session(session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        mvc.perform(write(product, "revoked", 1, "입고").session(session).with(csrf()))
                .andExpect(status().isForbidden());
        assertThrows(ApiException.class, () -> inventory.adjust(customer, product, "service", request(1)));
        assertThat(stock()).isEqualTo(10);
    }

    @Test void adjustmentCapturesActorStocksReasonNameAndUtcTimestamp() {
        Instant before = Instant.now().minusSeconds(1);
        var created = inventory.adjust(admin, product, "inbound", new AdjustInventoryRequest(5, "  추가 입고  "));
        assertThat(created.replayed()).isFalse();
        var row = created.adjustment();
        assertThat(row.adminId()).isEqualTo(admin);
        assertThat(row.productId()).isEqualTo(product);
        assertThat(row.productName()).isEqualTo("재고 관리 머그컵");
        assertThat(row.beforeStock()).isEqualTo(10);
        assertThat(row.afterStock()).isEqualTo(15);
        assertThat(row.reason()).isEqualTo("추가 입고");
        assertThat(row.createdAt()).isBetween(before, Instant.now().plusSeconds(1));
        assertThat(inventory.adjust(admin, product, "damage", new AdjustInventoryRequest(-3, "파손 폐기"))
                .adjustment().afterStock()).isEqualTo(12);
        assertThat(stock()).isEqualTo(12);
    }

    @Test void replayReturnsOriginalSnapshotEvenAfterLaterChanges() throws Exception {
        var original = inventory.adjust(admin, product, "once", request(5)).adjustment();
        inventory.adjust(admin, product, "later", request(-2));
        mvc.perform(write(product, "once", 5, "입고").with(user(principal(admin, UserRole.ADMIN))).with(csrf()))
                .andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.adjustmentId").value(original.adjustmentId()))
                .andExpect(jsonPath("$.afterStock").value(15));
        assertThat(stock()).isEqualTo(13);
        assertThat(historyCount()).isEqualTo(2);
    }

    @Test void changedPayloadCannotReuseKeyButDifferentAdminsHaveIndependentKeys() {
        inventory.adjust(admin, product, "shared", request(1));
        var conflict = assertThrows(ApiException.class,
                () -> inventory.adjust(admin, product, "shared", request(2)));
        assertThat(conflict.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThrows(ApiException.class,
                () -> inventory.adjust(admin, product, "shared", new AdjustInventoryRequest(1, "다른 사유")));
        long otherProduct = products.saveAndFlush(new Product("다른 상품", 100, 10)).getId();
        assertThrows(ApiException.class, () -> inventory.adjust(admin, otherProduct, "shared", request(1)));
        inventory.adjust(otherAdmin, product, "shared", request(1));
        assertThat(stock()).isEqualTo(12);
        assertThat(historyCount()).isEqualTo(2);
    }

    @Test void concurrentSameKeyChangesStockAndWritesHistoryOnce() throws Exception {
        runConcurrent(12, () -> inventory.adjust(admin, product, "concurrent", request(7)));
        assertThat(stock()).isEqualTo(17);
        assertThat(historyCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_adjustment_requests", Long.class)).isEqualTo(1);
    }

    @Test void stockAdjustmentAndOrdersShareTheSameProductLock() throws Exception {
        var pool = Executors.newFixedThreadPool(12);
        var start = new CountDownLatch(1);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final int n = i;
                tasks.add(() -> { start.await();
                    if (n % 2 == 0) inventory.adjust(admin, product, "add-" + n, request(1));
                    else orders.create(customer, new CreateOrderRequest(product, 1));
                    return null;
                });
            }
            var futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
            assertThat(stock()).isEqualTo(10);
            assertThat(historyCount()).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isEqualTo(10);
        } finally { pool.shutdownNow(); }
    }

    @Test void auditFailureAfterRealStockFlushRollsBackStockAndKey() {
        doAnswer(call -> {
            assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product)).isEqualTo(15);
            throw new DataIntegrityViolationException("Forced audit storage failure after stock flush");
        })
                .when(repository).insert(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        assertThrows(DataIntegrityViolationException.class, () -> inventory.adjust(admin, product, "rollback", request(5)));
        assertThat(stock()).isEqualTo(10);
        assertThat(historyCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_adjustment_requests", Long.class)).isZero();
    }

    @Test void outerRollbackRemovesFlushedStockHistoryAndReservation() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            inventory.adjust(admin, product, "outer-rollback", request(5));
            assertThat(historyCount()).isEqualTo(1);
            tx.setRollbackOnly();
        });
        assertThat(stock()).isEqualTo(10);
        assertThat(historyCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_adjustment_requests", Long.class)).isZero();
    }

    @Test void underflowOverflowAndMissingProductLeaveNoAuditOrReservation() {
        assertThrows(ApiException.class, () -> inventory.adjust(admin, product, "low", request(-11)));
        jdbc.update("UPDATE products SET stock=? WHERE id=?", Integer.MAX_VALUE, product);
        assertThrows(ApiException.class, () -> inventory.adjust(admin, product, "high", request(1)));
        assertThrows(ApiException.class, () -> inventory.adjust(admin, Long.MAX_VALUE, "missing", request(1)));
        assertThat(stock()).isEqualTo(Integer.MAX_VALUE);
        assertThat(historyCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_adjustment_requests", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"delta\":0,\"reason\":\"입고\"}", "{\"delta\":1.5,\"reason\":\"입고\"}",
            "{\"delta\":1,\"reason\":\" \"}", "{\"delta\":2147483648,\"reason\":\"입고\"}"})
    void invalidPayloadCannotChangeInventory(String body) throws Exception {
        mvc.perform(post(path(product)).header("Idempotency-Key", "invalid").with(user(principal(admin, UserRole.ADMIN)))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(stock()).isEqualTo(10);
        assertThat(historyCount()).isZero();
    }

    @Test void keyAndReasonLengthAndHistoryCursorAreValidated() throws Exception {
        mvc.perform(write(product, "invalid key", 1, "입고").with(user(principal(admin, UserRole.ADMIN))).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(write(product, null, 1, "입고").with(user(principal(admin, UserRole.ADMIN))).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(write(product, "long", 1, "가".repeat(201)).with(user(principal(admin, UserRole.ADMIN))).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/inventory-adjustments?beforeId=0").with(user(principal(admin, UserRole.ADMIN))))
                .andExpect(status().isBadRequest());
    }

    @Test void historyUsesBoundedCursorPaginationAndOptionalProductFilter() {
        for (int i = 0; i < 23; i++) inventory.adjust(admin, product, "page-" + i, request(1));
        var first = inventory.history(admin, product, null);
        var second = inventory.history(admin, product, first.nextBeforeId());
        assertThat(first.adjustments()).hasSize(20);
        assertThat(second.adjustments()).hasSize(3);
        assertThat(second.nextBeforeId()).isNull();
        assertThat(second.adjustments()).noneMatch(first.adjustments()::contains);
        assertThat(inventory.history(admin, Long.MAX_VALUE, null).adjustments()).isEmpty();
    }

    private int stock() { return products.findById(product).orElseThrow().getStock(); }
    private long historyCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_adjustments", Long.class); }
    private AdjustInventoryRequest request(int delta) { return new AdjustInventoryRequest(delta, "입고"); }
    private AuthenticatedUser principal(long id, UserRole role) { return new AuthenticatedUser(id, "user@example.test", null, role); }
    private String path(long id) { return "/admin/products/" + id + "/inventory-adjustments"; }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder write(long id, String key, int delta, String reason) {
        var request = post(path(id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"delta\":" + delta + ",\"reason\":\"" + reason + "\"}");
        if (key != null) request.header("Idempotency-Key", key);
        return request;
    }
    private MockHttpSession login(String email) throws Exception {
        return (MockHttpSession) mvc.perform(post("/auth/login").with(csrf()).param("email", email)
                .param("password", TestAccounts.PASSWORD)).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    private void runConcurrent(int count, Callable<?> action) throws Exception {
        var pool = Executors.newFixedThreadPool(count);
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < count; i++) futures.add(pool.submit(() -> { ready.countDown(); start.await(); return action.call(); }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        } finally { start.countDown(); pool.shutdownNow(); }
    }
}
