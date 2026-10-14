package com.zeroziba.orderstock.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.zeroziba.orderstock.monitoring.AuthMetrics.Endpoint;
import com.zeroziba.orderstock.monitoring.AuthMetrics.Reason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AuthRequestLimiterTests {
    private final AtomicLong clock = new AtomicLong();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private AuthLimitProperties settings(int global, int failures, int capacity) {
        return new AuthLimitProperties(true,global,global,failures,Duration.ofSeconds(10),
                Duration.ofSeconds(10),Duration.ofSeconds(10),capacity,List.of());
    }
    private AuthRequestLimiter limiter(int global, int failures, int capacity) {
        return new AuthRequestLimiter(settings(global,failures,capacity),registry,clock::get);
    }
    @Test void fixedWindowDoesNotSlideOnRejectedRequestsAndRoundsRetryUp() {
        var limiter=limiter(1,5,16);
        limiter.finish(limiter.begin(Endpoint.LOGIN,"ip","a"),200);
        clock.set(TimeUnit.MILLISECONDS.toNanos(1100));
        var rejected=limiter.begin(Endpoint.LOGIN,"ip","b");
        assertThat(rejected.reason()).isEqualTo(Reason.REQUEST_RATE);
        assertThat(rejected.retryAfter()).isEqualTo(9);
        clock.set(TimeUnit.SECONDS.toNanos(10));
        assertThat(limiter.begin(Endpoint.LOGIN,"ip","a").allowed()).isTrue();
    }
    @Test void normalizesEmailButScopesFailuresToClient() {
        var limiter=limiter(100,1,16);
        limiter.finish(limiter.begin(Endpoint.LOGIN,"ip"," A@Example.test "),401);
        assertThat(limiter.begin(Endpoint.LOGIN,"ip","a@example.test").reason()).isEqualTo(Reason.CREDENTIAL_FAILURES);
        assertThat(limiter.begin(Endpoint.LOGIN,"another-ip","a@example.test").allowed()).isTrue();
        assertThat(limiter.begin(Endpoint.LOGIN,"ip","another@example.test").allowed()).isTrue();
    }
    @Test void successResetsFailuresButNeverResetsClientRequestBudget() {
        var limiter=limiter(4,2,16);
        limiter.finish(limiter.begin(Endpoint.LOGIN,"ip","a"),401);
        limiter.finish(limiter.begin(Endpoint.LOGIN,"ip","a"),200);
        limiter.finish(limiter.begin(Endpoint.LOGIN,"ip","a"),401);
        limiter.finish(limiter.begin(Endpoint.LOGIN,"ip","a"),200);
        assertThat(limiter.begin(Endpoint.LOGIN,"ip","a").reason()).isEqualTo(Reason.REQUEST_RATE);
    }
    @Test void completionIsIdempotentAndValidationErrorsReleaseReservation() {
        var limiter=limiter(100,1,16);
        var admission=limiter.begin(Endpoint.LOGIN,"ip","a");
        limiter.finish(admission,400); limiter.finish(admission,401);
        var next=limiter.begin(Endpoint.LOGIN,"ip","a");
        assertThat(next.allowed()).isTrue(); limiter.finish(next,401);
        assertThat(limiter.begin(Endpoint.LOGIN,"ip","a").allowed()).isFalse();
    }
    @Test void registrationAndLoginHaveIndependentBudgets() {
        var limiter=limiter(1,5,16);
        assertThat(limiter.begin(Endpoint.REGISTER,"ip",null).allowed()).isTrue();
        assertThat(limiter.begin(Endpoint.REGISTER,"ip",null).allowed()).isFalse();
        assertThat(limiter.begin(Endpoint.LOGIN,"ip","a").allowed()).isTrue();
    }
    @Test void capacityIsBoundedWithoutEvictingActiveLimitsAndRecoversAfterExpiry() {
        var limiter=limiter(100,5,16);
        for (int i=0;i<16;i++) assertThat(limiter.begin(Endpoint.REGISTER,"ip"+i,null).allowed()).isTrue();
        assertThat(limiter.begin(Endpoint.REGISTER,"overflow",null).reason()).isEqualTo(Reason.CAPACITY);
        assertThat(limiter.trackedEntries()).isEqualTo(16);
        assertThat(registry.get("stockroom.auth.limit.entries").gauge().value()).isEqualTo(16);
        assertThat(registry.get("stockroom.auth.limit.capacity").gauge().value()).isEqualTo(16);
        clock.set(TimeUnit.SECONDS.toNanos(10));
        assertThat(limiter.begin(Endpoint.REGISTER,"overflow",null).allowed()).isTrue();
        assertThat(limiter.trackedEntries()).isEqualTo(1);
    }
    @Test void expiredBucketWithInflightWorkCannotBeEvictedToBypassLimit() {
        var limiter=limiter(100,1,16);
        var first=limiter.begin(Endpoint.LOGIN,"ip","a");
        clock.set(TimeUnit.SECONDS.toNanos(11));
        assertThat(limiter.begin(Endpoint.LOGIN,"ip","a").allowed()).isFalse();
        limiter.finish(first,401);
        assertThat(limiter.begin(Endpoint.LOGIN,"ip","a").allowed()).isFalse();
        clock.set(TimeUnit.SECONDS.toNanos(21));
        assertThat(limiter.begin(Endpoint.LOGIN,"ip","a").allowed()).isTrue();
    }
    @Test void concurrentWaveReservesOnlyFiveCredentialSlotsBeforeHashing() throws Exception {
        var limiter=limiter(100,5,100); var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(32);
        try {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<AuthRequestLimiter.Admission>>();
            for(int i=0;i<32;i++) futures.add(pool.submit(()-> { start.await(); return limiter.begin(Endpoint.LOGIN,"ip","a"); }));
            start.countDown(); int accepted=0;
            for(var future:futures) { var result=future.get(10,TimeUnit.SECONDS); if(result.allowed()) accepted++; }
            assertThat(accepted).isEqualTo(5);
        } finally { start.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void invalidBoundsFailAtStartup() {
        assertThrows(IllegalArgumentException.class,()->settings(0,5,16));
        assertThrows(IllegalArgumentException.class,()->settings(100,0,16));
        assertThrows(IllegalArgumentException.class,()->settings(100,5,15));
        assertThrows(IllegalArgumentException.class,()->new AuthLimitProperties(true,1,1,1,Duration.ZERO,
                Duration.ofSeconds(1),Duration.ofSeconds(1),16,List.of()));
    }
    @Test void disabledLimiterExportsZeroCapacityAndCreatesNoEntries() {
        var disabled=new AuthLimitProperties(false,1,1,1,Duration.ofSeconds(10),
                Duration.ofSeconds(10),Duration.ofSeconds(10),16,List.of());
        var limiter=new AuthRequestLimiter(disabled,registry,clock::get);
        assertThat(limiter.begin(Endpoint.LOGIN,"ip","a").allowed()).isTrue();
        assertThat(limiter.trackedEntries()).isZero();
        assertThat(registry.get("stockroom.auth.limit.capacity").gauge().value()).isZero();
    }
}
