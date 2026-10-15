package com.zeroziba.orderstock.auth;

import com.zeroziba.orderstock.monitoring.AuthMetrics.Endpoint;
import com.zeroziba.orderstock.monitoring.AuthMetrics.Reason;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class AuthRequestLimiter {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private final AuthLimitProperties settings;
    private final LongSupplier ticker;
    private final byte[] salt = new byte[32];
    private final Map<String, Bucket> buckets = new HashMap<>();
    private long lastCleanup;

    private static final class Bucket {
        long started, window;
        int count, inFlight;
        Bucket(long now, long window) { this.started=now; this.window=window; }
        boolean expired(long now) { return now-started >= window; }
        void renew(long now) { if (expired(now)) { started=now; count=0; } }
        long retryAfter(long now) { return Math.max(1, (window-(now-started)+SECOND-1)/SECOND); }
    }
    public static final class Admission {
        private final Bucket credential;
        private final Reason rejection;
        private final long retryAfter;
        private boolean finished;
        private Admission(Bucket credential, Reason rejection, long retryAfter) {
            this.credential=credential; this.rejection=rejection; this.retryAfter=retryAfter;
        }
        public boolean allowed() { return rejection==null; }
        public Reason reason() { return rejection; }
        public long retryAfter() { return retryAfter; }
    }

    public AuthRequestLimiter(AuthLimitProperties settings, MeterRegistry registry) {
        this(settings, registry, System::nanoTime);
    }
    AuthRequestLimiter(AuthLimitProperties settings, MeterRegistry registry, LongSupplier ticker) {
        this.settings=settings; this.ticker=ticker; this.lastCleanup=ticker.getAsLong();
        new SecureRandom().nextBytes(salt);
        Gauge.builder("stockroom.auth.limit.entries", this, AuthRequestLimiter::trackedEntries)
                .description("Bounded process-local authentication limiter entries").register(registry);
        Gauge.builder("stockroom.auth.limit.capacity", settings, value -> value.enabled() ? value.maxEntries() : 0)
                .description("Configured authentication limiter capacity; zero when disabled").register(registry);
    }

    public synchronized Admission begin(Endpoint endpoint, String client, String email) {
        if (!settings.enabled()) return new Admission(null,null,0);
        long now=ticker.getAsLong(); cleanup(now);
        long window=(endpoint!=Endpoint.REGISTER ? settings.loginWindow() : settings.registrationWindow()).toNanos();
        int limit=endpoint!=Endpoint.REGISTER ? settings.loginAttempts() : settings.registrationAttempts();
        Bucket request=bucket(endpoint+":"+fingerprint(client),now,window);
        if (request==null) return rejected(Reason.CAPACITY,1);
        request.renew(now);
        if (request.count>=limit) return rejected(Reason.REQUEST_RATE,request.retryAfter(now));
        request.count++;
        if (endpoint==Endpoint.REGISTER) return new Admission(null,null,0);
        // Oversize usernames share a bounded key. Never retain the raw email/IP/password.
        String normalized=email==null ? "" : AppUserDetailsService.normalizeEmail(email);
        if (normalized.length()>254) normalized="<oversize>";
        Bucket credential=bucket("failure:"+fingerprint(client+"\u0000"+normalized),now,settings.failureWindow().toNanos());
        if (credential==null) return rejected(Reason.CAPACITY,1);
        credential.renew(now);
        if (credential.count+credential.inFlight>=settings.failedLogins())
            return rejected(Reason.CREDENTIAL_FAILURES,credential.retryAfter(now));
        credential.inFlight++;
        return new Admission(credential,null,0);
    }

    public synchronized void finish(Admission admission, int status) {
        if (!admission.allowed() || admission.finished) return;
        admission.finished=true;
        Bucket credential=admission.credential;
        if (credential==null) return;
        credential.renew(ticker.getAsLong()); credential.inFlight--;
        if (status==401) credential.count++;
        else if (status>=200 && status<300) credential.count=0;
    }
    synchronized int trackedEntries() { return buckets.size(); }
    private Bucket bucket(String key, long now, long window) {
        Bucket existing=buckets.get(key);
        if (existing!=null) return existing;
        if (buckets.size()>=settings.maxEntries()) return null;
        Bucket created=new Bucket(now,window); buckets.put(key,created); return created;
    }
    private void cleanup(long now) {
        // At most one bounded scan per second, including when an attacker fills capacity.
        if (now-lastCleanup<SECOND) return;
        buckets.values().removeIf(bucket->bucket.inFlight==0 && bucket.expired(now)); lastCleanup=now;
    }
    private Admission rejected(Reason reason,long seconds) { return new Admission(null,reason,seconds); }
    private String fingerprint(String value) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(salt,"HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Auth limiter fingerprint unavailable",failure); }
    }
}
