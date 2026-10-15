package com.zeroziba.orderstock.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.session.jdbc.MySqlJdbcIndexedSessionRepositoryCustomizer;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration(proxyBeanMethods = false)
public class SessionConfiguration {
    @Bean
    CookieSerializer sessionCookieSerializer(Environment environment) {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName("JSESSIONID");
        cookie.setCookiePath("/");
        cookie.setUseBase64Encoding(false);
        cookie.setUseHttpOnlyCookie(true);
        cookie.setSameSite("Lax");
        cookie.setUseSecureCookie(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false));
        return cookie;
    }

    @Bean
    MySqlJdbcIndexedSessionRepositoryCustomizer mysqlSessionQueries() {
        return new MySqlJdbcIndexedSessionRepositoryCustomizer();
    }
}
