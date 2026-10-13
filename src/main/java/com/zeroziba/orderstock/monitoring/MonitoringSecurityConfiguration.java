package com.zeroziba.orderstock.monitoring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class MonitoringSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain prometheusSecurity(HttpSecurity http, Environment environment) throws Exception {
        http.securityMatcher("/actuator/prometheus")
                .authorizeHttpRequests(auth -> auth.anyRequest().access((authentication, context) -> {
                    int management = environment.getProperty("local.management.port", Integer.class,
                            environment.getProperty("management.server.port", Integer.class, -1));
                    int application = environment.getProperty("local.server.port", Integer.class,
                            environment.getProperty("server.port", Integer.class, 8080));
                    return new AuthorizationDecision(environment.matchesProfiles("observability")
                            && management > 0 && management != application
                            && context.getRequest().getLocalPort() == management
                            && context.getRequest().getMethod().equals("GET"));
                }))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> response.setStatus(403))
                        .accessDeniedHandler((request, response, failure) -> response.setStatus(403)));
        return http.build();
    }
}
