package com.zeroziba.orderstock.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import com.zeroziba.orderstock.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.auth.limits.enabled=true", "app.auth.limits.login-attempts=2", "app.auth.limits.failed-logins=2"})
@ActiveProfiles("test")
@Import(RequestTracingIntegrationTests.FailureConfiguration.class)
class RequestTracingIntegrationTests {
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    private static final String SECRET = "trace-private-marker";

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + environment.getRequiredProperty("local.server.port") + path)).timeout(Duration.ofSeconds(10));
    }

    @Test void realServerCorrelatesSuccessAndSecurityShortCircuitsWithoutLoggingCredentials() throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        try (var logs = new TraceLogCapture()) {
            var responses = new java.util.ArrayList<HttpResponse<String>>();
            responses.add(client.send(request("/products?password=" + SECRET)
                    .header("X-Request-ID", SECRET).GET().build(), HttpResponse.BodyHandlers.ofString()));
            responses.add(client.send(request("/orders").GET().build(), HttpResponse.BodyHandlers.ofString()));
            responses.add(client.send(request("/auth/login").POST(HttpRequest.BodyPublishers.ofString("email=" + SECRET))
                    .header("Content-Type", "application/x-www-form-urlencoded").build(), HttpResponse.BodyHandlers.ofString()));
            var csrfResponse = client.send(request("/auth/csrf").GET().build(), HttpResponse.BodyHandlers.ofString());
            var csrf = mapper.readTree(csrfResponse.body());
            String csrfName = csrf.get("headerName").asText(), csrfToken = csrf.get("token").asText();
            responses.add(client.send(request("/auth/register").header(csrfName, csrfToken)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{" + SECRET)).build(),
                    HttpResponse.BodyHandlers.ofString()));
            for (int i = 0; i < 3; i++) responses.add(client.send(request("/auth/login")
                    .header(csrfName, csrfToken).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("email=" + SECRET + "@example.test&password=" + SECRET)).build(),
                    HttpResponse.BodyHandlers.ofString()));
            assertThat(responses).extracting(HttpResponse::statusCode).containsExactly(200, 401, 403, 400, 401, 401, 429);
            var ids = new HashSet<String>();
            for (var response : responses) {
                String id = response.headers().firstValue(RequestTracingFilter.HEADER).orElseThrow();
                assertThat(id).matches("[0-9a-f-]{36}");
                assertThat(ids.add(id)).isTrue();
                var completed = logs.events.stream().filter(event -> event.getFormattedMessage().contains("request_completed requestId=" + id)).toList();
                assertThat(completed).hasSize(1);
                assertThat(completed.get(0).getFormattedMessage()).contains("status=" + response.statusCode());
                assertThat(completed.get(0).getMDCPropertyMap().get("requestId")).isEqualTo(id);
            }
            assertThat(logs.events).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain(SECRET, csrfToken, "JSESSIONID", "Authorization", "password="));
        }
    }

    @Test void unexpectedFailureReturnsGeneric500AndLogsSafeTypeAndLocationWithSameId() throws Exception {
        try (var logs = new TraceLogCapture()) {
            var response = HttpClient.newHttpClient().send(request("/assets/trace-failure").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(mapper.readTree(response.body()).get("code").asText()).isEqualTo("INTERNAL_ERROR");
            assertThat(response.body()).doesNotContain(SECRET, "IllegalStateException");
            String id = response.headers().firstValue(RequestTracingFilter.HEADER).orElseThrow();
            assertThat(logs.events).hasSize(2).allSatisfy(event -> {
                assertThat(event.getMDCPropertyMap().get("requestId")).isEqualTo(id);
                assertThat(event.getFormattedMessage()).doesNotContain(SECRET);
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(logs.events.get(0).getFormattedMessage()).contains("unexpected_failure", "IllegalStateException", "IllegalArgumentException", "FailureController.fail");
            assertThat(logs.events.get(1).getFormattedMessage()).contains("status=500");
        }
    }

    @Test void existingBusinessAndFrameworkStatusesArePreservedAndMissingAssetIsLoggedOnce() throws Exception {
        var client = HttpClient.newHttpClient();
        try (var logs = new TraceLogCapture()) {
            for (var expectation : java.util.Map.of("business", 409, "framework", 400, "missing", 404, "unavailable", 503).entrySet()) {
                var response = client.send(request("/assets/trace-" + expectation.getKey()).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(expectation.getValue());
                String id = response.headers().firstValue(RequestTracingFilter.HEADER).orElseThrow();
                assertThat(logs.events.stream().filter(event -> id.equals(event.getMDCPropertyMap().get("requestId"))).toList())
                        .hasSize(expectation.getValue() >= 500 ? 2 : 1);
                assertThat(response.body()).doesNotContain(SECRET);
                if (expectation.getKey().equals("business")) {
                    assertThat(mapper.readTree(response.body()).get("code").asText()).isEqualTo("TRACE_CONFLICT");
                }
                if (expectation.getKey().equals("unavailable")) {
                    assertThat(mapper.readTree(response.body()).get("code").asText()).isEqualTo("INTERNAL_ERROR");
                }
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailureConfiguration {
        @Bean FailureController failureController() { return new FailureController(); }
    }
    @RestController
    static class FailureController {
        @GetMapping("/assets/trace-failure") String fail() {
            throw new IllegalStateException(SECRET, new IllegalArgumentException(SECRET));
        }
        @GetMapping("/assets/trace-business") String business() {
            throw new ApiException(HttpStatus.CONFLICT, "TRACE_CONFLICT", "확인용 충돌입니다.");
        }
        @GetMapping("/assets/trace-framework") String framework() {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, SECRET);
        }
        @GetMapping("/assets/trace-unavailable") String unavailable() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, SECRET);
        }
    }
}
