package com.zeroziba.orderstock.order;

import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.monitoring.OrderMetrics;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotentOrderService {

    public record CreationResult(OrderResponse order, boolean replayed) { }

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    private final OrderRequestRepository requestRepository;
    private final OrderService orderService;
    private final OrderMetrics metrics;

    public IdempotentOrderService(OrderRequestRepository requestRepository, OrderService orderService, OrderMetrics metrics) {
        this.requestRepository = requestRepository;
        this.orderService = orderService;
        this.metrics = metrics;
    }

    @Transactional
    public CreationResult create(long userId, String key, CreateOrderRequest request) {
        if (userId < 1) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "로그인이 필요합니다.");
        }
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "요청 키는 영문·숫자로 시작하는 1~64자의 영문·숫자·점·밑줄·콜론·하이픈이어야 합니다.");
        }
        if (request.productId() == null || request.productId() < 1
                || request.quantity() == null || request.quantity() < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "상품 ID와 주문 수량은 1 이상이어야 합니다.");
        }
        OrderRequestRepository.StoredRequest stored = requestRepository.reserveAndLock(userId, key, request);
        if (stored.productId() != request.productId() || stored.quantity() != request.quantity()) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "이미 다른 주문 내용에 사용한 요청 키입니다. 새 주문에는 새 키를 사용하세요.");
        }
        if (stored.orderId() != null) {
            metrics.afterCommit(OrderMetrics.Outcome.REPLAYED);
            return new CreationResult(orderService.findById(userId, stored.orderId()), true);
        }
        // 별도 Spring 빈의 메서드를 호출하여 기존 주문 생성이 이 트랜잭션에 참여하도록 한다.
        OrderResponse order = orderService.create(userId, request);
        requestRepository.complete(userId, key, order.orderId());
        return new CreationResult(order, false);
    }
}
