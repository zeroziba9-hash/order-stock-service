package com.zeroziba.orderstock.order;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<PurchaseOrder, Long> {

    @EntityGraph(attributePaths = "items")
    Optional<PurchaseOrder> findByIdAndUserId(Long id, Long userId);

    // 결제와 취소가 같은 주문 행 잠금을 공유한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrder o where o.id = :id and o.userId = :userId")
    Optional<PurchaseOrder> findByIdForUpdate(@Param("id") long id, @Param("userId") long userId);
}
