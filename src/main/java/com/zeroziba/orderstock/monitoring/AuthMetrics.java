package com.zeroziba.orderstock.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AuthMetrics {
    public enum Endpoint { LOGIN, REGISTER }
    public enum Outcome { SUCCESS, FAILURE, RATE_LIMITED, ERROR }
    public enum Reason { REQUEST_RATE, CREDENTIAL_FAILURES, CAPACITY }
    private final Map<String, Counter> requests = new HashMap<>();
    private final Map<String, Counter> rejections = new HashMap<>();

    public AuthMetrics(MeterRegistry registry) {
        for (Endpoint endpoint : Endpoint.values()) {
            for (Outcome outcome : Outcome.values()) requests.put(endpoint+":"+outcome,
                    Counter.builder("stockroom.auth.requests").description("CSRF-valid authentication HTTP results")
                            .tag("endpoint", label(endpoint)).tag("outcome", label(outcome)).register(registry));
            for (Reason reason : Reason.values()) rejections.put(endpoint+":"+reason,
                    Counter.builder("stockroom.auth.rejections").description("Authentication admission rejections")
                            .tag("endpoint", label(endpoint)).tag("reason", label(reason)).register(registry));
        }
    }
    public void result(Endpoint endpoint, Outcome outcome) { increment(requests.get(endpoint+":"+outcome)); }
    public void rejected(Endpoint endpoint, Reason reason) {
        result(endpoint, Outcome.RATE_LIMITED); increment(rejections.get(endpoint+":"+reason));
    }
    private static String label(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
    private static void increment(Counter counter) {
        try { counter.increment(); }
        catch (RuntimeException failure) {
            LoggerFactory.getLogger(AuthMetrics.class).warn("Auth metric update failed; error={}", failure.getClass().getSimpleName());
        }
    }
}
