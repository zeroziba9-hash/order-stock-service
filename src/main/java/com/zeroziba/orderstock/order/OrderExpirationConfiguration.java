package com.zeroziba.orderstock.order;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderExpirationProperties.class)
@EnableScheduling
public class OrderExpirationConfiguration {
    @Bean
    Clock orderClock() {
        return Clock.systemUTC();
    }
}
