package com.zeroziba.orderstock.monitoring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Correlates synchronous requests without recording client-controlled text. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTracingFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-ID";
    private static final Logger log = LoggerFactory.getLogger(RequestTracingFilter.class);
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // A caller cannot select an ID, inject a log line, or impersonate an earlier request.
        String requestId = UUID.randomUUID().toString();
        String previous = MDC.get("requestId");
        MDC.put("requestId", requestId);
        response.setHeader(HEADER, requestId);
        long started = System.nanoTime();
        int status = 500;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            try {
                String category = category(request);
                // Health probes and static assets otherwise dominate local monitoring logs.
                if (status >= 400 || !(category.equals("health") || category.equals("assets"))) {
                    String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
                    long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    var event = status >= 500 ? log.atError() : log.atInfo();
                    event.log("request_completed requestId={} method={} category={} status={} durationMs={}",
                            requestId, method, category, status, duration);
                }
            } finally {
                if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous);
            }
        }
    }

    private static String category(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals("/actuator") || path.startsWith("/actuator/")) return "health";
        if (path.equals("/") || path.endsWith(".html") || path.startsWith("/assets/")) return "assets";
        for (String category : new String[]{"auth", "orders", "products", "admin", "storefront"}) {
            if (path.equals("/" + category) || path.startsWith("/" + category + "/")) return category;
        }
        return "other";
    }
}
