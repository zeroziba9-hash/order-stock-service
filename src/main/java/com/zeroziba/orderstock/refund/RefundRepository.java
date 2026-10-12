package com.zeroziba.orderstock.refund;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<RefundAttempt, Long> {
    Optional<RefundAttempt> findByOrderIdAndRequestKey(long orderId, String requestKey);
    List<RefundAttempt> findByOrderIdOrderByIdAsc(long orderId);
}
