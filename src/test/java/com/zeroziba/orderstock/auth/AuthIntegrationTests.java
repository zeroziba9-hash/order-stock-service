package com.zeroziba.orderstock.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zeroziba.orderstock.TestAccounts;
import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.order.CreateOrderRequest;
import com.zeroziba.orderstock.order.IdempotentOrderService;
import com.zeroziba.orderstock.product.Product;
import com.zeroziba.orderstock.product.ProductRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTests {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ProductRepository productRepository;
    @Autowired private IdempotentOrderService idempotentOrderService;
    @Autowired private RegistrationService registrationService;
    private long ownerId;
    private long otherId;

    @BeforeEach
    void prepare() {
        clean();
        ownerId = TestAccounts.create(jdbcTemplate, "owner@example.test");
        otherId = TestAccounts.create(jdbcTemplate, "other@example.test");
    }

    @AfterEach
    void clean() {
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("order_stock_test");
        jdbcTemplate.update("DELETE FROM refund_attempts");
        jdbcTemplate.update("DELETE FROM payment_attempts");
        jdbcTemplate.update("DELETE FROM order_requests");
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM purchase_orders");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("DELETE FROM app_users");
    }

    @Test
    void productAndHealthStayPublicWhileOrdersRequireAuthentication() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(get("/assets/store.js")).andExpect(status().isOk());
        mockMvc.perform(get("/assets/store.css")).andExpect(status().isOk());
        mockMvc.perform(get("/storefront/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mockPayments").value(true)).andExpect(jsonPath("$.mockRefunds").value(true));
        mockMvc.perform(get("/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/products")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mockMvc.perform(get("/orders/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/orders").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":1,\"quantity\":1}")).andExpect(status().isUnauthorized());
    }

    @Test
    void registersNormalizedEmailAndStoresOnlyPasswordHash() throws Exception {
        var registration = mockMvc.perform(post("/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"NewUser@Example.test\",\"password\":\"NewPass123!\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("newuser@example.test"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse();
        assertThat(registration.getContentAsString()).doesNotContain("NewPass123!");
        String stored = jdbcTemplate.queryForObject("SELECT password_hash FROM app_users WHERE email = ?",
                String.class, "newuser@example.test");
        assertThat(stored).isNotEqualTo("NewPass123!");
        assertThat(passwordEncoder.matches("NewPass123!", stored)).isTrue();
        MockHttpSession session = login("NEWUSER@example.test", "NewPass123!");
        mockMvc.perform(get("/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("newuser@example.test"));
    }

    @Test
    void duplicateEmailIncludingDifferentCaseIsRejected() throws Exception {
        mockMvc.perform(post("/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"OWNER@EXAMPLE.TEST\",\"password\":\"OtherPass123!\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_users", Long.class)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"email\":\"bad\",\"password\":\"ValidPass123!\"}",
            "{\"email\":\"new@example.test\",\"password\":\"short\"}",
            "{\"email\":\"new@example.test\",\"password\":null}",
            "{\"email\":\"\",\"password\":\"ValidPass123!\"}",
            "{\"email\":\"new@example.test\",\"password\":\"        \"}"
    })
    void invalidRegistrationIsRejected(String body) throws Exception {
        mockMvc.perform(post("/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_users", Long.class)).isEqualTo(2);
    }

    @Test
    void rejectsPasswordsOverBcryptByteLimitWithoutTruncation() throws Exception {
        String body = "{\"email\":\"new@example.test\",\"password\":\"" + "가".repeat(25) + "\"}";
        mockMvc.perform(post("/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(passwordEncoder.matches("a".repeat(73), passwordEncoder.encode("a".repeat(72)))).isFalse();
    }

    @Test
    void loginRotatesSessionIdPersistsAuthenticationAndErasesHashFromSession() throws Exception {
        var csrfResult = mockMvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        String originalId = session.getId();
        String token = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.token");
        mockMvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "owner@example.test").param("password", TestAccounts.PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(ownerId));
        assertThat(session.getId()).isNotEqualTo(originalId);
        SecurityContext context = (SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(((AuthenticatedUser) context.getAuthentication().getPrincipal()).getPassword()).isNull();
        mockMvc.perform(get("/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(ownerId));
    }

    @Test
    void wrongPasswordAndUnknownEmailReturnSameGenericFailure() throws Exception {
        String first = mockMvc.perform(post("/auth/login").with(csrf())
                        .param("email", "owner@example.test").param("password", "WrongPass123!"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/auth/login").with(csrf())
                        .param("email", "missing@example.test").param("password", "WrongPass123!"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void csrfIsRequiredForRegistrationLoginOrdersAndLogout() throws Exception {
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"new@example.test\",\"password\":\"NewPass123!\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/auth/login").param("email", "owner@example.test").param("password", TestAccounts.PASSWORD))
                .andExpect(status().isForbidden());
        MockHttpSession session = login("owner@example.test", TestAccounts.PASSWORD);
        mockMvc.perform(post("/orders").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":1,\"quantity\":1}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/auth/logout").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void logoutInvalidatesSessionAndSubsequentRequestsRequireLogin() throws Exception {
        MockHttpSession session = login("owner@example.test", TestAccounts.PASSWORD);
        mockMvc.perform(post("/auth/logout").session(session).header("X-CSRF-TOKEN", csrfToken(session)))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mockMvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyOwnerCanReadOrCancelOrderAndBodyCannotSpoofOwner() throws Exception {
        Product product = productRepository.saveAndFlush(new Product("소유권 상품", 15000, 10));
        MockHttpSession ownerSession = login("owner@example.test", TestAccounts.PASSWORD);
        MockHttpSession otherSession = login("other@example.test", TestAccounts.PASSWORD);
        String response = mockMvc.perform(post("/orders").session(ownerSession)
                        .header("X-CSRF-TOKEN", csrfToken(ownerSession)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + product.getId() + ",\"quantity\":2,\"userId\":" + otherId + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long orderId = ((Number) JsonPath.read(response, "$.orderId")).longValue();
        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM purchase_orders WHERE id = ?", Long.class, orderId))
                .isEqualTo(ownerId);
        mockMvc.perform(get("/orders/" + orderId).session(otherSession)).andExpect(status().isNotFound());
        mockMvc.perform(post("/orders/" + orderId + "/cancel").session(otherSession)
                        .header("X-CSRF-TOKEN", csrfToken(otherSession)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        assertThat(stockOf(product.getId())).isEqualTo(8);
        mockMvc.perform(get("/orders/" + orderId).session(ownerSession)).andExpect(status().isOk());
        mockMvc.perform(post("/orders/" + orderId + "/cancel").session(ownerSession)
                        .header("X-CSRF-TOKEN", csrfToken(ownerSession)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(stockOf(product.getId())).isEqualTo(10);
    }

    @Test
    void sameKeyIsScopedToEachUser() {
        Product product = productRepository.saveAndFlush(new Product("회원별 키 상품", 1000, 10));
        var first = idempotentOrderService.create(ownerId, "shared-key", new CreateOrderRequest(product.getId(), 2));
        var second = idempotentOrderService.create(otherId, "shared-key", new CreateOrderRequest(product.getId(), 1));
        assertThat(first.order().orderId()).isNotEqualTo(second.order().orderId());
        assertThat(second.replayed()).isFalse();
        assertThat(idempotentOrderService.create(ownerId, "shared-key", new CreateOrderRequest(product.getId(), 2)).order())
                .isEqualTo(first.order());
        assertThat(stockOf(product.getId())).isEqualTo(7);
    }

    @Test
    void legacyAnonymousOrderRemainsUnownedAndInaccessible() throws Exception {
        jdbcTemplate.update("INSERT INTO purchase_orders (total_amount, status, created_at) VALUES (1000, 'CREATED', UTC_TIMESTAMP(6))");
        long legacyId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM purchase_orders", Long.class);
        MockHttpSession session = login("owner@example.test", TestAccounts.PASSWORD);
        mockMvc.perform(get("/orders/" + legacyId).session(session)).andExpect(status().isNotFound());
        mockMvc.perform(post("/orders/" + legacyId + "/cancel").session(session).header("X-CSRF-TOKEN", csrfToken(session)))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE id = ? AND user_id IS NULL",
                Long.class, legacyId)).isEqualTo(1);
    }

    @Test
    void concurrentRegistrationOfSameEmailCreatesOneUser() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var action = (java.util.concurrent.Callable<Boolean>) () -> {
                if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Start timeout"); }
                try {
                    registrationService.register(new RegisterRequest("race@example.test", "RacePass123!"));
                    return true;
                } catch (ApiException exception) {
                    assertThat(exception.getCode()).isEqualTo("EMAIL_ALREADY_EXISTS");
                    return false;
                }
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isNotEqualTo(second.get(20, TimeUnit.SECONDS));
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_users WHERE email = 'race@example.test'", Long.class))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private MockHttpSession login(String email, String password) throws Exception {
        var result = mockMvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
        mockMvc.perform(post("/auth/login").session(session).header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("email", email).param("password", password))
                .andExpect(status().isOk());
        return session;
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        String body = mockMvc.perform(get("/auth/csrf").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private int stockOf(long productId) {
        return jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }
}
