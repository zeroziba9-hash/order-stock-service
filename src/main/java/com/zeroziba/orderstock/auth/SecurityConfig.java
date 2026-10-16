package com.zeroziba.orderstock.auth;

import com.zeroziba.orderstock.common.ApiExceptionHandler.ApiError;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import com.zeroziba.orderstock.monitoring.AuthMetrics;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new PasswordEncoder() {
            private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder(10);

            @Override
            public String encode(CharSequence rawPassword) { return delegate.encode(rawPassword); }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return rawPassword != null && rawPassword.toString().getBytes(StandardCharsets.UTF_8).length <= 72
                        && delegate.matches(rawPassword, encodedPassword);
            }
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AppUserDetailsService userDetailsService,
                                                   PasswordEncoder passwordEncoder, ObjectMapper mapper,
                                                   AuthRequestLimiter limiter, AuthClientAddress clients, AuthMetrics authMetrics,
                                                   AccountService accounts) throws Exception {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        http.authenticationProvider(provider)
                // Construct inside this chain to avoid a second servlet-container registration.
                .addFilterAfter(new AuthRequestLimitFilter(limiter,clients,authMetrics,mapper),CsrfFilter.class)
                .addFilterBefore(new AccountVersionFilter(accounts), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/reset.html", "/assets/**", "/storefront/config", "/products", "/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness", "/auth/csrf", "/products/search", "/products/{id}", "/products/{id}/image", "/auth/password-reset/config").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/register", "/auth/login", "/auth/password-reset/request", "/auth/password-reset/confirm").permitAll()
                        .requestMatchers("/admin/**", "/admin.html", "/catalog.html").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(form -> form.loginProcessingUrl("/auth/login").usernameParameter("email")
                        .successHandler((request, response, authentication) -> write(mapper, response, 200,
                                ((AuthenticatedUser) authentication.getPrincipal()).response()))
                        .failureHandler((request, response, exception) -> write(mapper, response, 401,
                                new ApiError("INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."))))
                .logout(logout -> logout.logoutUrl("/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> write(mapper, response, 401,
                                new ApiError("AUTH_REQUIRED", "로그인이 필요합니다.")))
                        .accessDeniedHandler((request, response, exception) -> write(mapper, response, 403,
                                new ApiError("ACCESS_DENIED", "권한 또는 CSRF 토큰을 확인하세요."))));
        // 기본 CSRF 검증과 로그인 시 세션 ID 교체를 유지한다.
        return http.build();
    }

    private static void write(ObjectMapper mapper, HttpServletResponse response, int status, Object value) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(mapper.writeValueAsString(value));
    }
}
