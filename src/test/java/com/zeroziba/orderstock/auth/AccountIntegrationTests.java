package com.zeroziba.orderstock.auth;

import static org.assertj.core.api.Assertions.*;

import com.zeroziba.orderstock.TestAccounts;
import com.zeroziba.orderstock.TestDatabase;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.autoconfigure.exclude=", "spring.session.jdbc.cleanup-cron=-", "app.auth.limits.enabled=true"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountService accounts;
    @Autowired JdbcIndexedSessionRepository repository;
    @Autowired AppUserDetailsService details;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    private String email;
    private long owner;
    private static final String NEW_PASSWORD = "ChangedPass456!";

    @BeforeEach void prepare() {
        clean(); email = "account-" + UUID.randomUUID() + "@example.test"; owner = TestAccounts.create(jdbc, email);
    }
    @AfterEach void clean() {
        TestDatabase.cleanInventory(jdbc);
        for (String table : List.of("refund_attempts", "payment_attempts", "order_requests", "order_items", "purchase_orders", "products", "app_users")) jdbc.update("DELETE FROM " + table);
    }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path)).timeout(Duration.ofSeconds(15));
    }
    private final class Browser {
        final CookieManager cookies = new CookieManager();
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        String csrfName, csrfToken;
        HttpResponse<String> get(String path) throws Exception { return http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString()); }
        void csrf() throws Exception {
            var token = mapper.readTree(get("/auth/csrf").body()); csrfName = token.get("headerName").asText(); csrfToken = token.get("token").asText();
        }
        HttpResponse<String> post(String path, String body, boolean csrf, boolean json) throws Exception {
            var builder = request(path).header("Content-Type", json ? "application/json" : "application/x-www-form-urlencoded");
            if (csrf) builder.header(csrfName, csrfToken);
            return http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        int login(String address, String password) throws Exception {
            csrf();
            return post("/auth/login", "email=" + URLEncoder.encode(address, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8), true, false).statusCode();
        }
        HttpResponse<String> change(String current, String replacement) throws Exception {
            csrf(); return post("/auth/password", mapper.writeValueAsString(new ChangePasswordRequest(current, replacement)), true, true);
        }
        String id() { return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue(); }
    }
    private Browser login() throws Exception { return login(email); }
    private Browser login(String address) throws Exception {
        var browser = new Browser(); assertThat(browser.login(address, TestAccounts.PASSWORD)).isEqualTo(200); return browser;
    }
    private int stored(String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM app_sessions WHERE SESSION_ID=?", Integer.class, id); }
    private int replay(String id) throws Exception {
        return HttpClient.newHttpClient().send(request("/auth/me").header("Cookie", "JSESSIONID=" + id).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }
    private String hash() { return jdbc.queryForObject("SELECT password_hash FROM app_users WHERE id=?", String.class, owner); }
    private long version() { return jdbc.queryForObject("SELECT auth_version FROM app_users WHERE id=?", Long.class, owner); }

    @Test void sessionListContainsOnlyOwnActiveMetadataAndNoTokens() throws Exception {
        var current = login(); var other = login(); var expired = login();
        TestAccounts.create(jdbc, "unrelated@example.test"); var unrelated = login("unrelated@example.test");
        jdbc.update("UPDATE app_sessions SET EXPIRY_TIME=1 WHERE SESSION_ID=?", expired.id());
        var response = current.get("/auth/sessions"); assertThat(response.statusCode()).isEqualTo(200);
        var result = mapper.readTree(response.body());
        assertThat(result.get("sessions").size()).isEqualTo(2); assertThat(result.get("truncated").asBoolean()).isFalse();
        assertThat(result.get("sessions").get(0).get("current").asBoolean()).isTrue();
        for (var row : result.get("sessions")) {
            assertThat(row.size()).isEqualTo(4); assertThat(row.has("createdAt") && row.has("lastAccessedAt") && row.has("expiresAt")).isTrue();
        }
        assertThat(response.body()).doesNotContain(current.id(), other.id(), unrelated.id(), "password", "SPRING_SECURITY_CONTEXT", "token", email);
    }

    @Test void sessionListIsBoundedAndKeepsCurrentFirst() throws Exception {
        var current = login(); long now = System.currentTimeMillis();
        for (int i = 0; i < 105; i++) jdbc.update("INSERT INTO app_sessions VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), now, now + i, 1800, now + 1_800_000, email);
        var result = mapper.readTree(current.get("/auth/sessions").body());
        assertThat(result.get("sessions").size()).isEqualTo(100); assertThat(result.get("truncated").asBoolean()).isTrue();
        assertThat(result.get("sessions").get(0).get("current").asBoolean()).isTrue();
        current.csrf(); assertThat(current.post("/auth/sessions/logout-others", "{}", true, true).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_sessions WHERE PRINCIPAL_NAME=?", Integer.class, email)).isEqualTo(1);
    }

    @Test void logoutOthersKeepsCurrentAndUnrelatedUsersAndDeletesAttributes() throws Exception {
        var current = login(); var other = login(); String removed = other.id();
        TestAccounts.create(jdbc, "unrelated@example.test"); var unrelated = login("unrelated@example.test");
        String primary = jdbc.queryForObject("SELECT PRIMARY_ID FROM app_sessions WHERE SESSION_ID=?", String.class, removed);
        current.csrf();
        assertThat(current.post("/auth/sessions/logout-others", "{}", true, true).statusCode()).isEqualTo(204);
        assertThat(stored(removed)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_sessions_ATTRIBUTES WHERE SESSION_PRIMARY_ID=?", Integer.class, primary)).isZero();
        assertThat(current.get("/auth/me").statusCode()).isEqualTo(200); assertThat(unrelated.get("/auth/me").statusCode()).isEqualTo(200);
        assertThat(replay(removed)).isEqualTo(401);
        assertThat(current.post("/auth/sessions/logout-others", "{}", true, true).statusCode()).isEqualTo(204);
    }

    @Test void passwordChangeReplacesHashRevokesAllSessionsAndRequiresNewCredentials() throws Exception {
        var current = login(); var other = login(); String id = current.id(), otherId = other.id(), previousHash = hash();
        TestAccounts.create(jdbc, "unrelated@example.test"); var unrelated = login("unrelated@example.test");
        var response = current.change(TestAccounts.PASSWORD, NEW_PASSWORD);
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.headers().allValues("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie).contains("Max-Age=0", "HttpOnly", "SameSite=Lax"));
        assertThat(hash()).isNotEqualTo(previousHash).doesNotContain(NEW_PASSWORD); assertThat(passwords.matches(NEW_PASSWORD, hash())).isTrue();
        assertThat(version()).isEqualTo(1); assertThat(stored(id)).isZero(); assertThat(stored(otherId)).isZero();
        assertThat(replay(id)).isEqualTo(401); assertThat(replay(otherId)).isEqualTo(401);
        assertThat(unrelated.get("/auth/me").statusCode()).isEqualTo(200);
        var fresh = new Browser(); assertThat(fresh.login(email, TestAccounts.PASSWORD)).isEqualTo(401);
        assertThat(fresh.login(email, NEW_PASSWORD)).isEqualTo(200); assertThat(fresh.get("/auth/me").statusCode()).isEqualTo(200);
    }

    @Test void wrongPasswordDoesNotChangeCredentialsOrInvalidateSessions() throws Exception {
        var current = login(); var other = login(); String before = hash();
        var response = current.change("WrongPassword!", NEW_PASSWORD);
        assertThat(response.statusCode()).isEqualTo(400); assertThat(response.body()).contains("CURRENT_PASSWORD_INVALID");
        assertThat(hash()).isEqualTo(before); assertThat(version()).isZero();
        assertThat(current.get("/auth/me").statusCode()).isEqualTo(200); assertThat(other.get("/auth/me").statusCode()).isEqualTo(200);
    }

    @Test void invalidShortAndMultibytePasswordsAreRejectedWithoutMutation() throws Exception {
        var current = login(); String before = hash();
        assertThat(current.change(TestAccounts.PASSWORD, "short").statusCode()).isEqualTo(400);
        assertThat(current.change(TestAccounts.PASSWORD, "한".repeat(25)).statusCode()).isEqualTo(400);
        assertThat(current.change("한".repeat(25), NEW_PASSWORD).statusCode()).isEqualTo(400);
        assertThat(hash()).isEqualTo(before); assertThat(version()).isZero();
        assertThat(current.get("/auth/me").statusCode()).isEqualTo(200);
    }

    @Test void identicalPasswordDoesNotLogoutUser() throws Exception {
        var current = login();
        assertThat(current.change(TestAccounts.PASSWORD, TestAccounts.PASSWORD).body()).contains("PASSWORD_UNCHANGED");
        assertThat(version()).isZero(); assertThat(current.get("/auth/me").statusCode()).isEqualTo(200);
    }

    @Test void byteBoundaryPasswordIsAcceptedWithoutTruncation() throws Exception {
        var current = login(); String replacement = "한".repeat(24);
        assertThat(current.change(TestAccounts.PASSWORD, replacement).statusCode()).isEqualTo(204);
        var fresh = new Browser(); assertThat(fresh.login(email, replacement)).isEqualTo(200);
        assertThat(fresh.login(email, replacement + "a")).isEqualTo(401);
    }

    @Test void authAndCsrfAreRequiredForAccountWrites() throws Exception {
        var anonymous = new Browser(); anonymous.csrf();
        assertThat(anonymous.get("/auth/sessions").statusCode()).isEqualTo(401);
        assertThat(anonymous.post("/auth/password", "{}", true, true).statusCode()).isEqualTo(401);
        assertThat(anonymous.post("/auth/sessions/logout-others", "{}", true, true).statusCode()).isEqualTo(401);
        var current = login();
        assertThat(current.post("/auth/password", "{}", false, true).statusCode()).isEqualTo(403);
        assertThat(current.post("/auth/sessions/logout-others", "{}", false, true).statusCode()).isEqualTo(403);
        assertThat(version()).isZero();
    }

    @Test void passwordWriteAndAllSessionDeletionsRollbackTogether() throws Exception {
        var current = login(); var other = login(); String before = hash();
        var principal = (AuthenticatedUser) details.loadUserByUsername(email);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            accounts.changePassword(principal, new ChangePasswordRequest(TestAccounts.PASSWORD, NEW_PASSWORD));
            assertThat(version()).isEqualTo(1); assertThat(stored(current.id())).isZero(); assertThat(stored(other.id())).isZero();
            throw new IllegalStateException("deliberate failure after database writes");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(hash()).isEqualTo(before); assertThat(version()).isZero();
        assertThat(current.get("/auth/me").statusCode()).isEqualTo(200); assertThat(other.get("/auth/me").statusCode()).isEqualTo(200);
    }

    @Test void staleAuthenticationSavedAfterPasswordChangeIsRejected() throws Exception {
        var current = login(); var stalePrincipal = (AuthenticatedUser) details.loadUserByUsername(email); stalePrincipal.eraseCredentials();
        assertThat(current.change(TestAccounts.PASSWORD, NEW_PASSWORD).statusCode()).isEqualTo(204);
        Session stale = repository.createSession();
        stale.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(stalePrincipal, null, stalePrincipal.getAuthorities())));
        save(stale);
        assertThat(stored(stale.getId())).isEqualTo(1); assertThat(replay(stale.getId())).isEqualTo(401); assertThat(stored(stale.getId())).isZero();
    }

    @Test void savingPreviouslyLoadedRevokedSessionDoesNotResurrectIt() throws Exception {
        var current = login(); var other = login(); Session stale = repository.findById(other.id());
        current.csrf(); assertThat(current.post("/auth/sessions/logout-others", "{}", true, true).statusCode()).isEqualTo(204);
        stale.setLastAccessedTime(java.time.Instant.now()); save(stale);
        assertThat(stored(other.id())).isZero(); assertThat(replay(other.id())).isEqualTo(401);
    }

    @SuppressWarnings({"rawtypes", "unchecked"}) private void save(Session session) { ((SessionRepository) repository).save(session); }

    @Test void concurrentPasswordChangesCanCommitOnlyOnce() throws Exception {
        var first = login(); var second = login(); first.csrf(); second.csrf();
        String body = mapper.writeValueAsString(new ChangePasswordRequest(TestAccounts.PASSWORD, NEW_PASSWORD));
        var executor = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try {
            var futures = List.of(executor.submit(() -> { ready.countDown(); start.await(); return first.post("/auth/password", body, true, true).statusCode(); }),
                    executor.submit(() -> { ready.countDown(); start.await(); return second.post("/auth/password", body, true, true).statusCode(); }));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); start.countDown();
            var statuses = List.of(futures.get(0).get(15, java.util.concurrent.TimeUnit.SECONDS), futures.get(1).get(15, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(statuses.stream().filter(s -> s == 204).count()).isEqualTo(1); assertThat(statuses).allMatch(s -> s == 204 || s == 401 || s == 403);
            assertThat(version()).isEqualTo(1); assertThat(passwords.matches(NEW_PASSWORD, hash())).isTrue();
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test void passwordGuessingIsLimitedAndHasRetryAfter() throws Exception {
        var current = login();
        for (int i = 0; i < 5; i++) assertThat(current.change("WrongPassword!", NEW_PASSWORD).statusCode()).isEqualTo(400);
        var denied = current.change(TestAccounts.PASSWORD, NEW_PASSWORD);
        assertThat(denied.statusCode()).isEqualTo(429); assertThat(denied.headers().firstValue("Retry-After")).isPresent();
        assertThat(version()).isZero(); assertThat(current.get("/auth/me").statusCode()).isEqualTo(200);
    }
}
