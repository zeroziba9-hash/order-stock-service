package com.zeroziba.orderstock.refund;

import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.config.MockOperationsPolicy;
import com.zeroziba.orderstock.order.OrderItem;
import com.zeroziba.orderstock.order.OrderRepository;
import com.zeroziba.orderstock.payment.PaymentAttempt;
import com.zeroziba.orderstock.payment.PaymentRepository;
import com.zeroziba.orderstock.product.Product;
import com.zeroziba.orderstock.product.ProductRepository;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundService {
    public record RefundResult(RefundResponse refund, boolean replayed) { }
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final ProductRepository products;
    private final RefundRepository refunds;
    private final MockOperationsPolicy policy;

    public RefundService(OrderRepository orders, PaymentRepository payments, ProductRepository products,
            RefundRepository refunds, MockOperationsPolicy policy) {
        this.orders = orders;
        this.payments = payments;
        this.products = products;
        this.refunds = refunds;
        this.policy = policy;
    }

    @Transactional
    public RefundResult refund(long userId, long orderId, String key, MockRefundRequest request) {
        validateIds(userId, orderId);
        policy.requireRefundsEnabled();
        if (key == null || !KEY.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "환불 요청 키는 영문·숫자로 시작하는 1~64자의 영문·숫자·._:- 조합이어야 합니다.");
        }
        if (request == null || request.result() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "모의 환불 결과가 필요합니다.");
        }
        // 결제·취소·환불 모두 같은 주문 행부터 잠근다.
        var order = orders.findByIdForUpdate(orderId, userId).orElseThrow(this::notFound);
        var existing = refunds.findByOrderIdAndRequestKey(orderId, key);
        if (existing.isPresent()) {
            var refund = existing.get();
            boolean success = request.result() == MockRefundRequest.Result.SUCCESS;
            if (success != (refund.getStatus() == RefundAttempt.Status.SUCCEEDED)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "같은 환불 요청 키로 결과 선택을 바꿀 수 없습니다.");
            }
            return new RefundResult(RefundResponse.from(refund), true);
        }
        order.requireRefundable();
        var payment = payments.findByOrderIdAndStatus(orderId, PaymentAttempt.Status.SUCCEEDED)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "PAYMENT_STATE_CONFLICT", "주문의 성공 결제 이력을 확인할 수 없습니다."));
        if (payment.getAmount() != order.getTotalAmount()) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_STATE_CONFLICT", "주문 금액과 결제 이력이 일치하지 않습니다.");
        }
        var refund = new RefundAttempt(orderId, payment.getId(), key, payment.getAmount(), request.result());
        if (refund.getStatus() == RefundAttempt.Status.SUCCEEDED) {
            order.markRefunded(refund.getCreatedAt());
            for (OrderItem item : order.getItems().stream()
                    .sorted(Comparator.comparing(OrderItem::getProductId)).toList()) {
                Product product = products.findByIdForUpdate(item.getProductId())
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다."));
                product.restoreStock(item.getQuantity());
            }
        }
        // 실패 이력도 커밋한다. 저장 오류는 환불 이력·주문 상태·재고 복구 전체를 롤백한다.
        refunds.saveAndFlush(refund);
        return new RefundResult(RefundResponse.from(refund), false);
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> history(long userId, long orderId) {
        validateIds(userId, orderId);
        orders.findByIdAndUserId(orderId, userId).orElseThrow(this::notFound);
        return refunds.findByOrderIdOrderByIdAsc(orderId).stream().map(RefundResponse::from).toList();
    }

    private void validateIds(long userId, long orderId) {
        if (userId < 1) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "로그인이 필요합니다.");
        }
        if (orderId < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "주문 ID는 1 이상이어야 합니다.");
        }
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.");
    }
}
