package com.zeroziba.orderstock.order;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.time.LocalDateTime;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<PurchaseOrder, Long> {

    @EntityGraph(attributePaths = "items")
    Optional<PurchaseOrder> findByIdAndUserId(Long id, Long userId);

    // Page IDs first: paginating a collection fetch can load every order into memory.
    @Query("select o.id from PurchaseOrder o where o.userId = :userId and (:beforeId is null or o.id < :beforeId) order by o.id desc")
    List<Long> findRecentIds(@Param("userId") long userId, @Param("beforeId") Long beforeId, Pageable pageable);

    @EntityGraph(attributePaths = "items")
    List<PurchaseOrder> findByUserIdAndIdInOrderByIdDesc(Long userId, List<Long> ids);

    // 결제와 취소가 같은 주문 행 잠금을 공유한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrder o where o.id = :id and o.userId = :userId")
    Optional<PurchaseOrder> findByIdForUpdate(@Param("id") long id, @Param("userId") long userId);

    @Query("select o.id from PurchaseOrder o where o.status = 'CREATED' and o.expiresAt <= :now order by o.expiresAt, o.id")
    List<Long> findDueIds(@Param("now") LocalDateTime now, Pageable pageable);

    // Internal expiration worker only; HTTP endpoints always use the owner-scoped lookup above.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrder o where o.id = :id")
    Optional<PurchaseOrder> findByIdForExpiration(@Param("id") long id);
}
