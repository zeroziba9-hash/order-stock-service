package com.zeroziba.orderstock.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.auth.limits")
public record AuthLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("120") int loginAttempts,
        @DefaultValue("60") int registrationAttempts,
        @DefaultValue("5") int failedLogins,
        @DefaultValue("PT1M") Duration loginWindow,
        @DefaultValue("PT10M") Duration registrationWindow,
        @DefaultValue("PT5M") Duration failureWindow,
        @DefaultValue("10000") int maxEntries,
        List<String> trustedProxies) {
    public AuthLimitProperties {
        for (int limit : new int[]{loginAttempts, registrationAttempts, failedLogins}) {
            if (limit < 1 || limit > 10000) throw new IllegalArgumentException("Auth attempt limits must be 1..10000");
        }
        for (Duration window : new Duration[]{loginWindow, registrationWindow, failureWindow}) {
            if (window == null || window.compareTo(Duration.ofSeconds(1)) < 0 || window.compareTo(Duration.ofHours(1)) > 0)
                throw new IllegalArgumentException("Auth limit windows must be 1 second..1 hour");
        }
        if (maxEntries < 16 || maxEntries > 100000) throw new IllegalArgumentException("Auth limit capacity must be 16..100000");
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
    }
}
