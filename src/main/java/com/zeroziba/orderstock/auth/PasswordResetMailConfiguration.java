package com.zeroziba.orderstock.auth;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
@Configuration(proxyBeanMethods=false)
public class PasswordResetMailConfiguration {
    @Bean("passwordResetMailExecutor")
    ThreadPoolTaskExecutor executor(){var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(2);executor.setMaxPoolSize(2);executor.setQueueCapacity(100);executor.setThreadNamePrefix("password-reset-mail-");executor.setAwaitTerminationSeconds(10);return executor;}
}
