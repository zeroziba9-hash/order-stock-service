package com.zeroziba.orderstock.auth;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
class PasswordResetSettingsTests {
    MockEnvironment local(){return new MockEnvironment().withProperty("app.password-reset.enabled","true").withProperty("app.password-reset.base-url","http://localhost:18083").withProperty("app.password-reset.from","no-reply@stockroom.test").withProperty("spring.mail.host","mailpit");}
    @Test void disabledRecoveryDoesNotRequireMailCredentials(){assertThat(new PasswordResetSettings(new MockEnvironment()).enabled()).isFalse();}
    @Test void trustedBaseUrlIsUsedForTheFragmentLink(){assertThat(new PasswordResetSettings(local()).link("sample")).isEqualTo("http://localhost:18083/reset.html#token=sample");}
    @ParameterizedTest @ValueSource(strings={"https://user@shop.example","https://shop.example/path","https://shop.example?next=elsewhere","https://shop.example#secret","javascript:alert(1)"}) void untrustedLinkBasesAreRejected(String url){assertThatThrownBy(()->new PasswordResetSettings(local().withProperty("app.password-reset.base-url",url))).isInstanceOf(IllegalStateException.class);}
    @Test void productionRequiresHttpsAndAuthenticatedMandatoryStarttls(){var env=local();env.setActiveProfiles("prod");assertThatThrownBy(()->new PasswordResetSettings(env)).isInstanceOf(IllegalStateException.class);env.withProperty("app.password-reset.base-url","https://shop.example").withProperty("app.password-reset.from","no-reply@shop.example").withProperty("spring.mail.username","test-user").withProperty("spring.mail.password","test-placeholder").withProperty("spring.mail.properties.mail.smtp.auth","true").withProperty("spring.mail.properties.mail.smtp.starttls.enable","true").withProperty("spring.mail.properties.mail.smtp.starttls.required","true");assertThat(new PasswordResetSettings(env).enabled()).isTrue();for(var url:java.util.List.of("https://127.0.0.1","https://[::1]","https://localhost","https://shop.localhost")){env.withProperty("app.password-reset.base-url",url);assertThatThrownBy(()->new PasswordResetSettings(env)).isInstanceOf(IllegalStateException.class);}}
}
