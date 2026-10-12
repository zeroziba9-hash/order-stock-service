package com.zeroziba.orderstock.config;

import com.zeroziba.orderstock.common.ApiException;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class MockOperationsPolicy {
    private final boolean paymentsEnabled;
    private final boolean refundsEnabled;

    public MockOperationsPolicy(Environment environment) {
        paymentsEnabled = environment.getProperty("app.mock-payments.enabled", Boolean.class, false);
        refundsEnabled = environment.getProperty("app.mock-refunds.enabled", Boolean.class, false);
    }

    public void requirePaymentsEnabled() { requireEnabled(paymentsEnabled); }
    public void requireRefundsEnabled() { requireEnabled(refundsEnabled); }

    private void requireEnabled(boolean enabled) {
        if (!enabled) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MOCK_API_DISABLED", "이 환경에서는 모의 결제·환불 요청을 지원하지 않습니다.");
        }
    }
}
