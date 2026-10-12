package com.zeroziba.orderstock.refund;

import com.zeroziba.orderstock.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders/{orderId}/refunds")
public class RefundController {
    private final RefundService refunds;

    public RefundController(RefundService refunds) { this.refunds = refunds; }

    @PostMapping
    public ResponseEntity<RefundResponse> refund(@PathVariable long orderId,
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody MockRefundRequest request) {
        var result = refunds.refund(user.getId(), orderId, key, request);
        return ResponseEntity.status(result.replayed() ? 200 : 201)
                .location(URI.create("/orders/" + orderId + "/refunds"))
                .header("Idempotency-Replayed", Boolean.toString(result.replayed())).body(result.refund());
    }

    @GetMapping
    public List<RefundResponse> history(@PathVariable long orderId, @AuthenticationPrincipal AuthenticatedUser user) {
        return refunds.history(user.getId(), orderId);
    }
}
