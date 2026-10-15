package com.zeroziba.orderstock.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.zeroziba.orderstock.TestAccounts;
import com.zeroziba.orderstock.TestDatabase;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.autoconfigure.exclude=", "spring.session.jdbc.cleanup-cron=-"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JdbcSessionIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcIndexedSessionRepository repository;
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    private static final String EMAIL = "session@example.test";
    private long owner;

    @BeforeEach void prepare() {
        clean();
        owner = TestAccounts.create(jdbc, EMAIL);
    }
    @AfterEach void clean() {
        TestDatabase.cleanInventory(jdbc);
        for (String table : List.of("refund_attempts", "payment_attempts", "order_requests", "order_items", "purchase_orders", "products", "app_users")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path))
                .timeout(Duration.ofSeconds(10));
    }
    private final class Browser {
        final CookieManager cookies = new CookieManager();
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        String csrfName;
        String csrfToken;
        HttpResponse<String> get(String path) throws Exception {
            return http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        void csrf() throws Exception {
            var response = get("/auth/csrf");
            assertThat(response.statusCode()).isEqualTo(200);
            var token = mapper.readTree(response.body());
            csrfName = token.get("headerName").asText(); csrfToken = token.get("token").asText();
        }
        String id() {
            return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("JSESSIONID"))
                    .findFirst().orElseThrow().getValue();
        }
        HttpResponse<String> post(String path, String body, String token) throws Exception {
            return http.send(request(path).header(csrfName, token).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        void login(String email) throws Exception {
            csrf();
            var response = post("/auth/login", "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(TestAccounts.PASSWORD, StandardCharsets.UTF_8), csrfToken);
            assertThat(response.statusCode()).isEqualTo(200);
        }
    }
    private Browser login() throws Exception { var browser = new Browser(); browser.login(EMAIL); return browser; }
    private HttpResponse<String> replay(String id, String path) throws Exception {
        return HttpClient.newHttpClient().send(request(path).header("Cookie", "JSESSIONID=" + id).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private int stored(String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM app_sessions WHERE SESSION_ID=?", Integer.class, id); }
    private void expire(String id) {
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE app_sessions SET LAST_ACCESS_TIME=?, EXPIRY_TIME=? WHERE SESSION_ID=?", now - 1_801_000, now - 1000, id);
    }

    @Test void loginRotatesIdAndStoresCredentialFreeSecurityContextInMysql() throws Exception {
        var browser = new Browser(); browser.csrf();
        String anonymous = browser.id();
        browser.login(EMAIL);
        String authenticated = browser.id();
        assertThat(authenticated).isNotEqualTo(anonymous).matches("[0-9a-f-]{36}");
        assertThat(stored(anonymous)).isZero();
        assertThat(stored(authenticated)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT MAX_INACTIVE_INTERVAL FROM app_sessions WHERE SESSION_ID=?", Integer.class, authenticated)).isEqualTo(1800);
        byte[] bytes = jdbc.queryForObject("SELECT a.ATTRIBUTE_BYTES FROM app_sessions_ATTRIBUTES a JOIN app_sessions s ON s.PRIMARY_ID=a.SESSION_PRIMARY_ID WHERE s.SESSION_ID=? AND a.ATTRIBUTE_NAME='SPRING_SECURITY_CONTEXT'", byte[].class, authenticated);
        SecurityContext context;
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes))) { context = (SecurityContext) input.readObject(); }
        var principal = (AuthenticatedUser) context.getAuthentication().getPrincipal();
        assertThat(principal.getId()).isEqualTo(owner);
        assertThat(principal.getPassword()).isNull();
        assertThat(context.getAuthentication().getCredentials()).isNull();
        String encoded = new String(bytes, StandardCharsets.ISO_8859_1);
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_users WHERE id=?", String.class, owner);
        assertThat(encoded).doesNotContain(TestAccounts.PASSWORD, hash);
    }

    @Test void separatelyLoadedSessionAuthenticatesFreshHttpClient() throws Exception {
        var browser = login();
        org.springframework.session.Session loaded = repository.findById(browser.id());
        assertThat(loaded).isNotNull();
        SecurityContext context = loaded.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(((AuthenticatedUser) context.getAuthentication().getPrincipal()).getId()).isEqualTo(owner);
        var response = replay(browser.id(), "/auth/me");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).get("userId").asLong()).isEqualTo(owner);
    }

    @Test void logoutDeletesSessionAndAttributesAndRejectsOldCookie() throws Exception {
        var browser = login(); browser.csrf();
        String id = browser.id();
        String primary = jdbc.queryForObject("SELECT PRIMARY_ID FROM app_sessions WHERE SESSION_ID=?", String.class, id);
        var logout = browser.post("/auth/logout", "", browser.csrfToken);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.headers().allValues("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie).contains("JSESSIONID=", "Max-Age=0", "HttpOnly", "SameSite=Lax"));
        assertThat(stored(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_sessions_ATTRIBUTES WHERE SESSION_PRIMARY_ID=?", Integer.class, primary)).isZero();
        assertThat(replay(id, "/auth/me").statusCode()).isEqualTo(401);
        assertThat(stored(id)).isZero();
    }

    @Test void expiredSessionIsRejectedBeforeCleanupJobRuns() throws Exception {
        var browser = login();
        String id = browser.id(); expire(id);
        assertThat(stored(id)).isEqualTo(1);
        assertThat(replay(id, "/auth/me").statusCode()).isEqualTo(401);
        assertThat(stored(id)).isZero();
    }

    @Test void cleanupDeletesExpiredAttributesAndKeepsActiveSession() throws Exception {
        var expired = login(); var active = login();
        expire(expired.id());
        repository.cleanUpExpiredSessions();
        assertThat(stored(expired.id())).isZero();
        assertThat(stored(active.id())).isEqualTo(1);
        assertThat(replay(active.id(), "/auth/me").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_sessions_ATTRIBUTES a LEFT JOIN app_sessions s ON s.PRIMARY_ID=a.SESSION_PRIMARY_ID WHERE s.PRIMARY_ID IS NULL", Integer.class)).isZero();
    }

    @Test void loginInvalidatesAnonymousCookieAndOldCsrfToken() throws Exception {
        var browser = new Browser(); browser.csrf();
        String oldId = browser.id(), oldToken = browser.csrfToken;
        browser.login(EMAIL);
        assertThat(replay(oldId, "/auth/me").statusCode()).isEqualTo(401);
        assertThat(browser.post("/auth/logout", "", oldToken).statusCode()).isEqualTo(403);
        assertThat(browser.get("/auth/me").statusCode()).isEqualTo(200);
        browser.csrf();
        assertThat(browser.post("/auth/logout", "", browser.csrfToken).statusCode()).isEqualTo(204);
    }

    @Test void currentDatabaseRoleOverridesPersistedAdminPrincipalForAdminApi() throws Exception {
        jdbc.update("UPDATE app_users SET user_role='ADMIN' WHERE id=?", owner);
        var browser = login();
        assertThat(replay(browser.id(), "/admin/products").statusCode()).isEqualTo(200);
        jdbc.update("UPDATE app_users SET user_role='USER' WHERE id=?", owner);
        var denied = replay(browser.id(), "/admin/products");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(mapper.readTree(denied.body()).get("code").asText()).isEqualTo("ADMIN_REQUIRED");
    }

    @Test void concurrentRequestsCanInitializeCsrfAttributesOnSharedSession() throws Exception {
        var browser = login();
        String id = browser.id();
        var executor = Executors.newFixedThreadPool(8);
        try {
            var requests = new ArrayList<Callable<Integer>>();
            for (int i = 0; i < 24; i++) requests.add(() -> replay(id, "/auth/csrf").statusCode());
            for (var result : executor.invokeAll(requests)) assertThat(result.get()).isEqualTo(200);
        } finally { executor.shutdownNow(); }
        assertThat(stored(id)).isEqualTo(1);
        browser.csrf();
        assertThat(browser.post("/auth/logout", "", browser.csrfToken).statusCode()).isEqualTo(204);
    }

    @Test void maximumLengthEmailFitsPrincipalIndexAndCanAuthenticate() throws Exception {
        String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(56) + ".test";
        assertThat(email).hasSize(254);
        TestAccounts.create(jdbc, email);
        var browser = new Browser(); browser.login(email);
        assertThat(jdbc.queryForObject("SELECT PRINCIPAL_NAME FROM app_sessions WHERE SESSION_ID=?", String.class, browser.id())).isEqualTo(email);
        assertThat(browser.get("/auth/me").statusCode()).isEqualTo(200);
    }
}
