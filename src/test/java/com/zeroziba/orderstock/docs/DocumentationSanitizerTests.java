package com.zeroziba.orderstock.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

class DocumentationSanitizerTests {
    private final DocumentationSanitizer sanitizer = new DocumentationSanitizer();

    @Test
    void masksJsonSecretsIncludingEscapedQuotesWithoutCorruptingKorean() {
        String json = "{\"email\":\"한국@example.test\",\"password\":\"a\\\"b\\\\c\",\"currentPassword\":\"old-secret\",\"newPassword\":\"new-secret\",\"token\":\"private-token\"}";
        var sanitized = JsonMapper.builder().build().readTree(sanitizer.sanitize(json.getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON));
        assertThat(sanitized.path("email").asString()).isEqualTo("한국@example.test");
        assertThat(sanitized.path("password").asString()).isEqualTo("<redacted>");
        assertThat(sanitized.path("token").asString()).isEqualTo("<redacted>");
        assertThat(sanitized.path("currentPassword").asString()).isEqualTo("<redacted>");
        assertThat(sanitized.path("newPassword").asString()).isEqualTo("<redacted>");
    }

    @Test
    void masksEveryFormPasswordAndPreservesOtherParameters() {
        String form = "password=a%26b&email=reader%40example.test&password=second";
        assertThat(new String(sanitizer.sanitize(form.getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_FORM_URLENCODED), StandardCharsets.UTF_8))
                .isEqualTo("password=%3Credacted%3E&email=reader%40example.test&password=%3Credacted%3E");
    }

    @Test
    void doesNotInventFieldsOrChangeEmptyBodies() {
        var json = JsonMapper.builder().build().readTree(sanitizer.sanitize("{\"stock\":10}".getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON));
        assertThat(json.has("password")).isFalse();
        assertThat(json.has("token")).isFalse();
        assertThat(json.path("stock").asInt()).isEqualTo(10);
        assertThat(sanitizer.sanitize(new byte[0], MediaType.APPLICATION_JSON)).isEmpty();
    }
}
