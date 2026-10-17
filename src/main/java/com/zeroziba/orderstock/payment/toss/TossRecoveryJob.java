package com.zeroziba.orderstock.payment.toss;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TossRecoveryJob {
    private static final Logger log=LoggerFactory.getLogger(TossRecoveryJob.class);
    private final TossSettings settings;
    private final TossTransactions transactions;
    private final TossStore store;
    private final TossPaymentService service;
    public TossRecoveryJob(TossSettings settings,TossTransactions transactions,TossStore store,TossPaymentService service) {
        this.settings=settings;this.transactions=transactions;this.store=store;this.service=service;
    }
    @Scheduled(fixedDelayString="${app.toss.recovery-delay:10000}",initialDelayString="${app.toss.recovery-initial-delay:10000}")
    public void run() {
        if (!settings.enabled) return;
        for (String id:store.due(transactions.now())) {
            try { service.recover(id); }
            catch (RuntimeException failure) { log.warn("toss_recovery_failed type={}",failure.getClass().getSimpleName()); }
        }
    }
}
