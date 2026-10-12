package com.zeroziba.orderstock.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zeroziba.orderstock.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationTests {
    private final ApplicationContextRunner prod = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
            .withUserConfiguration(ProductionConfiguration.class)
            .withPropertyValues("spring.datasource.url=jdbc:mysql://db:3306/order_stock_prod",
                    "spring.datasource.username=order_prod", "spring.datasource.password=test-placeholder-only",
                    "server.servlet.session.cookie.secure=true",
                    "app.mock-payments.enabled=false", "app.mock-refunds.enabled=false");

    @Test
    void validProductionSettingsStart() {
        prod.run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.datasource.url", "spring.datasource.username", "spring.datasource.password"})
    void missingCredentialsFailBeforeDatabaseCreation(String key) {
        assertRejected(prod.withPropertyValues(key + "="), "Missing required production setting: " + key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.mock-payments.enabled", "app.mock-refunds.enabled"})
    void enablingMockOperationsInProductionIsRejected(String key) {
        assertRejected(prod.withPropertyValues(key + "=true"), "Mock operations must be disabled");
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "test"})
    void learningOrTestProfileCannotBeCombinedWithProduction(String profile) {
        assertRejected(prod.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod", profile)),
                "prod cannot be combined");
    }

    @Test
    void disablingSecureCookiesIsRejected() {
        assertRejected(prod.withPropertyValues("server.servlet.session.cookie.secure=false"), "Secure session cookies");
    }

    @Test
    void rootDatabaseAccountIsRejected() {
        assertRejected(prod.withPropertyValues("spring.datasource.username=root"), "dedicated database account");
    }

    @Test
    void differentDatabaseEngineIsRejected() {
        assertRejected(prod.withPropertyValues("spring.datasource.url=jdbc:h2:mem:unsafe"), "must use MySQL");
    }

    @Test
    void mockOperationsAreDisabledWithoutAnExplicitSetting() {
        var policy = new MockOperationsPolicy(new MockEnvironment());
        assertThat(assertThrows(ApiException.class, policy::requirePaymentsEnabled).getCode()).isEqualTo("MOCK_API_DISABLED");
        assertThat(assertThrows(ApiException.class, policy::requireRefundsEnabled).getCode()).isEqualTo("MOCK_API_DISABLED");
    }

    @Test
    void mockFlagsAreIndependent() {
        var paymentOnly = new MockOperationsPolicy(new MockEnvironment().withProperty("app.mock-payments.enabled", "true"));
        paymentOnly.requirePaymentsEnabled();
        assertThrows(ApiException.class, paymentOnly::requireRefundsEnabled);
        var refundOnly = new MockOperationsPolicy(new MockEnvironment().withProperty("app.mock-refunds.enabled", "true"));
        refundOnly.requireRefundsEnabled();
        assertThrows(ApiException.class, refundOnly::requirePaymentsEnabled);
    }

    private void assertRejected(ApplicationContextRunner runner, String expected) {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()).getMessage()).contains(expected);
        });
    }
}
