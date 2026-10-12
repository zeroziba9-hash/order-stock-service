package com.zeroziba.orderstock.payment;

import com.zeroziba.orderstock.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders/{orderId}/payments")
public class PaymentController {
    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> pay(@PathVariable long orderId,
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody MockPaymentRequest request) {
        var result = paymentService.pay(user.getId(), orderId, key, request);
        URI history = URI.create("/orders/" + orderId + "/payments");
        return ResponseEntity.status(result.replayed() ? 200 : 201).location(history)
                .header("Idempotency-Replayed", Boolean.toString(result.replayed())).body(result.payment());
    }

    @GetMapping
    public List<PaymentResponse> history(@PathVariable long orderId, @AuthenticationPrincipal AuthenticatedUser user) {
        return paymentService.history(user.getId(), orderId);
    }
}
