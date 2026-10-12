package com.zeroziba.orderstock.order;

import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.product.Product;
import com.zeroziba.orderstock.product.ProductRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;

    public OrderService(ProductRepository productRepository, OrderRepository orderRepository) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderResponse create(long userId, CreateOrderRequest request) {
        requireUser(userId);
        if (request.productId() == null || request.productId() < 1
                || request.quantity() == null || request.quantity() < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "상품 ID와 주문 수량은 1 이상이어야 합니다.");
        }
        Product product = productRepository.findByIdForUpdate(request.productId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다."));
        long totalAmount;
        try {
            totalAmount = Math.multiplyExact(product.getPrice(), (long) request.quantity());
        } catch (ArithmeticException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_AMOUNT", "주문 금액이 허용 범위를 초과합니다.");
        }
        product.decreaseStock(request.quantity());
        PurchaseOrder order = new PurchaseOrder(userId, product, request.quantity(), totalAmount);
        // 저장 실패는 RuntimeException으로 전파되어 재고 변경과 주문 전체가 롤백된다.
        orderRepository.saveAndFlush(order);
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderListResponse findRecent(long userId, Long beforeId) {
        requireUser(userId);
        if (beforeId != null && beforeId < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "이전 주문 ID는 1 이상이어야 합니다.");
        }
        List<Long> ids = orderRepository.findRecentIds(userId, beforeId, PageRequest.of(0, 21));
        boolean hasMore = ids.size() > 20;
        List<Long> page = ids.stream().limit(20).toList();
        List<OrderResponse> orders = page.isEmpty() ? List.of()
                : orderRepository.findByUserIdAndIdInOrderByIdDesc(userId, page).stream().map(OrderResponse::from).toList();
        return new OrderListResponse(orders, hasMore ? page.get(page.size() - 1) : null);
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(long userId, long orderId) {
        requireUser(userId);
        if (orderId < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "주문 ID는 1 이상이어야 합니다.");
        }
        return orderRepository.findByIdAndUserId(orderId, userId).map(OrderResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));
    }

    @Transactional
    public OrderResponse cancel(long userId, long orderId) {
        requireUser(userId);
        if (orderId < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "주문 ID는 1 이상이어야 합니다.");
        }
        PurchaseOrder order = orderRepository.findByIdForUpdate(orderId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."));
        if (!order.cancel()) {
            // 이미 취소된 주문은 최초 취소 시각과 결과를 그대로 반환한다.
            return OrderResponse.from(order);
        }
        for (OrderItem item : order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getProductId)).toList()) {
            Product product = productRepository.findByIdForUpdate(item.getProductId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다."));
            product.restoreStock(item.getQuantity());
        }
        // 취소 상태와 재고 복구를 함께 반영한다. DB 오류는 전체 트랜잭션을 롤백한다.
        orderRepository.flush();
        return OrderResponse.from(order);
    }

    private void requireUser(long userId) {
        if (userId < 1) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "로그인이 필요합니다.");
        }
    }
}
