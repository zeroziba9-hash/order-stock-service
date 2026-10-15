package com.zeroziba.orderstock.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestTracingFilterTests {
    private final RequestTracingFilter filter = new RequestTracingFilter();
    @AfterEach void clear() { MDC.clear(); }

    @Test void restoresExistingContextEvenAfterFailureAndRecords500WithoutExceptionMessage() {
        MDC.put("requestId", "outer-context");
        MDC.put("unrelated", "preserved");
        var response = new MockHttpServletResponse();
        try (var logs = new TraceLogCapture()) {
            assertThrows(ServletException.class, () -> filter.doFilter(
                    new MockHttpServletRequest("POST", "/orders"), response, (request, result) -> {
                        assertThat(MDC.get("requestId")).isEqualTo(response.getHeader(RequestTracingFilter.HEADER));
                        throw new ServletException("private-exception-secret");
                    }));
            assertThat(logs.events).hasSize(1);
            assertThat(logs.events.get(0).getFormattedMessage()).contains("status=500").doesNotContain("private-exception-secret");
            assertThat(logs.events.get(0).getMDCPropertyMap().get("requestId")).isEqualTo(response.getHeader(RequestTracingFilter.HEADER));
        }
        assertThat(MDC.get("requestId")).isEqualTo("outer-context");
        assertThat(MDC.get("unrelated")).isEqualTo("preserved");
    }

    @Test void neverLogsCallerTextFromMethodPathHeadersQueryOrBody() throws ServletException, IOException {
        var request = new MockHttpServletRequest("FORGED\r\nsecret-method", "/orders/secret-path");
        request.setQueryString("password=secret-query");
        request.addHeader("X-Request-ID", "secret-id\r\nforged-line");
        request.addHeader("Authorization", "Bearer secret-token");
        request.setContent("secret-body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var logs = new TraceLogCapture()) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {});
            assertThat(logs.events).hasSize(1);
            assertThat(logs.events.get(0).getFormattedMessage()).contains("method=OTHER", "category=orders")
                    .doesNotContain("secret", "\r", "\n");
            assertThat(response.getHeader(RequestTracingFilter.HEADER)).matches("[0-9a-f-]{36}");
        }
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test void suppressesSuccessfulProbesAndAssetsButRetainsTheirErrors() throws Exception {
        try (var logs = new TraceLogCapture()) {
            for (String path : List.of("/actuator/health", "/assets/store.js", "/")) {
                filter.doFilter(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), (req, res) -> {});
            }
            assertThat(logs.events).isEmpty();
            var response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/assets/missing.js"), response, (req, res) -> response.setStatus(404));
            assertThat(logs.events).hasSize(1);
            assertThat(logs.events.get(0).getFormattedMessage()).contains("status=404", "category=assets");
        }
    }

    @Test void reusedWorkerThreadsDoNotLeakIdsBetweenConcurrentRequests() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try (var logs = new TraceLogCapture()) {
            var tasks = new ArrayList<Callable<String>>();
            for (int i = 0; i < 40; i++) tasks.add(() -> {
                assertThat(MDC.get("requestId")).isNull();
                var response = new MockHttpServletResponse();
                filter.doFilter(new MockHttpServletRequest("GET", "/products"), response, (req, res) -> {
                    assertThat(MDC.get("requestId")).isEqualTo(response.getHeader(RequestTracingFilter.HEADER));
                    Thread.yield();
                });
                assertThat(MDC.get("requestId")).isNull();
                return response.getHeader(RequestTracingFilter.HEADER);
            });
            var ids = new HashSet<String>();
            for (var future : executor.invokeAll(tasks)) ids.add(future.get());
            assertThat(ids).hasSize(40);
            assertThat(logs.events).hasSize(40);
            assertThat(logs.events).allSatisfy(event -> {
                String id = event.getMDCPropertyMap().get("requestId");
                assertThat(ids).contains(id);
                assertThat(event.getFormattedMessage()).contains("requestId=" + id);
            });
        } finally { executor.shutdownNow(); }
    }
}
