package com.zeroziba.orderstock.config;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StorefrontController {
    public record StorefrontConfig(boolean mockPayments, boolean mockRefunds) { }
    private final StorefrontConfig config;

    public StorefrontController(Environment environment) {
        config = new StorefrontConfig(
                environment.getProperty("app.mock-payments.enabled", Boolean.class, false),
                environment.getProperty("app.mock-refunds.enabled", Boolean.class, false));
    }

    @GetMapping("/storefront/config")
    public StorefrontConfig config() { return config; }
}
