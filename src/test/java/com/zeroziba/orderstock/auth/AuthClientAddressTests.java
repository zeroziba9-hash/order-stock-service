package com.zeroziba.orderstock.auth;

import static org.assertj.core.api.Assertions.assertThat;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthClientAddressTests {
    private AuthClientAddress resolver(List<String> proxies) {
        return new AuthClientAddress(new AuthLimitProperties(true,120,60,5,Duration.ofMinutes(1),
                Duration.ofMinutes(10),Duration.ofMinutes(5),10000,proxies));
    }
    private MockHttpServletRequest request(String peer, String claimed) {
        var request=new MockHttpServletRequest(); request.setRemoteAddr(peer);
        request.addHeader(AuthClientAddress.HEADER,claimed);
        request.addHeader("X-Forwarded-For","192.0.2.99");
        request.addHeader("Forwarded","for=192.0.2.99"); return request;
    }
    @Test void ignoresAllClaimsByDefaultAndFromUntrustedPeers() {
        assertThat(resolver(List.of()).resolve(request("127.0.0.1","203.0.113.1"))).isEqualTo("127.0.0.1");
        assertThat(resolver(List.of("10.0.0.1/32")).resolve(request("10.0.0.2","203.0.113.1"))).isEqualTo("10.0.0.2");
    }
    @Test void acceptsOneNumericClientAddressOnlyFromExplicitProxy() {
        assertThat(resolver(List.of("10.0.0.1/32")).resolve(request("10.0.0.1","203.0.113.1"))).isEqualTo("203.0.113.1");
        assertThat(resolver(List.of("::1/128")).resolve(request("::1","2001:db8::1"))).contains("2001:db8:");
    }
    @ParameterizedTest @ValueSource(strings={"localhost","deadbeef","a.b","203.0.113.1, 203.0.113.2","999.999.999.999","[::1]",""})
    void malformedOrHostnameClaimsFallBackToPeerWithoutDnsLookup(String value) {
        assertThat(resolver(List.of("10.0.0.1/32")).resolve(request("10.0.0.1",value))).isEqualTo("10.0.0.1");
    }
    @Test void defaultBindingIsEnabledWithBoundedSettings() {
        new ApplicationContextRunner().withUserConfiguration(AuthLimitConfiguration.class)
                .withBean(SimpleMeterRegistry.class).run(context->{
                    assertThat(context).hasNotFailed();
                    var properties=context.getBean(AuthLimitProperties.class);
                    assertThat(properties.enabled()).isTrue(); assertThat(properties.loginAttempts()).isEqualTo(120);
                    assertThat(properties.registrationAttempts()).isEqualTo(60);
                    assertThat(properties.trustedProxies()).isEmpty();
                });
    }
    @Test void automaticForwardingCannotSilentlyChangeTrustedPeer() {
        new ApplicationContextRunner().withUserConfiguration(AuthLimitConfiguration.class)
                .withBean(SimpleMeterRegistry.class).withPropertyValues("server.forward-headers-strategy=framework")
                .run(context->assertThat(context).hasFailed());
    }
    @Test void composeEnvironmentNamesBindShortWindowsAndTrustedProxy() {
        new ApplicationContextRunner().withUserConfiguration(AuthLimitConfiguration.class)
                .withBean(SimpleMeterRegistry.class).withInitializer(context->context.getEnvironment().getPropertySources()
                        .addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource("compose-test",java.util.Map.of(
                                "APP_AUTH_LIMITS_LOGIN_ATTEMPTS","6",
                                "APP_AUTH_LIMITS_FAILURE_WINDOW","PT3S",
                                "APP_AUTH_LIMITS_TRUSTED_PROXIES","10.0.0.1/32"))))
                .run(context->{
                    assertThat(context).hasNotFailed();
                    var settings=context.getBean(AuthLimitProperties.class);
                    assertThat(settings.loginAttempts()).isEqualTo(6);
                    assertThat(settings.failureWindow()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(settings.trustedProxies()).containsExactly("10.0.0.1/32");
                });
    }
}
