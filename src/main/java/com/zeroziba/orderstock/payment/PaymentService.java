package com.zeroziba.orderstock.payment;

import com.zeroziba.orderstock.common.ApiException;
import com.zeroziba.orderstock.config.MockOperationsPolicy;
import com.zeroziba.orderstock.order.OrderRepository;
import com.zeroziba.orderstock.order.PurchaseOrder;
import java.util.List;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
    public record PaymentResult(PaymentResponse payment, boolean replayed) { }
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final MockOperationsPolicy policy;
    private final Clock clock;

    public PaymentService(OrderRepository orderRepository, PaymentRepository paymentRepository, MockOperationsPolicy policy,
            Clock clock) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional
    public PaymentResult pay(long userId, long orderId, String key, MockPaymentRequest request) {
        validateIds(userId, orderId);
        policy.requirePaymentsEnabled();
        if (key == null || !KEY.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "결제 요청 키는 영문·숫자로 시작하는 1~64자의 영문·숫자·._:- 조합이어야 합니다.");
        }
        if (request == null || request.result() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "모의 결제 결과가 필요합니다.");
        }
        // 부모 주문 잠금을 먼저 획득하므로 서로 다른 결제 키와 취소도 직렬화된다.
        PurchaseOrder order = orderRepository.findByIdForUpdate(orderId, userId)
                .orElseThrow(this::notFound);
        var existing = paymentRepository.findByOrderIdAndRequestKey(orderId, key);
        if (existing.isPresent()) {
            PaymentAttempt payment = existing.get();
            boolean success = request.result() == MockPaymentRequest.Result.SUCCESS;
            if (success != (payment.getStatus() == PaymentAttempt.Status.SUCCEEDED)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "같은 결제 요청 키로 결과 선택을 바꿀 수 없습니다.");
            }
            // 주문이 이후 취소되더라도 원래 실패 결과는 그대로 재생한다.
            return new PaymentResult(PaymentResponse.from(payment), true);
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        order.requirePayable(now);
        PaymentAttempt payment = new PaymentAttempt(orderId, key, order.getTotalAmount(), request.result(), now);
        if (payment.getStatus() == PaymentAttempt.Status.SUCCEEDED) {
            order.markPaid(payment.getCreatedAt());
        }
        // 모의 승인 거절도 정상적인 이력으로 커밋한다. DB 저장 오류는 상태와 이력을 모두 롤백한다.
        paymentRepository.saveAndFlush(payment);
        return new PaymentResult(PaymentResponse.from(payment), false);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> history(long userId, long orderId) {
        validateIds(userId, orderId);
        orderRepository.findByIdAndUserId(orderId, userId).orElseThrow(this::notFound);
        return paymentRepository.findByOrderIdOrderByIdAsc(orderId).stream().map(PaymentResponse::from).toList();
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
