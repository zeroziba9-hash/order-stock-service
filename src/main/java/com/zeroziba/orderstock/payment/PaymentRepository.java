package com.zeroziba.orderstock.payment;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<PaymentAttempt, Long> {
    Optional<PaymentAttempt> findByOrderIdAndRequestKey(long orderId, String requestKey);
    List<PaymentAttempt> findByOrderIdOrderByIdAsc(long orderId);
    Optional<PaymentAttempt> findByOrderIdAndStatus(long orderId, PaymentAttempt.Status status);
}
