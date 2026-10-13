package com.zeroziba.orderstock.config;

import java.util.Arrays;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionConfiguration {
    // DataSource와 Flyway가 만들어지기 전에 잘못된 운영 설정으로 시작하는 것을 막는다.
    @Bean
    static BeanFactoryPostProcessor productionConfigurationGuard(Environment environment) {
        return beanFactory -> {
            if (Arrays.stream(environment.getActiveProfiles()).anyMatch(profile ->
                    profile.equals("local") || profile.equals("test"))) {
                throw new IllegalStateException("prod cannot be combined with local or test profiles.");
            }
            for (String key : new String[]{"app.mock-payments.enabled", "app.mock-refunds.enabled"}) {
                if (environment.getProperty(key, Boolean.class, false)) {
                    throw new IllegalStateException("Mock operations must be disabled in prod: " + key);
                }
            }
            if (!environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) {
                throw new IllegalStateException("Secure session cookies are required in prod.");
            }
            if (!environment.getProperty("app.auth.limits.enabled", Boolean.class, true)) {
                throw new IllegalStateException("Authentication request limits must be enabled in prod.");
            }
            if (!environment.getProperty("server.forward-headers-strategy", "none").equalsIgnoreCase("none")) {
                throw new IllegalStateException("Auth limits require the original peer address.");
            }
            for (String key : new String[]{"spring.datasource.url", "spring.datasource.username", "spring.datasource.password"}) {
                String value = environment.getProperty(key);
                if (value == null || value.isBlank()) {
                    // 접속 정보의 실제 값은 오류 메시지에 포함하지 않는다.
                    throw new IllegalStateException("Missing required production setting: " + key);
                }
            }
            if (!environment.getProperty("spring.datasource.url").startsWith("jdbc:mysql://")) {
                throw new IllegalStateException("Production datasource must use MySQL.");
            }
            if (environment.getProperty("spring.datasource.username").strip().equalsIgnoreCase("root")) {
                throw new IllegalStateException("Production must use a dedicated database account.");
            }
        };
    }
}
