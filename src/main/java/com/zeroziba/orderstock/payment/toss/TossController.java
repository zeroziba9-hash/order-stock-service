package com.zeroziba.orderstock.payment.toss;

import com.zeroziba.orderstock.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class TossController {
    public record Confirm(@NotBlank @Pattern(regexp="srp_[a-f0-9]{32}") String orderId,
                          @NotBlank @Pattern(regexp="[A-Za-z0-9_.=-]{1,200}") String paymentKey,
                          @Positive @Max(9007199254740991L) long amount) { }
    public record Config(boolean enabled, String mode) { }
    private final TossSettings settings;
    private final TossTransactions transactions;
    private final TossPaymentService service;
    public TossController(TossSettings settings,TossTransactions transactions,TossPaymentService service) {
        this.settings=settings;this.transactions=transactions;this.service=service;
    }
    @GetMapping("/payments/toss/config")
    public Config config() { return new Config(settings.enabled,"TEST"); }
    @PostMapping("/orders/{orderId}/toss/checkout")
    public TossTransactions.Checkout checkout(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable long orderId,
            @RequestHeader(value="Idempotency-Key",required=false) String key) { return transactions.checkout(user.getId(),orderId,key); }
    @GetMapping("/orders/{orderId}/toss")
    public TossView state(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable long orderId) { return transactions.view(user.getId(),orderId); }
    @GetMapping("/payments/toss/states")
    public List<TossView> states(@AuthenticationPrincipal AuthenticatedUser user,@RequestParam List<Long> ids) { return transactions.states(user.getId(),ids); }
    @GetMapping("/payments/toss/{id}")
    public TossView stateById(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String id) { return transactions.viewById(user.getId(),id); }
    @PostMapping("/payments/toss/confirm")
    public ResponseEntity<TossView> confirm(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody Confirm body) {
        return response(service.confirm(user.getId(),body.orderId(),body.paymentKey(),body.amount()));
    }
    @PostMapping("/payments/toss/{id}/reconcile")
    public ResponseEntity<TossView> reconcile(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String id) { return response(service.reconcile(user.getId(),id)); }
    @PostMapping("/payments/toss/{id}/refund")
    public ResponseEntity<TossView> refund(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String id) { return response(service.cancel(user.getId(),id)); }
    @PostMapping("/webhooks/toss/payment")
    public ResponseEntity<Void> webhook(HttpServletRequest request) {
        try {
            byte[] body=request.getInputStream().readNBytes(16385);
            if (body.length>16384) return ResponseEntity.status(413).build();
            var notice=JsonMapper.builder().build().readTree(body);
            if (notice==null || !notice.isObject()) return ResponseEntity.badRequest().build();
            String type=notice.path("eventType").asString("");
            String id=notice.path("data").path("orderId").asString("");
            String key=notice.path("data").path("paymentKey").asString("");
            if (settings.enabled && java.util.Set.of("PAYMENT_STATUS_CHANGED","CANCEL_STATUS_CHANGED").contains(type)
                    && id.matches("srp_[a-f0-9]{32}") && key.length()<=200) transactions.hint(id,key);
            return ResponseEntity.noContent().build();
        } catch (java.io.IOException | tools.jackson.core.JacksonException failure) { return ResponseEntity.badRequest().build(); }
    }
    private ResponseEntity<TossView> response(TossView view) { return ResponseEntity.status(view.pending()?202:200).body(view); }
}
