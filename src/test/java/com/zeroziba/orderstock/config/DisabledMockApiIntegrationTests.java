package com.zeroziba.orderstock.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zeroziba.orderstock.TestAccounts;
import com.zeroziba.orderstock.auth.AuthenticatedUser;
import com.zeroziba.orderstock.order.CreateOrderRequest;
import com.zeroziba.orderstock.order.OrderService;
import com.zeroziba.orderstock.product.Product;
import com.zeroziba.orderstock.product.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"app.mock-payments.enabled=false", "app.mock-refunds.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DisabledMockApiIntegrationTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private OrderService orders;
    @Autowired private ProductRepository products;
    private long orderId;
    private long productId;
    private AuthenticatedUser owner;

    @BeforeEach
    void prepare() {
        clean();
        long userId = TestAccounts.create(jdbc, "disabled-mock@example.test");
        owner = new AuthenticatedUser(userId, "disabled-mock@example.test", null);
        productId = products.saveAndFlush(new Product("환경 제어 테스트", 1000, 2)).getId();
        orderId = orders.create(userId, new CreateOrderRequest(productId, 1)).orderId();
    }

    @AfterEach
    void clean() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("order_stock_test");
        for (String table : new String[]{"refund_attempts", "payment_attempts", "order_requests", "order_items", "purchase_orders", "products", "app_users"}) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void storefrontHidesDisabledMockOperations() throws Exception {
        mvc.perform(get("/storefront/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mockPayments").value(false)).andExpect(jsonPath("$.mockRefunds").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"payments", "refunds"})
    void mutationIsBlockedWithoutChangingData(String path) throws Exception {
        mvc.perform(post("/orders/" + orderId + "/" + path).with(user(owner)).with(csrf())
                .header("Idempotency-Key", "disabled").contentType(MediaType.APPLICATION_JSON)
                .content("{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MOCK_API_DISABLED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_attempts", Long.class)).isZero();
        assertThat(products.findById(productId).orElseThrow().getStock()).isEqualTo(1);
        assertThat(orders.findById(owner.getId(), orderId).status()).isEqualTo("CREATED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"payments", "refunds"})
    void historyRemainsOwnerOnlyAndAvailable(String path) throws Exception {
        mvc.perform(get("/orders/" + orderId + "/" + path).with(user(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/orders/" + orderId + "/" + path))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/orders/" + orderId + "/" + path)
                .with(user(new AuthenticatedUser(owner.getId() + 1, "other@example.test", null))))
                .andExpect(status().isNotFound());
    }
}
