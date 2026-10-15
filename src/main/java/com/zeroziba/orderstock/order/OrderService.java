package com.zeroziba.orderstock.order;

import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.monitoring.OrderMetrics;
import com.zeroziba.orderstock.product.Product;
import com.zeroziba.orderstock.product.ProductRepository;
import com.zeroziba.orderstock.product.StockAllocator;
import com.zeroziba.orderstock.inventory.InventoryLedger;
import java.util.Comparator;
import java.util.List;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final Clock clock;
    private final OrderExpirationProperties expiration;
    private final OrderMetrics metrics;
    private final StockAllocator allocator;
    private final InventoryLedger ledger;

    public OrderService(ProductRepository productRepository, OrderRepository orderRepository,
            Clock clock, OrderExpirationProperties expiration, OrderMetrics metrics, StockAllocator allocator, InventoryLedger ledger) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.clock = clock;
        this.expiration = expiration;
        this.metrics = metrics;
        this.allocator=allocator; this.ledger=ledger;
    }

    @Transactional
    public OrderResponse create(long userId, CreateOrderRequest request) {
        return createItems(userId,java.util.List.of(request));
    }

    @Transactional
    public OrderResponse createItems(long userId,List<CreateOrderRequest> requests) {
        requireUser(userId);
        var items=new CartOrderRequest(requests).normalized();
        var allocations=new java.util.ArrayList<StockAllocator.Allocation>();
        long totalAmount=0;
        try {
            // All paths acquire product locks in ascending ID order, including reversed cart submissions.
            for(var item:items){
                var allocation=allocator.allocate(item.productId(),item.quantity()); allocations.add(allocation);
                totalAmount=Math.addExact(totalAmount,Math.multiplyExact(allocation.product().getPrice(),(long)item.quantity()));
            }
        } catch (ArithmeticException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_AMOUNT", "주문 금액이 허용 범위를 초과합니다.");
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        PurchaseOrder order = new PurchaseOrder(userId,allocations.get(0).product(),items.get(0).quantity(),totalAmount,
                now, now.plus(expiration.paymentWindow()));
        for(int i=1;i<items.size();i++)order.addItem(allocations.get(i).product(),items.get(i).quantity());
        // 저장 실패는 RuntimeException으로 전파되어 재고 변경과 주문 전체가 롤백된다.
        orderRepository.saveAndFlush(order);
        for(var allocation:allocations)ledger.record(allocation.product(),allocation.beforeStock(),InventoryLedger.Type.ORDER,order.getId().toString(),userId,"주문 재고 차감");
        metrics.afterCommit(OrderMetrics.Outcome.CREATED);
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderListResponse findRecent(long userId, Long beforeId) {
        return findRecent(userId,beforeId,null,null,null);
    }

    @Transactional(readOnly = true)
    public OrderListResponse findRecent(long userId, Long beforeId, PurchaseOrder.Status status, java.time.LocalDate from, java.time.LocalDate to) {
        requireUser(userId);
        if (beforeId != null && beforeId < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "이전 주문 ID는 1 이상이어야 합니다.");
        }
        var range=com.zeroziba.orderstock.common.DateRange.of(from,to);
        List<Long> ids = orderRepository.findFilteredIds(userId, beforeId,status,range.from(),range.until(),PageRequest.of(0, 21));
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
            int before=product.getStock();
            product.restoreStock(item.getQuantity());
            ledger.record(product,before,InventoryLedger.Type.CANCEL,order.getId().toString(),userId,"미결제 주문 취소");
        }
        // 취소 상태와 재고 복구를 함께 반영한다. DB 오류는 전체 트랜잭션을 롤백한다.
        orderRepository.flush();
        metrics.afterCommit(OrderMetrics.Outcome.CANCELLED);
        return OrderResponse.from(order);
    }

    private void requireUser(long userId) {
        if (userId < 1) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "로그인이 필요합니다.");
        }
    }
}
