package com.zeroziba.orderstock.payment.toss;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class TossSettingsTests {
    MockEnvironment valid() { return new MockEnvironment().withProperty("app.toss.enabled","true").withProperty("app.toss.client-key","test_ck_fixture123").withProperty("app.toss.secret-key","test_sk_fixture123").withProperty("app.toss.origin","http://localhost:18083"); }
    @Test void disabledByDefaultWithoutKeys() { assertThat(new TossSettings(new MockEnvironment()).enabled).isFalse(); }
    @Test void validTestConfigurationUsesFixedHttpsProvider() { assertThat(new TossSettings(valid()).api.toString()).isEqualTo("https://api.tosspayments.com"); }
    @Test void liveKeysRejected() { assertThatThrownBy(()->new TossSettings(valid().withProperty("app.toss.secret-key","live_sk_fixture123"))).isInstanceOf(IllegalStateException.class); }
    @Test void productionCannotEnableTestPayments() { var env=valid();env.setActiveProfiles("prod");assertThatThrownBy(()->new TossSettings(env)).isInstanceOf(IllegalStateException.class); }
    @Test void stubNeedsExplicitProfile() { assertThatThrownBy(()->new TossSettings(valid().withProperty("app.toss.stub-enabled","true").withProperty("app.toss.stub-url","http://localhost:1234"))).isInstanceOf(IllegalStateException.class); }
    @ParameterizedTest @ValueSource(strings={"https://user:pass@example.test","http://example.test","https://example.test/path","https://example.test?next=a","https://example.test/#a","//example.test","https://example.test/"})
    void rejectsUntrustedCallbackOrigins(String origin) { assertThatThrownBy(()->new TossSettings(valid().withProperty("app.toss.origin",origin))).isInstanceOf(IllegalStateException.class); }
    @ParameterizedTest @ValueSource(strings={"https://localhost:1234","http://127.0.0.2:1234","http://evil.test:1234","http://localhost:1234/path","http://user@localhost:1234"})
    void rejectsUnsafeStubEndpoints(String endpoint) { var env=valid().withProperty("app.toss.stub-enabled","true").withProperty("app.toss.stub-url",endpoint);env.setActiveProfiles("pg-stub");assertThatThrownBy(()->new TossSettings(env)).isInstanceOf(IllegalStateException.class); }
}
