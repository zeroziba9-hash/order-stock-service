package com.zeroziba.orderstock.payment.toss;

import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class TossSettings {
    final boolean enabled;
    final String clientKey;
    final String secretKey;
    final String origin;
    final URI api;
    final boolean stub;

    public TossSettings(Environment env) {
        enabled = env.getProperty("app.toss.enabled", Boolean.class, false);
        clientKey = env.getProperty("app.toss.client-key", "");
        secretKey = env.getProperty("app.toss.secret-key", "");
        origin = env.getProperty("app.toss.origin", "");
        stub = env.getProperty("app.toss.stub-enabled", Boolean.class, false);
        URI endpoint = URI.create("https://api.tosspayments.com");
        if (stub) {
            if (!env.matchesProfiles("pg-stub") || env.matchesProfiles("prod")) invalid();
            try {
                endpoint = URI.create(env.getRequiredProperty("app.toss.stub-url"));
                if (!"http".equals(endpoint.getScheme()) || !java.util.Set.of("127.0.0.1", "localhost", "pgstub").contains(endpoint.getHost())
                        || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                        || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/"))) invalid();
            } catch (RuntimeException failure) { invalid(); }
        }
        api = endpoint;
        if (enabled) {
            if (env.matchesProfiles("prod")) invalid();
            if (!clientKey.matches("test_ck_[A-Za-z0-9_-]{8,}") || !secretKey.matches("test_sk_[A-Za-z0-9_-]{8,}")) invalid();
            try {
                URI url = URI.create(origin);
                boolean local = "http".equals(url.getScheme()) && java.util.Set.of("localhost", "127.0.0.1").contains(url.getHost());
                if ((!local && !"https".equals(url.getScheme())) || url.getHost() == null || url.getUserInfo() != null
                        || url.getQuery() != null || url.getFragment() != null || !url.getPath().isEmpty()) invalid();
            } catch (RuntimeException failure) { invalid(); }
        }
    }

    private static void invalid() { throw new IllegalStateException("Toss test payment configuration is invalid; use private test keys and a trusted origin"); }
    void requireEnabled() {
        if (!enabled) throw new com.zeroziba.orderstock.common.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                "TOSS_DISABLED", "카드 테스트 결제가 준비되지 않았어요.");
    }
}
