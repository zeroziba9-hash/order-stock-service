package com.zeroziba.orderstock.auth;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(AuthLimitProperties.class)
public class AuthLimitConfiguration {
    @Bean AuthRequestLimiter authRequestLimiter(AuthLimitProperties properties, MeterRegistry registry, Environment env) {
        if (properties.enabled() && !env.getProperty("server.forward-headers-strategy","none").equalsIgnoreCase("none"))
            throw new IllegalStateException("Auth limits require the original peer address; use explicit trusted proxies");
        if (env.matchesProfiles("prod") && !properties.enabled())
            throw new IllegalStateException("Authentication request limits must be enabled in prod");
        return new AuthRequestLimiter(properties,registry);
    }
    @Bean AuthClientAddress authClientAddress(AuthLimitProperties properties) { return new AuthClientAddress(properties); }
}
