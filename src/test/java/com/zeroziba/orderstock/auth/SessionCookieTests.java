package com.zeroziba.orderstock.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;

class SessionCookieTests {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesCookieContractAndUsesExplicitSecureSetting(boolean secure) {
        var serializer = new SessionConfiguration().sessionCookieSerializer(
                new MockEnvironment().withProperty("server.servlet.session.cookie.secure", Boolean.toString(secure)));
        var request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Proto", "https");
        var response = new MockHttpServletResponse();
        String id = "550e8400-e29b-41d4-a716-446655440000";
        serializer.writeCookieValue(new CookieSerializer.CookieValue(request, response, id));
        String cookie = response.getHeader("Set-Cookie");
        assertThat(cookie).contains("JSESSIONID=" + id, "Path=/", "HttpOnly", "SameSite=Lax");
        assertThat(cookie.contains("Secure")).isEqualTo(secure);
        assertThat(cookie).doesNotContain("Domain=", "Max-Age=");
    }
}
